package io.ghostsoftware.ghostchat

// ============================================================
// GhostKeyManager.kt
//
// Управляет жизненным циклом ключей пользователя:
//  - Создание ключей при первом запуске
//  - Хранение в EncryptedSharedPreferences
//  - Публикация публичного ключа в Firebase
//  - Ротация ключей
// ============================================================

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import io.ghostsoftware.ghostchat.GhostCrypto.fromBase64
import io.ghostsoftware.ghostchat.GhostCrypto.toBase64
import kotlinx.coroutines.tasks.await

class GhostKeyManager(private val context: Context) {

    companion object {
        private const val TAG = "GhostKeyManager"
        private const val PREFS_NAME = "ghost_xchacha_keys"

        // Ключи в EncryptedSharedPreferences
        private const val KEY_X25519_PRIVATE   = "x25519_private_key"
        private const val KEY_X25519_PUBLIC    = "x25519_public_key"
        private const val KEY_ED25519_PRIVATE  = "ed25519_private_key"
        private const val KEY_ED25519_PUBLIC   = "ed25519_public_key"
        // Раздельные флаги формата — КРИТИЧНО не объединять в один общий флаг.
        // wrapKey(X25519) и wrapKey(Ed25519) — два независимых вызова, каждый
        // может провалиться и упасть в Base64-fallback независимо от другого.
        // Один общий флаг "usingKeystore" не может корректно описать смешанное
        // состояние (напр. X25519 в Keystore, Ed25519 в plaintext) — раньше это
        // приводило к тому, что getXXXPrivateKey() передавал обычный Base64 в
        // GhostKeyStore.unwrapKey(), который пытался Base64.decode + AES-GCM
        // расшифровать случайные байты и падал SecurityException/IllegalArgumentException
        // даже когда реальных проблем с ключом не было.
        private const val KEY_X25519_USING_KEYSTORE  = "x25519_using_keystore_wrap"
        private const val KEY_ED25519_USING_KEYSTORE = "ed25519_using_keystore_wrap"
    }

    // EncryptedSharedPreferences — всё хранилище зашифровано AES256
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ========================================================
    // ИНИЦИАЛИЗАЦИЯ
    // ========================================================

    /**
     * Вызвать при запуске / регистрации.
     * Если ключи уже есть — ничего не делает.
     * Если ключей нет — генерирует и публикует в Firebase.
     */
    /**
     * Вызвать при запуске / регистрации.
     * Если ключей нет — генерирует и публикует в Firebase.
     * Если ключи есть, но НЕ разворачиваются (битый Keystore-alias после self-heal
     * в GhostKeyStore, factory reset, смена ROM) — тоже регенерирует: наличие
     * записи в prefs ещё не значит, что ключ реально пригоден к использованию.
     */
    suspend fun ensureKeysExist() {
        val keysUsable = hasKeys() && getX25519PrivateKey() != null && getEd25519PrivateKey() != null

        if (!keysUsable) {
            Log.i(TAG, "No usable keys found, generating new keypairs...")
            generateAndSaveKeys()
        } else {
            Log.d(TAG, "Keys already exist and are usable, skipping generation.")
        }

        // Убеждаемся что публичные ключи есть в Firebase
        publishPublicKeysIfNeeded()
    }

    /**
     * Генерирует свежие X25519 + Ed25519 пары и сохраняет локально.
     * Вызывается автоматически из [ensureKeysExist].
     */
    fun generateAndSaveKeys() {
        val encKeyPair  = GhostCrypto.generateKeyPair()
        val signKeyPair = GhostCrypto.generateSigningKeyPair()

        // Оборачиваем приватные ключи в Keystore AES-GCM перед сохранением.
        // Keystore wrapping key аппаратно защищён (StrongBox / TEE) —
        // даже root-доступ к файловой системе не даст приватные ключи.
        //
        // wasKeystoreWrapped фиксирует РЕАЛЬНЫЙ формат данных, которые сейчас
        // ляжут в prefs — а не то, что мы ХОТЕЛИ сделать. Если wrapKey упал,
        // wrappedXxxPriv содержит обычный Base64, и флаг обязан это отражать,
        // иначе getXxxPrivateKey() на следующем чтении попытается прогнать
        // сырой Base64 через AES-GCM unwrap и упадёт на ровном месте.
        var x25519WasKeystoreWrapped = true
        val wrappedX25519Priv = try {
            GhostKeyStore.wrapKey(encKeyPair.privateKey, GhostKeyStore.ALIAS_IDENTITY_WRAP)
        } catch (e: SecurityException) {
            // Fallback: если Keystore недоступен — сохраняем в Base64 (всё равно
            // защищено EncryptedSharedPreferences, просто без аппаратной изоляции)
            Log.w(TAG, "Keystore wrapping failed for X25519, storing plaintext in ESP: ${e.javaClass.simpleName}")
            x25519WasKeystoreWrapped = false
            encKeyPair.privateKey.toBase64()
        }

        var ed25519WasKeystoreWrapped = true
        val wrappedEd25519Priv = try {
            GhostKeyStore.wrapKey(signKeyPair.privateKey, GhostKeyStore.ALIAS_SIGNING_WRAP)
        } catch (e: SecurityException) {
            Log.w(TAG, "Keystore wrapping failed for Ed25519, storing plaintext in ESP: ${e.javaClass.simpleName}")
            ed25519WasKeystoreWrapped = false
            signKeyPair.privateKey.toBase64()
        }

        prefs.edit()
            .putString(KEY_X25519_PRIVATE,  wrappedX25519Priv)    // формат зависит от x25519WasKeystoreWrapped
            .putString(KEY_X25519_PUBLIC,   encKeyPair.publicKey.toBase64())
            .putString(KEY_ED25519_PRIVATE, wrappedEd25519Priv)   // формат зависит от ed25519WasKeystoreWrapped
            .putString(KEY_ED25519_PUBLIC,  signKeyPair.publicKey.toBase64())
            .putBoolean(KEY_X25519_USING_KEYSTORE,  x25519WasKeystoreWrapped)
            .putBoolean(KEY_ED25519_USING_KEYSTORE, ed25519WasKeystoreWrapped)
            .apply()

        Log.i(TAG, "New keypairs generated and saved. " +
                "x25519Keystore=$x25519WasKeystoreWrapped ed25519Keystore=$ed25519WasKeystoreWrapped " +
                "securityLevel=${GhostKeyStore.getKeySecurityLevel(GhostKeyStore.ALIAS_IDENTITY_WRAP)}")
    }



    fun getX25519PrivateKey(): ByteArray? {
        val stored = prefs.getString(KEY_X25519_PRIVATE, null) ?: return null
        val usingKeystore = prefs.getBoolean(KEY_X25519_USING_KEYSTORE, false)

        val raw: ByteArray? = if (!usingKeystore) {
            try {
                stored.fromBase64()
            } catch (e: IllegalArgumentException) {
                // Base64.decode может кинуть на мусорной/усечённой строке (артефакт
                // предыдущих self-heal сценариев, ручного вмешательства в prefs,
                // либо повреждения EncryptedSharedPreferences). Раньше это исключение
                // никто не ловил здесь — оно либо утекало наверх необработанным,
                // либо (что хуже) Base64.decode тихо возвращал массив НЕПРАВИЛЬНОЙ
                // длины, который доходил аж до X25519.computeSharedSecret() в Tink
                // и падал там с InvalidKeyException("Private key must have 32 bytes"),
                // за много кадров стека от настоящей причины.
                Log.e(TAG, "getX25519PrivateKey: stored value is not valid Base64 — corrupted")
                null
            }
        } else {
            try {
                GhostKeyStore.unwrapKey(stored, GhostKeyStore.ALIAS_IDENTITY_WRAP)
            } catch (e: SecurityException) {
                // Wrapped-блоб больше не разворачивается ЭТИМ Keystore-ключом — например,
                // GhostKeyStore self-heal удалил старый битый alias (StrongBox-провал) и
                // создал новый; новый ключ физически не может расшифровать данные,
                // зашифрованные старым (разные секреты). Приватный ключ невосстановим
                // здесь и сейчас. Регенерацию НЕ делаем в этом non-suspend геттере —
                // она требует сети (publishPublicKeysIfNeeded) и вызывается централизованно
                // из ensureKeysExist() при старте приложения (см. MainActivity.onCreate).
                Log.e(TAG, "getX25519PrivateKey: unwrap failed (${e.javaClass.simpleName}) — wrapped blob is dead")
                null
            }
        }

        // КРИТИЧНО: X25519 приватный ключ обязан быть РОВНО 32 байта. Проверяем
        // ЗДЕСЬ, на границе GhostKeyManager, а не полагаемся на то, что упадёт
        // где-то глубоко в Tink (X3DH/DH ratchet) с невнятным сообщением через
        // 5 кадров стека. Если длина неверна — данные битые, возвращаем null,
        // вызывающий код (GhostSessionManager) корректно превращает это в
        // SecurityException с понятным сообщением вместо голого InvalidKeyException.
        if (raw != null && raw.size != 32) {
            Log.e(TAG, "getX25519PrivateKey: decoded key has wrong length=${raw.size}, expected 32 — treating as corrupted")
            return null
        }
        return raw
    }

    fun getX25519PublicKey(): ByteArray? =
        prefs.getString(KEY_X25519_PUBLIC, null)?.fromBase64()

    fun getX25519PublicKeyBase64(): String? =
        prefs.getString(KEY_X25519_PUBLIC, null)

    fun getEd25519PrivateKey(): ByteArray? {
        val stored = prefs.getString(KEY_ED25519_PRIVATE, null) ?: return null
        val usingKeystore = prefs.getBoolean(KEY_ED25519_USING_KEYSTORE, false)

        val raw: ByteArray? = if (usingKeystore) {
            try {
                GhostKeyStore.unwrapKey(stored, GhostKeyStore.ALIAS_SIGNING_WRAP)
            } catch (e: SecurityException) {
                // См. подробный комментарий в getX25519PrivateKey — тот же паттерн:
                // wrapped-блоб мёртв, регенерация централизована в ensureKeysExist().
                Log.e(TAG, "getEd25519PrivateKey: unwrap failed (${e.javaClass.simpleName}) — wrapped blob is dead")
                null
            }
        } else {
            try {
                stored.fromBase64()
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "getEd25519PrivateKey: stored value is not valid Base64 — corrupted")
                null
            }
        }

        // Ed25519 seed/приватный ключ у Tink тоже 32 байта — та же граница
        // валидации, что и для X25519 (см. подробный комментарий там).
        if (raw != null && raw.size != 32) {
            Log.e(TAG, "getEd25519PrivateKey: decoded key has wrong length=${raw.size}, expected 32 — treating as corrupted")
            return null
        }
        return raw
    }

    fun getEd25519PublicKey(): ByteArray? =
        prefs.getString(KEY_ED25519_PUBLIC, null)?.fromBase64()

    fun getEd25519PublicKeyBase64(): String? =
        prefs.getString(KEY_ED25519_PUBLIC, null)

    fun hasKeys(): Boolean =
        prefs.contains(KEY_X25519_PRIVATE) &&
                prefs.contains(KEY_ED25519_PRIVATE)

    // ========================================================
    // FIREBASE
    // ========================================================

    /**
     * Публикует публичные ключи в Firebase под users/{uid}/keys/.
     * Приватные ключи НИКОГДА не покидают устройство.
     */
    suspend fun publishPublicKeysIfNeeded() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val x25519Pub  = getX25519PublicKeyBase64()  ?: return
        val ed25519Pub = getEd25519PublicKeyBase64() ?: return

        try {
            val keysRef = FirebaseDatabase.getInstance()
                .getReference("users/$uid/keys")

            // Публикуем только если изменились (избегаем лишних writes)
            val snapshot = keysRef.get().await()
            val storedX25519  = snapshot.child("x25519").getValue(String::class.java)
            val storedEd25519 = snapshot.child("ed25519").getValue(String::class.java)

            if (storedX25519 != x25519Pub || storedEd25519 != ed25519Pub) {
                keysRef.setValue(mapOf(
                    "x25519"  to x25519Pub,
                    "ed25519" to ed25519Pub,
                    "updatedAt" to System.currentTimeMillis()
                )).await()
                Log.i(TAG, "Public keys published to Firebase.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish public keys", e)
        }
    }

    /**
     * Получает X25519 публичный ключ другого пользователя из Firebase.
     */
    suspend fun getRecipientPublicKey(recipientUid: String): String? {
        return try {
            FirebaseDatabase.getInstance()
                .getReference("users/$recipientUid/keys/x25519")
                .get().await()
                .getValue(String::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get recipient public key for $recipientUid", e)
            null
        }
    }

    // ========================================================
    // ОБМЕН КЛЮЧАМИ ЧАТА
    // ========================================================

    /**
     * Создаёт симметричный ключ чата и шифрует его для обоих участников.
     * Возвращает пару: (ключ для меня, ключ для получателя) — оба в Base64.
     *
     * Пример сохранения в Firebase:
     *   chats/{chatId}/keys/{myUid}    = result.first
     *   chats/{chatId}/keys/{theirUid} = result.second
     */
    suspend fun createAndEncryptChatKey(
        recipientUid: String
    ): Pair<String, String>? {
        val myPublicKey        = getX25519PublicKeyBase64()  ?: return null
        val myPrivateKey       = getX25519PrivateKey()       ?: return null
        val recipientPublicKey = getRecipientPublicKey(recipientUid) ?: return null

        // Генерируем общий симметричный ключ чата
        val chatKey = GhostCrypto.generateRandomKey()

        // Шифруем для себя
        val encryptedForMe = GhostCrypto.encryptKeyForUser(
            chatKey,
            myPublicKey.fromBase64()
        )

        // Шифруем для получателя
        val encryptedForThem = GhostCrypto.encryptKeyForUser(
            chatKey,
            recipientPublicKey.fromBase64()
        )

        return Pair(encryptedForMe, encryptedForThem)
    }

    /**
     * Дешифрует ключ чата, сохранённый в Firebase для текущего пользователя.
     * @return Симметричный ключ чата как Base64, готовый для Room / GhostCrypto.encrypt
     */
    fun decryptChatKey(encryptedChatKey: String): String? {
        val myPrivateKey = getX25519PrivateKey()
        if (myPrivateKey == null) {
            Log.e(TAG, "decryptChatKey: private key is null — keys not generated yet? Call ensureKeysExist() first")
            return null
        }
        if (encryptedChatKey.isBlank()) {
            Log.w(TAG, "decryptChatKey: encryptedChatKey is blank")
            return null
        }
        return try {
            val chatKeyBytes = GhostCrypto.decryptKeyForUser(encryptedChatKey, myPrivateKey)
            val result = chatKeyBytes.toBase64()
            Log.d(TAG, "decryptChatKey: success bundle_len=${encryptedChatKey.length}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "decryptChatKey: FAILED bundle_len=${encryptedChatKey.length} exception=${e.javaClass.simpleName} — " +
                    "possible cause: key rotation happened, or bundle was encrypted for different key pair")
            null
        }
    }

    // ========================================================
    // РОТАЦИЯ КЛЮЧЕЙ
    // ========================================================

    /**
     * Ротирует ключи: генерирует новые и публикует в Firebase.
     * Старые зашифрованные ключи чатов станут недоступны — вызывать осторожно.
     */
    suspend fun rotateKeys() {
        Log.w(TAG, "Rotating keypairs! Old chat keys will be inaccessible.")
        generateAndSaveKeys()
        publishPublicKeysIfNeeded()
    }

    /**
     * Удаляет все локальные ключи (выход из аккаунта / сброс).
     */
    /**
     * Возвращает уровень аппаратной защиты для отображения в UI безопасности.
     */
    fun getKeySecurityLevel(): GhostKeyStore.KeySecurityLevel =
        GhostKeyStore.getKeySecurityLevel(GhostKeyStore.ALIAS_IDENTITY_WRAP)

    fun clearKeys() {
        prefs.edit().clear().apply()
        // Удаляем wrapping ключи из Keystore — без них wrapped бандлы
        // в EncryptedSharedPreferences становятся неразворачиваемыми.
        // Это обеспечивает полное удаление при выходе из аккаунта.
        GhostKeyStore.deleteAllGhostKeys()
        Log.i(TAG, "All local keys and Keystore entries cleared.")
    }
}
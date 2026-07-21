package io.ghostsoftware.ghostchat

// ============================================================
// ЗАВИСИМОСТИ (build.gradle.kts):
//   implementation("com.google.crypto.tink:tink-android:1.10.0")
// ============================================================

import android.util.Base64
import android.util.Log
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import com.google.crypto.tink.subtle.XChaCha20Poly1305
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

// ============================================================
// DATA CLASSES
// ============================================================

/**
 * X25519 ключевая пара для шифрования.
 *   privateKey → только EncryptedSharedPreferences, НИКОГДА не передавать по сети
 *   publicKey  → Firebase users/{uid}/keys/x25519
 */
data class GhostKeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray
) {
    override fun equals(other: Any?) =
        other is GhostKeyPair &&
                privateKey.contentEquals(other.privateKey) &&
                publicKey.contentEquals(other.publicKey)

    override fun hashCode() =
        31 * privateKey.contentHashCode() + publicKey.contentHashCode()
}

/**
 * Ed25519 ключевая пара для подписей.
 *   privateKey → только EncryptedSharedPreferences
 *   publicKey  → Firebase users/{uid}/keys/ed25519
 */
data class GhostSigningKeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray
) {
    override fun equals(other: Any?) =
        other is GhostSigningKeyPair &&
                privateKey.contentEquals(other.privateKey) &&
                publicKey.contentEquals(other.publicKey)

    override fun hashCode() =
        31 * privateKey.contentHashCode() + publicKey.contentHashCode()
}

// ============================================================
// MAIN CRYPTO OBJECT
// ============================================================

object GhostCrypto {

    private const val TAG = "GhostCrypto"

    // XChaCha20-Poly1305 через Tink:
    //   nonce  = 24 байта (встроен Tink-ом в начало ciphertext)
    //   tag    = 16 байт  (Poly1305 auth tag, в конце ciphertext)
    //   минимальный ciphertext = nonce(24) + tag(16) = 40 байт

    private const val KEY_SIZE = 32
    private const val XCHACHA20_OVERHEAD = 40  // 24 nonce + 16 Poly1305 tag

    private const val HKDF_ALG  = "HmacSha256"
    private const val HKDF_INFO = "GhostChat-v2-XChaCha20-KeyExchange"

    // ========================================================
    // ЧАСТЬ 1: ШИФРОВАНИЕ СООБЩЕНИЙ
    //
    // ПОЧЕМУ chatId ОБЯЗАТЕЛЕН КАК AAD:
    //   AEAD (Authenticated Encryption with Associated Data) защищает
    //   не только от подделки содержимого, но и от переноса шифртекста
    //   из одного чата в другой (ciphertext transplant attack).
    //   Если AAD пуст — Poly1305 тег не привязан к контексту, и
    //   злоумышленник может взять шифртекст из чата A и доставить его
    //   в чат B. С chatId в AAD это невозможно: тег будет неверным.
    //
    //   AEADBadTagException с "AAD length: 0" означает именно это:
    //   сообщение зашифровано с одним AAD, а расшифровка идёт с другим.
    //   Корень проблемы — рассинхрон AAD между encrypt и decrypt.
    // ========================================================

    /**
     * Шифрует текстовое сообщение с привязкой к чату (AAD = chatId).
     *
     * Формат вывода (Base64): nonce(24) ‖ ciphertext ‖ Poly1305_tag(16)
     * Nonce генерируется случайно внутри Tink при каждом вызове.
     *
     * @param plaintext  Открытый текст сообщения
     * @param key        Симметричный ключ чата (32 байта)
     * @param chatId     ID чата — AAD для привязки шифртекста к конкретному чату.
     *                   ОБЯЗАТЕЛЕН. Используется при дешифровке — должен совпадать.
     * @throws IllegalArgumentException если key.size != 32 или chatId пуст
     * @throws SecurityException при ошибке шифрования
     */
    fun encryptMessage(plaintext: String, key: ByteArray, chatId: String): String {
        require(key.size == KEY_SIZE) {
            "Key must be exactly $KEY_SIZE bytes, got ${key.size}"
        }
        // Programming error — используем require, а не silent fallback.
        // Пустой chatId здесь означает баг на стороне вызывающего кода.
        require(chatId.isNotBlank()) {
            "chatId must not be blank: AAD cannot be empty for encryptMessage"
        }

        return try {
            val aead = XChaCha20Poly1305(key)
            val aad  = chatId.toByteArray(Charsets.UTF_8)
            val ct   = aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), aad)
            ct.toBase64()
        } catch (e: Exception) {
            // Логируем ТОЛЬКО метаданные: длину AAD и тип исключения.
            // НИКОГДА не логируем ключ или plaintext.
            Log.e(TAG,
                "encryptMessage failed: " +
                        "aad_len=${chatId.length} " +
                        "exception=${e.javaClass.simpleName}"
            )
            throw SecurityException("Message encryption failed", e)
        }
    }

    /**
     * Overload: ключ как Base64-строка (хранится в Room / Firebase).
     */
    fun encryptMessage(plaintext: String, keyBase64: String, chatId: String): String =
        encryptMessage(plaintext, keyBase64.fromBase64(), chatId)

    /**
     * Дешифрует сообщение, зашифрованное [encryptMessage].
     *
     * Poly1305 проверяет целостность и AAD-привязку автоматически.
     * Если chatId не совпадает с тем, что был при шифровании —
     * кидает AEADBadTagException (обёрнут в SecurityException).
     *
     * @param ciphertext  Base64-строка из [encryptMessage]
     * @param key         Симметричный ключ чата (32 байта)
     * @param chatId      Тот же chatId, что был при шифровании. ОБЯЗАТЕЛЕН.
     * @throws IllegalArgumentException если параметры невалидны или ciphertext слишком короткий
     * @throws SecurityException при ошибке или нарушении целостности
     */
    fun decryptMessage(ciphertext: String, key: ByteArray, chatId: String): String {
        require(key.size == KEY_SIZE) {
            "Key must be exactly $KEY_SIZE bytes, got ${key.size}"
        }
        require(chatId.isNotBlank()) {
            "chatId must not be blank: AAD cannot be empty for decryptMessage"
        }

        val ctBytes = try {
            ciphertext.fromBase64()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG,
                "decryptMessage: invalid Base64 input " +
                        "exception=${e.javaClass.simpleName}"
            )
            throw SecurityException("Invalid ciphertext encoding")
        }

        // Защита от слишком короткого ciphertext.
        // XChaCha20-Poly1305: nonce(24) + plaintext(>=0) + tag(16).
        // Минимум = ровно 40 байт (пустой plaintext) — это валидный случай Tink.
        // Использовать >= , а не > : пустой plaintext встречается в реальном коде.
        require(ctBytes.size >= XCHACHA20_OVERHEAD) {
            "Ciphertext too short: got ${ctBytes.size} bytes, minimum is $XCHACHA20_OVERHEAD"
        }

        return try {
            val aead = XChaCha20Poly1305(key)
            val aad  = chatId.toByteArray(Charsets.UTF_8)
            val pt   = aead.decrypt(ctBytes, aad)
            String(pt, Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            // AEADBadTagException означает одно из:
            //   (1) chatId при дешифровке != chatId при шифровке (AAD mismatch)
            //   (2) неверный ключ
            //   (3) данные повреждены или подделаны
            //   (4) сообщение зашифровано legacy-методом без AAD
            // Все случаи — Security event, не просто ошибка.
            Log.e(TAG,
                "decryptMessage: AEADBadTagException — " +
                        "AAD mismatch, wrong key, tampered data, or legacy format. " +
                        "ct_len=${ctBytes.size} " +
                        "aad_len=${chatId.length}"
            )
            throw SecurityException("Message authentication failed: bad tag")
        } catch (e: Exception) {
            Log.e(TAG,
                "decryptMessage: unexpected failure " +
                        "exception=${e.javaClass.simpleName} " +
                        "ct_len=${ctBytes.size} " +
                        "aad_len=${chatId.length}"
            )
            throw SecurityException("Message decryption failed")
        }
    }

    /**
     * Overload: ключ как Base64-строка.
     */
    fun decryptMessage(ciphertext: String, keyBase64: String, chatId: String): String =
        decryptMessage(ciphertext, keyBase64.fromBase64(), chatId)

    // ========================================================
    // МИГРАЦИОННЫЙ МОСТ (LEGACY SUPPORT)
    //
    // Все старые сообщения в Room зашифрованы без AAD (ByteArray(0)).
    // Их невозможно расшифровать новым decryptMessage с chatId —
    // Poly1305 tag не совпадёт, получите AEADBadTagException.
    //
    // СТРАТЕГИЯ МИГРАЦИИ:
    //
    //   Шаг 1. В ChatScreen / ActiveChatRow оборачиваем дешифровку:
    //
    //     fun safeDecrypt(msg: MessageEntity, key: String, chatId: String): String {
    //         return try {
    //             GhostCrypto.decryptMessage(msg.text, key, chatId)
    //         } catch (e: SecurityException) {
    //             // Пробуем legacy (без AAD)
    //             try {
    //                 @Suppress("DEPRECATION")
    //                 val plain = GhostCrypto.decryptLegacy(msg.text, key)
    //                 // Сразу перешифровываем в фоне — больше не трогаем legacy
    //                 scope.launch(Dispatchers.IO) {
    //                     val reEncrypted = GhostCrypto.encryptMessage(plain, key, chatId)
    //                     dao.insertMessage(msg.copy(text = reEncrypted))
    //                 }
    //                 plain
    //             } catch (e2: SecurityException) {
    //                 "[не удалось расшифровать]"
    //             }
    //         }
    //     }
    //
    //   Шаг 2. Через 1-2 релиза после того, как все клиенты обновятся —
    //           удалить decryptLegacy из кода.
    // ========================================================

    /**
     * Дешифрует ТОЛЬКО сообщения, зашифрованные без AAD (старый encrypt/decrypt).
     * Использовать ИСКЛЮЧИТЕЛЬНО в миграционном слое — см. комментарий выше.
     */
    @Deprecated(
        message = "Legacy: только для сообщений без AAD. После миграции удалить.",
        replaceWith = ReplaceWith("decryptMessage(ciphertext, key, chatId)")
    )
    fun decryptLegacy(ciphertext: String, key: ByteArray): String {
        require(key.size == KEY_SIZE) { "Key must be $KEY_SIZE bytes" }

        val ctBytes = try {
            ciphertext.fromBase64()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "decryptLegacy: invalid Base64, exception=${e.javaClass.simpleName}")
            throw SecurityException("Invalid ciphertext encoding (legacy)")
        }

        require(ctBytes.size >= XCHACHA20_OVERHEAD) {
            "Ciphertext too short for legacy decrypt: ${ctBytes.size} bytes"
        }

        return try {
            val aead = XChaCha20Poly1305(key)
            // Пустой AAD — именно так шифровал старый encrypt()
            val pt = aead.decrypt(ctBytes, ByteArray(0))
            String(pt, Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            Log.e(TAG, "decryptLegacy: AEADBadTagException ct_len=${ctBytes.size}")
            throw SecurityException("Legacy decrypt: bad tag")
        } catch (e: Exception) {
            Log.e(TAG, "decryptLegacy: exception=${e.javaClass.simpleName} ct_len=${ctBytes.size}")
            throw SecurityException("Legacy decrypt failed")
        }
    }

    /** Overload для decryptLegacy с Base64-ключом */
    @Suppress("DEPRECATION")
    @Deprecated(
        message = "Legacy: только для сообщений без AAD.",
        replaceWith = ReplaceWith("decryptMessage(ciphertext, keyBase64, chatId)")
    )
    fun decryptLegacy(ciphertext: String, keyBase64: String): String =
        decryptLegacy(ciphertext, keyBase64.fromBase64())

    // ========================================================
    // ЧАСТЬ 2: ОБМЕН КЛЮЧАМИ (X25519 ECDH + HKDF)
    //
    // ECIES (Elliptic Curve Integrated Encryption Scheme):
    //
    //   Отправитель:
    //     1. Генерирует эфемерную пару (ephPriv, ephPub)
    //     2. ECDH(ephPriv, recipientPub) → sharedSecret
    //     3. HKDF(sharedSecret, salt=ephPub, info=HKDF_INFO) → wrapKey
    //     4. XChaCha20(wrapKey, aad=ephPub).encrypt(chatKey) → encChatKey
    //     5. Записывает в Firebase: ephPub(32) ‖ encChatKey
    //
    //   Получатель:
    //     1. Читает ephPub + encChatKey
    //     2. ECDH(myPriv, ephPub) → тот же sharedSecret
    //     3. HKDF(...) → тот же wrapKey
    //     4. XChaCha20(wrapKey, aad=ephPub).decrypt(encChatKey) → chatKey
    // ========================================================

    /**
     * Генерирует X25519 ключевую пару для пользователя.
     *   privateKey → EncryptedSharedPreferences
     *   publicKey  → Firebase users/{uid}/keys/x25519
     */
    fun generateKeyPair(): GhostKeyPair {
        val privateKey = X25519.generatePrivateKey()
        val publicKey  = X25519.publicFromPrivate(privateKey)
        return GhostKeyPair(privateKey, publicKey)
    }

    /**
     * Генерирует случайный 256-битный симметричный ключ чата (32 байта).
     * Один ключ — один чат. Никогда не переиспользовать между чатами.
     */
    fun generateRandomKey(): ByteArray {
        val key = ByteArray(KEY_SIZE)
        SecureRandom().nextBytes(key)
        return key
    }

    /** [generateRandomKey] → Base64-строка для хранения в Room / Firebase */
    fun generateRandomKeyBase64(): String =
        generateRandomKey().toBase64()

    /**
     * Шифрует симметричный ключ чата для получателя (ECIES).
     *
     * @param chatKey            32-байтный ключ чата
     * @param recipientPublicKey X25519 публичный ключ получателя (32 байта)
     * @return Base64: ephPub(32) ‖ XChaCha20_enc(chatKey, aad=ephPub)
     */
    fun encryptKeyForUser(chatKey: ByteArray, recipientPublicKey: ByteArray): String {
        require(chatKey.size == KEY_SIZE) { "Chat key must be $KEY_SIZE bytes" }
        require(recipientPublicKey.size == KEY_SIZE) { "Recipient public key must be $KEY_SIZE bytes" }

        return try {
            val ephPriv = X25519.generatePrivateKey()
            val ephPub  = X25519.publicFromPrivate(ephPriv)

            val sharedSecret = X25519.computeSharedSecret(ephPriv, recipientPublicKey)
            val wrapKey = Hkdf.computeHkdf(
                HKDF_ALG,
                sharedSecret,
                ephPub,                   // salt = ephPub привязывает к конкретной сессии
                HKDF_INFO.toByteArray(),
                KEY_SIZE
            )

            // AAD = ephPub: связывает enc(chatKey) с конкретным ephemeral обменом,
            // предотвращает переиспользование зашифрованного ключа в другом контексте
            val aead = XChaCha20Poly1305(wrapKey)
            val encChatKey = aead.encrypt(chatKey, ephPub)

            (ephPub + encChatKey).toBase64()
        } catch (e: Exception) {
            Log.e(TAG, "encryptKeyForUser (ECIES) failed: exception=${e.javaClass.simpleName}")
            throw SecurityException("Key encryption failed")
        }
    }

    /** Overload: ключи как Base64-строки */
    fun encryptKeyForUser(chatKeyBase64: String, recipientPublicKeyBase64: String): String =
        encryptKeyForUser(chatKeyBase64.fromBase64(), recipientPublicKeyBase64.fromBase64())

    /**
     * Дешифрует ключ чата своим X25519 приватным ключом (ECIES).
     *
     * @param encryptedKeyBundle  Base64-строка из [encryptKeyForUser]
     * @param myPrivateKey        X25519 приватный ключ (32 байта)
     * @return 32-байтный ключ чата
     */
    fun decryptKeyForUser(encryptedKeyBundle: String, myPrivateKey: ByteArray): ByteArray {
        require(myPrivateKey.size == KEY_SIZE) { "Private key must be $KEY_SIZE bytes" }

        val bundle = try {
            encryptedKeyBundle.fromBase64()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "decryptKeyForUser: invalid Base64 bundle, exception=${e.javaClass.simpleName}")
            throw SecurityException("Invalid key bundle encoding")
        }

        // Минимум: ephPub(32) + nonce(24) + chatKey(32) + tag(16) = 104 байта
        val minBundleSize = KEY_SIZE + XCHACHA20_OVERHEAD + KEY_SIZE
        require(bundle.size >= minBundleSize) {
            "Key bundle too short: got ${bundle.size}, minimum is $minBundleSize"
        }

        return try {
            val ephPub     = bundle.copyOfRange(0, KEY_SIZE)
            val encChatKey = bundle.copyOfRange(KEY_SIZE, bundle.size)

            val sharedSecret = X25519.computeSharedSecret(myPrivateKey, ephPub)
            val wrapKey = Hkdf.computeHkdf(
                HKDF_ALG,
                sharedSecret,
                ephPub,
                HKDF_INFO.toByteArray(),
                KEY_SIZE
            )

            val aead = XChaCha20Poly1305(wrapKey)
            aead.decrypt(encChatKey, ephPub)
        } catch (e: AEADBadTagException) {
            Log.e(TAG,
                "decryptKeyForUser: AEADBadTagException — wrong private key or corrupted bundle " +
                        "bundle_len=${bundle.size}"
            )
            throw SecurityException("Key decryption failed: bad tag")
        } catch (e: Exception) {
            Log.e(TAG, "decryptKeyForUser: exception=${e.javaClass.simpleName} bundle_len=${bundle.size}")
            throw SecurityException("Key decryption failed")
        }
    }

    /** Overload: приватный ключ как Base64-строка */
    fun decryptKeyForUser(encryptedKeyBundle: String, myPrivateKeyBase64: String): ByteArray =
        decryptKeyForUser(encryptedKeyBundle, myPrivateKeyBase64.fromBase64())

    // ========================================================
    // ЧАСТЬ 3: ЦИФРОВЫЕ ПОДПИСИ (Ed25519)
    // ========================================================

    /** Генерирует Ed25519 ключевую пару для подписей */
    fun generateSigningKeyPair(): GhostSigningKeyPair {
        val kp = Ed25519Sign.KeyPair.newKeyPair()
        return GhostSigningKeyPair(kp.privateKey, kp.publicKey)
    }

    /**
     * Подписывает сообщение приватным Ed25519 ключом.
     * @return Base64-строка подписи (64 байта) или "" при ошибке
     */
    fun signMessage(message: String, privateKey: ByteArray): String {
        return try {
            Ed25519Sign(privateKey)
                .sign(message.toByteArray(Charsets.UTF_8))
                .toBase64()
        } catch (e: Exception) {
            Log.e(TAG, "signMessage failed: exception=${e.javaClass.simpleName}")
            ""
        }
    }

    /** Overload: приватный ключ как Base64-строка */
    fun signMessage(message: String, privateKeyBase64: String): String =
        signMessage(message, privateKeyBase64.fromBase64())

    /**
     * Проверяет Ed25519 подпись.
     * @return true если подпись валидна
     */
    fun verifySignature(message: String, signatureBase64: String, publicKey: ByteArray): Boolean {
        if (signatureBase64.isBlank()) {
            Log.w(TAG, "verifySignature: empty signature provided")
            return false
        }
        return try {
            Ed25519Verify(publicKey)
                .verify(signatureBase64.fromBase64(), message.toByteArray(Charsets.UTF_8))
            true
        } catch (e: Exception) {
            // Неверная подпись — ожидаемый исход при атаке, логируем как warn, не error
            Log.w(TAG, "verifySignature: invalid, exception=${e.javaClass.simpleName}")
            false
        }
    }

    /** Overload: публичный ключ как Base64-строка */
    fun verifySignature(message: String, signatureBase64: String, publicKeyBase64: String): Boolean =
        verifySignature(message, signatureBase64, publicKeyBase64.fromBase64())

    // ========================================================
    // ЧАСТЬ 4: УТИЛИТЫ
    // ========================================================

    /** Кодирует ByteArray → Base64 (NO_WRAP, без переносов строк) */
    fun ByteArray.toBase64(): String =
        Base64.encodeToString(this, Base64.NO_WRAP)

    /**
     * Декодирует Base64 → ByteArray.
     * .trim() — защита от случайных пробелов / \n при чтении из Firebase или Room.
     */
    fun String.fromBase64(): ByteArray =
        Base64.decode(this.trim(), Base64.NO_WRAP)

    /**
     * Fingerprint публичного ключа для UI верификации.
     * SHA-256(key)[0..7] → "A1:2B:...:FF"
     */
    fun getKeyFingerprint(publicKeyBase64: String): String {
        return try {
            val hash = java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(publicKeyBase64.fromBase64())
            hash.take(8).joinToString(":") { "%02X".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "getKeyFingerprint failed: exception=${e.javaClass.simpleName}")
            "ERR:OR:KE:Y"
        }
    }

    /**
     * Constant-time сравнение ByteArray без timing-атак.
     * Использовать вместо contentEquals в секьюрном контексте.
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
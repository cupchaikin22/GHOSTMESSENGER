package io.ghostsoftware.ghostchat

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Кидается вместо обычного SecurityException, когда unwrap/wrap упёрся в
 * требование свежей биометрической аутентификации (UserNotAuthenticatedException).
 * Вызывающий код должен поймать это исключение и вызвать
 * BiometricAuthManager.authenticate(...), затем повторить операцию.
 */
class KeyAuthenticationRequiredException(
    val alias: String,
    cause: Throwable
) : SecurityException("Biometric authentication required to use key: $alias", cause)

object GhostKeyStore {

    private const val TAG = "GhostKeyStore"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val IV_SIZE = 12

    // Таймаут, в течение которого после биометрии ключ считается "разблокированным" (сек).
    private const val AUTH_VALIDITY_SECONDS = 30

    const val ALIAS_IDENTITY_WRAP   = "ghostchat_identity_wrap_v3"
    const val ALIAS_SIGNING_WRAP    = "ghostchat_signing_wrap_v3"
    const val ALIAS_RATCHET_WRAP    = "ghostchat_ratchet_wrap_v3"

    /**
     * requireUserAuth теперь РЕАЛЬНО работает: при true ключ создаётся с
     * setUserAuthenticationRequired(true) — Keystore будет отклонять Cipher.init()/
     * doFinal() с UserNotAuthenticatedException, если юзер не подтвердил себя
     * биометрией за последние AUTH_VALIDITY_SECONDS секунд.
     *
     * ВНИМАНИЕ: requireUserAuth=true НЕЛЬЗЯ использовать для identity/ratchet ключей,
     * которые нужны в фоне (FCM-сервис, авто-decrypt уведомлений) — там просто
     * физически некому показать BiometricPrompt. Используйте только для ключей,
     * к которым обращается код, гарантированно работающий на foreground UI-потоке
     * с доступной Activity.
     *
     * StrongBox НЕ используется по умолчанию. Причина: на практике StrongBox
     * оказался нестабилен на значительной части устройств (не только эмуляторов) —
     * заявляет FEATURE_STRONGBOX_KEYSTORE, но реально падает StrongBoxUnavailableException
     * при первом же использовании ключа (не при создании — Keystore иногда успевает
     * создать alias ДО того, как StrongBox-провайдер откажет, и тогда битый alias
     * остаётся в системе навсегда — новый вызов getOrCreateWrappingKey его просто
     * находит через containsAlias() и возвращает нерабочую ссылку, retry-логика
     * при создании тут уже не спасает). TEE даёт сравнимый уровень защиты для модели
     * угроз этого приложения (protection против root/extraction) без риска порчи
     * ключей. Если нужен full best-effort StrongBox — использовать отдельный
     * alias и ловить провал ДО первого реального использования, а не в рантайме.
     */
    fun getOrCreateWrappingKey(
        alias: String,
        requireUserAuth: Boolean = false
    ): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        if (keyStore.containsAlias(alias)) {
            return (keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
        }

        Log.i(TAG, "Creating new Keystore key: $alias requireUserAuth=$requireUserAuth (TEE, no StrongBox)")
        return buildKeyGenerator(alias, requireUserAuth, useStrongBox = false)
    }

    /**
     * Строит spec и реально генерирует ключ в Keystore.
     * useStrongBox=true — пытается прижать ключ к StrongBox-чипу (если есть).
     * useStrongBox=false — TEE-only, безопасный дефолт для эмуляторов/старых устройств.
     */
    private fun buildKeyGenerator(
        alias: String,
        requireUserAuth: Boolean,
        useStrongBox: Boolean
    ): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .apply {
                if (requireUserAuth) {
                    setUserAuthenticationRequired(true)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        setUserAuthenticationParameters(
                            AUTH_VALIDITY_SECONDS,
                            KeyProperties.AUTH_BIOMETRIC_STRONG
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
                    }
                }
                if (useStrongBox && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    /**
     * @param requireUserAuth должен совпадать с тем, что было передано при
     *        создании ключа этим alias'ом (иначе getOrCreateWrappingKey просто
     *        вернёт уже существующий ключ с другим флагом — это намеренно,
     *        Keystore не позволяет менять auth-required постфактум без удаления).
     */
    fun wrapKey(rawKeyBytes: ByteArray, alias: String, requireUserAuth: Boolean = false): String {
        require(rawKeyBytes.isNotEmpty()) { "Cannot wrap empty key" }
        return try {
            val wrappingKey = getOrCreateWrappingKey(alias, requireUserAuth)
            val cipher = Cipher.getInstance(AES_GCM).apply {
                init(Cipher.ENCRYPT_MODE, wrappingKey)
                updateAAD(alias.toByteArray(Charsets.UTF_8))
            }
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(rawKeyBytes)
            val bundle = iv + ciphertext
            Base64.encodeToString(bundle, Base64.NO_WRAP)
        } catch (e: UserNotAuthenticatedException) {
            Log.w(TAG, "wrapKey: biometric auth required alias=$alias")
            throw KeyAuthenticationRequiredException(alias, e)
        } catch (e: Exception) {
            val isStrongBoxFailure = e.javaClass.name == "android.security.keystore.StrongBoxUnavailableException" ||
                    e.cause?.javaClass?.name == "android.security.keystore.StrongBoxUnavailableException"

            if (isStrongBoxFailure) {
                // См. подробный комментарий в unwrapKey — тот же битый alias,
                // удаляем, чтобы следующий вызов (напр. после rotateKeys())
                // создал рабочий TEE-ключ вместо повторного столкновения с трупом.
                Log.e(TAG, "wrapKey: alias=$alias is permanently broken (StrongBox), deleting dead entry")
                deleteKey(alias)
            }
            Log.e(TAG, "wrapKey failed alias=$alias: ${e.javaClass.simpleName}")
            throw SecurityException("Key wrapping failed", e)
        }
    }

    fun unwrapKey(wrappedBase64: String, alias: String, requireUserAuth: Boolean = false): ByteArray {
        require(wrappedBase64.isNotBlank()) { "Wrapped key is blank" }
        return try {
            val bundle = Base64.decode(wrappedBase64.trim(), Base64.NO_WRAP)
            require(bundle.size > IV_SIZE) { "Wrapped key bundle too short: ${bundle.size}" }

            val iv = bundle.copyOfRange(0, IV_SIZE)
            val ciphertext = bundle.copyOfRange(IV_SIZE, bundle.size)

            val wrappingKey = getOrCreateWrappingKey(alias, requireUserAuth)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            val cipher = Cipher.getInstance(AES_GCM).apply {
                init(Cipher.DECRYPT_MODE, wrappingKey, spec)
                updateAAD(alias.toByteArray(Charsets.UTF_8))
            }
            cipher.doFinal(ciphertext)
        } catch (e: UserNotAuthenticatedException) {
            Log.w(TAG, "unwrapKey: biometric auth required alias=$alias")
            throw KeyAuthenticationRequiredException(alias, e)
        } catch (e: javax.crypto.AEADBadTagException) {
            Log.e(TAG, "unwrapKey: AEADBadTagException alias=$alias — key tampered or wrong alias")
            throw SecurityException("Key unwrap failed: bad tag")
        } catch (e: Exception) {
            val isStrongBoxFailure = e.javaClass.name == "android.security.keystore.StrongBoxUnavailableException" ||
                    e.cause?.javaClass?.name == "android.security.keystore.StrongBoxUnavailableException"

            if (isStrongBoxFailure) {
                // Alias был создан ДО фикса на TEE-only (старая версия кода пыталась
                // StrongBox первой попыткой) и остался в Keystore битым навсегда —
                // ключ физически нельзя использовать на этом устройстве. Удаляем
                // alias, чтобы следующая генерация (после clearKeys()/logout, см.
                // GhostKeyManager.clearKeys) создала рабочий TEE-ключ, а не
                // наткнулась через containsAlias() на тот же труп ещё раз.
                //
                // ВНИМАНИЕ: это НЕ чинит текущие данные, обёрнутые этим ключом —
                // wrappedX25519Priv в EncryptedSharedPreferences всё равно
                // невосстановим (шифровался ключом, который мы только что удалили).
                // Единственный выход для пользователя на этом устройстве —
                // полная переустановка приложения (сброс identity), см. вызывающий
                // код GhostKeyManager.getX25519PrivateKey(), который вернёт null
                // и заставит ChatScreen корректно сообщить об ошибке вместо краша.
                Log.e(TAG, "unwrapKey: alias=$alias is permanently broken (StrongBox), deleting dead entry")
                deleteKey(alias)
            }
            Log.e(TAG, "unwrapKey failed alias=$alias: ${e.javaClass.simpleName}")
            throw SecurityException("Key unwrap failed", e)
        }
    }

    fun getKeySecurityLevel(alias: String): KeySecurityLevel {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(alias)) return KeySecurityLevel.NOT_FOUND

            val secretKey = (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)
                ?.secretKey ?: return KeySecurityLevel.NOT_FOUND

            val factory = SecretKeyFactory.getInstance(secretKey.algorithm, ANDROID_KEYSTORE)
            val keyInfo = factory.getKeySpec(secretKey, KeyInfo::class.java) as KeyInfo

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                when (keyInfo.securityLevel) {
                    0    -> KeySecurityLevel.STRONGBOX
                    1    -> KeySecurityLevel.TEE
                    else -> KeySecurityLevel.SOFTWARE
                }
            } else {
                if (keyInfo.isInsideSecureHardware) KeySecurityLevel.TEE else KeySecurityLevel.SOFTWARE
            }
        } catch (e: Exception) {
            Log.w(TAG, "getKeySecurityLevel failed: ${e.javaClass.simpleName}")
            KeySecurityLevel.UNKNOWN
        }
    }

    fun deleteKey(alias: String) {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
                Log.i(TAG, "Deleted Keystore key: $alias")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete Keystore key $alias: ${e.javaClass.simpleName}")
        }
    }

    fun deleteAllGhostKeys() {
        listOf(ALIAS_IDENTITY_WRAP, ALIAS_SIGNING_WRAP, ALIAS_RATCHET_WRAP).forEach { deleteKey(it) }
    }

    enum class KeySecurityLevel { STRONGBOX, TEE, SOFTWARE, NOT_FOUND, UNKNOWN }
}
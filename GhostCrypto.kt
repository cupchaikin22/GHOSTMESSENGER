package io.ghostsoftware.ghostchat

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.firebase.auth.FirebaseAuth
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptionManager {
    private val provider = "AndroidKeyStore"

    // Динамический алиас: ключ теперь уникален для каждого UID
    private fun getAlias(): String {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: "default_ghost"
        return "identity_$uid"
    }

    fun generateIdentityKeys() {
        val alias = getAlias()
        // Если для текущего UID ключ уже создан — выходим
        if (isKeyGenerated(alias)) return

        val kpg: KeyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA, provider
        )
        val parameterSpec: KeyGenParameterSpec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).run {
            setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
            build()
        }
        kpg.initialize(parameterSpec)
        kpg.generateKeyPair()
    }

    fun isKeyGenerated(alias: String = getAlias()): Boolean {
        val keyStore = KeyStore.getInstance(provider)
        keyStore.load(null)
        return keyStore.containsAlias(alias)
    }

    fun getPublicKeyString(alias: String = getAlias()): String? {
        return try {
            val keyStore = KeyStore.getInstance(provider)
            keyStore.load(null)
            val publicKey = keyStore.getCertificate(alias)?.publicKey ?: return null
            Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
        } catch (e: Exception) { null }
    }

    // ВАЖНО: Метод для получения PrivateKey для дешифровки
    fun getPrivateKey(alias: String = getAlias()): PrivateKey? {
        val keyStore = KeyStore.getInstance(provider)
        keyStore.load(null)
        return keyStore.getKey(alias, null) as? PrivateKey
    }
}

object GhostEncryptor {
    private const val AES_ALGORITHM = "AES/CBC/PKCS5Padding"
    private const val RSA_ALGORITHM = "RSA/ECB/PKCS1Padding"

    fun generateRandomSecret(): String {
        val random = java.security.SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun encryptSecretWithPublicKey(secret: String, publicKeyString: String): String {
        return try {
            val publicBytes = Base64.decode(publicKeyString, Base64.NO_WRAP)
            val keySpec = X509EncodedKeySpec(publicBytes)
            val kf = KeyFactory.getInstance("RSA")
            val publicKey = kf.generatePublic(keySpec)

            val cipher = Cipher.getInstance(RSA_ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            val encryptedBytes = cipher.doFinal(secret.toByteArray())
            Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            "Error_RSA_Enc"
        }
    }

    fun decryptSecretWithPrivateKey(encryptedSecret: String, privateKey: PrivateKey): String {
        return try {
            val cipher = Cipher.getInstance(RSA_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, privateKey)
            val decryptedBytes = cipher.doFinal(Base64.decode(encryptedSecret, Base64.NO_WRAP))
            String(decryptedBytes)
        } catch (e: Exception) {
            "Error_RSA_Dec"
        }
    }

    private fun getSecretKey(seed: String): SecretKeySpec {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(seed.toByteArray())
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(text: String, seed: String): String {
        if (seed.isEmpty()) return text
        return try {
            val cipher = Cipher.getInstance(AES_ALGORITHM)
            val keySpec = getSecretKey(seed)
            val iv = ByteArray(16).apply { java.security.SecureRandom().nextBytes(this) }
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(iv))
            val encrypted = cipher.doFinal(text.toByteArray())
            Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            "Error"
        }
    }

    fun decrypt(encryptedText: String, seed: String): String {
        if (seed.isEmpty() || encryptedText.isEmpty()) return encryptedText
        return try {
            // Исправлено: Base64.NO_WRAP вместо несуществующего DEFAULTS
            val combined = Base64.decode(encryptedText, Base64.NO_WRAP)
            if (combined.size < 16) return "[Ошибка: Данные повреждены]"

            val iv = combined.copyOfRange(0, 16)
            val ciphertext = combined.copyOfRange(16, combined.size)

            val cipher = Cipher.getInstance(AES_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(seed), IvParameterSpec(iv))
            String(cipher.doFinal(ciphertext))
        } catch (e: Exception) {
            "[Ошибка расшифровки: ${e.message}]"
        }
    }
}
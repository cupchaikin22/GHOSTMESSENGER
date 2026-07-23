package com.ghostsoftware.ghostchat.crypto // Поменяй пакет на свой

import com.google.crypto.tink.subtle.X25519

/**
 * Криптографические утилиты для Ghost Chat.
 * Вынесены в отдельный объект, чтобы избежать дублирования кода и круговых рекурсий.
 */
object GhostCryptoUtils {


    @JvmStatic
    fun GhostCryptoUtils.computeSharedSecretSafe
(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "Invalid private key length: ${privateKey.size}" }
        require(publicKey.size == 32) { "Invalid public key length: ${publicKey.size}" }

        // Явный вызов библиотеки Tink X25519
        val sharedSecret = X25519.computeSharedSecret(privateKey, publicKey)

        // Проверяем, что секрет не состоит целиком из нулей (low-order curve point attack)
        require(!sharedSecret.all { it == 0.toByte() }) {
            "Rejected contributory-behavior shared secret (low-order point)"
        }

        return sharedSecret
    }

    /**
     * Безопасное затирание чувствительных данных (массивов байт) в RAM.
     */
    @JvmStatic
    fun wipe(vararg arrays: ByteArray?) {
        for (array in arrays) {
            array?.fill(0)
        }
    }

    /**
     * Безопасное выполнение блока кода с автоматически затираемыми ключами.
     */
    inline fun <T> useAndWipe(key: ByteArray, block: (ByteArray) -> T): T {
        return try {
            block(key)
        } finally {
            key.fill(0)
        }
    }
}
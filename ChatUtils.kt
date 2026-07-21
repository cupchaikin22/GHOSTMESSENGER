package io.ghostsoftware.ghostchat

import android.util.Base64
import java.security.MessageDigest

/**
 * Утилиты для работы с чатами
 */
object ChatUtils {
    /**
     * Создаёт уникальный chatId из двух пользователей
     * Всегда возвращает одинаковый ID независимо от порядка
     */
    fun getChatId(userId1: String, userId2: String): String {
        return listOf(userId1, userId2).sorted().joinToString("_")
    }

    /**
     * Генерирует fingerprint публичного ключа для верификации
     * Используется для защиты от Man-in-the-Middle атак
     */
    fun getKeyFingerprint(publicKey: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(publicKey.toByteArray())
            // Берём первые 8 байт и форматируем как XX:XX:XX:XX...
            hash.take(8).joinToString(":") { "%02X".format(it) }
        } catch (e: Exception) {
            "ERROR"
        }
    }

    /**
     * Проверяет, является ли сообщение фотографией
     */
    fun isPhotoMessage(text: String): Boolean {
        return text.startsWith("GHOST_IMG:")
    }

    /**
     * Извлекает URL из фото-сообщения
     */
    fun extractPhotoUrl(text: String): String {
        return if (isPhotoMessage(text)) {
            text.removePrefix("GHOST_IMG:")
        } else {
            text
        }
    }
}
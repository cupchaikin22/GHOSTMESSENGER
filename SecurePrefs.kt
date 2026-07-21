package io.ghostsoftware.ghostchat

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * ✅ ИСПРАВЛЕНО: Защищённое хранилище с безопасным fallback
 *
 * Изменения:
 * - Fallback возвращает обычные SharedPreferences вместо null
 * - Логирование деградации безопасности
 * - Флаг для отслеживания режима работы
 */
object SecurePrefs {
    private const val ENCRYPTED_PREFS_NAME = "ghost_key_vault"
    private const val FALLBACK_PREFS_NAME = "ghost_key_vault_fallback"
    private const val SECURITY_STATUS_KEY = "is_encrypted_storage"

    private var isUsingEncryption = true

    /**
     * Получить защищённое хранилище
     * При ошибке возвращает обычный SharedPreferences с предупреждением
     */
    fun get(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val encryptedPrefs = EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            isUsingEncryption = true
            Log.i("SecurePrefs", "Using encrypted storage")
            encryptedPrefs

        } catch (e: Exception) {
            Log.e("SecurePrefs", "Failed to create encrypted storage, using fallback", e)
            isUsingEncryption = false

            // ⚠️ Используем обычный SharedPreferences как fallback
            context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE).also {
                // Сохраняем флаг деградации
                it.edit().putBoolean(SECURITY_STATUS_KEY, false).apply()
            }
        }
    }

    /**
     * Проверяет, используется ли зашифрованное хранилище
     */
    fun isEncryptionActive(): Boolean = isUsingEncryption

    /**
     * Получить статус безопасности для отображения пользователю
     */
    fun getSecurityStatus(context: Context): SecurityStatus {
        return if (isUsingEncryption) {
            SecurityStatus.ENCRYPTED
        } else {
            SecurityStatus.FALLBACK
        }
    }

    /**
     * Попытка миграции с fallback на encrypted storage
     */
    fun attemptMigration(context: Context): Boolean {
        return try {
            val fallbackPrefs = context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
            val encryptedPrefs = get(context)

            if (isUsingEncryption && fallbackPrefs.all.isNotEmpty()) {
                // Копируем данные
                fallbackPrefs.all.forEach { (key, value) ->
                    when (value) {
                        is String -> encryptedPrefs.edit().putString(key, value).apply()
                        is Int -> encryptedPrefs.edit().putInt(key, value).apply()
                        is Boolean -> encryptedPrefs.edit().putBoolean(key, value).apply()
                        is Float -> encryptedPrefs.edit().putFloat(key, value).apply()
                        is Long -> encryptedPrefs.edit().putLong(key, value).apply()
                    }
                }

                // Очищаем fallback
                fallbackPrefs.edit().clear().apply()
                Log.i("SecurePrefs", "Migration successful")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e("SecurePrefs", "Migration failed", e)
            false
        }
    }

    enum class SecurityStatus {
        ENCRYPTED,  // Полная защита
        FALLBACK    // Деградированный режим
    }
}
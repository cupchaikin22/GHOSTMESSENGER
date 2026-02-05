package io.ghostsoftware.ghostchat

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Защищённое хранилище для приватных ключей.
 * При ошибке (старое устройство и т.п.) возвращает null — вызывающий код может
 * использовать обычные SharedPreferences как fallback.
 */
object SecurePrefs {
    fun get(context: Context): SharedPreferences? = try {
        EncryptedSharedPreferences.create(
            "ghost_key_vault",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        null
    }
}

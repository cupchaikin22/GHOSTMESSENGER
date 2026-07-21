package io.ghostsoftware.ghostchat

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import android.util.Log

/**
 * ✅ НОВАЯ ФУНКЦИЯ: Биометрическая аутентификация
 * 
 * Функции:
 * - Проверка доступности биометрии
 * - Аутентификация по отпечатку/Face ID
 * - Блокировка приложения
 * - Таймаут автоблокировки
 */
class BiometricAuthManager(private val activity: FragmentActivity) {

    companion object {
        const val PREF_BIOMETRIC_ENABLED = "biometric_enabled"
        const val PREF_LAST_UNLOCK_TIME = "last_unlock_time"
        const val AUTO_LOCK_TIMEOUT = 30_000L // 30 секунд
    }

    private val context = activity.applicationContext

    /**
     * Проверка доступности биометрической аутентификации
     */
    fun isBiometricAvailable(): BiometricAvailability {
        val biometricManager = BiometricManager.from(context)
        
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> 
                BiometricAvailability.AVAILABLE
            
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                BiometricAvailability.NO_HARDWARE
            
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                BiometricAvailability.HARDWARE_UNAVAILABLE
            
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                BiometricAvailability.NOT_ENROLLED
            
            else -> BiometricAvailability.UNKNOWN_ERROR
        }
    }

    /**
     * Запуск биометрической аутентификации
     */
    fun authenticate(
        title: String = "Вход в GhostChat",
        subtitle: String = "Подтвердите свою личность",
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        onFailed: () -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(context)
        
        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    Log.i("BiometricAuth", "Authentication successful")
                    saveLastUnlockTime()
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Log.e("BiometricAuth", "Authentication error: $errorCode - $errString")
                    
                    val message = when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> "Отменено пользователем"
                        BiometricPrompt.ERROR_USER_CANCELED -> "Отменено"
                        BiometricPrompt.ERROR_LOCKOUT -> "Слишком много попыток. Попробуйте позже"
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> "Биометрия заблокирована"
                        else -> "Ошибка аутентификации: $errString"
                    }
                    onError(message)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Log.w("BiometricAuth", "Authentication failed")
                    onFailed()
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Отмена")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    /**
     * Проверка, нужна ли разблокировка (по таймауту)
     */
    fun shouldRequireUnlock(): Boolean {
        if (!isBiometricEnabled()) return false
        
        val lastUnlockTime = getLastUnlockTime()
        val currentTime = System.currentTimeMillis()
        
        return (currentTime - lastUnlockTime) > AUTO_LOCK_TIMEOUT
    }

    /**
     * Включение/отключение биометрии
     */
    fun setBiometricEnabled(enabled: Boolean) {
        context.getSharedPreferences("ghost_security", android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_BIOMETRIC_ENABLED, enabled)
            .apply()
    }

    /**
     * Проверка, включена ли биометрия
     */
    fun isBiometricEnabled(): Boolean {
        return context.getSharedPreferences("ghost_security", android.content.Context.MODE_PRIVATE)
            .getBoolean(PREF_BIOMETRIC_ENABLED, false)
    }

    /**
     * Сохранение времени последней разблокировки
     */
    private fun saveLastUnlockTime() {
        context.getSharedPreferences("ghost_security", android.content.Context.MODE_PRIVATE)
            .edit()
            .putLong(PREF_LAST_UNLOCK_TIME, System.currentTimeMillis())
            .apply()
    }

    /**
     * Получение времени последней разблокировки
     */
    private fun getLastUnlockTime(): Long {
        return context.getSharedPreferences("ghost_security", android.content.Context.MODE_PRIVATE)
            .getLong(PREF_LAST_UNLOCK_TIME, 0L)
    }

    /**
     * Сброс времени разблокировки (для выхода)
     */
    fun resetUnlockTime() {
        context.getSharedPreferences("ghost_security", android.content.Context.MODE_PRIVATE)
            .edit()
            .putLong(PREF_LAST_UNLOCK_TIME, 0L)
            .apply()
    }

    enum class BiometricAvailability {
        AVAILABLE,
        NO_HARDWARE,
        HARDWARE_UNAVAILABLE,
        NOT_ENROLLED,
        UNKNOWN_ERROR
    }
}

/**
 * ✅ ПРИМЕР ИСПОЛЬЗОВАНИЯ В MAINACTIVITY
 * 
 * class MainActivity : ComponentActivity() {
 *     private lateinit var biometricAuth: BiometricAuthManager
 *     private var isUnlocked = false
 * 
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         biometricAuth = BiometricAuthManager(this)
 *         
 *         setContent {
 *             if (!isUnlocked && biometricAuth.isBiometricEnabled()) {
 *                 BiometricLockScreen(
 *                     onAuthenticate = {
 *                         biometricAuth.authenticate(
 *                             onSuccess = { isUnlocked = true },
 *                             onError = { error -> 
 *                                 Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
 *                             },
 *                             onFailed = {
 *                                 Toast.makeText(this, "Попробуйте снова", Toast.LENGTH_SHORT).show()
 *                             }
 *                         )
 *                     }
 *                 )
 *             } else {
 *                 // Основной контент приложения
 *                 GhostChatContent()
 *             }
 *         }
 *     }
 * 
 *     override fun onResume() {
 *         super.onResume()
 *         if (biometricAuth.shouldRequireUnlock()) {
 *             isUnlocked = false
 *         }
 *     }
 * }
 */

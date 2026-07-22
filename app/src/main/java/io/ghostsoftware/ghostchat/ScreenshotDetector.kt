package io.ghostsoftware.ghostchat

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.compose.material.icons.filled.Warning
import java.util.concurrent.TimeUnit

/**
 * ✅ НОВАЯ ФУНКЦИЯ: Детектор скриншотов
 * 
 * Функции:
 * - Обнаружение создания скриншотов
 * - Уведомление о попытке сохранения
 * - Логирование инцидентов безопасности
 * - Опционально: удаление скриншотов (требует разрешения)
 */
class ScreenshotDetector(
    private val context: Context,
    private val onScreenshotDetected: (String) -> Unit
) {
    
    private var contentObserver: ContentObserver? = null
    private val handler = Handler(Looper.getMainLooper())
    
    // Время последнего скриншота (для предотвращения дубликатов)
    private var lastScreenshotTime = 0L
    private val screenshotCooldown = 2000L // 2 секунды

    /**
     * Запуск мониторинга скриншотов
     */
    fun startMonitoring() {
        if (contentObserver != null) {
            Log.w("ScreenshotDetector", "Already monitoring")
            return
        }

        contentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                
                // Проверяем cooldown
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastScreenshotTime < screenshotCooldown) {
                    return
                }
                
                // Проверяем, был ли сделан скриншот
                checkForScreenshot(uri)
            }
        }

        // Регистрируем observer для медиафайлов
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver!!
        )
        
        Log.i("ScreenshotDetector", "Screenshot monitoring started")
    }

    /**
     * Остановка мониторинга
     */
    fun stopMonitoring() {
        contentObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
            contentObserver = null
            Log.i("ScreenshotDetector", "Screenshot monitoring stopped")
        }
    }

    /**
     * Проверка, является ли файл скриншотом
     */
    private fun checkForScreenshot(uri: Uri?) {
        try {
            val projection = arrayOf(
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT
            )

            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val displayName = it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                    val data = it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA))
                    val dateAdded = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                    
                    // Проверяем, что файл создан недавно (в последние 5 секунд)
                    val currentTime = System.currentTimeMillis()
                    val fileTime = TimeUnit.SECONDS.toMillis(dateAdded)
                    
                    if (currentTime - fileTime > 5000) {
                        return
                    }

                    // Проверяем, что это скриншот по имени файла или пути
                    val isScreenshot = displayName.lowercase().contains("screenshot") ||
                                      displayName.lowercase().contains("screen_capture") ||
                                      displayName.lowercase().contains("скриншот") ||
                                      data.lowercase().contains("screenshot") ||
                                      data.lowercase().contains("screencap")

                    if (isScreenshot) {
                        lastScreenshotTime = currentTime
                        Log.w("ScreenshotDetector", "Screenshot detected: $displayName")
                        onScreenshotDetected(data)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ScreenshotDetector", "Error checking screenshot", e)
        }
    }
}

/**
 * ✅ КОМПОНЕНТ ДЛЯ ОТОБРАЖЕНИЯ ПРЕДУПРЕЖДЕНИЯ О СКРИНШОТЕ
 */
@androidx.compose.runtime.Composable
fun ScreenshotWarningDialog(
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            androidx.compose.material.icons.Icons.Default.Warning
        },
        title = {
            androidx.compose.material3.Text(
                text = "⚠️ Обнаружен скриншот",
                color = androidx.compose.ui.graphics.Color.White
            )
        },
        text = {
            androidx.compose.material3.Text(
                text = "Вы сделали скриншот конфиденциальной переписки. Помните о безопасности ваших данных!",
                color = androidx.compose.ui.graphics.Color.Gray
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text("Понятно")
            }
        },
        containerColor = androidx.compose.ui.graphics.Color(0xFF1A1A1A)
    )
}

/**
 * ✅ ПРИМЕР ИСПОЛЬЗОВАНИЯ В CHATSCREEN
 * 
 * @Composable
 * fun ChatScreen(...) {
 *     val context = LocalContext.current
 *     var showScreenshotWarning by remember { mutableStateOf(false) }
 *     
 *     DisposableEffect(Unit) {
 *         val detector = ScreenshotDetector(context) { screenshotPath ->
 *             showScreenshotWarning = true
 *             
 *             // Опционально: уведомить собеседника
 *             sendScreenshotNotification(chatId)
 *         }
 *         detector.startMonitoring()
 *         
 *         onDispose {
 *             detector.stopMonitoring()
 *         }
 *     }
 *     
 *     if (showScreenshotWarning) {
 *         ScreenshotWarningDialog(
 *             onDismiss = { showScreenshotWarning = false }
 *         )
 *     }
 *     
 *     // Остальной UI чата...
 * }
 */

/**
 * ✅ РАСШИРЕННАЯ ВЕРСИЯ: Уведомление собеседника о скриншоте
 */
fun sendScreenshotNotification(chatId: String, senderId: String) {
    try {
        val ref = com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("chats")
            .child(chatId)
            .child("events")
            .push()

        val event = mapOf(
            "type" to "screenshot",
            "userId" to senderId,
            "timestamp" to System.currentTimeMillis()
        )

        ref.setValue(event)
        Log.i("ScreenshotDetector", "Screenshot notification sent")
    } catch (e: Exception) {
        Log.e("ScreenshotDetector", "Failed to send notification", e)
    }
}

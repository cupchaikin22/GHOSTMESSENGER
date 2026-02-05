package io.ghostsoftware.ghostchat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Random

class MyFirebaseMessagingService : FirebaseMessagingService() {

    // Scope для работы с базой данных в фоне
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        val senderId = data["senderId"] ?: ""
        val senderName = data["senderName"] ?: "Ghost User"
        val encryptedText = data["text"] ?: ""

        serviceScope.launch {
            val database = AppDatabase.getDatabase(applicationContext)
            val dao = database.messageDao()

            // 1. Получаем настройки и ключ шифрования
            val settings = dao.getSettingsSync()
            val secretKey = dao.getSecretKeyForChatSync(senderId)

            // 2. Дешифруем, если это возможно
            val decryptedBody = if (!secretKey.isNullOrEmpty() && encryptedText.isNotEmpty()) {
                GhostEncryptor.decrypt(encryptedText, secretKey)
            } else {
                "Новое сообщение"
            }

            // 3. Проверка безопасности (Маскировка)
            val isHighSecurity = (settings?.securityLevel ?: 0) >= 1
            val finalTitle = if (isHighSecurity) getString(R.string.notification_title) else senderName
            val finalBody = if (isHighSecurity) getString(R.string.confidential_message) else decryptedBody

            showNotification(senderId, senderName, finalTitle, finalBody)
        }
    }

    private fun showNotification(senderId: String, senderName: String, title: String, message: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            // Передаем данные для открытия чата
            putExtra("chat_id", senderId)
            putExtra("chat_name", senderName)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            senderId.hashCode(), // Уникальный ID, чтобы уведомления не перезаписывали друг друга
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val channelId = "ghost_chat_notifications"
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_ghost_notif) // Замени на свой ресурс!
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE) // Скрывает текст на экране блокировки системно

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "GhostChat Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о новых сообщениях в GhostChat"
                enableLights(true)
            }
            notificationManager.createNotificationChannel(channel)
        }


        notificationManager.notify(senderId.hashCode(), notificationBuilder.build())
    }

    override fun onDestroy() {
        super.onDestroy()
        // Отменяем корутины при уничтожении сервиса
    }
}
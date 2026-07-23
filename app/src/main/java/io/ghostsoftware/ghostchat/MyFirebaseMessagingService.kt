package io.ghostsoftware.ghostchat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MyFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data

        if (!validateMessageData(data)) {
            Log.w("FCMService", "Invalid message data received")
            return
        }

        val senderId   = data["senderId"]  ?: ""
        val senderName = data["senderName"] ?: "Ghost User"
        val encryptedText = data["text"]   ?: ""
        val signature  = data["signature"] ?: ""
        val timestamp  = data["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()

        serviceScope.launch {
            try {
                val database = AppDatabase.getDatabase(applicationContext)
                val dao = database.messageDao()

                val settings = dao.getSettingsSync()
                val securityLevel = settings?.securityLevel ?: 0

                val myId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
                val chatId = ChatUtils.getChatId(myId, senderId)

                // Проверка Ed25519 подписи (если включён уровень безопасности >= 1)
                if (securityLevel >= 1) {
                    if (signature.isEmpty() || !verifyMessageSignature(senderId, encryptedText, signature)) {
                        Log.w("FCMService", "Missing/invalid signature at securityLevel=$securityLevel from $senderId")
                        showSecurityAlert(senderId)
                        return@launch
                    }
                }

                val secretKey = dao.getSecretKeyForChatSync(chatId)

                // ✅ chatId уже вычислен выше через ChatUtils.getChatId
                val decryptedBody = if (!secretKey.isNullOrEmpty() && encryptedText.isNotEmpty()) {
                    try {
                        // Новый формат: chatId как AAD
                        GhostCrypto.decryptMessage(encryptedText, secretKey, chatId)
                    } catch (primary: SecurityException) {
                        // Legacy fallback: сообщения, зашифрованные без AAD
                        // Перешифровку здесь НЕ делаем — Room обновит ChatScreen при открытии чата
                        // TODO: удалить после 2 релизов
                        try {
                            @Suppress("DEPRECATION")
                            val legacyPlain = GhostCrypto.decryptLegacy(encryptedText, secretKey)
                            Log.i("CryptoMigration",
                                "FCM: legacy fallback chatId_len=${chatId.length} " +
                                        "primary=${primary.javaClass.simpleName}"
                            )
                            legacyPlain
                        } catch (legacy: SecurityException) {
                            Log.e("FCMService",
                                "FCM: all decryption failed " +
                                        "primary=${primary.javaClass.simpleName} " +
                                        "legacy=${legacy.javaClass.simpleName}"
                            )
                            getStringResourceSafe(R.string.encrypted_message_placeholder, "🔒 Зашифрованное сообщение")
                        }
                    }
                } else {
                    getStringResourceSafe(R.string.new_message, "Новое сообщение")
                }

                val (finalTitle, finalBody) = applySecurityPolicy(
                    settings = settings,
                    senderName = senderName,
                    messageBody = decryptedBody
                )

                if (!isChatCurrentlyOpen(chatId)) {
                    showNotification(senderId, senderName, finalTitle, finalBody)
                }

                saveMessageToDatabase(
                    dao = dao,
                    chatId = chatId,
                    senderId = senderId,
                    encryptedText = encryptedText,
                    timestamp = timestamp
                )

            } catch (e: Exception) {
                Log.e("FCMService", "Error processing notification", e)
            }
        }
    }

    private fun getStringResourceSafe(resourceId: Int, fallback: String): String {
        return try { getString(resourceId) } catch (e: Exception) { fallback }
    }

    private fun validateMessageData(data: Map<String, String>): Boolean {
        val senderId = data["senderId"]
        val text = data["text"]
        if (senderId.isNullOrEmpty() || text.isNullOrEmpty()) return false
        if (senderId.length < 20 || !senderId.matches(Regex("^[a-zA-Z0-9]+$"))) return false
        return true
    }

    /**
     * ✅ ИЗМЕНЕНО: Верифицируем Ed25519 подпись вместо RSA.
     * Публичный Ed25519 ключ хранится по пути users/{uid}/keys/ed25519
     * (публикует GhostKeyManager при старте приложения).
     */
    private suspend fun verifyMessageSignature(
        senderId: String,
        encryptedText: String,
        signature: String
    ): Boolean {
        return try {
            // ✅ ИЗМЕНЕНО: путь к Ed25519 ключу
            val ed25519PublicKey = getUserEd25519PublicKey(senderId) ?: return false
            // ✅ ЗАМЕНЕНО: GhostEncryptor.verifySignature → GhostCrypto.verifySignature
            GhostCrypto.verifySignature(encryptedText, signature, ed25519PublicKey)
        } catch (e: Exception) {
            Log.e("FCMService", "Signature verification failed", e)
            false
        }
    }

    /**
     * ✅ ИЗМЕНЕНО: Получаем Ed25519 публичный ключ из Firebase.
     * Новый путь: users/{userId}/keys/ed25519
     * (раньше был users/{userId}/publicKey — RSA)
     */
    private suspend fun getUserEd25519PublicKey(userId: String): String? {
        return try {
            com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference("users")
                .child(userId)
                .child("keys")   // ✅ ИЗМЕНЕНО: новая структура
                .child("ed25519") // ✅ ИЗМЕНЕНО: Ed25519, не RSA
                .get()
                .await()
                .value as? String
        } catch (e: Exception) {
            Log.e("FCMService", "Failed to get Ed25519 public key for $userId", e)
            null
        }
    }

    private fun applySecurityPolicy(
        settings: SettingsEntity?,
        senderName: String,
        messageBody: String
    ): Pair<String, String> {
        val securityLevel = settings?.securityLevel ?: 0
        return when {
            securityLevel >= 2 -> {
                val appName = getStringResourceSafe(R.string.app_name, "GhostChat")
                val maskedMsg = getStringResourceSafe(R.string.notification_masked, "Новое уведомление")
                appName to maskedMsg
            }
            securityLevel >= 1 -> {
                val confidential = getStringResourceSafe(R.string.confidential_message, "Конфиденциальное сообщение")
                senderName to confidential
            }
            else -> senderName to messageBody
        }
    }

    private fun isChatCurrentlyOpen(chatId: String): Boolean = false

    private fun showNotification(
        senderId: String,
        senderName: String,
        title: String,
        message: String
    ) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("chat_id", senderId)
            putExtra("chat_name", senderName)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this, senderId.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val channelId = "ghost_chat_notifications"
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_ghost_notif)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "GhostChat Messages", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о новых сообщениях в GhostChat"
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        notificationManager.notify(senderId.hashCode(), notificationBuilder.build())
    }

    private fun showSecurityAlert(senderId: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, "security_alert".hashCode(), intent, PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(this, "ghost_security_alerts")
            .setSmallIcon(R.drawable.ic_ghost_notif)
            .setContentTitle("⚠️ Предупреждение безопасности")
            .setContentText("Получено непроверенное сообщение. Возможна попытка атаки.")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "ghost_security_alerts", "Security Alerts", NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        notificationManager.notify("security_$senderId".hashCode(), notificationBuilder.build())
    }

    private suspend fun saveMessageToDatabase(
        dao: MessageDao,
        chatId: String,
        senderId: String,
        encryptedText: String,
        timestamp: Long
    ) {
        val firebaseKey = "$chatId-$timestamp"
        val existing = dao.getMessageByFirebaseKey(firebaseKey)
        if (existing == null) {
            dao.insertMessage(MessageEntity(
                chatId = chatId,
                senderId = senderId,
                text = encryptedText,
                timestamp = timestamp,
                isFromMe = false,
                fKey = firebaseKey,
                currentStatus = 2
            ))
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCMService", "New token: $token")
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        if (uid != null) {
            com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference("users").child(uid).child("fcmToken").setValue(token)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
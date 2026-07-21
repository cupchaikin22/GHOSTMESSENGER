package io.ghostsoftware.ghostchat

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun getMessagesForChat(chatId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: Int)

    @Query("DELETE FROM messages WHERE fKey = :fKey")
    suspend fun deleteByFirebaseKey(fKey: String)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteChatHistory(chatId: String)

    @Query("SELECT * FROM messages WHERE fKey = :key LIMIT 1")
    suspend fun getMessageByFirebaseKey(key: String): MessageEntity?

    @Query("SELECT * FROM settings WHERE id = 0")
    fun getSettings(): Flow<SettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: SettingsEntity)

    @Query("""
        SELECT * FROM messages 
        WHERE timestamp IN (SELECT MAX(timestamp) FROM messages GROUP BY chatId) 
        ORDER BY timestamp DESC
    """)
    fun getAllLastMessages(): Flow<List<MessageEntity>>

    // ✅ ИСПРАВЛЕНО: точное совпадение вместо LIKE
    @Query("SELECT secretKey FROM encryption_keys WHERE chatId = :chatId LIMIT 1")
    suspend fun getSecretKeyForChat(chatId: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSecretKey(keyEntity: EncryptionKeyEntity)

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()

    @Delete
    suspend fun deleteMessage(message: MessageEntity)

    @Query("SELECT * FROM settings WHERE id = 0")
    fun getSettingsSync(): SettingsEntity?

    // ✅ ИСПРАВЛЕНО: точное совпадение вместо LIKE
    @Query("SELECT secretKey FROM encryption_keys WHERE chatId = :chatId LIMIT 1")
    fun getSecretKeyForChatSync(chatId: String): String?

    @Query("DELETE FROM encryption_keys WHERE chatId = :chatId") // укажи свое имя таблицы
    suspend fun deleteSecretKeyForChat(chatId: String)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteMessagesForChat(chatId: String)
}
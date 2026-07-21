package io.ghostsoftware.ghostchat

import androidx.room.*

// ============================================================
// RatchetSessionDao.kt
//
// DAO для работы с таблицей ratchet_sessions в Room (SQLCipher).
//
// Добавить в AppDatabase:
//   entities = [..., RatchetSessionEntity::class]
//   abstract fun ratchetSessionDao(): RatchetSessionDao
// ============================================================

@Dao
interface RatchetSessionDao {

    @Query("SELECT * FROM ratchet_sessions WHERE chatId = :chatId LIMIT 1")
    suspend fun getSession(chatId: String): RatchetSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSession(session: RatchetSessionEntity)

    @Query("DELETE FROM ratchet_sessions WHERE chatId = :chatId")
    suspend fun deleteSession(chatId: String)

    @Query("DELETE FROM ratchet_sessions")
    suspend fun deleteAllSessions()

    // Для отладки / статистики
    @Query("SELECT chatId, sendingSeqNum, receivingSeqNum, isTofuVerified, sessionVersion FROM ratchet_sessions")
    suspend fun getSessionSummaries(): List<RatchetSessionSummary>
}

/**
 * Лёгкая проекция для дебага — не содержит ключевого материала.
 */
data class RatchetSessionSummary(
    val chatId: String,
    val sendingSeqNum: Int,
    val receivingSeqNum: Int,
    val isTofuVerified: Boolean,
    val sessionVersion: Int
)

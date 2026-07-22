package io.ghostsoftware.ghostchat

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ratchet_sessions")
data class RatchetSessionEntity(

    @PrimaryKey
    val chatId: String,

    val rootKey: String,

    val sendingChainKey: String,
    val sendingSeqNum: Int,

    val receivingChainKey: String,
    val receivingSeqNum: Int,

    val myRatchetPrivKeyWrapped: String,
    val myRatchetPubKey: String,
    val theirRatchetPubKey: String,

    // X3DH-бутстрап: эфемерный публичный ключ инициатора, который ОБЯЗАН быть
    // отправлен получателю в первом сообщении. null после первой отправки —
    // используется ровно один раз, повторно не передаётся.
    val pendingX3dhEphemeralPub: String? = null,

    val skippedMessageKeys: String = "{}",

    val replayWindowStart: Int = 0,
    val replayWindowBits: Long = 0L,

    val theirIdentityFingerprint: String = "",
    val isTofuVerified: Boolean = false,
    val tofuTimestamp: Long = 0L,

    val sessionVersion: Int = 4,
    val createdAt: Long = System.currentTimeMillis(),
    val lastActivityAt: Long = System.currentTimeMillis()
)
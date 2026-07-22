package io.ghostsoftware.ghostchat

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "messages",
    indices = [Index(value = ["fKey"], unique = true)]
)
data class MessageEntity(
    val chatId: String,
    val senderId: String,
    val text: String,
    val timestamp: Long,
    val isFromMe: Boolean,
    val fKey: String,
    val rText: String? = null,
    val isEdit: Boolean = false,
    val react: String? = null,
    val currentStatus: Int = 1,
    val seqNum: Int = 0,
    val ratchetPubKey: String? = null,
    val isPlaintextCached: Boolean = false,
    @PrimaryKey(autoGenerate = true) val id: Int = 0
)
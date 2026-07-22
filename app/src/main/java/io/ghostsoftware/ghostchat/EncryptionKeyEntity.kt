package io.ghostsoftware.ghostchat

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "encryption_keys")
data class EncryptionKeyEntity(
    @PrimaryKey val chatId: String,
    val secretKey: String
)
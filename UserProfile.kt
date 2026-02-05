package io.ghostsoftware.ghostchat


data class UserProfile(
    val uid: String = "",
    val username: String = "",
    val profileId: String = "",
    val profileImage: String = "",
    val publicKey: String = "",
    val email: String = "",
    val isGhostMode: Boolean = false
)
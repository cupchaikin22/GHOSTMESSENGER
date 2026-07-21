package io.ghostsoftware.ghostchat

import com.google.firebase.database.IgnoreExtraProperties
import com.google.firebase.database.PropertyName

@IgnoreExtraProperties
data class UserProfile(
    val uid: String = "",
    val username: String = "",
    val profileId: String = "",
    val profileImage: String = "",
    val publicKey: String = "",
    val email: String = "",

    @get:PropertyName("ghostMode")
    @set:PropertyName("ghostMode")
    var isGhostMode: Boolean = false,

    var fcmToken: String? = null,
    var status: String? = null,
    var keys: Map<String, Any>? = null
)
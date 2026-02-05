package io.ghostsoftware.ghostchat

import android.util.Base64
import java.security.KeyPairGenerator
import java.security.SecureRandom

object GhostKeyManager {
    fun generateKeyPair(): Pair<String, String> {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()

        val publicKey = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
        val privateKey = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)

        return Pair(publicKey, privateKey)
    }
}
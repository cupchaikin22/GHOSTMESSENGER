package io.ghostsoftware.ghostchat

// ============================================================
// GhostSessionManager.kt — v4
//
// Реально рабочий X3DH + Double Ratchet. Главные отличия от
// предыдущей версии (которая была фикцией, см. аудит п.2/3/4/5):
//
//   1. Эфемерный X3DH-ключ инициатора (aliceEphemPub) сохраняется
//      в сессии и передаётся в первом сообщении — без этого
//      ответчик не может вычислить тот же мастер-секрет.
//   2. Ответчик бутстрапит сессию ТОЛЬКО при получении первого
//      сообщения (там есть aliceEphemPub + её ratchetPubKey),
//      а не генерирует случайные ключи "на глаз".
//   3. sendingChainKey и receivingChainKey всегда выводятся из
//      РАЗНЫХ DH-операций — больше не совпадают.
//   4. ratchetPubKey уходит с КАЖДЫМ сообщением (а не раз в N) —
//      self-healing работает на каждый раунд обмена, а не по таймеру.
// ============================================================

import android.content.Context
import android.util.Log
import com.ghostsoftware.ghostchat.crypto.GhostCryptoUtils
import com.ghostsoftware.ghostchat.crypto.GhostCryptoUtils.computeSharedSecretSafe
import com.google.crypto.tink.subtle.X25519
import com.google.crypto.tink.subtle.XChaCha20Poly1305
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject


private const val TAG = "GhostSession"

data class EncryptedMessage(
    val ciphertext: String,
    val seqNum: Int,
    val ratchetPubKey: String,
    val x3dhEphemeralPubKey: String?,
    val isRatchetStep: Boolean
)

data class IncomingMessage(
    val ciphertext: String,
    val seqNum: Int,
    val ratchetPubKey: String?,
    val x3dhEphemeralPubKey: String?,
    val senderId: String
)

class GhostSessionManager(
    private val context: Context,
    private val keyManager: GhostKeyManager,
    private val sessionDao: RatchetSessionDao
) {

    // ========================================================
    // ОТПРАВКА
    // ========================================================

    /**
     * Шифрует сообщение. Если сессии нет — создаёт её как ИНИЦИАТОР.
     * Срабатывает: при каждом нажатии "отправить" в ChatScreen.onSend.
     */
    suspend fun encryptMessage(
        chatId: String,
        myId: String,
        recipientId: String,
        recipientX25519Pub: String,
        plaintext: String
    ): EncryptedMessage = withContext(Dispatchers.IO) {

        val session = sessionDao.getSession(chatId)
            ?: createInitiatorSession(chatId, myId, recipientId, recipientX25519Pub)

        val sendingChainKey = GhostCrypto.run { session.sendingChainKey.fromBase64() }
        val myRatchetPub    = GhostCrypto.run { session.myRatchetPubKey.fromBase64() }

        val step = GhostRatchet.advanceSendingRatchet(sendingChainKey, session.sendingSeqNum)
        val aad  = GhostRatchet.buildAAD(chatId, myId, step.seqNum, myRatchetPub)

        val ciphertext: String
        try {
            val aead = XChaCha20Poly1305(step.messageKey)
            val ct   = aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), aad)
            ciphertext = GhostCrypto.run { ct.toBase64() }
        } finally {
            step.messageKey.fill(0)
        }

        val outgoingEphemeral = session.pendingX3dhEphemeralPub

        val updatedSession = session.copy(
            sendingChainKey         = GhostCrypto.run { step.nextChainKey.toBase64() },
            sendingSeqNum           = step.seqNum + 1,
            lastActivityAt          = System.currentTimeMillis()
        )
        sessionDao.saveSession(updatedSession)

        Log.d(TAG, "encryptMessage: seqNum=${step.seqNum} chatId_len=${chatId.length} bootstrap=${outgoingEphemeral != null}")

        EncryptedMessage(
            ciphertext          = ciphertext,
            seqNum              = step.seqNum,
            ratchetPubKey       = session.myRatchetPubKey,
            x3dhEphemeralPubKey = outgoingEphemeral,
            isRatchetStep       = outgoingEphemeral == null
        )
    }

    /**
     * Создаёт сессию со стороны ИНИЦИАТОРА. aliceEphemPub генерируется
     * здесь и обязательно сохраняется в pendingX3dhEphemeralPub — иначе
     * получатель никогда не сможет вычислить тот же мастер-секрет.
     */
    private suspend fun createInitiatorSession(
        chatId: String,
        myId: String,
        recipientId: String,
        recipientX25519Pub: String
    ): RatchetSessionEntity {
        val myIdentityPriv = keyManager.getX25519PrivateKey()
            ?: throw SecurityException("createInitiatorSession: identity private key missing — call ensureKeysExist() first")
        val theirIdentityPub = GhostCrypto.run { recipientX25519Pub.fromBase64() }

        val (rootKey, sendingChainKey, aliceEphemPub) = GhostRatchet.x3dhInitiator(
            aliceIdentityPriv = myIdentityPriv,
            bobIdentityPub    = theirIdentityPub,
            bobSignedPreKey   = theirIdentityPub
        )

        val myRatchetPriv = X25519.generatePrivateKey()
        val myRatchetPub  = X25519.publicFromPrivate(myRatchetPriv)
        val myRatchetPrivWrapped = GhostKeyStore.wrapKey(myRatchetPriv, GhostKeyStore.ALIAS_RATCHET_WRAP)
        myRatchetPriv.fill(0)

        val theirFingerprint = computeIdentityFingerprint(recipientId, recipientX25519Pub)

        val session = RatchetSessionEntity(
            chatId                   = chatId,
            rootKey                  = GhostCrypto.run { rootKey.toBase64() },
            sendingChainKey          = GhostCrypto.run { sendingChainKey.toBase64() },
            sendingSeqNum            = 0,
            // placeholder — НЕ используется до первого ответа собеседника (DH-бутстрап).
            // Чтение этого значения до bootstrap'а было бы программной ошибкой.
            receivingChainKey        = GhostCrypto.run { ByteArray(32).toBase64() },
            receivingSeqNum          = 0,
            myRatchetPrivKeyWrapped  = myRatchetPrivWrapped,
            myRatchetPubKey          = GhostCrypto.run { myRatchetPub.toBase64() },
            theirRatchetPubKey       = recipientX25519Pub,
            pendingX3dhEphemeralPub  = GhostCrypto.run { aliceEphemPub.toBase64() },
            theirIdentityFingerprint = theirFingerprint,
            isTofuVerified           = false
        )

        sessionDao.saveSession(session)
        Log.i(TAG, "createInitiatorSession: X3DH session created chatId_len=${chatId.length}")
        return session
    }

    // ========================================================
    // ПОЛУЧЕНИЕ
    // ========================================================

    /**
     * Дешифрует входящее сообщение. Если сессии нет — бутстрапит её как
     * ОТВЕТЧИК через x3dhResponder + один DH против первого ratchet-ключа
     * инициатора (это и даёт отдельную sending-цепочку — фикс п.4).
     *
     * КРИТИЧНО: вызывать РОВНО ОДИН РАЗ на сообщение. Повторный вызов для
     * уже обработанного seqNum вернёт null (anti-replay) — ключ сообщения
     * уничтожается сразу после первого использования и не восстановим.
     * Поэтому ChatScreen обязан кешировать результат как plaintext.
     */
    suspend fun decryptMessage(
        chatId: String,
        myId: String,
        recipientId: String,
        incoming: IncomingMessage
    ): String? = withContext(Dispatchers.IO) {

        var session = sessionDao.getSession(chatId)

        if (session == null) {
            val theirRatchetPub = incoming.ratchetPubKey
            val theirEphemPub   = incoming.x3dhEphemeralPubKey
            if (theirRatchetPub.isNullOrBlank() || theirEphemPub.isNullOrBlank()) {
                Log.e(TAG, "decryptMessage: no session, no X3DH bootstrap data — seqNum=${incoming.seqNum}")
                return@withContext null
            }
            session = try {
                createResponderSession(chatId, myId, recipientId, theirEphemPub, theirRatchetPub)
            } catch (e: Exception) {
                Log.e(TAG, "decryptMessage: responder bootstrap failed: ${e.javaClass.simpleName}")
                return@withContext null
            }
        }

        var currentSession = session

        if (!incoming.ratchetPubKey.isNullOrBlank() &&
            incoming.ratchetPubKey != currentSession.theirRatchetPubKey) {
            currentSession = performDhRatchetStep(currentSession, incoming.ratchetPubKey)
                ?: run {
                    Log.e(TAG, "decryptMessage: DH ratchet step failed seqNum=${incoming.seqNum}")
                    return@withContext null
                }
        }

        val replayCheck = GhostRatchet.checkAndUpdateReplayWindow(
            seqNum      = incoming.seqNum,
            windowStart = currentSession.replayWindowStart,
            windowBits  = currentSession.replayWindowBits
        ) ?: run {
            Log.e(TAG, "decryptMessage: REPLAY DETECTED seqNum=${incoming.seqNum}")
            return@withContext null
        }
        val (_, newWindowStart, newWindowBits) = replayCheck

        val receivingChainKey = GhostCrypto.run { currentSession.receivingChainKey.fromBase64() }
        val skippedKeys = deserializeSkippedKeys(currentSession.skippedMessageKeys)

        val ratchetResult = GhostRatchet.advanceReceivingRatchet(
            receivingChainKey = receivingChainKey,
            expectedSeqNum    = currentSession.receivingSeqNum,
            targetSeqNum      = incoming.seqNum,
            skippedKeys       = skippedKeys
        ) ?: run {
            Log.e(TAG, "decryptMessage: advanceReceivingRatchet failed seqNum=${incoming.seqNum}")
            return@withContext null
        }
        val (messageKey, updatedSkippedKeys, newChainKey) = ratchetResult

        val ratchetPubKeyBytes = GhostCrypto.run {
            (incoming.ratchetPubKey ?: currentSession.theirRatchetPubKey).fromBase64()
        }
        val aad = GhostRatchet.buildAAD(chatId, incoming.senderId, incoming.seqNum, ratchetPubKeyBytes)

        val plaintext: String
        try {
            val ctBytes = GhostCrypto.run { incoming.ciphertext.fromBase64() }
            val aead    = XChaCha20Poly1305(messageKey)
            val ptBytes = aead.decrypt(ctBytes, aad)
            plaintext   = String(ptBytes, Charsets.UTF_8)
        } catch (e: javax.crypto.AEADBadTagException) {
            Log.e(TAG, "decryptMessage: AEADBadTagException seqNum=${incoming.seqNum}")
            messageKey.fill(0)
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "decryptMessage: ${e.javaClass.simpleName} seqNum=${incoming.seqNum}")
            messageKey.fill(0)
            return@withContext null
        } finally {
            messageKey.fill(0)
        }

        val updatedSession = currentSession.copy(
            receivingChainKey  = GhostCrypto.run { newChainKey.toBase64() },
            receivingSeqNum    = maxOf(currentSession.receivingSeqNum, incoming.seqNum + 1),
            skippedMessageKeys = serializeSkippedKeys(updatedSkippedKeys),
            replayWindowStart  = newWindowStart,
            replayWindowBits   = newWindowBits,
            lastActivityAt     = System.currentTimeMillis()
        )
        sessionDao.saveSession(updatedSession)

        Log.d(TAG, "decryptMessage: success seqNum=${incoming.seqNum}")
        plaintext
    }

    /**
     * Бутстрап сессии ОТВЕТЧИКА. Вызывается один раз — на первое входящее
     * сообщение нового чата. x3dhResponder даёт receivingChain (которым
     * расшифровывается само первое сообщение), а дополнительный одноразовый
     * DH против ratchet-ключа инициатора даёт ОТДЕЛЬНУЮ sendingChain —
     * это и есть фикс п.4 (раньше sending == receiving).
     */
    private suspend fun createResponderSession(
        chatId: String,
        myId: String,
        recipientId: String,
        aliceEphemeralPubBase64: String,
        aliceFirstRatchetPubBase64: String
    ): RatchetSessionEntity = withContext(Dispatchers.IO) {

        val myIdentityPriv = keyManager.getX25519PrivateKey()
            ?: throw SecurityException("createResponderSession: identity private key missing")
        val theirIdentityPubBase64 = keyManager.getRecipientPublicKey(recipientId)
            ?: throw SecurityException("createResponderSession: recipient identity pub not found")

        val aliceIdentityPub  = GhostCrypto.run { theirIdentityPubBase64.fromBase64() }
        val aliceEphemPub     = GhostCrypto.run { aliceEphemeralPubBase64.fromBase64() }
        val aliceFirstRatchet = GhostCrypto.run { aliceFirstRatchetPubBase64.fromBase64() }

        val (x3dhRoot, x3dhReceivingChain) = GhostRatchet.x3dhResponder(
            bobIdentityPriv     = myIdentityPriv,
            bobSignedPreKeyPriv = myIdentityPriv,
            aliceIdentityPub    = aliceIdentityPub,
            aliceEphemPub       = aliceEphemPub
        )

        val myRatchetPriv = X25519.generatePrivateKey()
        val myRatchetPub  = X25519.publicFromPrivate(myRatchetPriv)
        val bootstrapDh   = GhostCryptoUtils.computeSharedSecretSafe(myRatchetPriv, aliceFirstRatchet)
        val (newRoot, mySendingChain) = GhostRatchet.kdfRootChain(x3dhRoot, bootstrapDh)

        val myRatchetPrivWrapped = GhostKeyStore.wrapKey(myRatchetPriv, GhostKeyStore.ALIAS_RATCHET_WRAP)
        myRatchetPriv.fill(0)

        val theirFingerprint = computeIdentityFingerprint(recipientId, theirIdentityPubBase64)

        val session = RatchetSessionEntity(
            chatId                   = chatId,
            rootKey                  = GhostCrypto.run { newRoot.toBase64() },
            sendingChainKey          = GhostCrypto.run { mySendingChain.toBase64() },
            sendingSeqNum            = 0,
            receivingChainKey        = GhostCrypto.run { x3dhReceivingChain.toBase64() },
            receivingSeqNum          = 0,
            myRatchetPrivKeyWrapped  = myRatchetPrivWrapped,
            myRatchetPubKey          = GhostCrypto.run { myRatchetPub.toBase64() },
            theirRatchetPubKey       = aliceFirstRatchetPubBase64,
            pendingX3dhEphemeralPub  = null,
            theirIdentityFingerprint = theirFingerprint,
            isTofuVerified           = false
        )

        sessionDao.saveSession(session)
        Log.i(TAG, "createResponderSession: bootstrapped chatId_len=${chatId.length}")
        session
    }

    private suspend fun performDhRatchetStep(
        session: RatchetSessionEntity,
        newTheirPubKeyBase64: String
    ): RatchetSessionEntity? = withContext(Dispatchers.IO) {

        val myCurrentPriv = try {
            GhostKeyStore.unwrapKey(session.myRatchetPrivKeyWrapped, GhostKeyStore.ALIAS_RATCHET_WRAP)
        } catch (e: SecurityException) {
            Log.e(TAG, "performDhRatchetStep: unwrap failed: ${e.javaClass.simpleName}")
            return@withContext null
        }

        val rootKey     = GhostCrypto.run { session.rootKey.fromBase64() }
        val theirNewPub = GhostCrypto.run { newTheirPubKeyBase64.fromBase64() }

        val dhResult = GhostRatchet.performDhRatchetStep(rootKey, myCurrentPriv, theirNewPub)
        myCurrentPriv.fill(0)

        val newPrivWrapped = GhostKeyStore.wrapKey(dhResult.myNewPrivKey, GhostKeyStore.ALIAS_RATCHET_WRAP)
        dhResult.myNewPrivKey.fill(0)

        session.copy(
            rootKey                 = GhostCrypto.run { dhResult.newRootKey.toBase64() },
            receivingChainKey       = GhostCrypto.run { dhResult.newReceivingChainKey.toBase64() },
            sendingChainKey         = GhostCrypto.run { dhResult.newSendingChainKey.toBase64() },
            myRatchetPrivKeyWrapped = newPrivWrapped,
            myRatchetPubKey         = GhostCrypto.run { dhResult.myNewPubKey.toBase64() },
            pendingX3dhEphemeralPub = null,
            theirRatchetPubKey      = newTheirPubKeyBase64,
            skippedMessageKeys      = "{}"
        )
    }



    // ========================================================
    // TOFU
    // ========================================================

    suspend fun getContactFingerprint(recipientId: String): String? = withContext(Dispatchers.IO) {
        try {
            val ref = com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference("users/$recipientId/keys")
            val snapshot = ref.get().await()
            val x25519  = snapshot.child("x25519").getValue(String::class.java) ?: return@withContext null
            val ed25519 = snapshot.child("ed25519").getValue(String::class.java) ?: return@withContext null

            computeFingerprintFromKeys(x25519, ed25519)
        } catch (e: Exception) {
            Log.e(TAG, "getContactFingerprint failed: ${e.javaClass.simpleName}")
            null
        }
    }

    suspend fun markContactVerified(chatId: String) = withContext(Dispatchers.IO) {
        val session = sessionDao.getSession(chatId) ?: return@withContext
        sessionDao.saveSession(session.copy(isTofuVerified = true, tofuTimestamp = System.currentTimeMillis()))
        Log.i(TAG, "markContactVerified: chatId_len=${chatId.length}")
    }

    suspend fun checkTofuConsistency(chatId: String, recipientId: String): TofuStatus {
        val session = sessionDao.getSession(chatId) ?: return TofuStatus.NO_SESSION
        val currentFingerprint = getContactFingerprint(recipientId) ?: return TofuStatus.KEY_UNAVAILABLE
        val storedFingerprint  = session.theirIdentityFingerprint

        return when {
            storedFingerprint.isBlank() -> TofuStatus.FIRST_TIME
            storedFingerprint == currentFingerprint && session.isTofuVerified -> TofuStatus.VERIFIED
            storedFingerprint == currentFingerprint -> TofuStatus.TRUSTED_UNVERIFIED
            else -> {
                Log.w(TAG, "REAL TOFU MISMATCH: stored=$storedFingerprint current=$currentFingerprint")
                // Уведомляем систему, что ключ собеседника РЕАЛЬНО изменился (например, он переустановил приложение)
                TofuStatus.KEY_CHANGED
            }
        }
    }

    suspend fun resetSession(chatId: String) = withContext(Dispatchers.IO) {
        sessionDao.deleteSession(chatId)
        Log.w(TAG, "resetSession: cleared chatId_len=${chatId.length}")
    }

    private suspend fun computeIdentityFingerprint(recipientId: String, theirX25519Pub: String): String {
        return try {
            val ed25519Pub = withContext(Dispatchers.IO) {
                com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("users/$recipientId/keys/ed25519")
                    .get().await()
                    .getValue(String::class.java) ?: theirX25519Pub
            }
            computeFingerprintFromKeys(theirX25519Pub, ed25519Pub)
        } catch (e: Exception) {
            Log.w(TAG, "computeIdentityFingerprint failed: ${e.javaClass.simpleName}")
            ""
        }
    }

    private fun serializeSkippedKeys(keys: Map<Int, ByteArray>): String {
        val obj = JSONObject()
        keys.entries.take(MAX_SKIP).forEach { (seq, key) ->
            obj.put(seq.toString(), GhostCrypto.run { key.toBase64() })
        }
        return obj.toString()
    }

    private fun deserializeSkippedKeys(json: String): MutableMap<Int, ByteArray> {
        val result = mutableMapOf<Int, ByteArray>()
        try {
            val obj = JSONObject(json)
            obj.keys().forEach { key -> result[key.toInt()] = GhostCrypto.run { obj.getString(key).fromBase64() } }
        } catch (e: Exception) {
            Log.w(TAG, "deserializeSkippedKeys failed: ${e.javaClass.simpleName}")
        }
        return result
    }
    private fun computeFingerprintFromKeys(x25519Base64: String, ed25519Base64: String): String {
        val combined = GhostCrypto.run { x25519Base64.fromBase64() + ed25519Base64.fromBase64() }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(combined)
        return GhostCrypto.run { hash.toBase64() }
    }

    enum class TofuStatus { VERIFIED, TRUSTED_UNVERIFIED, FIRST_TIME, KEY_CHANGED, KEY_UNAVAILABLE, NO_SESSION }
}
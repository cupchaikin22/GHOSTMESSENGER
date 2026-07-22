package io.ghostsoftware.ghostchat

// ============================================================
// GhostRatchet.kt
//
// Реализация Double Ratchet (упрощённая, Signal-совместимая логика).
//
// Два уровня рачета:
//
//  1. SYMMETRIC RATCHET (цепочка ключей)
//     chain_key → HMAC(chain_key, 0x01) = message_key  (используем и уничтожаем)
//                 HMAC(chain_key, 0x02) = next_chain_key (заменяем)
//     Forward secrecy: компрометация message_key N
//     не раскрывает message_key < N (они уже удалены).
//
//  2. DH RATCHET (шаг при получении нового ratchet pubkey)
//     При получении нового эфемерного DH-ключа собеседника:
//       (new_root_key, recv_chain_key) = KDF_RK(root_key, DH(my_priv, their_pub))
//     Затем генерируем свой новый эфемерный ключ:
//       (new_root_key2, send_chain_key) = KDF_RK(new_root_key, DH(new_my_priv, their_pub))
//     Это даёт "self-healing" — даже если компрометируют текущий ключ,
//     следующий DH-шаг обновит всё состояние.
//
//  AAD для каждого сообщения:
//     chatId ‖ NUL ‖ senderId ‖ NUL ‖ seqNum(4 байта BE) ‖ ratchetPubKey(32 байта)
//     Это привязывает ciphertext к: конкретному чату, отправителю,
//     порядковому номеру и DH-ключу — предотвращает replay и transplant.
// ============================================================

import android.util.Log
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// ────────────────────────────────────────────────────────────
// CONSTANTS
// ────────────────────────────────────────────────────────────

private const val TAG = "GhostRatchet"

/** Максимум пропущенных ключей на DH-шаг (защита от exhaustion) */
const val MAX_SKIP = 50

/** Ширина anti-replay окна в битах (=сообщениях) */
const val REPLAY_WINDOW_SIZE = 64

// HKDF info-строки для изоляции доменов:
private const val INFO_ROOT_CHAIN     = "GhostChat-v3-RootChain"
private const val INFO_MESSAGE_KEY    = "GhostChat-v3-MessageKey"

// ────────────────────────────────────────────────────────────
// DATA CLASSES
// ────────────────────────────────────────────────────────────

/**
 * Результат шага симметричного рачета отправки.
 *
 * [messageKey]      — использовать для XChaCha20-Poly1305, затем уничтожить
 * [nextChainKey]    — сохранить как новый sendingChainKey
 * [seqNum]          — включить в тело сообщения и AAD
 */
data class SendingRatchetStep(
    val messageKey: ByteArray,
    val nextChainKey: ByteArray,
    val seqNum: Int
)

/**
 * Результат DH-шага рачета (выполняется при получении нового ratchetPubKey).
 *
 * [newRootKey]          — новый корневой ключ
 * [newReceivingChainKey]— новая цепочка получения
 * [newSendingChainKey]  — новая цепочка отправки
 * [myNewPrivKey]        — новый эфемерный X25519 приватный ключ (для следующего ответа)
 * [myNewPubKey]         — соответствующий публичный ключ (включить в следующее сообщение)
 */
data class DhRatchetResult(
    val newRootKey: ByteArray,
    val newReceivingChainKey: ByteArray,
    val newSendingChainKey: ByteArray,
    val myNewPrivKey: ByteArray,
    val myNewPubKey: ByteArray
)

// ────────────────────────────────────────────────────────────
// RATCHET PRIMITIVES
// ────────────────────────────────────────────────────────────

object GhostRatchet {

    // ========================================================
    // KDF ФУНКЦИИ
    // ========================================================

    /**
     * KDF корневой цепочки (Root Chain KDF).
     *
     * Вход: текущий root_key (32 bytes) + DH-выход (32 bytes)
     * Выход: (new_root_key, chain_key) — оба 32 байта
     *
     * Используется при DH Ratchet шаге.
     * HKDF с salt=root_key и ikm=dh_output даёт
     * детерминированный и случайный (для стороннего наблюдателя)
     * вывод — это ключевое свойство для forward secrecy.
     */
    fun kdfRootChain(rootKey: ByteArray, dhOutput: ByteArray): Pair<ByteArray, ByteArray> {
        require(rootKey.size == 32) { "Root key must be 32 bytes" }
        require(dhOutput.size == 32) { "DH output must be 32 bytes" }

        // 64 байта = 32 для нового root + 32 для chain key
        val expanded = Hkdf.computeHkdf(
            "HmacSha256",
            dhOutput,
            rootKey,
            INFO_ROOT_CHAIN.toByteArray(Charsets.UTF_8),
            64
        )
        return expanded.copyOfRange(0, 32) to expanded.copyOfRange(32, 64)
    }

    /**
     * KDF цепочки ключей (Chain KDF / Symmetric Ratchet Step).
     *
     * Входящий chain_key → (message_key, next_chain_key).
     * Использует HMAC-SHA256 с разными константами (0x01, 0x02),
     * что гарантирует независимость message_key и next_chain_key
     * — компрометация одного не раскрывает другой.
     *
     * Стандарт Signal Protocol Section 2.2.
     */
    fun kdfChain(chainKey: ByteArray): Pair<ByteArray, ByteArray> {
        require(chainKey.size == 32) { "Chain key must be 32 bytes" }

        fun hmac(key: ByteArray, input: Byte): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(byteArrayOf(input))
        }

        // 0x01 → message key (используем для шифрования одного сообщения)
        // 0x02 → next chain key (заменяем current chain key)
        val messageKey   = hmac(chainKey, 0x01)
        val nextChainKey = hmac(chainKey, 0x02)

        // Дополнительно расширяем message_key через HKDF для получения
        // 32-байтного ключа XChaCha20 из 32-байтного HMAC-вывода
        // (HMAC-SHA256 уже 32 байта — но HKDF добавляет domain separation)
        val expandedMsgKey = Hkdf.computeHkdf(
            "HmacSha256",
            messageKey,
            ByteArray(32),  // нулевой salt
            INFO_MESSAGE_KEY.toByteArray(Charsets.UTF_8),
            32
        )

        return expandedMsgKey to nextChainKey
    }

    // ========================================================
    // SYMMETRIC RATCHET STEP (отправка)
    // ========================================================

    /**
     * Шаг симметричного рачета для отправки сообщения.
     *
     * Возвращает [SendingRatchetStep] с:
     *   - messageKey для шифрования (после использования — уничтожить)
     *   - nextChainKey для замены sendingChainKey в сессии
     *   - seqNum = currentSeqNum (вставить в AAD и тело сообщения)
     *
     * ВЫЗЫВАТЬ ТОЛЬКО ОДИН РАЗ НА СООБЩЕНИЕ.
     * После вызова сохранить nextChainKey как новый sendingChainKey.
     */
    fun advanceSendingRatchet(
        sendingChainKey: ByteArray,
        currentSeqNum: Int
    ): SendingRatchetStep {
        val (messageKey, nextChainKey) = kdfChain(sendingChainKey)
        return SendingRatchetStep(
            messageKey   = messageKey,
            nextChainKey = nextChainKey,
            seqNum       = currentSeqNum
        )
    }

    // ========================================================
    // SYMMETRIC RATCHET STEP (получение)
    // ========================================================

    /**
     * Шаг симметричного рачета для получения сообщения с [targetSeqNum].
     *
     * Если targetSeqNum > expectedSeqNum — обрабатывает out-of-order:
     *   сохраняет пропущенные message_key в [skippedKeys],
     *   продвигает цепочку до targetSeqNum.
     *
     * Если targetSeqNum < expectedSeqNum — ищет в [skippedKeys].
     *
     * Возвращает (messageKey, updatedSkippedKeys, newChainKey)
     * или null если replay детектирован или MAX_SKIP превышен.
     */
    fun advanceReceivingRatchet(
        receivingChainKey: ByteArray,
        expectedSeqNum: Int,
        targetSeqNum: Int,
        skippedKeys: MutableMap<Int, ByteArray>
    ): Triple<ByteArray, MutableMap<Int, ByteArray>, ByteArray>? {

        // Случай 1: уже обработанное сообщение — ищем в skippedKeys
        if (targetSeqNum < expectedSeqNum) {
            val savedKey = skippedKeys.remove(targetSeqNum)
            if (savedKey == null) {
                Log.e(TAG, "advanceReceivingRatchet: replay or duplicate seqNum=$targetSeqNum expected=$expectedSeqNum")
                return null
            }
            return Triple(savedKey, skippedKeys, receivingChainKey)
        }

        // Случай 2: будущее сообщение пришло раньше — сохраняем пропущенные ключи
        if (targetSeqNum > expectedSeqNum) {
            val gap = targetSeqNum - expectedSeqNum
            if (gap > MAX_SKIP) {
                Log.e(TAG, "advanceReceivingRatchet: gap $gap > MAX_SKIP=$MAX_SKIP, refusing")
                return null
            }

            if (skippedKeys.size + gap > MAX_SKIP) {
                Log.e(TAG, "advanceReceivingRatchet: skipped cache would exceed MAX_SKIP")
                return null
            }
        }

        // Продвигаем цепочку от expectedSeqNum до targetSeqNum
        var chainKey = receivingChainKey
        for (seq in expectedSeqNum until targetSeqNum) {
            val (msgKey, nextChainKey) = kdfChain(chainKey)
            skippedKeys[seq] = msgKey
            chainKey = nextChainKey
            Log.d(TAG, "advanceReceivingRatchet: saved skipped key seqNum=$seq")
        }

        // Последний шаг — это и есть наш ключ
        val (messageKey, nextChainKey) = kdfChain(chainKey)
        return Triple(messageKey, skippedKeys, nextChainKey)
    }

    // ========================================================
    // DH RATCHET STEP
    // ========================================================

    /**
     * DH Ratchet шаг — выполняется при получении нового ratchet pubkey.
     *
     * Что происходит:
     *   1. ECDH(myCurrentPriv, theirNewPub) → DH1
     *   2. KDF_RK(rootKey, DH1) → (newRoot, newReceivingChain)
     *   3. Генерируем новую эфемерную пару (myNewPriv, myNewPub)
     *   4. ECDH(myNewPriv, theirNewPub) → DH2
     *   5. KDF_RK(newRoot, DH2) → (newRoot2, newSendingChain)
     *
     * После этого шага:
     *   - Текущий myRatchetPrivKey → удалить (forward secrecy)
     *   - myNewPub → включить в следующее исходящее сообщение
     *   - theirNewPub → сохранить как theirRatchetPubKey
     *
     * "Self-healing": если атакующий скомпрометировал текущее состояние,
     * следующий DH-шаг (когда собеседник ответит) создаёт новое состояние,
     * которое атакующий не знает.
     */
    fun performDhRatchetStep(
        rootKey: ByteArray,
        myCurrentPrivKey: ByteArray,
        theirNewPubKey: ByteArray
    ): DhRatchetResult {
        require(rootKey.size == 32) { "Root key must be 32 bytes" }
        require(myCurrentPrivKey.size == 32) { "My priv key must be 32 bytes" }
        require(theirNewPubKey.size == 32) { "Their pub key must be 32 bytes" }

        // Шаг 1-2: Receiving chain
        val dh1 = X25519.computeSharedSecret(myCurrentPrivKey, theirNewPubKey)
        val (newRoot1, newReceivingChain) = kdfRootChain(rootKey, dh1)

        // Шаг 3-5: Sending chain с новым эфемерным ключом
        val myNewPrivKey = X25519.generatePrivateKey()
        val myNewPubKey  = X25519.publicFromPrivate(myNewPrivKey)
        val dh2 = X25519.computeSharedSecret(myNewPrivKey, theirNewPubKey)
        val (newRoot2, newSendingChain) = kdfRootChain(newRoot1, dh2)

        Log.d(TAG, "DH ratchet step completed — new chains established")

        return DhRatchetResult(
            newRootKey           = newRoot2,
            newReceivingChainKey = newReceivingChain,
            newSendingChainKey   = newSendingChain,
            myNewPrivKey         = myNewPrivKey,
            myNewPubKey          = myNewPubKey
        )
    }

    // ========================================================
    // X3DH ИНИЦИАЛИЗАЦИЯ СЕССИИ
    // ========================================================

    /**
     * X3DH (Extended Triple Diffie-Hellman) — установка начальной сессии.
     *
     * Инициатор (Alice) вычисляет общий секрет через 3 DH-операции:
     *   DH1 = ECDH(aliceIdentityPriv, bobSignedPreKey)
     *   DH2 = ECDH(aliceEphemPriv, bobIdentityPub)
     *   DH3 = ECDH(aliceEphemPriv, bobSignedPreKey)
     *
     * masterSecret = HKDF(DH1 ‖ DH2 ‖ DH3)
     * → (rootKey, sendingChainKey)
     *
     * Это обеспечивает mutual authentication + forward secrecy.
     *
     * [aliceIdentityPriv]  — долговременный X25519 приватный ключ Alice
     * [aliceEphemeralPriv] — эфемерный X25519 приватный ключ Alice (генерится здесь)
     * [bobIdentityPub]     — долговременный X25519 публичный ключ Bob (из Firebase)
     * [bobSignedPreKey]    — подписанный pre-key Bob (= его ratchetPubKey)
     *
     * Возвращает (rootKey, sendingChainKey, aliceEphemPubKey).
     * aliceEphemPubKey включается в первое сообщение.
     */
    fun x3dhInitiator(
        aliceIdentityPriv: ByteArray,
        bobIdentityPub: ByteArray,
        bobSignedPreKey: ByteArray
    ): Triple<ByteArray, ByteArray, ByteArray> {
        // Явная проверка длины на границе крипто-примитива — defense-in-depth.
        // GhostKeyManager.getX25519PrivateKey() уже валидирует длину identity-ключа
        // перед выдачей наружу, но эта функция — низкоуровневый крипто-примитив,
        // который может быть вызван и из других мест в будущем. Без этой проверки
        // битые данные проваливались бы до X25519.computeSharedSecret() внутри Tink
        // и падали там с InvalidKeyException без указания, ЧЕЙ именно ключ битый —
        // это стоило часа отладки на реальном инциденте.
        require(aliceIdentityPriv.size == 32) {
            "x3dhInitiator: aliceIdentityPriv must be 32 bytes, got ${aliceIdentityPriv.size} — identity key is corrupted, call ensureKeysExist() to regenerate"
        }
        require(bobIdentityPub.size == 32) {
            "x3dhInitiator: bobIdentityPub must be 32 bytes, got ${bobIdentityPub.size} — recipient public key is malformed"
        }
        require(bobSignedPreKey.size == 32) {
            "x3dhInitiator: bobSignedPreKey must be 32 bytes, got ${bobSignedPreKey.size} — recipient pre-key is malformed"
        }

        val aliceEphemPriv = X25519.generatePrivateKey()
        val aliceEphemPub  = X25519.publicFromPrivate(aliceEphemPriv)

        val dh1 = X25519.computeSharedSecret(aliceIdentityPriv, bobSignedPreKey)
        val dh2 = X25519.computeSharedSecret(aliceEphemPriv, bobIdentityPub)
        val dh3 = X25519.computeSharedSecret(aliceEphemPriv, bobSignedPreKey)

        // Конкатенируем и прогоняем через HKDF с domain info
        val inputKeyMaterial = dh1 + dh2 + dh3
        val masterSecret = Hkdf.computeHkdf(
            "HmacSha256",
            inputKeyMaterial,
            ByteArray(32), // нулевой salt
            "GhostChat-v3-X3DH-Init".toByteArray(Charsets.UTF_8),
            64
        )

        val rootKey          = masterSecret.copyOfRange(0, 32)
        val sendingChainKey  = masterSecret.copyOfRange(32, 64)

        return Triple(rootKey, sendingChainKey, aliceEphemPub)
    }

    /**
     * X3DH со стороны получателя (Bob).
     * Bob вычисляет тот же masterSecret по aliceEphemPubKey.
     *
     * Возвращает (rootKey, receivingChainKey).
     */
    fun x3dhResponder(
        bobIdentityPriv: ByteArray,
        bobSignedPreKeyPriv: ByteArray,
        aliceIdentityPub: ByteArray,
        aliceEphemPub: ByteArray
    ): Pair<ByteArray, ByteArray> {
        // Зеркальные DH-операции
        val dh1 = X25519.computeSharedSecret(bobSignedPreKeyPriv, aliceIdentityPub)
        val dh2 = X25519.computeSharedSecret(bobIdentityPriv, aliceEphemPub)
        val dh3 = X25519.computeSharedSecret(bobSignedPreKeyPriv, aliceEphemPub)

        val inputKeyMaterial = dh1 + dh2 + dh3
        val masterSecret = Hkdf.computeHkdf(
            "HmacSha256",
            inputKeyMaterial,
            ByteArray(32),
            "GhostChat-v3-X3DH-Init".toByteArray(Charsets.UTF_8),
            64
        )

        val rootKey           = masterSecret.copyOfRange(0, 32)
        val receivingChainKey = masterSecret.copyOfRange(32, 64)

        return rootKey to receivingChainKey
    }

    // ========================================================
    // AAD BUILDER
    // ========================================================

    /**
     * Строит AAD для шифрования/дешифрования сообщения.
     *
     * Формат: chatId ‖ 0x00 ‖ senderId ‖ 0x00 ‖ seqNum(4 bytes BE) ‖ ratchetPubKey(32 bytes)
     *
     * Привязывает ciphertext к:
     *   - чату (chatId) → нельзя перенести в другой чат
     *   - отправителю (senderId) → нельзя подменить авторство
     *   - порядковому номеру (seqNum) → нельзя переставить сообщения
     *   - DH-ключу сессии (ratchetPubKey) → нельзя использовать ключ из другой сессии
     */
    fun buildAAD(
        chatId: String,
        senderId: String,
        seqNum: Int,
        ratchetPubKey: ByteArray
    ): ByteArray {
        require(ratchetPubKey.size == 32) { "Ratchet pub key must be 32 bytes" }
        val chatIdBytes   = chatId.toByteArray(Charsets.UTF_8)
        val senderIdBytes = senderId.toByteArray(Charsets.UTF_8)
        val seqNumBytes   = byteArrayOf(
            (seqNum shr 24).toByte(),
            (seqNum shr 16).toByte(),
            (seqNum shr 8).toByte(),
            seqNum.toByte()
        )
        // Разделители 0x00 гарантируют что разные chatId/senderId
        // не могут дать одинаковый AAD через конкатенацию
        return chatIdBytes + byteArrayOf(0x00) + senderIdBytes + byteArrayOf(0x00) +
                seqNumBytes + ratchetPubKey
    }

    // ========================================================
    // ANTI-REPLAY
    // ========================================================

    /**
     * Проверяет seqNum на replay-атаку с использованием скользящего окна.
     *
     * @return true если сообщение допустимо (новое), false если replay
     */
    fun checkAndUpdateReplayWindow(
        seqNum: Int,
        windowStart: Int,
        windowBits: Long
    ): Triple<Boolean, Int, Long>? {

        val windowEnd = windowStart + REPLAY_WINDOW_SIZE - 1

        return when {
            // Слишком старое — за пределами окна (replay или дублирование)
            seqNum < windowStart -> {
                Log.w(TAG, "Anti-replay: seqNum=$seqNum < windowStart=$windowStart — REJECTED")
                null
            }
            // Очень новое — сдвигаем окно вперёд
            seqNum > windowEnd -> {
                val shift = seqNum - windowEnd
                val newStart = windowStart + shift
                val newBits  = (windowBits shl shift) or 1L
                Log.d(TAG, "Anti-replay: advancing window to $newStart")
                Triple(true, newStart, newBits)
            }
            // Внутри окна — проверяем бит
            else -> {
                val bitPos = seqNum - windowStart
                val mask = 1L shl bitPos
                if (windowBits and mask != 0L) {
                    Log.w(TAG, "Anti-replay: seqNum=$seqNum already received — REJECTED")
                    null
                } else {
                    Triple(true, windowStart, windowBits or mask)
                }
            }
        }
    }
}
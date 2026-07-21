package io.ghostsoftware.ghostchat

// ============================================================
// ChatScreen.kt — интеграция GhostSessionManager
//
// Этот файл показывает ТОЛЬКО изменяемые места — не полный ChatScreen.
// Ищи каждый блок по комментарию-метке и заменяй соответствующий код.
// ============================================================

// ── [1] ДОБАВИТЬ В ПАРАМЕТРЫ ChatScreen ──────────────────────
//
// fun ChatScreen(
//     database: AppDatabase,
//     keyManager: GhostKeyManager,
//     recipientId: String,
//     ...
// ) {
//
// ── [2] ДОБАВИТЬ В НАЧАЛО ТЕЛА ChatScreen ────────────────────

private fun chatScreenIntegrationExample() {
    // ─── Инициализировать в начале ChatScreen ───────────────
    //
    // val ratchetSessionDao = database.ratchetSessionDao()
    // val sessionManager = remember {
    //     GhostSessionManager(context, keyManager, ratchetSessionDao)
    // }
    //
    // // Текущая сессия — обновляется при шифровании/дешифровании
    // var ratchetSession by remember { mutableStateOf<RatchetSessionEntity?>(null) }
    //
    // // TOFU предупреждение (null = нет проблем)
    // var tofuWarning by remember { mutableStateOf<GhostSessionManager.TofuStatus?>(null) }

    // ─── LaunchedEffect(chatId): инициализация рачет-сессии ─
    //
    // LaunchedEffect(chatId) {
    //     val resolvedKey = withContext(Dispatchers.IO) {
    //         resolveOrCreateChatKey(myId, chatId, recipientId, keyManager, chatKeysRef, messageDao)
    //     }
    //     chatSecretKey = resolvedKey
    //
    //     // Инициализируем рачет-сессию параллельно
    //     val recipientPub = withContext(Dispatchers.IO) {
    //         keyManager.getRecipientPublicKey(recipientId)
    //     }
    //     if (recipientPub != null) {
    //         ratchetSession = sessionManager.getOrInitSession(
    //             chatId         = chatId,
    //             myId           = myId,
    //             recipientId    = recipientId,
    //             recipientX25519Pub = recipientPub,
    //             isInitiator    = myId < recipientId  // детерминированный выбор инициатора
    //         )
    //         Log.d("KeyInit", "Ratchet session ready seqNum=${ratchetSession?.sendingSeqNum}")
    //     }
    //
    //     // TOFU проверка
    //     val tofuStatus = sessionManager.checkTofuConsistency(chatId, recipientId)
    //     if (tofuStatus == GhostSessionManager.TofuStatus.KEY_CHANGED) {
    //         tofuWarning = tofuStatus  // Показать критическое предупреждение в UI!
    //     }
    //
    //     isKeyLoading = false
    //     Log.d("KeyInit", "chatSecretKey ready key_blank=${resolvedKey.isBlank()}")
    // }
}

// ── [3] ОТПРАВКА СООБЩЕНИЯ (onSend) ─────────────────────────
//
// Заменяет: val encrypted = GhostCrypto.encryptMessage(inputText.trim(), chatSecretKey, chatId)
//
// onSend = {
//     if (inputText.isBlank()) return@GlassBottomPanel
//     scope.launch {
//         val session = ratchetSession
//         if (session != null) {
//             // Путь 1: Рачет доступен — используем Double Ratchet
//             val (encMsg, updatedSession) = withContext(Dispatchers.IO) {
//                 sessionManager.encryptMessage(session, inputText.trim(), myId)
//             }
//             ratchetSession = updatedSession
//
//             val msgMap = mutableMapOf<String, Any>(
//                 "senderId"  to myId,
//                 "text"      to encMsg.ciphertext,
//                 "seqNum"    to encMsg.seqNum,
//                 "timestamp" to ServerValue.TIMESTAMP,
//                 "status"    to 1
//             )
//             // Включаем ratchetPubKey только при DH-шаге (каждые N сообщений)
//             encMsg.ratchetPubKey?.let { msgMap["ratchetPubKey"] = it }
//
//             chatRef.push().setValue(msgMap)
//         } else {
//             // Путь 2: Рачет ещё не готов — fallback на статический ключ
//             val encrypted = GhostCrypto.encryptMessage(inputText.trim(), chatSecretKey, chatId)
//             val msgMap = mutableMapOf<String, Any>(
//                 "senderId"  to myId,
//                 "text"      to encrypted,
//                 "timestamp" to ServerValue.TIMESTAMP,
//                 "status"    to 1
//             )
//             chatRef.push().setValue(msgMap)
//         }
//         inputText = ""
//     }
// }

// ── [4] ДЕШИФРОВКА В GlassBubble ────────────────────────────
//
// Заменяет: safeDecryptSync(msg.text, chatKey, chatId)
//
// val dec = remember(msg.text, chatKey, chatId, msg.fKey) {
//     when {
//         chatKey.isEmpty() || chatId.isBlank() -> "Инициализация..."
//         else -> {
//             // Если в сообщении есть seqNum — это рачет-формат
//             // seqNum хранится в MessageEntity (добавить поле) или
//             // читается из Firebase при загрузке
//             if (msg.seqNum > 0 && ratchetSession != null) {
//                 // Рачет-дешифровка выполняется в LaunchedEffect, не в remember{}
//                 // (нельзя suspend в remember). Используем cached plaintext.
//                 cachedDecryptedTexts[msg.fKey] ?: "Расшифровывается..."
//             } else {
//                 // Legacy: статический ключ с AAD
//                 safeDecryptSync(msg.text, chatKey, chatId, onLegacySuccess = { plain ->
//                     legacyPlain = plain
//                 })
//             }
//         }
//     }
// }
//
// // LaunchedEffect для рачет-дешифровки
// LaunchedEffect(msg.fKey) {
//     val session = ratchetSession ?: return@LaunchedEffect
//     if (msg.seqNum <= 0) return@LaunchedEffect  // Legacy сообщение
//
//     val incoming = IncomingMessage(
//         ciphertext    = msg.text,
//         seqNum        = msg.seqNum,
//         ratchetPubKey = msg.ratchetPubKey,  // нужно добавить в MessageEntity
//         senderId      = msg.senderId
//     )
//     val result = sessionManager.decryptMessage(session, incoming)
//     if (result != null) {
//         val (plaintext, updatedSession) = result
//         cachedDecryptedTexts[msg.fKey] = plaintext
//         ratchetSession = updatedSession
//     }
// }

// ── [5] FIREBASE LISTENER: сохранение seqNum ────────────────
//
// При получении сообщения из Firebase — сохранять seqNum в MessageEntity.
// Добавить в MessageEntity:
//   val seqNum: Int = 0,
//   val ratchetPubKey: String? = null
//
// В Firebase listener:
// val seqNum = rMsg.child("seqNum").getValue(Int::class.java) ?: 0
// val ratchetPubKey = rMsg.child("ratchetPubKey").getValue(String::class.java)
// dao.insertMessage(MessageEntity(
//     chatId       = chatId,
//     senderId     = senderId,
//     text         = encText,
//     timestamp    = ts,
//     isFromMe     = senderId == myId,
//     fKey         = key,
//     seqNum       = seqNum,           // ← НОВОЕ
//     ratchetPubKey = ratchetPubKey    // ← НОВОЕ
// ))

// ── [6] TOFU ПРЕДУПРЕЖДЕНИЕ В UI ────────────────────────────
//
// В теле ChatScreen добавить Dialog при KEY_CHANGED:
//
// if (tofuWarning == GhostSessionManager.TofuStatus.KEY_CHANGED) {
//     GlassAlertDialog(
//         title = "⚠️ Ключ безопасности изменился",
//         text = "Ключ шифрования $liveRecipientName изменился с момента последнего общения. " +
//                "Это может означать переустановку приложения или атаку типа MITM. " +
//                "Проверьте ключ лично с контактом.",
//         isDark = isDark, accent = Color(0xFFFF4B4B),
//         confirmText = "Проверить ключ",
//         onConfirm = { /* navigateToKeyVerification */ },
//         dismissText = "Игнорировать",
//         onDismiss = { tofuWarning = null }
//     )
// }

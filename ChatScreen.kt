package io.ghostsoftware.ghostchat

import android.app.Activity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.util.Log
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

// ══════════════════════════════════════════════════════════
// FLAT BLACK DESIGN SYSTEM
//
// Никаких Brush-градиентов, drawBehind-эффектов (grain/vignette),
// никакой alpha-полупрозрачности на topBar/bottomBar (раньше это
// давало эффект "стекла" поверх контента). Плоские сплошные цвета,
// как в Telegram / Signal / Nothing OS.
// ══════════════════════════════════════════════════════════

private object FlatDesign {

    /** Фон экрана — если юзер не задал кастомный цвет, чистый чёрный */
    fun screenBackground(base: Color): Color =
        if (base == Color.Unspecified) Color.Black else base

    /** Разделительная линия topBar/bottomBar — тонкая, плоская, без градиента */
    fun divider(isDark: Boolean): Color =
        if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)

    /** Пузырь чужого сообщения — сплошной тёмно-серый, как в Telegram/Signal */
    fun recipientBubble(isDark: Boolean): Color =
        if (isDark) Color(0xFF1C1C1E) else Color(0xFFEFEFF0)

    /** Пузырь своего сообщения — сплошной accent, без прозрачности/подсветки */
    fun myBubble(accent: Color): Color = accent

    fun secondaryText(isDark: Boolean): Color =
        if (isDark) Color.White.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.45f)

    fun surfaceMuted(isDark: Boolean): Color =
        if (isDark) Color(0xFF1C1C1E) else Color(0xFFEFEFF0)
}

@Composable
fun GlassAvatar(
    avatarUrl: String?,
    fallbackLetter: String,
    size: Dp,
    accent: Color,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    ringWidth: Dp = 1.5.dp
) {
    val context = LocalContext.current
    val backgroundColor = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF0F0F2)

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(ringWidth, accent.copy(alpha = 0.55f), CircleShape)
            .padding(ringWidth)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        if (!avatarUrl.isNullOrBlank()) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(avatarUrl)
                    .crossfade(true)
                    .memoryCacheKey(avatarUrl)
                    .diskCacheKey(avatarUrl)
                    .build(),
                contentDescription = "Avatar",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
                loading = { AvatarFallbackLetter(letter = fallbackLetter, accent = accent) },
                error = { AvatarFallbackLetter(letter = fallbackLetter, accent = accent) }
            )
        } else {
            AvatarFallbackLetter(letter = fallbackLetter, accent = accent)
        }
    }
}

@Composable
private fun AvatarFallbackLetter(letter: String, accent: Color) {
    Text(text = letter.uppercase(), color = accent, fontWeight = FontWeight.Bold, fontSize = 17.sp)
}

@Composable
fun SmallGlassAvatar(
    avatarUrl: String?,
    fallbackLetter: String,
    accent: Color,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val backgroundColor = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF0F0F2)

    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .border(1.dp, accent.copy(alpha = 0.45f), CircleShape)
            .padding(1.5.dp)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        if (!avatarUrl.isNullOrBlank()) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(avatarUrl)
                    .crossfade(true)
                    .memoryCacheKey(avatarUrl)
                    .diskCacheKey(avatarUrl)
                    .build(),
                contentDescription = "Avatar",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
                loading = { SmallAvatarFallback(letter = fallbackLetter, accent = accent) },
                error = { SmallAvatarFallback(letter = fallbackLetter, accent = accent) }
            )
        } else {
            SmallAvatarFallback(letter = fallbackLetter, accent = accent)
        }
    }
}

@Composable
private fun SmallAvatarFallback(letter: String, accent: Color) {
    Text(text = letter.uppercase(), color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
}

// ══════════════════════════════════════════════════════════
// CHAT SCREEN
// ══════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    database: AppDatabase,
    keyManager: GhostKeyManager,
    recipientId: String,
    recipientName: String,
    onBack: () -> Unit,
    onVideoCall: (() -> Unit)? = null,
    onAudioCall: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val auth = FirebaseAuth.getInstance()
    val myId = auth.currentUser?.uid ?: ""
    val chatId = remember(myId, recipientId) { ChatUtils.getChatId(myId, recipientId) }

    val messageDao = database.messageDao()
    val sessionManager = remember { GhostSessionManager(context, keyManager, database.ratchetSessionDao()) }

    val settingsState by messageDao.getSettings().collectAsState(initial = SettingsEntity())
    val currentSettings = settingsState ?: SettingsEntity()
    val messages by messageDao.getMessagesForChat(chatId).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val chatRef = FirebaseDatabase.getInstance().getReference("messages").child(chatId)
    val statusRef = FirebaseDatabase.getInstance().getReference("status").child(recipientId)
    val myStatusRef = FirebaseDatabase.getInstance().getReference("status").child(myId)
    val typingRef = FirebaseDatabase.getInstance().getReference("typing").child(chatId)
    val usersRef = FirebaseDatabase.getInstance().getReference("users")

    // Легаси-ключ оставлен только как fallback для сообщений ДО внедрения рачета.
    val chatKeysRef = FirebaseDatabase.getInstance().getReference("chats").child(chatId).child("keys")

    var statusText by remember { mutableStateOf("не в сети") }
    var isRecipientTyping by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var chatSecretKey by remember { mutableStateOf("") } // только для legacy-фоллбэка
    var recipientPublicKey by remember { mutableStateOf<String?>(null) }
    var isUploading by remember { mutableStateOf(false) }
    var liveRecipientName by remember { mutableStateOf(recipientName) }
    var recipientSecurityLevel by remember { mutableIntStateOf(0) }
    var isKeyLoading by remember { mutableStateOf(true) }
    var recipientAvatarUrl by remember { mutableStateOf<String?>(null) }
    var tofuWarning by remember { mutableStateOf<GhostSessionManager.TofuStatus?>(null) }

    var viewerImageUrl by remember { mutableStateOf<String?>(null) }
    var messageToManage by remember { mutableStateOf<MessageEntity?>(null) }
    var replyingMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var editingMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            isUploading = true
            ProfileManager(context).uploadImage(it) { url ->
                isUploading = false
                if (url != null) {
                    val pubKey = recipientPublicKey
                    if (pubKey == null) {
                        Toast.makeText(context, "Ключ собеседника ещё не загружен", Toast.LENGTH_SHORT).show()
                        return@uploadImage
                    }
                    scope.launch {
                        try {
                            val rawData = "GHOST_IMG:$url"
                            val pushRef = chatRef.push()
                            val fKey = pushRef.key ?: return@launch
                            val enc = sessionManager.encryptMessage(chatId, myId, recipientId, pubKey, rawData)
                            val msgMap = mutableMapOf<String, Any>(
                                "senderId" to myId,
                                "text" to enc.ciphertext,
                                "seqNum" to enc.seqNum,
                                "ratchetPubKey" to enc.ratchetPubKey,
                                "timestamp" to ServerValue.TIMESTAMP,
                                "status" to 1
                            )
                            enc.x3dhEphemeralPubKey?.let { msgMap["x3dhEphemeralPubKey"] = it }
                            // Сую ю ю ю

                            replyingMessage?.let { rMsg ->
                                if (rMsg.isPlaintextCached && rMsg.text.isNotBlank() && chatSecretKey.isNotBlank()) {
                                    try {
                                        msgMap["replyToText"] = GhostCrypto.encryptMessage(rMsg.text, chatSecretKey, chatId)
                                    } catch (e: SecurityException) {
                                        Log.w("ChatScreen", "photoSend: reply encryption failed, omitting quote: ${e.javaClass.simpleName}")
                                    }
                                }
                            }

                            withContext(Dispatchers.IO) {
                                // 1. Бля тут короче локально пишем сначало
                                messageDao.insertMessage(MessageEntity(
                                    chatId = chatId, senderId = myId, text = rawData,
                                    timestamp = System.currentTimeMillis(), isFromMe = true, fKey = fKey,
                                    seqNum = enc.seqNum, ratchetPubKey = enc.ratchetPubKey,
                                    isPlaintextCached = true, currentStatus = 1
                                ))

                                // 2.  отправляем в Firebase
                                pushRef.setValue(msgMap).await()
                            }
                            replyingMessage = null
                        } catch (e: SecurityException) {
                            Log.e("ChatScreen", "photoSend: encryption failed: ${e.javaClass.simpleName}", e)
                            Toast.makeText(
                                context,
                                "Не удалось зашифровать фото. Ключи ещё инициализируются, попробуйте снова.",
                                Toast.LENGTH_LONG
                            ).show()
                        } catch (e: Exception) {
                            Log.e("ChatScreen", "photoSend: unexpected failure: ${e.javaClass.simpleName}", e)
                            Toast.makeText(context, "Ошибка отправки фото", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    BackHandler(
        enabled = viewerImageUrl != null || messageToManage != null ||
                replyingMessage != null || editingMessage != null
    ) {
        viewerImageUrl = null; messageToManage = null
        replyingMessage = null; editingMessage = null
    }

    // Legacy-ключ грузим параллельно — нужен ТОЛЬКО чтобы расшифровывать
    // старые до-рачетные сообщения, на отправку новых больше не влияет.
    LaunchedEffect(chatId) {
        val resolvedKey = withContext(Dispatchers.IO) {
            resolveOrCreateChatKey(
                myId = myId, chatId = chatId, recipientId = recipientId,
                keyManager = keyManager, chatKeysRef = chatKeysRef, messageDao = messageDao
            )
        }
        chatSecretKey = resolvedKey

        val pubKey = withContext(Dispatchers.IO) { keyManager.getRecipientPublicKey(recipientId) }
        recipientPublicKey = pubKey

        if (pubKey != null) {
            val tofu = sessionManager.checkTofuConsistency(chatId, recipientId)
            if (tofu == GhostSessionManager.TofuStatus.KEY_CHANGED) {
                tofuWarning = tofu
            }
        }

        isKeyLoading = false
        Log.d("KeyInit", "ready chatId_len=${chatId.length} hasPubKey=${pubKey != null}")
    }

    LaunchedEffect(myId) {
        myStatusRef.onDisconnect().setValue(ServerValue.TIMESTAMP)
        while (isActive) { myStatusRef.setValue(ServerValue.TIMESTAMP); delay(60000) }
    }

    // ────────────────────────────────────────────────────────────────
    // ФИКС: те же 4 листенера, что и раньше, НО теперь дополнительно
    // регистрируются в AppSessionManager. onDispose{} — основной путь
    // отписки (срабатывает при уходе с экрана чата), AppSessionManager —
    // страховка на случай, если процесс убьют посреди logout-гонки и
    // Compose не успеет корректно продиспоузить. Порядок critical:
    // untrack() снимает listener СРАЗУ ЖЕ, так что двойного removeEventListener
    // не происходит — Firebase SDK идемпотентен к повторному remove.
    // ────────────────────────────────────────────────────────────────
    DisposableEffect(recipientId) {
        val recipientRef = usersRef.child(recipientId)
        val recipientTypingRef = typingRef.child(recipientId)

        val nameListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                liveRecipientName =
                    s.child("username").getValue(String::class.java) ?: liveRecipientName
                recipientSecurityLevel = s.child("securityLevel").getValue(Int::class.java) ?: 0
                recipientAvatarUrl = s.child("avatarUrl").getValue(String::class.java)
                    ?: s.child("profileImage").getValue(String::class.java)
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        AppSessionManager.track(recipientRef, nameListener)
        recipientRef.addValueEventListener(nameListener)

        val typingListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                isRecipientTyping = s.getValue(Boolean::class.java) ?: false
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        AppSessionManager.track(recipientTypingRef, typingListener)
        recipientTypingRef.addValueEventListener(typingListener)

        val statusListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val lastSeen = s.getValue(Long::class.java) ?: 0L
                statusText =
                    if (System.currentTimeMillis() - lastSeen < 120000) "в сети" else "был(а) недавно"
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        AppSessionManager.track(statusRef, statusListener)
        statusRef.addValueEventListener(statusListener)

        // ── Главный листенер сообщений: расшифровка рачет-сообщений ОДИН РАЗ здесь ──
        val chatListener = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) {
                val fKey = s.key ?: return
                val senderId = s.child("senderId").getValue(String::class.java) ?: ""
                val remoteStatus = s.child("status").getValue(Int::class.java) ?: 1
                if (senderId != myId && remoteStatus < 3) chatRef.child(fKey).child("status").setValue(3)

                val rawText = s.child("text").getValue(String::class.java) ?: ""
                val seqNum = s.child("seqNum").getValue(Int::class.java) ?: 0
                val ratchetPubKey = s.child("ratchetPubKey").getValue(String::class.java)
                val x3dhEphemeralPubKey = s.child("x3dhEphemeralPubKey").getValue(String::class.java)
                val timestamp = s.child("timestamp").getValue(Long::class.java) ?: System.currentTimeMillis()
                val encryptedReplyToText = s.child("replyToText").getValue(String::class.java)
                val isEdited = s.child("isEdited").getValue(Boolean::class.java) ?: false

                scope.launch(Dispatchers.IO) {
                    val existingMsg = messageDao.getMessageByFirebaseKey(fKey)

                    // 1. ЕСЛИ ЭТО НАШЕ СООБЩЕНИЕ (СВОЁ)
                    if (senderId == myId) {
                        if (existingMsg != null) {
                            if (existingMsg.currentStatus != remoteStatus) {
                                messageDao.insertMessage(existingMsg.copy(currentStatus = remoteStatus))
                            }
                        } else {
                            val decryptedReplyText: String? = if (!encryptedReplyToText.isNullOrBlank()) {
                                try { GhostCrypto.decryptMessage(encryptedReplyToText, chatSecretKey, chatId) } catch (e: Exception) { null }
                            } else null

                            messageDao.insertMessage(MessageEntity(
                                chatId = chatId, senderId = senderId, text = rawText, timestamp = timestamp,
                                isFromMe = true, fKey = fKey, seqNum = seqNum, ratchetPubKey = ratchetPubKey,
                                rText = decryptedReplyText,
                                isPlaintextCached = false, currentStatus = remoteStatus
                            ))
                        }
                        return@launch
                    }

                    // 2. ЕСЛИ СООБЩЕНИЕ ЧУЖОЕ И УЖЕ ЕСТЬ В БАЗЕ — ИГНОРИРУЕМ
                    if (existingMsg != null) return@launch

                    // 3. ОБРАБОТКА ВХОДЯЩИХ СООБЩЕНИЙ ОТ СОБЕСЕДНИКА
                    val decryptedReplyText: String? = if (!encryptedReplyToText.isNullOrBlank()) {
                        try {
                            GhostCrypto.decryptMessage(encryptedReplyToText, chatSecretKey, chatId)
                        } catch (e: Exception) {
                            Log.w("ChatScreen", "onChildAdded: reply preview decrypt failed fKey=$fKey: ${e.javaClass.simpleName}")
                            null
                        }
                    } else null

                    if (ratchetPubKey != null) {
                        val plaintext = sessionManager.decryptMessage(
                            chatId = chatId, myId = myId, recipientId = recipientId,
                            incoming = IncomingMessage(
                                ciphertext = rawText, seqNum = seqNum,
                                ratchetPubKey = ratchetPubKey,
                                x3dhEphemeralPubKey = x3dhEphemeralPubKey,
                                senderId = senderId
                            )
                        )
                        messageDao.insertMessage(MessageEntity(
                            chatId = chatId, senderId = senderId,
                            text = plaintext ?: "[не удалось расшифровать]",
                            timestamp = timestamp, isFromMe = false, fKey = fKey,
                            seqNum = seqNum, ratchetPubKey = ratchetPubKey,
                            rText = decryptedReplyText,
                            isPlaintextCached = true, currentStatus = remoteStatus
                        ))
                    } else {
                        messageDao.insertMessage(MessageEntity(
                            chatId = chatId, senderId = senderId, text = rawText, timestamp = timestamp,
                            isFromMe = false, fKey = fKey,
                            rText = decryptedReplyText,
                            isEdit = isEdited,
                            isPlaintextCached = false, currentStatus = remoteStatus
                        ))
                    }
                }
            }

            override fun onChildChanged(s: DataSnapshot, prev: String?) {
                val fKey = s.key ?: return
                val isEdited = s.child("isEdited").getValue(Boolean::class.java) ?: false
                val newStatus = s.child("status").getValue(Int::class.java)
                val newCiphertext = s.child("text").getValue(String::class.java)

                scope.launch(Dispatchers.IO) {
                    val old = messageDao.getMessageByFirebaseKey(fKey) ?: return@launch

                    if (isEdited && newCiphertext != null && newCiphertext != old.text) {
                        val decrypted = if (chatSecretKey.isNotBlank()) {
                            try {
                                GhostCrypto.decryptMessage(newCiphertext, chatSecretKey, chatId)
                            } catch (e: SecurityException) {
                                Log.e("ChatScreen", "onChildChanged: edit decrypt failed fKey=$fKey: ${e.javaClass.simpleName}")
                                null
                            }
                        } else {
                            Log.w("ChatScreen", "onChildChanged: legacy key not ready, deferring edit decrypt fKey=$fKey")
                            null
                        }

                        messageDao.insertMessage(
                            old.copy(
                                text = decrypted ?: old.text,
                                isEdit = true,
                                isPlaintextCached = decrypted != null,
                                seqNum = if (decrypted != null) 0 else old.seqNum,
                                ratchetPubKey = if (decrypted != null) null else old.ratchetPubKey,
                                currentStatus = newStatus ?: old.currentStatus
                            )
                        )
                    } else {
                        messageDao.insertMessage(
                            old.copy(
                                isEdit = isEdited,
                                currentStatus = newStatus ?: old.currentStatus
                            )
                        )
                    }
                }
            }

            override fun onChildRemoved(s: DataSnapshot) {
                scope.launch(Dispatchers.IO) { messageDao.deleteByFirebaseKey(s.key ?: "") }
            }
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        AppSessionManager.track(chatRef, chatListener)
        chatRef.addChildEventListener(chatListener)

        onDispose {
            AppSessionManager.untrack(recipientRef, nameListener)
            AppSessionManager.untrack(recipientTypingRef, typingListener)
            AppSessionManager.untrack(statusRef, statusListener)
            AppSessionManager.untrack(chatRef, chatListener)
        }
    }
    val chatAccent = Color(currentSettings.globalAccentColor)
    val bgColor = FlatDesign.screenBackground(Color(currentSettings.globalBackgroundColor))
    val isDark = bgColor.luminance() < 0.5f
    val contentColor = if (isDark) Color.White else Color.Black
    val textOnMe = if (chatAccent.luminance() > 0.5f) Color.Black else Color.White
    val isSecureSession = currentSettings.securityLevel >= 1 || recipientSecurityLevel >= 1

    DisposableEffect(isSecureSession) {
        if (isSecureSession && activity != null) {
            activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    // Плоский чёрный фон, БЕЗ градиента и БЕЗ верхней "glow"-полосы.
    Box(Modifier.fillMaxSize().background(bgColor)) {
        currentSettings.chatWallpaperUrl?.let {
            AsyncImage(model = it, contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.10f)
        }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                FlatTopBar(
                    recipientName = liveRecipientName,
                    recipientAvatarUrl = recipientAvatarUrl,
                    statusText = statusText,
                    isTyping = isRecipientTyping,
                    accent = chatAccent,
                    contentColor = contentColor,
                    isDark = isDark,
                    onBack = onBack,
                    onVideoCall = onVideoCall,
                    onAudioCall = onAudioCall,
                    onClear = { showClearDialog = true }
                )
            },
            bottomBar = {
                FlatBottomPanel(
                    text = inputText,
                    onTextChange = {
                        inputText = it
                        if (!currentSettings.stealthMode) typingRef.child(myId).setValue(it.isNotBlank())
                    },
                    accent = chatAccent, isDark = isDark, contentColor = contentColor,
                    uploading = isUploading, replyingMessage = replyingMessage,
                    editingMessage = editingMessage,
                    onCancelReply = { replyingMessage = null },
                    onCancelEdit = { editingMessage = null; inputText = "" },
                    onPhoto = { photoLauncher.launch("image/*") },
                    onSend = {
                        if (inputText.isBlank()) return@FlatBottomPanel
                        val pubKey = recipientPublicKey
                        if (pubKey == null) {
                            Toast.makeText(context, "Ключ собеседника ещё не загружен, попробуйте через секунду", Toast.LENGTH_SHORT).show()
                            return@FlatBottomPanel
                        }
                        val textToSend = inputText.trim()
                        val editTarget = editingMessage
                        val replyTarget = replyingMessage

                        inputText = ""; editingMessage = null; replyingMessage = null
                        typingRef.child(myId).setValue(false)

                        scope.launch {
                            try {
                                if (editTarget != null) {
                                    // Редактирование оставляем на legacy-ключе — Double Ratchet
                                    // принципиально не поддерживает "изменить уже отправленное
                                    // сообщение" (ключ уничтожен сразу после отправки).
                                    val encrypted = GhostCrypto.encryptMessage(textToSend, chatSecretKey, chatId)
                                    chatRef.child(editTarget.fKey).updateChildren(
                                        mapOf("text" to encrypted, "isEdited" to true)
                                    )
                                    withContext(Dispatchers.IO) {
                                        // ФИКС БАГА №3a: text уже plaintext (textToSend) → isPlaintextCached
                                        // ОБЯЗАН быть true, иначе safeDecryptSync увидит унаследованный
                                        // seqNum>0 от старого ratchet-сообщения, посчитает это ratchet-payload
                                        // и покажет заглушку "Отправлено с другого устройства" вместо текста,
                                        // который уже лежит в Room в открытом виде.
                                        // seqNum/ratchetPubKey сбрасываем — сообщение теперь навсегда живёт
                                        // в legacy-схеме, повторный decrypt (если isPlaintextCached когда-либо
                                        // собьётся) должен идти по legacy-пути, а не пытаться найти давно
                                        // уничтоженный ratchet message key.
                                        messageDao.updateEditedMessage(editTarget.fKey, textToSend)


                                    }
                                } else {
                                    val pushRef = chatRef.push()
                                    val fKey = pushRef.key ?: return@launch
                                    val enc = sessionManager.encryptMessage(chatId, myId, recipientId, pubKey, textToSend)

                                    val msgMap = mutableMapOf<String, Any>(
                                        "senderId" to myId,
                                        "text" to enc.ciphertext,
                                        "seqNum" to enc.seqNum,
                                        "ratchetPubKey" to enc.ratchetPubKey,
                                        "timestamp" to ServerValue.TIMESTAMP,
                                        "status" to 1
                                    )
                                    enc.x3dhEphemeralPubKey?.let { msgMap["x3dhEphemeralPubKey"] = it }

                                    // ФИКС БАГА №1: раньше сюда клался ОТКРЫТЫЙ ТЕКСТ цитаты (rMsg.text),
                                    // минуя всё E2EE. Теперь шифруем legacy-ключом чата — той же схемой,
                                    // что и правки (chatSecretKey к этому моменту уже резолвлен, см.
                                    // LaunchedEffect(chatId) → resolveOrCreateChatKey выше по файлу).
                                    replyTarget?.let { rMsg ->
                                        if (rMsg.isPlaintextCached && rMsg.text.isNotBlank() && chatSecretKey.isNotBlank()) {
                                            try {
                                                msgMap["replyToText"] = GhostCrypto.encryptMessage(rMsg.text, chatSecretKey, chatId)
                                            } catch (e: SecurityException) {
                                                Log.w("ChatScreen", "onSend: reply encryption failed, omitting quote: ${e.javaClass.simpleName}")
                                            }
                                        }
                                    }

                                    withContext(Dispatchers.IO) {
                                        // 1. СНАЧАЛА вставляем в локальную базу Room с isPlaintextCached = true!
                                        messageDao.insertMessage(MessageEntity(
                                            chatId = chatId, senderId = myId, text = textToSend,
                                            timestamp = System.currentTimeMillis(), isFromMe = true, fKey = fKey,
                                            rText = if (replyTarget?.isPlaintextCached == true) replyTarget.text else null,
                                            seqNum = enc.seqNum, ratchetPubKey = enc.ratchetPubKey,
                                            isPlaintextCached = true, currentStatus = 1
                                        ))

                                        // 2. И ТОЛЬКО ПОТОМ отправляем в Firebase!
                                        pushRef.setValue(msgMap).await()
                                    }
                                }
                            } catch (e: SecurityException) {
                                Log.e("ChatScreen", "onSend: encryption failed: ${e.javaClass.simpleName}", e)
                                inputText = textToSend
                                Toast.makeText(
                                    context,
                                    "Не удалось зашифровать сообщение. Ключи ещё инициализируются, попробуйте через пару секунд.",
                                    Toast.LENGTH_LONG
                                ).show()
                            } catch (e: Exception) {
                                Log.e("ChatScreen", "onSend: unexpected failure: ${e.javaClass.simpleName}", e)
                                inputText = textToSend
                                Toast.makeText(context, "Ошибка отправки сообщения", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (!isKeyLoading && messages.isEmpty()) {
                    EmptyChatState(contentColor = contentColor, accent = chatAccent)
                }

                LazyColumn(
                    state = listState, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    items(messages, key = { it.fKey }) { msg ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(tween(220, easing = FastOutSlowInEasing)) +
                                    slideInVertically(initialOffsetY = { it / 6 }, animationSpec = tween(220, easing = FastOutSlowInEasing))
                        ) {
                            GlassBubble(
                                msg = msg,
                                accent = chatAccent,
                                textOnMe = textOnMe,
                                chatKey = chatSecretKey,
                                chatId = chatId,
                                settings = currentSettings,
                                isDark = isDark,
                                senderName = if (!msg.isFromMe) liveRecipientName else "",
                                senderAvatarUrl = if (!msg.isFromMe) recipientAvatarUrl else null,
                                onLong = { messageToManage = msg },
                                onImg = { viewerImageUrl = it },
                                onLegacyDecrypt = { legacyMsg, plain ->
                                    scope.launch(Dispatchers.IO) {
                                        reEncryptAndUpdate(legacyMsg, plain, chatSecretKey, chatId, messageDao)
                                    }
                                }
                            )
                        }
                    }
                }

                if (isKeyLoading) {
                    Surface(
                        modifier = Modifier.align(Alignment.Center),
                        color = FlatDesign.surfaceMuted(isDark),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            Modifier.padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = chatAccent, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("Установка шифрования...", color = contentColor.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }

    if (tofuWarning == GhostSessionManager.TofuStatus.KEY_CHANGED) {
        GlassAlertDialog(
            title = "⚠️ Ключ безопасности изменился",
            text = "Ключ шифрования $liveRecipientName изменился с момента последнего общения. " +
                    "Это может означать переустановку приложения или атаку MITM. Проверьте ключ лично.",
            isDark = isDark,
            accent = Color(0xFFFF4B4B),
            confirmText = "Принять новый ключ",
            confirmColor = Color(0xFFFF4B4B),
            onConfirm = {
                tofuWarning = null
                scope.launch(Dispatchers.IO) {
                    // 1. Стираем старый левый ключ из Room
                    messageDao.deleteSecretKeyForChat(chatId)

                    // 2. Запускаем пересоздание с твоими родными переменными из LaunchedEffect!
                    resolveOrCreateChatKey(
                        myId = myId,
                        chatId = chatId,
                        recipientId = recipientId,
                        keyManager = keyManager,
                        chatKeysRef = chatKeysRef,
                        messageDao = messageDao
                    )
                }
            },
            dismissText = "Закрыть",
            onDismiss = { tofuWarning = null },
            onDismissAction = { tofuWarning = null }
        )
    }

    if (messageToManage != null) {
        GlassDialog(onDismiss = { messageToManage = null }, isDark = isDark, accent = chatAccent) {
            Text("Сообщение", color = if (isDark) Color.White else Color.Black,
                fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 14.dp))
            GhostActionItem(Icons.Default.Reply, "Ответить", chatAccent) {
                replyingMessage = messageToManage; messageToManage = null
            }
            if (messageToManage?.isFromMe == true && messageToManage?.isPlaintextCached == true) {
                GhostActionItem(Icons.Default.Edit, "Изменить", chatAccent) {
                    editingMessage = messageToManage
                    inputText = messageToManage!!.text
                    messageToManage = null
                }
            }
            GhostActionItem(Icons.Default.Delete, "Удалить", Color(0xFFFF4B4B)) { showDeleteDialog = true }
        }
    }

    if (showDeleteDialog && messageToManage != null) {
        val msgToDelete = messageToManage
        GlassAlertDialog(
            title = "Удаление",
            text = "Удалить это сообщение?",
            isDark = isDark,
            accent = chatAccent,
            onDismiss = { showDeleteDialog = false; messageToManage = null },
            confirmText = "У всех",
            confirmColor = Color(0xFFFF4B4B),
            onConfirm = {
                msgToDelete?.let { msg ->
                    chatRef.child(msg.fKey).removeValue()
                    scope.launch(Dispatchers.IO) { messageDao.deleteByFirebaseKey(msg.fKey) }
                }
                showDeleteDialog = false
                messageToManage = null
            },
            dismissText = "У себя",
            onDismissAction = {
                msgToDelete?.let { msg ->
                    scope.launch(Dispatchers.IO) { messageDao.deleteByFirebaseKey(msg.fKey) }
                }
                showDeleteDialog = false
                messageToManage = null
            }
        )
    }

    if (showClearDialog) {
        GlassAlertDialog(
            title = "Очистить чат?", text = "Все сообщения будут удалены",
            isDark = isDark, accent = chatAccent,
            onDismiss = { showClearDialog = false },
            confirmText = "У всех", confirmColor = Color(0xFFFF4B4B),
            onConfirm = {
                chatRef.removeValue()
                scope.launch(Dispatchers.IO) { messageDao.deleteMessagesForChat(chatId) }
                showClearDialog = false
            },
            dismissText = "Только у меня",
            onDismissAction = {
                scope.launch(Dispatchers.IO) { messageDao.deleteMessagesForChat(chatId) }
                showClearDialog = false
            }
        )
    }

    if (viewerImageUrl != null) {
        GhostImageViewer(url = viewerImageUrl!!, onDismiss = { viewerImageUrl = null })
    }
}

// ══════════════════════════════════════════════════════════
// LEGACY-КЛЮЧ ЧАТА (фоллбэк для до-рачетных сообщений)
// ══════════════════════════════════════════════════════════

private suspend fun resolveOrCreateChatKey(
    myId: String, chatId: String, recipientId: String,
    keyManager: GhostKeyManager, chatKeysRef: DatabaseReference, messageDao: MessageDao
): String {
    val cached = messageDao.getSecretKeyForChat(chatId)
    if (!cached.isNullOrBlank()) return cached

    val encBundle: String? = try {
        chatKeysRef.child(myId).get().await().getValue(String::class.java)
    } catch (e: Exception) { null }

    if (!encBundle.isNullOrBlank()) {
        val decrypted = keyManager.decryptChatKey(encBundle)
        if (!decrypted.isNullOrBlank()) {
            messageDao.saveSecretKey(EncryptionKeyEntity(chatId, decrypted))
            return decrypted
        }
    }

    return createAndPublishNewChatKey(myId, chatId, recipientId, keyManager, chatKeysRef, messageDao)
}

private suspend fun createAndPublishNewChatKey(
    myId: String, chatId: String, recipientId: String,
    keyManager: GhostKeyManager, chatKeysRef: DatabaseReference, messageDao: MessageDao
): String {
    val newKey = GhostCrypto.generateRandomKeyBase64()
    messageDao.saveSecretKey(EncryptionKeyEntity(chatId, newKey))

    val myPub = keyManager.getX25519PublicKeyBase64()
    if (myPub != null) {
        try {
            val encForMe = GhostCrypto.encryptKeyForUser(newKey, myPub)
            chatKeysRef.child(myId).setValue(encForMe).await()
        } catch (e: Exception) { Log.w("KeyInit", "publish self failed: ${e.javaClass.simpleName}") }
    }

    val recipientPub: String? = try {
        FirebaseDatabase.getInstance().getReference("users/$recipientId/keys/x25519").get().await()
            .getValue(String::class.java)
    } catch (e: Exception) { null }

    if (!recipientPub.isNullOrBlank()) {
        try {
            val encForThem = GhostCrypto.encryptKeyForUser(newKey, recipientPub)
            chatKeysRef.child(recipientId).setValue(encForThem).await()
        } catch (e: Exception) { Log.w("KeyInit", "publish recipient failed: ${e.javaClass.simpleName}") }
    }

    return newKey
}

// ══════════════════════════════════════════════════════════
// EMPTY STATE — "Начните общение" (как в target-дизайне)
//
// Рендерится в Box поверх LazyColumn, когда список сообщений пуст
// и ключи уже загружены (isKeyLoading == false). Чисто визуальный
// композабл, никакой логики не содержит.
// ══════════════════════════════════════════════════════════

@Composable
private fun EmptyChatState(contentColor: Color, accent: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                shape = CircleShape,
                color = contentColor.copy(alpha = 0.06f),
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        tint = contentColor.copy(alpha = 0.35f),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Начните общение",
                color = contentColor.copy(alpha = 0.85f),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Отправьте сообщение, чтобы начать\nзащищённый чат",
                color = contentColor.copy(alpha = 0.4f),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

// ══════════════════════════════════════════════════════════
// FLAT TOP BAR — сплошной чёрный/белый, без blur и alpha-стекла
// ══════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlatTopBar(
    recipientName: String, recipientAvatarUrl: String?, statusText: String, isTyping: Boolean,
    accent: Color, contentColor: Color, isDark: Boolean, onBack: () -> Unit,
    onVideoCall: (() -> Unit)?, onAudioCall: (() -> Unit)?, onClear: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(FlatDesign.screenBackground(if (isDark) Color.Black else Color.White))
            .statusBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = if (isDark) Color(0xFF1C1C1E) else Color(0xFFEFEFF0)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = contentColor.copy(alpha = 0.9f))
                }

                GlassAvatar(
                    avatarUrl = recipientAvatarUrl,
                    fallbackLetter = recipientName.firstOrNull()?.toString() ?: "?",
                    size = 38.dp, accent = accent, isDark = isDark, ringWidth = 1.5.dp
                )

                Spacer(Modifier.width(10.dp))

                Column(Modifier.weight(1f)) {
                    Text(recipientName, color = contentColor,
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, letterSpacing = (-0.1).sp)
                    Spacer(Modifier.height(1.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isTyping) { TypingDots(accent = accent); Spacer(Modifier.width(6.dp)) }
                        Text(
                            text = if (isTyping) "печатает..." else statusText,
                            color = if (isTyping) accent else FlatDesign.secondaryText(isDark),
                            fontSize = 11.sp,
                            fontWeight = if (isTyping) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }

                if (onAudioCall != null) {
                    TopBarCircleButton(onClick = onAudioCall, isDark = isDark) {
                        Icon(Icons.Default.Call, "Аудиозвонок", tint = contentColor.copy(alpha = 0.9f), modifier = Modifier.size(17.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                }
                if (onVideoCall != null) {
                    TopBarCircleButton(onClick = onVideoCall, isDark = isDark) {
                        Icon(Icons.Default.Videocam, "Видеозвонок", tint = contentColor.copy(alpha = 0.9f), modifier = Modifier.size(17.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                }
                TopBarCircleButton(onClick = onClear, isDark = isDark) {
                    Icon(Icons.Default.MoreVert, null, tint = contentColor.copy(alpha = 0.9f), modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}

/** Round dark-filled icon button for top-bar actions — matches reference screenshot */
@Composable
private fun TopBarCircleButton(onClick: () -> Unit, isDark: Boolean, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(34.dp),
        shape = CircleShape,
        color = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun TypingDots(accent: Color) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500, delayMillis = index * 160, easing = EaseInOutCubic),
                    repeatMode = RepeatMode.Reverse
                ), label = "dot$index"
            )
            Box(
                Modifier.padding(horizontal = 1.5.dp).size(5.dp).clip(CircleShape)
                    .background(accent.copy(alpha = alpha))
            )
        }
    }
}

private val EaseInOutCubic: Easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

// ══════════════════════════════════════════════════════════
// GLASS BUBBLE — переименование сохранено (используется извне),
// но визуал теперь полностью плоский: сплошная заливка, без
// drawWithContent-подсветки (bubbleShine) поверх контента.
// Decrypt-логика для legacy/ratchet сообщений не тронута.
// ══════════════════════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassBubble(
    msg: MessageEntity,
    accent: Color,
    textOnMe: Color,
    chatKey: String,
    chatId: String,
    settings: SettingsEntity,
    isDark: Boolean,
    senderName: String = "",
    senderAvatarUrl: String? = null,
    onLong: () -> Unit,
    onImg: (String) -> Unit,
    onLegacyDecrypt: ((msg: MessageEntity, plaintext: String) -> Unit)? = null
) {
    val isMe = msg.isFromMe

    // Рачет-сообщения УЖЕ расшифрованы при получении (см. ChatScreen-листенер) —
    // никакой работы на UI-потоке не требуется, читаем напрямую.
    // Legacy-сообщения расшифровываются здесь, но строго на Dispatchers.Default,
    // а не синхронно в remember{} на главном потоке.
    val dec by produceState(
        initialValue = if (msg.isPlaintextCached) msg.text else "Загрузка...",
        msg.fKey, msg.text, chatKey, chatId
    ) {
        value = safeDecryptSync(msg, chatKey, chatId) { plain ->
            onLegacyDecrypt?.invoke(msg, plain)
        }
    }

    val isImg = dec.startsWith("GHOST_IMG:")
    val url = if (isImg) dec.substringAfter("GHOST_IMG:") else null

    val baseRadius = settings.chatCornerRadius.dp.coerceAtLeast(12.dp)
    val tailRadius = 6.dp

    val bubbleShape = RoundedCornerShape(
        topStart = baseRadius, topEnd = baseRadius,
        bottomEnd = if (isMe) tailRadius else baseRadius,
        bottomStart = if (isMe) baseRadius else tailRadius
    )

    val bubbleColor = if (isMe) FlatDesign.myBubble(accent) else FlatDesign.recipientBubble(isDark)
    val textColor = if (isMe) textOnMe else if (isDark) Color.White.copy(alpha = 0.94f) else Color.Black.copy(alpha = 0.88f)
    val metaColor = if (isMe) textOnMe.copy(alpha = 0.55f) else FlatDesign.secondaryText(isDark)

    if (isMe) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp, start = 52.dp, end = 0.dp),
            horizontalAlignment = Alignment.End
        ) {
            BubbleContent(
                msg = msg, dec = dec, isImg = isImg, url = url, isMe = true,
                bubbleShape = bubbleShape, bubbleColor = bubbleColor,
                textColor = textColor, textOnMe = textOnMe, metaColor = metaColor,
                accent = accent, settings = settings,
                onLong = onLong, onImg = onImg
            )
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp, start = 0.dp, end = 52.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Bottom
        ) {
            SmallGlassAvatar(
                avatarUrl = senderAvatarUrl,
                fallbackLetter = senderName.firstOrNull()?.toString() ?: "?",
                accent = accent, isDark = isDark,
                modifier = Modifier.padding(end = 8.dp, bottom = 2.dp)
            )
            Column(horizontalAlignment = Alignment.Start, modifier = Modifier.weight(1f, fill = false)) {
                BubbleContent(
                    msg = msg, dec = dec, isImg = isImg, url = url, isMe = false,
                    bubbleShape = bubbleShape, bubbleColor = bubbleColor,
                    textColor = textColor, textOnMe = textOnMe, metaColor = metaColor,
                    accent = accent, settings = settings,
                    onLong = onLong, onImg = onImg
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BubbleContent(
    msg: MessageEntity, dec: String, isImg: Boolean, url: String?, isMe: Boolean,
    bubbleShape: RoundedCornerShape, bubbleColor: Color,
    textColor: Color, textOnMe: Color, metaColor: Color, accent: Color,
    settings: SettingsEntity, onLong: () -> Unit, onImg: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .combinedClickable(onLongClick = onLong, onClick = { if (isImg && url != null) onImg(url) })
            .widthIn(min = 68.dp, max = 280.dp),
        shape = bubbleShape, color = bubbleColor
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (!msg.rText.isNullOrBlank()) {
                Surface(
                    modifier = Modifier.padding(bottom = 8.dp),
                    color = if (isMe) Color.Black.copy(alpha = 0.12f) else accent.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.width(2.5.dp).height(20.dp).clip(RoundedCornerShape(2.dp))
                                .background(accent)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(msg.rText!!, color = if (isMe) textOnMe.copy(alpha = 0.65f) else metaColor,
                            fontSize = 12.sp, maxLines = 1, fontWeight = FontWeight.Medium)
                    }
                }
            }

            if (isImg) {
                AsyncImage(model = url, contentDescription = null,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).widthIn(max = 240.dp),
                    contentScale = ContentScale.FillWidth)
            } else {
                Text(dec, color = textColor, fontSize = settings.fontSize.sp,
                    lineHeight = (settings.fontSize + 5).sp)
            }

            Row(
                modifier = Modifier.align(Alignment.End).padding(top = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (msg.isEdit) Text("изм.", color = metaColor, fontSize = 9.sp)
                Text(
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp)),
                    color = metaColor, fontSize = 10.sp, letterSpacing = 0.3.sp
                )
                if (isMe) ChatStatusIconLocal(status = msg.currentStatus, tint = textOnMe.copy(alpha = 0.65f))
            }
        }
    }
}

// ══════════════════════════════════════════════════════════
// FLAT BOTTOM PANEL — плоский pill-инпут в стиле target-дизайна:
// attach слева, текстовое поле по центру, круглая кнопка отправки
// (белый круг / accent) справа. Никакого свечения и грейна.
// ══════════════════════════════════════════════════════════

@Composable
private fun FlatBottomPanel(
    text: String, onTextChange: (String) -> Unit,
    accent: Color, isDark: Boolean, contentColor: Color,
    uploading: Boolean, replyingMessage: MessageEntity?,
    editingMessage: MessageEntity?,
    onCancelReply: () -> Unit, onCancelEdit: () -> Unit,
    onPhoto: () -> Unit, onSend: () -> Unit
) {
    val sendScale by animateFloatAsState(
        targetValue = if (text.isNotBlank()) 1f else 0.9f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 380f), label = "ss"
    )

    Column(
        Modifier
            .fillMaxWidth()
            .background(FlatDesign.screenBackground(if (isDark) Color.Black else Color.White))
            .navigationBarsPadding()
    ) {
        HorizontalDivider(thickness = 1.dp, color = FlatDesign.divider(isDark))

        AnimatedVisibility(
            visible = replyingMessage != null,
            enter = expandVertically(tween(180)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(140)) + fadeOut(tween(140))
        ) {
            replyingMessage?.let { msg ->
                Surface(Modifier.fillMaxWidth(), color = FlatDesign.surfaceMuted(isDark)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.width(3.dp).height(36.dp).clip(RoundedCornerShape(2.dp))
                                .background(accent)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Ответ", color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                if (msg.isPlaintextCached) msg.text.ifBlank { "..." } else "...",
                                color = FlatDesign.secondaryText(isDark), fontSize = 13.sp, maxLines = 1
                            )
                        }
                        IconButton(onClick = onCancelReply, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.Close, null, tint = contentColor.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = editingMessage != null,
            enter = expandVertically(tween(180)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(140)) + fadeOut(tween(140))
        ) {
            Surface(Modifier.fillMaxWidth(), color = FlatDesign.surfaceMuted(isDark)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Edit, null, tint = accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Редактирование", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onCancelEdit, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, null, tint = contentColor.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom) {

            // ── Единый pill: GIF-бейдж + текстовое поле + скрепка ──
            // Ровно как в референсе — всё в одном контейнере, а не разбросано по кругам.
            Surface(
                modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                shape = RoundedCornerShape(26.dp), color = FlatDesign.surfaceMuted(isDark)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // GIF-бейдж — чисто визуальный элемент как в референсе,
                    // приложение не поддерживает GIF, поэтому клик игнорируется.
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.35f)),
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Text(
                            "GIF",
                            color = contentColor.copy(alpha = 0.55f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Spacer(Modifier.width(10.dp))

                    TextField(
                        value = text, onValueChange = onTextChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Сообщение", color = contentColor.copy(alpha = 0.32f), fontSize = 15.sp) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = accent, focusedTextColor = contentColor, unfocusedTextColor = contentColor
                        ),
                        textStyle = TextStyle(fontSize = 15.sp),
                        maxLines = 5
                    )

                    IconButton(onClick = onPhoto, modifier = Modifier.size(38.dp)) {
                        if (uploading) CircularProgressIndicator(Modifier.size(18.dp), color = accent, strokeWidth = 2.dp)
                        else Icon(
                            Icons.Default.AttachFile, null,
                            tint = contentColor.copy(alpha = 0.55f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.width(10.dp))

            // ── Отдельный круглый белый button справа — mic/send, как в референсе ──
            Surface(
                onClick = { if (text.isNotBlank()) onSend() },
                modifier = Modifier.size(46.dp).align(Alignment.CenterVertically).scale(sendScale),
                shape = CircleShape,
                color = if (isDark) Color.White else Color.Black
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (text.isNotBlank()) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send, null,
                            tint = if (isDark) Color.Black else Color.White,
                            modifier = Modifier.size(20.dp).graphicsLayer { translationX = 1f }
                        )
                    } else {
                        Icon(
                            Icons.Default.Mic, null,
                            tint = if (isDark) Color.Black else Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════
// FLAT DIALOGS — без блюра/грейна, сплошной фон
// ══════════════════════════════════════════════════════════

@Composable
fun GlassDialog(onDismiss: () -> Unit, isDark: Boolean, accent: Color, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, confirmButton = {},
        containerColor = if (isDark) Color(0xFF121214) else Color(0xFFF5F5F7),
        shape = RoundedCornerShape(20.dp), tonalElevation = 0.dp,
        text = { Column { content() } }
    )
}

@Composable
fun GlassAlertDialog(
    title: String, text: String, isDark: Boolean, accent: Color,
    onDismiss: () -> Unit, confirmText: String, confirmColor: Color,
    onConfirm: () -> Unit, dismissText: String, onDismissAction: () -> Unit
) {
    val textColor = if (isDark) Color.White else Color.Black
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (isDark) Color(0xFF121214) else Color(0xFFF5F5F7),
        shape = RoundedCornerShape(20.dp), tonalElevation = 0.dp,
        title = { Text(title, color = textColor.copy(alpha = 0.95f), fontWeight = FontWeight.SemiBold, fontSize = 18.sp) },
        text = { Text(text, color = textColor.copy(alpha = 0.6f), fontSize = 14.sp, lineHeight = 20.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.padding(end = 4.dp)) {
                Text(confirmText, color = confirmColor, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissAction) {
                Text(dismissText, color = textColor.copy(alpha = 0.6f), fontSize = 15.sp)
            }
        }
    )
}

// ══════════════════════════════════════════════════════════
// SHARED COMPONENTS
// ══════════════════════════════════════════════════════════

@Composable
fun ChatStatusIconLocal(status: Int, tint: Color) {
    Icon(
        if (status >= 3) Icons.Default.DoneAll else Icons.Default.Done, null,
        modifier = Modifier.size(13.dp),
        tint = if (status >= 3) tint else tint.copy(alpha = 0.30f)
    )
}

@Composable
fun GhostImageViewer(url: String, onDismiss: () -> Unit) {
    val scale = remember { mutableFloatStateOf(1f) }
    val offset = remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, off, _ -> scale.floatValue *= zoom; offset.value += off }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.97f)).clickable { onDismiss() }) {
        AsyncImage(model = url, contentDescription = null,
            modifier = Modifier.fillMaxSize()
                .graphicsLayer(
                    scaleX = scale.floatValue.coerceIn(1f, 5f),
                    scaleY = scale.floatValue.coerceIn(1f, 5f),
                    translationX = offset.value.x,
                    translationY = offset.value.y
                )
                .transformable(state),
            contentScale = ContentScale.Fit)

        Surface(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).statusBarsPadding(),
            shape = CircleShape, color = Color.White.copy(alpha = 0.08f)
        ) {
            Icon(Icons.Default.Close, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.padding(10.dp).size(20.dp))
        }
    }
}

@Composable
fun GhostActionItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color = Color.White, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape = RoundedCornerShape(14.dp), color = color.copy(alpha = 0.06f)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(34.dp), shape = RoundedCornerShape(10.dp), color = color.copy(alpha = 0.10f)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = color, modifier = Modifier.size(18.dp)) }
            }
            Spacer(Modifier.width(14.dp))
            Text(label, color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ══════════════════════════════════════════════════════════
// LEGACY DECRYPT HELPERS (без изменений, используются только
// для до-рачетных сообщений, isPlaintextCached=false)
// ══════════════════════════════════════════════════════════

private const val CRYPTO_MIGRATION_TAG = "CryptoMigration"

suspend fun safeDecryptSync(
    msg: MessageEntity,
    legacyKey: String,
    chatId: String,
    onLegacySuccess: (plaintext: String) -> Unit = {}
): String = withContext(Dispatchers.Default) {

    // 0. Уже расшифровано и закешировано (в т.ч. ratchet-сообщения) — крипто не трогаем.
    if (msg.isPlaintextCached) {
        return@withContext msg.text
    }

    val encrypted = msg.text.trim()
    if (encrypted.isBlank()) return@withContext ""

    // 1. МАРКЕР DOUBLE RATCHET PAYLOAD. Никогда не отправлять в легаси-путь —
    //    другой ключ, другой AAD, другая KDF-схема. Даже не пытаемся.
    val isRatchetPayload = msg.seqNum > 0 || !msg.ratchetPubKey.isNullOrBlank()
    if (isRatchetPayload) {
        Log.w(
            "CryptoMigration",
            "safeDecryptSync: ratchet payload без кеша plaintext, decrypt пропущен " +
                    "fKey=${msg.fKey} seqNum=${msg.seqNum} isFromMe=${msg.isFromMe} ct_len=${encrypted.length}"
        )
        return@withContext if (msg.isFromMe) {
            "📤 Отправлено с другого устройства"
        } else {
            // Штатно недостижимо — см. комментарий выше. Если сюда долетело,
            // это повреждение локальной БД или баг миграции, не крипто-ошибка.
            "[сообщение недоступно]"
        }
    }

    // 2. ЛЕГАСИ-ПУТЬ (V1/V2, статический ключ чата). Только для НЕ-ratchet сообщений.
    if (legacyKey.isBlank()) return@withContext "Инициализация..."
    if (chatId.isBlank())   return@withContext "Инициализация..."

    val normalizedChatId = chatId.trim()

    try {
        GhostCrypto.decryptMessage(encrypted, legacyKey, normalizedChatId)
    } catch (primary: SecurityException) {
        try {
            @Suppress("DEPRECATION")
            val plain = GhostCrypto.decryptLegacy(encrypted, legacyKey)
            Log.i(
                "CryptoMigration",
                "safeDecryptSync: legacy(no-AAD) fallback succeeded ct_len=${encrypted.length}"
            )
            onLegacySuccess(plain)
            plain
        } catch (legacy: SecurityException) {
            Log.e(
                "CryptoMigration",
                "safeDecryptSync: ВСЕ попытки провалились " +
                        "primary=${primary.javaClass.simpleName} legacy=${legacy.javaClass.simpleName} " +
                        "ct_len=${encrypted.length}"
            )
            "[не удалось расшифровать]"
        }
    }
}

internal suspend fun reEncryptAndUpdate(
    msg: MessageEntity, plaintext: String, key: String, chatId: String, dao: MessageDao
) {
    if (plaintext.isBlank()) return
    try {
        val reEncrypted = GhostCrypto.encryptMessage(plaintext, key, chatId)
        dao.insertMessage(msg.copy(text = reEncrypted))
        Log.i(CRYPTO_MIGRATION_TAG, "Re-encrypted fKey=${msg.fKey}")
    } catch (e: Exception) {
        Log.e(CRYPTO_MIGRATION_TAG, "Re-encryption failed fKey=${msg.fKey}: ${e.javaClass.simpleName}")
    }
}
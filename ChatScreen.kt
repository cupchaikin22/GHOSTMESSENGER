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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import kotlin.random.Random
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

// ══════════════════════════════════════════════════════════
// PREMIUM GLASS DESIGN SYSTEM v2 (без изменений)
// ══════════════════════════════════════════════════════════

private object GlassDesign {

    const val TOPBAR_ALPHA = 0.58f
    const val BOTTOMBAR_ALPHA = 0.62f
    const val DIALOG_ALPHA = 0.74f
    const val FLOATING_ALPHA = 0.52f

    fun glassColor(base: Color, alpha: Float = 0.12f): Color =
        base.copy(alpha = alpha)

    fun glassBorder(isDark: Boolean): Color =
        if (isDark) Color.White.copy(alpha = 0.10f)
        else Color.Black.copy(alpha = 0.09f)

    fun glassBorderAccent(accent: Color): Color =
        accent.copy(alpha = 0.20f)

    fun innerGlow(accent: Color, isDark: Boolean): Brush {
        val peak = if (isDark) 0.055f else 0.035f
        val base = if (isDark) 0.025f else 0.015f
        return Brush.verticalGradient(
            0.0f to accent.copy(alpha = peak),
            0.15f to accent.copy(alpha = peak * 0.5f),
            0.4f to Color.Transparent,
            0.6f to Color.Transparent,
            0.85f to accent.copy(alpha = base * 0.5f),
            1.0f to accent.copy(alpha = base)
        )
    }

    fun backgroundGradient(base: Color): Brush {
        val isDark = base.luminance() < 0.5f
        return if (isDark) {
            Brush.verticalGradient(
                listOf(base, Color(0xFF0B0B0F), Color(0xFF080C10), Color(0xFF050810))
            )
        } else {
            Brush.verticalGradient(
                listOf(base, Color(0xFFF7F7FA), Color(0xFFF0F0F5))
            )
        }
    }

    fun bubbleShine(isMe: Boolean, accent: Color, isDark: Boolean): Brush {
        return if (isMe) {
            Brush.verticalGradient(
                0.0f to Color.White.copy(alpha = 0.10f),
                0.3f to Color.White.copy(alpha = 0.03f),
                0.7f to Color.Transparent,
                1.0f to Color.Black.copy(alpha = 0.04f)
            )
        } else {
            val tint = if (isDark) Color.White else Color.Black
            Brush.verticalGradient(
                0.0f to tint.copy(alpha = 0.035f),
                0.4f to Color.Transparent,
                1.0f to tint.copy(alpha = 0.015f)
            )
        }
    }

    fun myBubbleColor(accent: Color): Color = accent.copy(alpha = 0.80f)

    fun recipientBubbleColor(isDark: Boolean): Color =
        if (isDark) Color.White.copy(alpha = 0.065f) else Color.Black.copy(alpha = 0.045f)

    fun recipientBubbleBorder(isDark: Boolean): Color =
        if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)

    fun avatarRingBrush(accent: Color): Brush = Brush.sweepGradient(
        listOf(
            accent.copy(alpha = 0.6f),
            accent.copy(alpha = 0.3f),
            accent.copy(alpha = 0.6f),
            accent.copy(alpha = 0.4f),
            accent.copy(alpha = 0.6f)
        )
    )
}

private fun Modifier.grainOverlay(isDark: Boolean, density: Int = 800): Modifier =
    this.drawBehind {
        val grainColor = if (isDark) Color.White else Color.Black
        val rng = Random(42)
        repeat(density) {
            drawCircle(
                color = grainColor.copy(alpha = rng.nextFloat() * 0.035f),
                radius = 0.5f,
                center = Offset(rng.nextFloat() * size.width, rng.nextFloat() * size.height)
            )
        }
    }

private fun DrawScope.drawVignette(isDark: Boolean, intensity: Float = 0.08f) {
    val color = if (isDark) Color.Black else Color(0xFF1A1A2E)
    drawRect(
        brush = Brush.radialGradient(
            listOf(color.copy(alpha = intensity), Color.Transparent),
            center = Offset(0f, 0f),
            radius = size.minDimension * 0.7f
        ), size = size
    )
    drawRect(
        brush = Brush.radialGradient(
            listOf(color.copy(alpha = intensity * 0.6f), Color.Transparent),
            center = Offset(size.width, size.height),
            radius = size.minDimension * 0.7f
        ), size = size
    )
}

@Composable
fun GlassAvatar(
    avatarUrl: String?,
    fallbackLetter: String,
    size: Dp,
    accent: Color,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    ringWidth: Dp = 2.dp
) {
    val context = LocalContext.current
    val backgroundColor = if (isDark) Color(0xFF0E0E12) else Color(0xFFF4F4F8)

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(GlassDesign.avatarRingBrush(accent))
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
    val backgroundColor = if (isDark) Color(0xFF0E0E12) else Color(0xFFF4F4F8)

    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(GlassDesign.avatarRingBrush(accent))
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
                            replyingMessage?.let { rMsg -> msgMap["replyToText"] = rMsg.text.takeIf { rMsg.isPlaintextCached } ?: "" }

                            withContext(Dispatchers.IO) {
                                pushRef.setValue(msgMap).await()
                                // Пишем сразу как plaintext — это НАШЕ сообщение, повторный decrypt не нужен
                                // (и физически вреден: receivingChain не предназначена для собственных сообщений).
                                messageDao.insertMessage(MessageEntity(
                                    chatId = chatId, senderId = myId, text = rawData,
                                    timestamp = System.currentTimeMillis(), isFromMe = true, fKey = fKey,
                                    seqNum = enc.seqNum, ratchetPubKey = enc.ratchetPubKey,
                                    isPlaintextCached = true, currentStatus = 1
                                ))
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

    DisposableEffect(recipientId) {
        val nameListener = usersRef.child(recipientId).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                liveRecipientName = s.child("username").getValue(String::class.java) ?: liveRecipientName
                recipientSecurityLevel = s.child("securityLevel").getValue(Int::class.java) ?: 0
                recipientAvatarUrl = s.child("avatarUrl").getValue(String::class.java)
                    ?: s.child("profileImage").getValue(String::class.java)
            }
            override fun onCancelled(e: DatabaseError) {}
        })

        val typingListener = typingRef.child(recipientId).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { isRecipientTyping = s.getValue(Boolean::class.java) ?: false }
            override fun onCancelled(e: DatabaseError) {}
        })

        val statusListener = statusRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val lastSeen = s.getValue(Long::class.java) ?: 0L
                statusText = if (System.currentTimeMillis() - lastSeen < 120000) "в сети" else "был(а) недавно"
            }
            override fun onCancelled(e: DatabaseError) {}
        })

        // ── Главный листенер сообщений: расшифровка рачет-сообщений ОДИН РАЗ здесь ──
        val chatListener = chatRef.addChildEventListener(object : ChildEventListener {
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

                scope.launch(Dispatchers.IO) {
                    if (messageDao.getMessageByFirebaseKey(fKey) != null) return@launch // уже обработано (в т.ч. наше же эхо)

                    if (senderId == myId) {
                        // Эхо собственного сообщения, но мы его почему-то ещё не закешировали
                        // (например, отправили с другого устройства) — кладём как ciphertext,
                        // расшифровать своё сообщение через decryptMessage НЕЛЬЗЯ (чужой ключ направления).
                        messageDao.insertMessage(MessageEntity(
                            chatId = chatId, senderId = senderId, text = rawText, timestamp = timestamp,
                            isFromMe = true, fKey = fKey, seqNum = seqNum, ratchetPubKey = ratchetPubKey,
                            isPlaintextCached = false, currentStatus = remoteStatus
                        ))
                        return@launch
                    }

                    if (ratchetPubKey != null) {
                        // Рачет-сообщение: расшифровываем РОВНО ОДИН РАЗ прямо сейчас.
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
                            isPlaintextCached = true, currentStatus = remoteStatus
                        ))
                    } else {
                        // Legacy-сообщение без рачета — старое поведение, decrypt при рендере.
                        messageDao.insertMessage(MessageEntity(
                            chatId = chatId, senderId = senderId, text = rawText, timestamp = timestamp,
                            isFromMe = false, fKey = fKey,
                            rText = s.child("replyToText").getValue(String::class.java),
                            isEdit = s.child("isEdited").getValue(Boolean::class.java) ?: false,
                            isPlaintextCached = false, currentStatus = remoteStatus
                        ))
                    }
                }
            }
            override fun onChildChanged(s: DataSnapshot, prev: String?) {
                val fKey = s.key ?: return
                scope.launch(Dispatchers.IO) {
                    messageDao.getMessageByFirebaseKey(fKey)?.let { old ->
                        messageDao.insertMessage(old.copy(
                            isEdit = s.child("isEdited").getValue(Boolean::class.java) ?: old.isEdit,
                            currentStatus = s.child("status").getValue(Int::class.java) ?: old.currentStatus
                        ))
                    }
                }
            }
            override fun onChildRemoved(s: DataSnapshot) {
                scope.launch(Dispatchers.IO) { messageDao.deleteByFirebaseKey(s.key ?: "") }
            }
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        })

        onDispose {
            usersRef.child(recipientId).removeEventListener(nameListener)
            typingRef.child(recipientId).removeEventListener(typingListener)
            statusRef.removeEventListener(statusListener)
            chatRef.removeEventListener(chatListener)
        }
    }

    val chatAccent = Color(currentSettings.globalAccentColor)
    val bgColor = Color(currentSettings.globalBackgroundColor)
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

    Box(Modifier.fillMaxSize().background(GlassDesign.backgroundGradient(bgColor))) {
        currentSettings.chatWallpaperUrl?.let {
            AsyncImage(model = it, contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.10f)
        }

        Box(Modifier.fillMaxWidth().height(220.dp).background(
            Brush.verticalGradient(listOf(
                chatAccent.copy(alpha = if (isDark) 0.035f else 0.018f), Color.Transparent
            ))
        ))

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                GlassTopBar(
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
                GlassBottomPanel(
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
                        if (inputText.isBlank()) return@GlassBottomPanel
                        val pubKey = recipientPublicKey
                        if (pubKey == null) {
                            Toast.makeText(context, "Ключ собеседника ещё не загружен, попробуйте через секунду", Toast.LENGTH_SHORT).show()
                            return@GlassBottomPanel
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
                                        messageDao.insertMessage(editTarget.copy(text = textToSend, isEdit = true, isPlaintextCached = false))
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
                                    replyTarget?.let { rMsg ->
                                        if (rMsg.isPlaintextCached) msgMap["replyToText"] = rMsg.text
                                    }

                                    withContext(Dispatchers.IO) {
                                        pushRef.setValue(msgMap).await()
                                        messageDao.insertMessage(MessageEntity(
                                            chatId = chatId, senderId = myId, text = textToSend,
                                            timestamp = System.currentTimeMillis(), isFromMe = true, fKey = fKey,
                                            rText = if (replyTarget?.isPlaintextCached == true) replyTarget.text else null,
                                            seqNum = enc.seqNum, ratchetPubKey = enc.ratchetPubKey,
                                            isPlaintextCached = true, currentStatus = 1
                                        ))
                                    }
                                }
                            } catch (e: SecurityException) {
                                // Сюда прилетит "identity private key missing" из createInitiatorSession,
                                // если ключи ещё не успели сгенерироваться, или сбой Keystore unwrap.
                                // НЕ роняем приложение — возвращаем текст пользователю, чтобы не терять сообщение.
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
                LazyColumn(
                    state = listState, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    items(messages, key = { it.fKey }) { msg ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(tween(320, easing = FastOutSlowInEasing)) +
                                    slideInVertically(initialOffsetY = { it / 5 }, animationSpec = tween(320, easing = FastOutSlowInEasing))
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
                        color = GlassDesign.glassColor(if (isDark) Color.Black else Color.White, GlassDesign.FLOATING_ALPHA),
                        shape = RoundedCornerShape(22.dp),
                        border = BorderStroke(0.75.dp, GlassDesign.glassBorder(isDark)),
                        shadowElevation = 12.dp
                    ) {
                        Column(
                            Modifier.padding(30.dp).drawBehind { drawVignette(isDark, 0.05f) }.grainOverlay(isDark, 300),
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
// GLASS TOP BAR (без изменений)
// ══════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlassTopBar(
    recipientName: String, recipientAvatarUrl: String?, statusText: String, isTyping: Boolean,
    accent: Color, contentColor: Color, isDark: Boolean, onBack: () -> Unit,
    onVideoCall: (() -> Unit)?, onAudioCall: (() -> Unit)?, onClear: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = GlassDesign.glassColor(if (isDark) Color.Black else Color.White, GlassDesign.TOPBAR_ALPHA),
        border = BorderStroke(0.5.dp, GlassDesign.glassBorder(isDark)),
        shadowElevation = 6.dp
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(GlassDesign.innerGlow(accent, isDark))
                .drawBehind { drawVignette(isDark, 0.04f) }
                .grainOverlay(isDark, 500)
        ) {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlassAvatar(
                            avatarUrl = recipientAvatarUrl,
                            fallbackLetter = recipientName.firstOrNull()?.toString() ?: "?",
                            size = 44.dp, accent = accent, isDark = isDark, ringWidth = 2.dp
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(recipientName, color = contentColor.copy(alpha = 0.95f),
                                fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, letterSpacing = (-0.15).sp)
                            Spacer(Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isTyping) { TypingDots(accent = accent); Spacer(Modifier.width(6.dp)) }
                                Text(
                                    text = if (isTyping) "печатает..." else statusText,
                                    color = if (isTyping) accent else contentColor.copy(alpha = 0.42f),
                                    fontSize = 12.sp,
                                    fontWeight = if (isTyping) FontWeight.Medium else FontWeight.Normal
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = contentColor.copy(alpha = 0.72f))
                    }
                },
                actions = {
                    if (onAudioCall != null) {
                        IconButton(onClick = onAudioCall) {
                            Icon(Icons.Default.Call, "Аудиозвонок", tint = contentColor.copy(alpha = 0.58f), modifier = Modifier.size(21.dp))
                        }
                    }
                    if (onVideoCall != null) {
                        IconButton(onClick = onVideoCall) {
                            Icon(Icons.Default.Videocam, "Видеозвонок", tint = contentColor.copy(alpha = 0.58f), modifier = Modifier.size(21.dp))
                        }
                    }
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.MoreVert, null, tint = contentColor.copy(alpha = 0.40f), modifier = Modifier.size(20.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )

            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp)
                    .background(Brush.horizontalGradient(listOf(
                        Color.Transparent, accent.copy(alpha = 0.13f),
                        accent.copy(alpha = 0.13f), Color.Transparent
                    )))
            )
        }
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
// GLASS BUBBLE — decrypt вынесен в produceState (фикс п.7)
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
    // а не синхронно в remember{} на главном потоке (это и был баг п.7).
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
    val tailRadius = 8.dp

    val bubbleShape = RoundedCornerShape(
        topStart = baseRadius, topEnd = baseRadius,
        bottomEnd = if (isMe) tailRadius else baseRadius,
        bottomStart = if (isMe) baseRadius else tailRadius
    )

    val bubbleColor = if (isMe) GlassDesign.myBubbleColor(accent) else GlassDesign.recipientBubbleColor(isDark)
    val bubbleBorder = if (isMe) BorderStroke(0.5.dp, accent.copy(alpha = 0.22f)) else BorderStroke(0.5.dp, GlassDesign.recipientBubbleBorder(isDark))
    val textColor = if (isMe) textOnMe else if (isDark) Color.White.copy(alpha = 0.94f) else Color.Black.copy(alpha = 0.88f)
    val metaColor = if (isMe) textOnMe.copy(alpha = 0.48f) else if (isDark) Color.White.copy(alpha = 0.36f) else Color.Black.copy(alpha = 0.36f)

    if (isMe) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp, start = 52.dp, end = 0.dp),
            horizontalAlignment = Alignment.End
        ) {
            BubbleContent(
                msg = msg, dec = dec, isImg = isImg, url = url, isMe = true,
                bubbleShape = bubbleShape, bubbleColor = bubbleColor, bubbleBorder = bubbleBorder,
                textColor = textColor, textOnMe = textOnMe, metaColor = metaColor,
                accent = accent, settings = settings, isDark = isDark,
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
                    bubbleShape = bubbleShape, bubbleColor = bubbleColor, bubbleBorder = bubbleBorder,
                    textColor = textColor, textOnMe = textOnMe, metaColor = metaColor,
                    accent = accent, settings = settings, isDark = isDark,
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
    bubbleShape: RoundedCornerShape, bubbleColor: Color, bubbleBorder: BorderStroke,
    textColor: Color, textOnMe: Color, metaColor: Color, accent: Color,
    settings: SettingsEntity, isDark: Boolean, onLong: () -> Unit, onImg: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .combinedClickable(onLongClick = onLong, onClick = { if (isImg && url != null) onImg(url) })
            .widthIn(min = 68.dp, max = 280.dp),
        shape = bubbleShape, color = bubbleColor, border = bubbleBorder,
        shadowElevation = if (isMe) 4.dp else 1.5.dp
    ) {
        Box(Modifier.drawWithContent {
            drawContent()
            drawRect(brush = GlassDesign.bubbleShine(isMe, accent, isDark), size = size)
        }) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (!msg.rText.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier.padding(bottom = 8.dp),
                        color = if (isMe) Color.Black.copy(alpha = 0.09f) else accent.copy(alpha = 0.06f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.width(2.5.dp).height(20.dp).clip(RoundedCornerShape(2.dp))
                                    .background(Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.15f))))
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(msg.rText!!, color = if (isMe) textOnMe.copy(alpha = 0.58f) else metaColor,
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
                    if (isMe) ChatStatusIconLocal(status = msg.currentStatus, tint = textOnMe.copy(alpha = 0.60f))
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════
// GLASS BOTTOM PANEL — chatSecretKey/chatId больше не нужны для ratchet-пути,
// оставлены только для legacy-превью реплая
// ══════════════════════════════════════════════════════════

@Composable
private fun GlassBottomPanel(
    text: String, onTextChange: (String) -> Unit,
    accent: Color, isDark: Boolean, contentColor: Color,
    uploading: Boolean, replyingMessage: MessageEntity?,
    editingMessage: MessageEntity?,
    onCancelReply: () -> Unit, onCancelEdit: () -> Unit,
    onPhoto: () -> Unit, onSend: () -> Unit
) {
    val sendScale by animateFloatAsState(
        targetValue = if (text.isNotBlank()) 1f else 0.82f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 350f), label = "ss"
    )
    val sendElevation by animateDpAsState(
        targetValue = if (text.isNotBlank()) 8.dp else 0.dp,
        animationSpec = tween(250), label = "se"
    )

    Column(
        Modifier
            .fillMaxWidth()
            .background(GlassDesign.glassColor(if (isDark) Color.Black else Color.White, GlassDesign.BOTTOMBAR_ALPHA))
            .drawBehind { drawVignette(isDark, 0.03f) }
            .grainOverlay(isDark, 400)
            .navigationBarsPadding()
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(
            Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.10f), accent.copy(alpha = 0.10f), Color.Transparent))
        ))

        AnimatedVisibility(
            visible = replyingMessage != null,
            enter = expandVertically(tween(220)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(160))
        ) {
            replyingMessage?.let { msg ->
                Surface(Modifier.fillMaxWidth(), color = accent.copy(alpha = 0.04f)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.width(3.dp).height(36.dp).clip(RoundedCornerShape(2.dp))
                                .background(Brush.verticalGradient(listOf(
                                    accent, accent.copy(alpha = 0.6f), accent.copy(alpha = 0.25f), accent.copy(alpha = 0.08f)
                                )))
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Ответ", color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                if (msg.isPlaintextCached) msg.text.ifBlank { "..." } else "...",
                                color = contentColor.copy(alpha = 0.50f), fontSize = 13.sp, maxLines = 1
                            )
                        }
                        IconButton(onClick = onCancelReply, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.Close, null, tint = contentColor.copy(alpha = 0.32f), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = editingMessage != null,
            enter = expandVertically(tween(220)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(160))
        ) {
            Surface(Modifier.fillMaxWidth(), color = accent.copy(alpha = 0.05f)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Edit, null, tint = accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Редактирование", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onCancelEdit, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, null, tint = contentColor.copy(alpha = 0.42f), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
            Surface(
                onClick = onPhoto,
                modifier = Modifier.size(46.dp).align(Alignment.CenterVertically),
                shape = CircleShape, color = contentColor.copy(alpha = 0.04f),
                border = BorderStroke(0.5.dp, GlassDesign.glassBorder(isDark))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (uploading) CircularProgressIndicator(Modifier.size(20.dp), color = accent, strokeWidth = 2.dp)
                    else Icon(Icons.Default.AttachFile, null, tint = contentColor.copy(alpha = 0.52f), modifier = Modifier.size(21.dp))
                }
            }

            Spacer(Modifier.width(12.dp))

            Surface(
                Modifier.weight(1f).heightIn(min = 46.dp),
                shape = RoundedCornerShape(26.dp), color = contentColor.copy(alpha = 0.045f),
                border = BorderStroke(0.5.dp, GlassDesign.glassBorder(isDark))
            ) {
                TextField(
                    value = text, onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Сообщение...", color = contentColor.copy(alpha = 0.28f), fontSize = 15.sp) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = accent, focusedTextColor = contentColor, unfocusedTextColor = contentColor
                    ),
                    textStyle = TextStyle(fontSize = 15.sp),
                    maxLines = 5
                )
            }

            Spacer(Modifier.width(12.dp))

            Box(modifier = Modifier.size(46.dp).align(Alignment.CenterVertically), contentAlignment = Alignment.Center) {
                if (text.isNotBlank()) {
                    Box(Modifier.size(52.dp).clip(CircleShape).background(accent.copy(alpha = 0.12f)))
                }
                Surface(
                    onClick = onSend,
                    modifier = Modifier.size(46.dp).scale(sendScale),
                    shape = CircleShape,
                    color = if (text.isNotBlank()) accent else accent.copy(alpha = 0.35f),
                    shadowElevation = sendElevation
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send, null,
                            tint = if (text.isNotBlank()) {
                                if (accent.luminance() > 0.5f) Color.Black else Color.White
                            } else contentColor.copy(alpha = 0.28f),
                            modifier = Modifier.size(20.dp).graphicsLayer { translationX = 1f }
                        )
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════
// GLASS DIALOGS (без изменений)
// ══════════════════════════════════════════════════════════

@Composable
fun GlassDialog(onDismiss: () -> Unit, isDark: Boolean, accent: Color, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, confirmButton = {},
        containerColor = if (isDark) Color(0xFF101015) else Color(0xFFF5F5F9),
        shape = RoundedCornerShape(24.dp), tonalElevation = 0.dp,
        modifier = Modifier.border(0.5.dp, GlassDesign.glassBorderAccent(accent), RoundedCornerShape(24.dp)),
        text = {
            Column(Modifier.drawBehind { drawVignette(isDark, 0.03f) }.grainOverlay(isDark, 350)) { content() }
        }
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
        containerColor = if (isDark) Color(0xFF101015) else Color(0xFFF5F5F9),
        shape = RoundedCornerShape(24.dp), tonalElevation = 0.dp,
        modifier = Modifier
            .border(0.5.dp, GlassDesign.glassBorderAccent(accent), RoundedCornerShape(24.dp))
            .drawBehind { drawVignette(isDark, 0.03f) },
        title = { Text(title, color = textColor.copy(alpha = 0.92f), fontWeight = FontWeight.SemiBold, fontSize = 18.sp) },
        text = { Text(text, color = textColor.copy(alpha = 0.52f), fontSize = 14.sp, lineHeight = 20.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.padding(end = 4.dp)) {
                Text(confirmText, color = confirmColor, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissAction) {
                Text(dismissText, color = textColor.copy(alpha = 0.55f), fontSize = 15.sp)
            }
        }
    )
}

// ══════════════════════════════════════════════════════════
// SHARED COMPONENTS (без изменений)
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
            shape = CircleShape, color = Color.White.copy(alpha = 0.07f),
            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f))
        ) {
            Icon(Icons.Default.Close, null, tint = Color.White.copy(alpha = 0.88f), modifier = Modifier.padding(10.dp).size(20.dp))
        }
    }
}

@Composable
fun GhostActionItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color = Color.White, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape = RoundedCornerShape(14.dp), color = color.copy(alpha = 0.04f),
        border = BorderStroke(0.5.dp, color.copy(alpha = 0.07f))
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(34.dp), shape = RoundedCornerShape(10.dp), color = color.copy(alpha = 0.08f)) {
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
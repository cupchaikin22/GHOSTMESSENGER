package io.ghostsoftware.ghostchat

import android.app.Activity
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    database: AppDatabase,
    recipientId: String,
    recipientName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val auth = FirebaseAuth.getInstance()
    val myId = auth.currentUser?.uid ?: ""
    val chatId = remember(myId, recipientId) {
        if (myId < recipientId) "${myId}_$recipientId" else "${recipientId}_$myId"
    }

    val messageDao = database.messageDao()
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
    val keysRef = FirebaseDatabase.getInstance().getReference("keys").child(chatId)

    var statusText by remember { mutableStateOf("не в сети") }
    var isRecipientTyping by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var chatSecretKey by remember { mutableStateOf("") }
    var isUploading by remember { mutableStateOf(false) }
    var liveRecipientName by remember { mutableStateOf(recipientName) }
    var recipientSecurityLevel by remember { mutableIntStateOf(0) }
    var isKeyLoading by remember { mutableStateOf(true) }

    var viewerImageUrl by remember { mutableStateOf<String?>(null) }
    var messageToManage by remember { mutableStateOf<MessageEntity?>(null) }
    var replyingMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var editingMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    val encManager = remember { EncryptionManager() }

    BackHandler(enabled = viewerImageUrl != null || messageToManage != null || replyingMessage != null || editingMessage != null) {
        viewerImageUrl = null
        messageToManage = null
        replyingMessage = null
        editingMessage = null
    }

    LaunchedEffect(chatId) {
        Log.d("ChatScreen", "Loading key for chatId: $chatId")
        withContext(Dispatchers.IO) {
            val localKey = messageDao.getSecretKeyForChat(chatId)
            Log.d("ChatScreen", "Local key: $localKey")

            if (!localKey.isNullOrBlank()) {
                chatSecretKey = localKey
                isKeyLoading = false
                Log.d("ChatScreen", "Key loaded from local: $chatSecretKey")
            } else {
                keysRef.child(myId).get().addOnSuccessListener { snapshot ->
                    val encryptedSecret = snapshot.getValue(String::class.java)
                    Log.d("ChatScreen", "Encrypted secret from Firebase: $encryptedSecret")
                    if (!encryptedSecret.isNullOrBlank()) {
                        val privateKey = encManager.getPrivateKey()
                        if (privateKey != null) {
                            val decrypted = GhostEncryptor.decryptSecretWithPrivateKey(
                                encryptedSecret, privateKey
                            )
                            if (!decrypted.startsWith("Error")) {
                                chatSecretKey = decrypted
                                scope.launch(Dispatchers.IO) {
                                    messageDao.saveSecretKey(EncryptionKeyEntity(chatId, decrypted))
                                }
                                isKeyLoading = false
                                Log.d("ChatScreen", "Key decrypted and set: $chatSecretKey")
                            } else {
                                Log.e("ChatScreen", "Decryption error: $decrypted")
                            }
                        } else {
                            Log.e("ChatScreen", "Private key is null")
                        }
                    } else {
                        val newSecret = GhostEncryptor.generateRandomSecret()
                        chatSecretKey = newSecret
                        scope.launch(Dispatchers.IO) {
                            messageDao.saveSecretKey(EncryptionKeyEntity(chatId, newSecret))
                        }
                        isKeyLoading = false
                        Log.d("ChatScreen", "New key generated: $chatSecretKey")

                        usersRef.child(recipientId).child("publicKey").get().addOnSuccessListener { pubSnap ->
                            val pubKey = pubSnap.getValue(String::class.java)
                            if (!pubKey.isNullOrBlank()) {
                                val encryptedForOther = GhostEncryptor.encryptSecretWithPublicKey(newSecret, pubKey)
                                keysRef.child(recipientId).setValue(encryptedForOther)
                                Log.d("ChatScreen", "Key sent to recipient")
                            } else {
                                Log.e("ChatScreen", "Recipient public key not found")
                            }
                        }
                    }
                }.addOnFailureListener { error ->
                    Log.e("ChatScreen", "Firebase key load failure: ${error.message}")
                }
            }
        }

        delay(10000) // Таймаут 10 сек
        if (chatSecretKey.isEmpty()) {
            val fallbackSecret = GhostEncryptor.generateRandomSecret()
            chatSecretKey = fallbackSecret
            scope.launch(Dispatchers.IO) {
                messageDao.saveSecretKey(EncryptionKeyEntity(chatId, fallbackSecret))
            }
            isKeyLoading = false
            Toast.makeText(context, "Ключ создан локально из-за задержки", Toast.LENGTH_SHORT).show()
            Log.d("ChatScreen", "Fallback key set: $fallbackSecret")
        }
    }

    LaunchedEffect(myId) {
        myStatusRef.onDisconnect().setValue(ServerValue.TIMESTAMP)

        while (isActive) {
            myStatusRef.setValue(ServerValue.TIMESTAMP)
            delay(60000)
        }
    }

    DisposableEffect(recipientId) {
        val nameListener = usersRef.child(recipientId).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                liveRecipientName = s.child("username").getValue(String::class.java) ?: liveRecipientName
                recipientSecurityLevel = s.child("securityLevel").getValue(Int::class.java) ?: 0
            }
            override fun onCancelled(e: DatabaseError) {}
        })

        val typingListener = typingRef.child(recipientId).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                isRecipientTyping = s.getValue(Boolean::class.java) ?: false
            }
            override fun onCancelled(e: DatabaseError) {}
        })

        val statusListener = statusRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val lastSeen = s.getValue(Long::class.java) ?: 0L
                statusText = if (System.currentTimeMillis() - lastSeen < 120000) "в сети" else "был(а) недавно"
            }
            override fun onCancelled(e: DatabaseError) {}
        })

        val chatListener = chatRef.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, previousChildName: String?) {
                val fKey = s.key ?: return
                val senderId = s.child("senderId").getValue(String::class.java) ?: ""
                val remoteStatus = s.child("status").getValue(Int::class.java) ?: 1

                if (senderId != myId && remoteStatus < 3) {
                    chatRef.child(fKey).child("status").setValue(3)
                }

                scope.launch(Dispatchers.IO) {
                    if (messageDao.getMessageByFirebaseKey(fKey) == null) {
                        messageDao.insertMessage(
                            MessageEntity(
                                chatId = chatId,
                                senderId = senderId,
                                text = s.child("text").getValue(String::class.java) ?: "",
                                timestamp = s.child("timestamp").getValue(Long::class.java) ?: System.currentTimeMillis(),
                                isFromMe = senderId == myId,
                                fKey = fKey,
                                rText = s.child("replyToText").getValue(String::class.java),
                                isEdit = s.child("isEdited").getValue(Boolean::class.java) ?: false,
                                currentStatus = remoteStatus
                            )
                        )
                    }
                }
            }

            override fun onChildChanged(s: DataSnapshot, previousChildName: String?) {
                val fKey = s.key ?: return
                scope.launch(Dispatchers.IO) {
                    messageDao.getMessageByFirebaseKey(fKey)?.let { old ->
                        messageDao.insertMessage(
                            old.copy(
                                text = s.child("text").getValue(String::class.java) ?: old.text,
                                isEdit = s.child("isEdited").getValue(Boolean::class.java) ?: old.isEdit,
                                currentStatus = s.child("status").getValue(Int::class.java) ?: old.currentStatus
                            )
                        )
                    }
                }
            }

            override fun onChildRemoved(s: DataSnapshot) {
                scope.launch(Dispatchers.IO) {
                    messageDao.deleteByFirebaseKey(s.key ?: "")
                }
            }

            override fun onChildMoved(s: DataSnapshot, previousChildName: String?) {}
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
    val contentColor = if (bgColor.luminance() > 0.5f) Color.Black else Color.White
    val textOnMe = if (chatAccent.luminance() > 0.5f) Color.Black else Color.White
    val bubbleRecipient = if (bgColor.luminance() > 0.5f) Color(0xFFF0F0F0) else Color(0xFF262626)

    val isSecureSession = currentSettings.securityLevel >= 1 || recipientSecurityLevel >= 1

    DisposableEffect(isSecureSession) {
        if (isSecureSession && activity != null) {
            activity.window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(liveRecipientName, color = contentColor, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            text = if (isRecipientTyping) "печатает..." else statusText,
                            color = if (isRecipientTyping) chatAccent else contentColor.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = contentColor)
                    }
                },
                actions = {
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(Icons.Default.DeleteSweep, null, tint = contentColor)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bgColor)
            )
        },
        bottomBar = {
            Column(Modifier.background(bgColor).navigationBarsPadding()) {
                AnimatedVisibility(visible = replyingMessage != null) {
                    replyingMessage?.let {
                        ReplyPreview(it, chatSecretKey) { replyingMessage = null }
                    }
                }

                AnimatedVisibility(visible = editingMessage != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(contentColor.copy(alpha = 0.05f))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Edit, null, tint = chatAccent, modifier = Modifier.size(16.dp))
                        Text(" Редактирование", color = chatAccent, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { editingMessage = null; inputText = "" }) {
                            Icon(Icons.Default.Close, null, tint = contentColor)
                        }
                    }
                }

                val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    uri?.let {
                        isUploading = true
                        ProfileManager(context).uploadImage(it) { url ->
                            isUploading = false
                            url?.let { u ->
                                val enc = GhostEncryptor.encrypt("GHOST_IMG:$u", chatSecretKey)
                                chatRef.push().setValue(
                                    mapOf(
                                        "senderId" to myId,
                                        "text" to enc,
                                        "timestamp" to ServerValue.TIMESTAMP,
                                        "status" to 1
                                    )
                                )
                            }
                        }
                    }
                }

                ChatBottomBar(
                    text = inputText,
                    onTextChange = {
                        inputText = it
                        if (!currentSettings.stealthMode) {
                            typingRef.child(myId).setValue(it.isNotBlank())
                        }
                    },
                    accent = chatAccent,
                    uploading = isUploading,
                    onPhoto = { photoLauncher.launch("image/*") },
                    onSend = {
                        if (inputText.isBlank()) return@ChatBottomBar

                        val encrypted = GhostEncryptor.encrypt(inputText.trim(), chatSecretKey)

                        if (editingMessage != null) {
                            chatRef.child(editingMessage!!.fKey).updateChildren(
                                mapOf("text" to encrypted, "isEdited" to true)
                            )
                            editingMessage = null
                        } else {
                            val msgMap = mutableMapOf<String, Any>(
                                "senderId" to myId,
                                "text" to encrypted,
                                "timestamp" to ServerValue.TIMESTAMP,
                                "status" to 1
                            )
                            replyingMessage?.let {
                                msgMap["replyToText"] = try {
                                    GhostEncryptor.decrypt(it.text, chatSecretKey)
                                } catch (e: Exception) { "" }
                            }
                            chatRef.push().setValue(msgMap)
                            replyingMessage = null
                        }

                        inputText = ""
                        typingRef.child(myId).setValue(false)
                    },
                    bgColor = bgColor,
                    contentColor = contentColor
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .padding(padding)
        ) {
            currentSettings.chatWallpaperUrl?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alpha = 0.4f
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp)
            ) {
                items(messages, key = { it.fKey }) { msg ->
                    GhostBubble(
                        msg = msg,
                        accent = chatAccent,
                        recipientColor = bubbleRecipient,
                        textOnMe = textOnMe,
                        chatKey = chatSecretKey,
                        settings = currentSettings,
                        onLong = { messageToManage = msg },
                        onImg = { viewerImageUrl = it }
                    )
                }
            }

            if (isKeyLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = chatAccent
                )
            }
        }
    }

    if (messageToManage != null) {
        AlertDialog(
            onDismissRequest = { messageToManage = null },
            containerColor = Color(0xFF1A1A1A),
            title = { Text("Сообщение", color = Color.White) },
            text = {
                Column {
                    GhostActionItem(Icons.Default.Reply, "Ответить") {
                        replyingMessage = messageToManage
                        messageToManage = null
                    }
                    if (messageToManage?.isFromMe == true) {
                        GhostActionItem(Icons.Default.Edit, "Изменить") {
                            editingMessage = messageToManage
                            inputText = try {
                                GhostEncryptor.decrypt(messageToManage!!.text, chatSecretKey)
                            } catch (e: Exception) { "" }
                            messageToManage = null
                        }
                    }
                    GhostActionItem(Icons.Default.Delete, "Удалить", Color.Red) {
                        showDeleteDialog = true
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (showDeleteDialog && messageToManage != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false; messageToManage = null },
            containerColor = Color(0xFF1A1A1A),
            title = { Text("Удаление", color = Color.White) },
            text = { Text("Удалить это сообщение?", color = Color.Gray) },
            confirmButton = {
                TextButton(onClick = {
                    chatRef.child(messageToManage!!.fKey).removeValue()
                    scope.launch(Dispatchers.IO) {
                        messageDao.deleteByFirebaseKey(messageToManage!!.fKey)
                    }
                    showDeleteDialog = false
                    messageToManage = null
                }) {
                    Text("У всех", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        messageDao.deleteByFirebaseKey(messageToManage!!.fKey)
                    }
                    showDeleteDialog = false
                    messageToManage = null
                }) {
                    Text("У себя", color = Color.White)
                }
            }
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = Color(0xFF1A1A1A),
            title = { Text("Очистить чат?", color = Color.White) },
            confirmButton = {
                TextButton(onClick = {
                    chatRef.removeValue()
                    scope.launch(Dispatchers.IO) {
                        messageDao.deleteMessagesForChat(chatId)
                    }
                    showClearDialog = false
                }) {
                    Text("У всех", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        messageDao.deleteMessagesForChat(chatId)
                    }
                    showClearDialog = false
                }) {
                    Text("Только у меня", color = Color.White)
                }
            }
        )
    }

    if (viewerImageUrl != null) {
        GhostImageViewer(url = viewerImageUrl!!, onDismiss = { viewerImageUrl = null })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GhostBubble(
    msg: MessageEntity,
    accent: Color,
    recipientColor: Color,
    textOnMe: Color,
    chatKey: String,
    settings: SettingsEntity,
    onLong: () -> Unit,
    onImg: (String) -> Unit
) {
    val isMe = msg.isFromMe
    val dec = remember(msg.text, chatKey) {
        if (chatKey.isEmpty()) "Инициализация..."
        else try {
            GhostEncryptor.decrypt(msg.text, chatKey)
        } catch (e: Exception) {
            "[Ошибка расшифровки]"
        }
    }
    val isImg = dec.startsWith("GHOST_IMG:")
    val url = if (isImg) dec.substringAfter("GHOST_IMG:") else null
    val radius = settings.chatCornerRadius.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
    ) {
        Surface(
            modifier = Modifier
                .combinedClickable(
                    onLongClick = onLong,
                    onClick = { if (isImg) onImg(url!!) }
                )
                .widthIn(max = 280.dp),
            shape = RoundedCornerShape(
                topStart = radius,
                topEnd = radius,
                bottomEnd = if (isMe) 4.dp else radius,
                bottomStart = if (isMe) radius else 4.dp
            ),
            color = if (isMe) accent else recipientColor
        ) {
            Column(Modifier.padding(10.dp)) {
                if (!msg.rText.isNullOrBlank()) {
                    Box(
                        Modifier
                            .padding(bottom = 6.dp)
                            .background(Color.Black.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                            .padding(6.dp)
                    ) {
                        Text(
                            text = msg.rText!!,
                            color = Color.Gray,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                }

                if (isImg) {
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Text(
                        text = dec,
                        color = if (isMe) textOnMe else Color.White,
                        fontSize = settings.fontSize.sp
                    )
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (msg.isEdit) {
                        Text(
                            text = "изм. ",
                            color = if (isMe) textOnMe.copy(alpha = 0.6f) else Color.Gray,
                            fontSize = 9.sp
                        )
                    }
                    Text(
                        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp)),
                        color = if (isMe) textOnMe.copy(alpha = 0.6f) else Color.Gray,
                        fontSize = 10.sp
                    )
                    if (isMe) {
                        Spacer(Modifier.width(4.dp))
                        ChatStatusIconLocal(
                            status = msg.currentStatus,
                            tint = if (isMe) textOnMe else Color(0xFF007AFF)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatBottomBar(
    text: String,
    onTextChange: (String) -> Unit,
    accent: Color,
    uploading: Boolean,
    onPhoto: () -> Unit,
    onSend: () -> Unit,
    bgColor: Color,
    contentColor: Color
) {
    Row(
        modifier = Modifier
            .background(bgColor)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPhoto) {
            if (uploading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = accent)
            } else {
                Icon(Icons.Default.AttachFile, null, tint = contentColor)
            }
        }

        TextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Сообщение...", color = contentColor.copy(alpha = 0.5f)) },
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = contentColor.copy(alpha = 0.05f),
                unfocusedContainerColor = contentColor.copy(alpha = 0.05f),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = contentColor,
                unfocusedTextColor = contentColor
            )
        )

        IconButton(onClick = onSend) {
            Icon(Icons.AutoMirrored.Filled.Send, null, tint = accent)
        }
    }
}

@Composable
fun ReplyPreview(msg: MessageEntity, key: String, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color.Black.copy(0.2f)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(30.dp).background(Color.Cyan))
        Text(try { GhostEncryptor.decrypt(msg.text, key) } catch (e: Exception) { "..." }, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1)
        IconButton(onClick = onCancel) { Icon(Icons.Default.Close, null, tint = Color.Gray) }
    }
}

@Composable
fun ChatStatusIconLocal(status: Int, tint: Color) {
    val icon = if (status >= 3) Icons.Default.DoneAll else Icons.Default.Done
    Icon(icon, null, modifier = Modifier.size(12.dp), tint = if (status == 3) tint else Color.Gray)
}

@Composable
fun GhostImageViewer(url: String, onDismiss: () -> Unit) {
    val scale = remember { mutableStateOf(1f) }
    val offset = remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, off, _ ->
        scale.value *= zoom
        offset.value += off
    }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable { onDismiss() }) {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale.value.coerceIn(1f, 5f),
                    scaleY = scale.value.coerceIn(1f, 5f),
                    translationX = offset.value.x,
                    translationY = offset.value.y
                )
                .transformable(state),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
fun GhostActionItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color = Color.White, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = color, fontSize = 16.sp)
    }
}
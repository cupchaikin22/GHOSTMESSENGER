package io.ghostsoftware.ghostchat

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var keyManager: GhostKeyManager
    private val database by lazy { AppDatabase.getDatabase(this) }

    private val keysReadyState = androidx.compose.runtime.mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)

        keyManager = GhostKeyManager(this)
        lifecycleScope.launch {
            try {
                keyManager.ensureKeysExist()
            } catch (e: Exception) {
                Log.e("MainActivity", "ensureKeysExist failed: ${e.javaClass.simpleName}", e)
            } finally {
                keysReadyState.value = true
            }
        }

        lifecycleScope.launch {
            database.messageDao().getSettings().collectLatest { settings ->
                val isSecure = (settings?.securityLevel ?: 1) >= 2
                if (isSecure) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }

        enableEdgeToEdge()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = GhostTheme.accentColor,
                    background = GhostTheme.backgroundColor,
                    surface = Color(0xFF0A0A0A),
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = GhostTheme.backgroundColor
                ) {
                    val keysReady by keysReadyState
                    GhostChatApp(database = database, keyManager = keyManager, keysReady = keysReady)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateOnlineStatus("online")
    }

    override fun onPause() {
        super.onPause()
        updateOnlineStatus(System.currentTimeMillis().toString())
    }

    private fun updateOnlineStatus(status: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseDatabase.getInstance().getReference("users")
            .child(uid).child("status").setValue(status)
    }
}

data class IncomingCallData(
    val callId: String,
    val callerId: String,
    val callerName: String,
    val callerImage: String? = null,
    val offerSdp: String,
    val isVideoCall: Boolean
)

@Composable
fun GhostChatApp(
    database: AppDatabase,
    keyManager: GhostKeyManager,
    keysReady: Boolean
) {
    val auth = FirebaseAuth.getInstance()
    val context = LocalContext.current
    val activity = context as? Activity
    val myUserId = auth.currentUser?.uid ?: ""

    // Отдельный scope уровня приложения — нужен для performLogout(), так как
    // это suspend-функция, а onLogout колбэк в SettingsScreen — обычная лямбда.
    val appScope = rememberCoroutineScope()

    if (!keysReady) {
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                color = androidx.compose.ui.graphics.Color(0xFF00FFCC)
            )
        }
        return
    }

    val webRTCManager = remember(myUserId) {
        if (myUserId.isNotEmpty()) {
            WebRTCManager(context, myUserId)
        } else null
    }

    val startScreen = if (auth.currentUser != null && auth.currentUser!!.isEmailVerified) {
        "list"
    } else {
        "auth"
    }

    var currentScreen by remember { mutableStateOf(startScreen) }
    var selectedUser by remember { mutableStateOf<UserProfile?>(null) }
    var showExitDialog by remember { mutableStateOf(false) }
    var incomingCallData by remember { mutableStateOf<IncomingCallData?>(null) }

    var permissionsGranted by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        permissionsGranted = allGranted

        if (allGranted) {
            Log.d("MainActivity", "Permissions granted, initializing WebRTC")
            webRTCManager?.initialize()
        } else {
            Log.e("MainActivity", "Permissions denied")
            Toast.makeText(
                context,
                "Для звонков нужны разрешения на камеру и микрофон",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    LaunchedEffect(auth.currentUser, webRTCManager) {
        if (auth.currentUser != null && webRTCManager != null && !permissionsGranted) {
            Log.d("MainActivity", "Requesting permissions...")
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS
                )
            )
        }
    }

    DisposableEffect(webRTCManager) {
        onDispose {
            Log.d("MainActivity", "Disposing WebRTC")
            webRTCManager?.dispose()
        }
    }

    DisposableEffect(myUserId, webRTCManager) {
        if (myUserId.isEmpty() || webRTCManager == null) {
            return@DisposableEffect onDispose { }
        }

        val callsRef = FirebaseDatabase.getInstance()
            .getReference("calls")
            .child(myUserId)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (callSnapshot in snapshot.children) {
                    val type = callSnapshot.child("type").getValue(String::class.java)
                    val from = callSnapshot.child("from").getValue(String::class.java)
                    val callId = callSnapshot.child("callId").getValue(String::class.java)
                    val sdp = callSnapshot.child("sdp").getValue(String::class.java)
                    val isVideoCall = callSnapshot.child("isVideoCall")
                        .getValue(Boolean::class.java) ?: false

                    if (type == "offer" &&
                        from != null &&
                        from != myUserId &&
                        callId != null &&
                        sdp != null
                    ) {
                        Log.d("MainActivity", "Incoming call from $from (video: $isVideoCall)")

                        val safeFrom: String = from
                        val safeCallId: String = callId
                        val safeSdp: String = sdp

                        FirebaseDatabase.getInstance()
                            .getReference("users")
                            .child(safeFrom)
                            .get()
                            .addOnSuccessListener { userSnapshot ->
                                val callerName = userSnapshot.child("username")
                                    .getValue(String::class.java) ?: "Unknown"
                                val callerImage = userSnapshot.child("profileImage")
                                    .getValue(String::class.java)

                                incomingCallData = IncomingCallData(
                                    callId = safeCallId,
                                    callerId = safeFrom,
                                    callerName = callerName,
                                    callerImage = callerImage,
                                    offerSdp = safeSdp,
                                    isVideoCall = isVideoCall
                                )

                                currentScreen = "incoming_call"
                            }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("MainActivity", "Call listener error: ${error.message}")
            }
        }

        AppSessionManager.track(callsRef, listener)
        callsRef.addValueEventListener(listener)

        onDispose {
            AppSessionManager.untrack(callsRef, listener)
        }
    }

    LaunchedEffect(activity?.intent) {
        val intent = activity?.intent
        val chatId = intent?.getStringExtra("chat_id")
        val chatName = intent?.getStringExtra("chat_name")

        if (chatId != null && chatName != null) {
            intent.removeExtra("chat_id")
            intent.removeExtra("chat_name")
            selectedUser = UserProfile(uid = chatId, username = chatName)
            currentScreen = "chat"
        }
    }

    LaunchedEffect(auth.currentUser) {
        if (auth.currentUser != null) {
            syncFcmToken()
        }
    }

    if (showExitDialog) {
        ExitConfirmationDialog(
            onConfirm = { activity?.finish() },
            onDismiss = { showExitDialog = false }
        )
    }

    when (currentScreen) {
        "auth" -> {
            BackHandler { showExitDialog = true }
            AuthScreen(onAuthSuccess = {
                currentScreen = "list"
                syncFcmToken()
            })
        }

        "list" -> {
            BackHandler { showExitDialog = true }
            ChatListScreen(
                database = database,
                onUserClick = { user ->
                    selectedUser = user
                    currentScreen = "chat"
                },
                onSettingsClick = { currentScreen = "settings" }
            )
        }

        "chat" -> {
            BackHandler {
                currentScreen = "list"
                selectedUser = null
            }

            ChatScreen(
                database = database,
                keyManager = keyManager,
                recipientId = selectedUser?.uid ?: "",
                recipientName = selectedUser?.username ?: stringResource(R.string.unknown_ghost),
                onBack = {
                    currentScreen = "list"
                    selectedUser = null
                },
                onVideoCall = {
                    if (!permissionsGranted) {
                        Toast.makeText(
                            context,
                            "Нужны разрешения на камеру и микрофон",
                            Toast.LENGTH_SHORT
                        ).show()
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.CAMERA,
                                Manifest.permission.RECORD_AUDIO
                            )
                        )
                        return@ChatScreen
                    }

                    webRTCManager?.startCall(
                        recipientId = selectedUser?.uid ?: "",
                        isVideoCall = true,
                        onSuccess = {
                            Log.d("MainActivity", "Video call started")
                            currentScreen = "call"
                        },
                        onError = { error ->
                            Log.e("MainActivity", "Call failed: $error")
                            Toast.makeText(
                                context,
                                "Не удалось начать звонок: $error",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                },
                onAudioCall = {
                    if (!permissionsGranted) {
                        Toast.makeText(
                            context,
                            "Нужны разрешения на микрофон",
                            Toast.LENGTH_SHORT
                        ).show()
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.RECORD_AUDIO)
                        )
                        return@ChatScreen
                    }

                    webRTCManager?.startCall(
                        recipientId = selectedUser?.uid ?: "",
                        isVideoCall = false,
                        onSuccess = {
                            Log.d("MainActivity", "Audio call started")
                            currentScreen = "call"
                        },
                        onError = { error ->
                            Log.e("MainActivity", "Call failed: $error")
                            Toast.makeText(
                                context,
                                "Не удалось начать звонок: $error",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
            )
        }

        "incoming_call" -> {
            BackHandler {
                incomingCallData?.let { callData ->
                    FirebaseDatabase.getInstance()
                        .getReference("calls")
                        .child(myUserId)
                        .child(callData.callId)
                        .removeValue()
                }
                incomingCallData = null
                currentScreen = "list"
            }

            incomingCallData?.let { callData ->
                IncomingCallScreen(
                    callerName = callData.callerName,
                    callerImage = callData.callerImage,
                    isVideoCall = callData.isVideoCall,
                    callId = callData.callId,
                    callerId = callData.callerId,
                    offerSdp = callData.offerSdp,
                    onAccept = {
                        if (!permissionsGranted) {
                            Toast.makeText(
                                context,
                                "Нужны разрешения",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@IncomingCallScreen
                        }

                        webRTCManager?.answerCall(
                            callId = callData.callId,
                            callerId = callData.callerId,
                            offerSdp = callData.offerSdp,
                            isVideoCall = callData.isVideoCall,
                            onSuccess = {
                                Log.d("MainActivity", "Call answered")
                                selectedUser = UserProfile(
                                    uid = callData.callerId,
                                    username = callData.callerName
                                )
                                currentScreen = "call"
                                incomingCallData = null
                            },
                            onError = { error ->
                                Log.e("MainActivity", "Answer failed: $error")
                                Toast.makeText(
                                    context,
                                    "Не удалось ответить: $error",
                                    Toast.LENGTH_SHORT
                                ).show()
                                currentScreen = "list"
                                incomingCallData = null
                            }
                        )
                    },
                    onDecline = {
                        FirebaseDatabase.getInstance()
                            .getReference("calls")
                            .child(myUserId)
                            .child(callData.callId)
                            .removeValue()

                        incomingCallData = null
                        currentScreen = "list"
                    }
                )
            }
        }

        "call" -> {
            BackHandler {
                webRTCManager?.endCall()
                currentScreen = "list"
                selectedUser = null
            }

            webRTCManager?.let { manager ->
                CallScreen(
                    webRTCManager = manager,
                    recipientName = selectedUser?.username ?: "Unknown",
                    onEndCall = {
                        Log.d("MainActivity", "Ending call")
                        manager.endCall()
                        currentScreen = "list"
                        selectedUser = null
                    }
                )
            }
        }

        "settings" -> {
            BackHandler { currentScreen = "list" }
            SettingsScreen(
                database = database,
                onBack = { currentScreen = "list" },
                onLogout = {
                    // ФИКС: раньше здесь было "auth.signOut()" напрямую — без снятия
                    // Firebase-листенеров и без очистки ratchet_sessions. Теперь
                    // строгий порядок: сначала обрываем звонок и WebRTC (сигнальные
                    // листенеры звонков сами уходят вместе с ним), затем
                    // AppSessionManager.performLogout() снимает ВСЕ трекнутые
                    // Firebase-листенеры, чистит ratchet_sessions в Room и
                    // ТОЛЬКО ПОТОМ зовёт FirebaseAuth.signOut(). Экран меняем на
                    // "auth" уже после того, как логаут гарантированно завершён.
                    appScope.launch {
                        webRTCManager?.endCall()
                        webRTCManager?.dispose()
                        AppSessionManager.performLogout(database)
                        currentScreen = "auth"
                        selectedUser = null
                    }
                }
            )
        }
    }
}

@Composable
fun ExitConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Выход из GhostChat",
                color = Color.White
            )
        },
        text = {
            Text(
                text = "Вы действительно хотите выйти из приложения?",
                color = Color.Gray
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color(0xFF00FFCC)
                )
            ) {
                Text("Выйти")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.White
                )
            ) {
                Text("Отмена")
            }
        },
        containerColor = Color(0xFF1A1A1A),
        iconContentColor = Color(0xFF00FFCC),
        titleContentColor = Color.White,
        textContentColor = Color.Gray
    )
}

private fun syncFcmToken() {
    val myId = FirebaseAuth.getInstance().currentUser?.uid ?: return
    FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
        FirebaseDatabase.getInstance().getReference("users")
            .child(myId).child("fcmToken").setValue(token)
    }
}

private fun updateOnlineStatus(status: String) {
    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
    FirebaseDatabase.getInstance().getReference("users")
        .child(uid).child("status").setValue(status)
}
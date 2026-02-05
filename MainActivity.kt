package io.ghostsoftware.ghostchat

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging
import io.ghostsoftware.ghostchat.ui.theme.GhostchatTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val encryptionManager = EncryptionManager()
    private val database by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)

        // --- НОВАЯ ЛОГИКА: ДИНАМИЧЕСКИЙ ANTI-SCREENSHOT ---
        lifecycleScope.launch {
            database.messageDao().getSettings().collectLatest { settings ->
                // Если securityLevel >= 2, включаем защиту
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

        // Синхронизация ключей (под новый RSA стандарт)
        if (!encryptionManager.isKeyGenerated()) {
            encryptionManager.generateIdentityKeys()
        }

        enableEdgeToEdge()

        setContent {
            GhostchatTheme {
                val auth = FirebaseAuth.getInstance()
                val context = LocalContext.current
                val activity = context as? Activity

                val startScreen = if (auth.currentUser != null && auth.currentUser!!.isEmailVerified) "list" else "auth"
                var currentScreen by remember { mutableStateOf(startScreen) }
                var selectedUser by remember { mutableStateOf<UserProfile?>(null) }

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

                when (currentScreen) {
                    "auth" -> AuthScreen(onAuthSuccess = {
                        currentScreen = "list"
                        syncFcmToken()
                    })

                    "list" -> ChatListScreen(
                        database = database,
                        onUserClick = { user ->
                            selectedUser = user
                            currentScreen = "chat"
                        },
                        onSettingsClick = { currentScreen = "settings" }
                    )

                    "chat" -> ChatScreen(
                        database = database,
                        recipientId = selectedUser?.uid ?: "",
                        recipientName = selectedUser?.username ?: stringResource(R.string.unknown_ghost),
                        onBack = { currentScreen = "list" }
                    )

                    "settings" -> SettingsScreen(
                        database = database,
                        onBack = { currentScreen = "list" },
                        onLogout = {
                            updateOnlineStatus(System.currentTimeMillis().toString())
                            auth.signOut()
                            currentScreen = "auth"
                        }
                    )
                }
            }
        }
    }

    private fun syncFcmToken() {
        val myId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            FirebaseDatabase.getInstance().getReference("users")
                .child(myId)
                .child("fcmToken")
                .setValue(token)
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
        FirebaseDatabase.getInstance()
            .getReference("users")
            .child(uid)
            .child("status")
            .setValue(status)
    }
}
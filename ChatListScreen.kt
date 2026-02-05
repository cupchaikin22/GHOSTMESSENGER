package io.ghostsoftware.ghostchat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    database: AppDatabase,
    onUserClick: (UserProfile) -> Unit,
    onSettingsClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var searchResult by remember { mutableStateOf<UserProfile?>(null) }
    var isSearching by remember { mutableStateOf(false) }

    val messageDao = database.messageDao()
    val myId = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    val settingsState by messageDao.getSettings().collectAsState(initial = SettingsEntity())

    val globalAccent by animateColorAsState(
        targetValue = Color(settingsState?.globalAccentColor ?: Color.White.toArgb()),
        animationSpec = tween(durationMillis = 600), label = "accent"
    )
    val backgroundColor by animateColorAsState(
        targetValue = Color(settingsState?.globalBackgroundColor ?: Color.Black.toArgb()),
        animationSpec = tween(durationMillis = 600), label = "bg"
    )

    val activeChats by messageDao.getAllLastMessages().collectAsState(initial = emptyList())

    // Change- Улучшенная логика поиска (UID + ProfileID)
    LaunchedEffect(searchQuery) {
        val query = searchQuery.trim()
        if (query.isNotEmpty()) {
            isSearching = true
            delay(400) // Debounce
            val usersRef = FirebaseDatabase.getInstance().getReference("users")

            // 1. Пробуем найти как прямой UID
            usersRef.child(query).get().addOnSuccessListener { s ->
                val profileByUid = s.getValue(UserProfile::class.java)
                if (profileByUid != null && profileByUid.uid.isNotEmpty()) {
                    searchResult = profileByUid
                    isSearching = false
                } else {
                    // 2. Если не нашли, ищем по полю profileId
                    usersRef.orderByChild("profileId").equalTo(query)
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(snapshot: DataSnapshot) {
                                val found = snapshot.children.firstOrNull()?.getValue(UserProfile::class.java)
                                searchResult = found
                                isSearching = false
                            }
                            override fun onCancelled(e: DatabaseError) { isSearching = false }
                        })
                }
            }.addOnFailureListener { isSearching = false }
        } else {
            searchResult = null
            isSearching = false
        }
    }

    // Мониторинг входящих сообщений
    LaunchedEffect(myId) {
        val messagesRef = FirebaseDatabase.getInstance().getReference("messages")
        messagesRef.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val chatId = snapshot.key ?: return
                if (chatId.contains(myId)) {
                    val lastMsgSnap = snapshot.children.lastOrNull() ?: return
                    val senderId = lastMsgSnap.child("senderId").getValue(String::class.java) ?: ""
                    val firebaseKey = lastMsgSnap.key ?: ""
                    val textEnc = lastMsgSnap.child("text").getValue(String::class.java) ?: ""

                    if (textEnc.startsWith("GHOST_KEY:")) return

                    if (senderId != myId) {
                        val ts = lastMsgSnap.child("timestamp").getValue(Long::class.java) ?: System.currentTimeMillis()
                        scope.launch(Dispatchers.IO) {
                            if (messageDao.getMessageByFirebaseKey(firebaseKey) == null) {
                                messageDao.insertMessage(MessageEntity(
                                    chatId = chatId, senderId = senderId, text = textEnc,
                                    timestamp = ts, isFromMe = false, fKey = firebaseKey, currentStatus = 1
                                ))
                            }
                        }
                    }
                }
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Box(Modifier.fillMaxSize().background(backgroundColor)) {
        Column(Modifier.fillMaxSize()) {
            // Header
            Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("Ghost.im", color = globalAccent, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, null, tint = Color.Gray.copy(0.6f))
                    }
                }
                OutlinedTextField(
                    value = searchQuery, onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    placeholder = { Text("Поиск по Profile ID или UID...", color = Color.Gray) },
                    shape = RoundedCornerShape(16.dp),
                    trailingIcon = {
                        if (isSearching) CircularProgressIndicator(Modifier.size(20.dp), color = globalAccent, strokeWidth = 2.dp)
                        else if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, null, tint = Color.Gray) }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = globalAccent, unfocusedBorderColor = Color.White.copy(0.1f)
                    )
                )
            }

            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 100.dp)) {
                // LLM APDED: Отображение результата поиска
                searchResult?.let { profile ->
                    item {
                        Text("Результат поиска:", color = globalAccent, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp))
                        SearchResultRow(profile, globalAccent) { onUserClick(profile) }
                        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = Color.White.copy(0.05f))
                        Spacer(Modifier.height(10.dp))
                    }
                }

                // Список чатов (показываем только если поиск пуст)
                if (searchQuery.isEmpty()) {
                    items(activeChats.sortedByDescending { it.timestamp }, key = { it.chatId }) { msg ->
                        ActiveChatRow(msg, globalAccent, myId, messageDao) {
                            val peerId = if (msg.chatId.startsWith(myId)) msg.chatId.substringAfter("${myId}_") else msg.chatId.substringBefore("_$myId")
                            onUserClick(UserProfile(uid = peerId))
                        }
                    }
                } else if (searchResult == null && !isSearching) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                            Text("Пользователь не найден", color = Color.Gray, fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        // Bottom Navigation
        Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(backgroundColor.copy(alpha = 0.8f)).padding(bottom = 16.dp, top = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp).clip(RoundedCornerShape(28.dp)).background(Color.White.copy(0.05f)).border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(28.dp)).padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomNavItem("Chats", Icons.Default.Email, globalAccent, true) { }
                BottomNavItem("Profile", Icons.Default.Person, Color.Gray, false) { onSettingsClick() }
            }
        }
    }
}

@Composable
fun SearchResultRow(profile: UserProfile, accent: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(54.dp).clip(CircleShape).background(Color.White.copy(0.05f)).border(1.dp, accent.copy(0.5f), CircleShape)) {
            if (profile.profileImage.isNotEmpty()) AsyncImage(profile.profileImage, null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
            else Text("👻", modifier = Modifier.align(Alignment.Center), fontSize = 24.sp)
        }
        Column(Modifier.padding(start = 16.dp)) {
            Text(profile.username, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("@${profile.profileId}", color = accent.copy(alpha = 0.7f), fontSize = 13.sp)
        }
        Spacer(Modifier.weight(1f))
        Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
    }
}

@Composable
fun BottomNavItem(label: String, icon: ImageVector, color: Color, isSelected: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (isSelected) 1.15f else 1.0f, label = "")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }.graphicsLayer(scaleX = scale, scaleY = scale)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
        Text(label, color = color, fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
fun ActiveChatRow(message: MessageEntity, accent: Color, myId: String, messageDao: MessageDao, onClick: () -> Unit) {
    val defaultName = stringResource(R.string.ghost_user)
    val youPrefix = stringResource(R.string.you_prefix)
    val peerId = remember(message.chatId) {
        if (message.chatId.startsWith(myId)) message.chatId.substringAfter("${myId}_")
        else message.chatId.substringBefore("_$myId")
    }

    var liveName by remember { mutableStateOf(defaultName) }
    var livePhoto by remember { mutableStateOf("") }
    var decryptedPreview by remember { mutableStateOf("...") }

    LaunchedEffect(message.text, message.chatId) {
        val secretKey = messageDao.getSecretKeyForChat(message.chatId)
        if (secretKey != null) {
            val raw = try { GhostEncryptor.decrypt(message.text, secretKey) } catch (e: Exception) { "[Ошибка ключа]" }
            decryptedPreview = if (raw.startsWith("GHOST_IMG:")) "📷 Фотография" else raw
        } else {
            decryptedPreview = "Зашифровано"
        }
    }

    LaunchedEffect(peerId) {
        FirebaseDatabase.getInstance().getReference("users").child(peerId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    val p = s.getValue(UserProfile::class.java)
                    liveName = p?.username ?: defaultName
                    livePhoto = p?.profileImage ?: ""
                }
                override fun onCancelled(e: DatabaseError) {}
            })
    }

    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))

    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(58.dp).clip(CircleShape).background(Color.White.copy(0.05f)).border(1.dp, accent.copy(0.3f), CircleShape)) {
            if (livePhoto.isNotEmpty()) AsyncImage(livePhoto, null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
            else Text("👻", modifier = Modifier.align(Alignment.Center), fontSize = 26.sp)
        }
        Column(Modifier.padding(start = 16.dp).weight(1f)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text(liveName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(time, color = Color.Gray, fontSize = 12.sp)
            }
            Text(
                text = if (message.isFromMe) youPrefix + decryptedPreview else decryptedPreview,
                color = if (message.currentStatus < 3 && !message.isFromMe) Color.White else Color.Gray,
                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (message.currentStatus < 3 && !message.isFromMe) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
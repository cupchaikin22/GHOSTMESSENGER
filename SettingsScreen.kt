package io.ghostsoftware.ghostchat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(database: AppDatabase, onBack: () -> Unit, onLogout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messageDao = database.messageDao()
    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    val profileManager = remember { ProfileManager(context) }

    val settingsState by messageDao.getSettings().collectAsState(initial = SettingsEntity())
    val current = settingsState ?: SettingsEntity()

    var profileImageUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var profileId by remember { mutableStateOf("") }
    var isInvisible by remember { mutableStateOf(false) }
    var isAntiScreenshotRemote by remember { mutableStateOf(false) } // Состояние для Firebase
    var showColorDialog by remember { mutableStateOf(false) }

    val fullPalette = remember {
        listOf(
            Color(0xFF00FFCC), Color(0xFF00E5FF), Color(0xFF2962FF), Color(0xFF7D5FFF),
            Color(0xFFBB86FC), Color(0xFFE040FB), Color(0xFFD500F9), Color(0xFFF83B78),
            Color(0xFFFF4081), Color(0xFFFF1744), Color(0xFFD50000), Color(0xFFFF5722),
            Color(0xFFFF9100), Color(0xFFFFC400), Color(0xFFFFEA00), Color(0xFFC6FF00),
            Color(0xFF76FF03), Color(0xFF00E676), Color(0xFF1DE9B6), Color(0xFF00BFA5),
            Color(0xFF0091EA), Color(0xFF304FFE), Color(0xFF6200EA), Color(0xFFAA00FF),
            Color(0xFFFF00FF), Color(0xFF607D8B), Color(0xFF455A64), Color(0xFFFFFFFF),
            Color(0xFF000000), Color(0xFF37474F), Color(0xFF808080), Color(0xFFFFA07A),

            Color(0xFFD8BFD8), Color(0xFFDDA0DD), Color(0xFFEE82EE), Color(0xFFDA70D6),
            Color(0xFFFF00FF), Color(0xFFBA55D3), Color(0xFF9370DB), Color(0xFF8A2BE2),
            Color(0xFF9400D3), Color(0xFF9932CC), Color(0xFF8B008B), Color(0xFF800080),
            Color(0xFF4B0082), Color(0xFF6A5ACD), Color(0xFF483D8B),
        )
    }


    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { selectedUri ->
            scope.launch(Dispatchers.IO) {
                try {
                    val tempFile = File(context.cacheDir, "gh_upload_${System.currentTimeMillis()}.jpg")
                    context.contentResolver.openInputStream(selectedUri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    profileManager.uploadAvatar(Uri.fromFile(tempFile), uid) { url ->
                        if (url != null) {
                            profileImageUrl = url
                            FirebaseDatabase.getInstance().getReference("users")
                                .child(uid).child("profileImage").setValue(url)
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    val wallpaperLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { selectedUri ->
            scope.launch(Dispatchers.IO) {
                val wallFile = File(context.filesDir, "wall_${System.currentTimeMillis()}.jpg")
                context.contentResolver.openInputStream(selectedUri)?.use { it.copyTo(wallFile.outputStream()) }
                messageDao.saveSettings(current.copy(chatWallpaperUrl = wallFile.absolutePath))
            }
        }
    }

    LaunchedEffect(uid) {
        FirebaseDatabase.getInstance().getReference("users").child(uid).get().addOnSuccessListener { s ->
            profileImageUrl = s.child("profileImage").getValue(String::class.java) ?: ""
            username = s.child("username").getValue(String::class.java) ?: "Ghost User"
            profileId = s.child("profileId").getValue(String::class.java) ?: ""
            isInvisible = s.child("invisible").getValue(Boolean::class.java) ?: false
            isAntiScreenshotRemote = s.child("securityLevel").getValue(Int::class.java) ?: 0 >= 2
        }
    }

    val uiAccent = Color(current.globalAccentColor)
    val bgColor = Color(current.globalBackgroundColor)
    val contentColor = if (bgColor == Color.White) Color.Black else Color.White

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GHOST ELITE CONFIG", fontWeight = FontWeight.Black, letterSpacing = 1.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = contentColor) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bgColor, titleContentColor = contentColor)
            )
        },
        containerColor = bgColor
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {

            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(120.dp).clickable { photoLauncher.launch("image/*") }) {
                        AsyncImage(
                            model = profileImageUrl.ifEmpty { "https://cdn-icons-png.flaticon.com/512/149/149071.png" },
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(CircleShape).border(1.5.dp, uiAccent, CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        Surface(Modifier.size(32.dp).align(Alignment.BottomEnd), shape = CircleShape, color = uiAccent) {
                            Icon(Icons.Default.Edit, null, Modifier.padding(6.dp), tint = Color.Black)
                        }
                    }
                    Spacer(Modifier.height(20.dp))

                    GhostInputField("GHOST NICKNAME", username, contentColor, uiAccent) {
                        username = it
                        FirebaseDatabase.getInstance().getReference("users").child(uid).child("username").setValue(it)
                    }
                    Spacer(Modifier.height(12.dp))
                    GhostInputField("PUBLIC GHOST ID", profileId, contentColor, uiAccent) {
                        profileId = it
                        FirebaseDatabase.getInstance().getReference("users").child(uid).child("profileId").setValue(it)
                    }
                }
            }

            item { GhostHeader("CORE VISUALS", uiAccent) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PresetButton(Modifier.weight(1f), "NEON", Color(0xFF00FFCC), uiAccent) {
                        scope.launch { messageDao.saveSettings(current.copy(globalAccentColor = Color(0xFF00FFCC).toArgb(), globalBackgroundColor = Color.Black.toArgb())) }
                    }
                    PresetButton(Modifier.weight(1f), "OLED", Color.White, uiAccent) {
                        scope.launch { messageDao.saveSettings(current.copy(globalAccentColor = Color.White.toArgb(), globalBackgroundColor = Color.Black.toArgb())) }
                    }
                    PresetButton(Modifier.weight(1f), "SNOW", Color.Black, uiAccent) {
                        scope.launch { messageDao.saveSettings(current.copy(globalAccentColor = Color.Black.toArgb(), globalBackgroundColor = Color.White.toArgb())) }
                    }
                }
            }

            item { GhostItem(Icons.Default.ColorLens, "Accent Palette", "Select custom color", uiAccent) { showColorDialog = true } }
            item { GhostItem(Icons.Default.Wallpaper, "Wallpaper Engine", "Set chat background", uiAccent) { wallpaperLauncher.launch("image/*") } }

            item { GhostHeader("GHOST PROTOCOL", uiAccent) }

            // ОБНОВЛЕННЫЙ АНТИ-СКРИНШОТ (СИНХРОНИЗАЦИЯ С ОБЛАКОМ)
            item {
                GhostSwitchItem(Icons.Default.Security, "Anti-Screenshot", "Block screenshots for recipients", isAntiScreenshotRemote, uiAccent) { enabled ->
                    isAntiScreenshotRemote = enabled
                    scope.launch {
                        val newLevel = if (enabled) 2 else 1
                        // Локальное сохранение
                        messageDao.saveSettings(current.copy(securityLevel = newLevel))
                        // Облачное сохранение для собеседника
                        FirebaseDatabase.getInstance().getReference("users").child(uid).child("securityLevel").setValue(newLevel)
                    }
                }
            }

            item {
                GhostSwitchItem(Icons.Default.VisibilityOff, "Stealth Mode", "No typing/online status", isInvisible, uiAccent) {
                    isInvisible = it
                    FirebaseDatabase.getInstance().getReference("users").child(uid).child("invisible").setValue(it)
                }
            }
            item {
                GhostItem(Icons.Default.Timer, "Self-Destruct", "Auto-delete: 24 hours", uiAccent) { /* Timer Logic */ }
            }
            item {
                GhostItem(Icons.Default.DeleteForever, "Panic Button", "Wipe all local data", Color.Red, true) {
                    scope.launch(Dispatchers.IO) {

                        GhostKeyManager(context).clearKeys()


                        database.clearAllTables()


                        FirebaseDatabase.getInstance().getReference("users").child(uid).child("sessionResetToken").setValue(System.currentTimeMillis())


                        AppSessionManager.performLogout(database)

                        withContext(Dispatchers.Main) {
                            onLogout()
                        }
                    }
                }
            }

            item { GhostHeader("TYPOGRAPHY", uiAccent) }
            item { GhostSliderItem("Font Size", current.fontSize.toFloat(), 12f..26f, uiAccent) { scope.launch { messageDao.saveSettings(current.copy(fontSize = it.toInt())) } } }
            item { GhostSliderItem("Corner Radius", current.chatCornerRadius.toFloat(), 0f..32f, uiAccent) { scope.launch { messageDao.saveSettings(current.copy(chatCornerRadius = it.toInt())) } } }

            item { GhostHeader("SYSTEM", Color.Red) }
            item { GhostItem(Icons.AutoMirrored.Filled.Logout, "Logout", "Deauthorize session", Color.Red, true) { onLogout() } }

            item { Spacer(Modifier.height(60.dp)) }
        }
    }

    if (showColorDialog) {
        GhostColorDialog("ELITE PALETTE", fullPalette, { showColorDialog = false }) {
            scope.launch { messageDao.saveSettings(current.copy(globalAccentColor = it.toArgb())) }
        }
    }
}

// --- ВСПОМОГАТЕЛЬНЫЕ КОМПОНЕНТЫ (БЕЗ ИЗМЕНЕНИЙ, НО С ПОДДЕРЖКОЙ АКЦЕНТОВ) ---

@Composable
fun GhostInputField(label: String, value: String, textColor: Color, accent: Color, onSave: (String) -> Unit) {
    var t by remember { mutableStateOf(value) }
    LaunchedEffect(value) { t = value }
    OutlinedTextField(
        value = t, onValueChange = { t = it },
        label = { Text(label, color = Color.Gray, fontSize = 10.sp) },
        modifier = Modifier.fillMaxWidth(0.9f),
        shape = RoundedCornerShape(12.dp),
        singleLine = true,
        trailingIcon = { IconButton(onClick = { onSave(t) }) { Icon(Icons.Default.Check, null, tint = accent) } },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = textColor, unfocusedTextColor = textColor,
            focusedBorderColor = accent, unfocusedBorderColor = Color.White.copy(0.1f)
        )
    )
}

@Composable
fun GhostItem(icon: ImageVector, title: String, subtitle: String, accent: Color, isDestructive: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick, color = Color.White.copy(0.03f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        border = BorderStroke(0.5.dp, if(isDestructive) Color.Red.copy(0.3f) else Color.White.copy(0.05f))
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (isDestructive) Color.Red else accent, modifier = Modifier.size(22.dp))
            Column(Modifier.padding(start = 16.dp)) {
                Text(title, color = if (isDestructive) Color.Red else Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(subtitle, color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun GhostSwitchItem(icon: ImageVector, title: String, subtitle: String, state: Boolean, accent: Color, onToggle: (Boolean) -> Unit) {
    Surface(
        color = Color.White.copy(0.03f), shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        border = BorderStroke(0.5.dp, Color.White.copy(0.05f))
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp))
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(subtitle, color = Color.Gray, fontSize = 12.sp)
            }
            Switch(checked = state, onCheckedChange = onToggle, colors = SwitchDefaults.colors(checkedThumbColor = accent))
        }
    }
}

@Composable
fun GhostHeader(title: String, color: Color) {
    Text(title, color = color, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 8.dp), letterSpacing = 2.sp)
}

@Composable
fun GhostSliderItem(label: String, value: Float, range: ClosedFloatingPointRange<Float>, accent: Color, onValueChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(label, color = Color.White, fontSize = 14.sp)
            Text("${value.toInt()}", color = accent, fontWeight = FontWeight.Bold)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
    }
}

@Composable
fun PresetButton(modifier: Modifier, name: String, accent: Color, currentAccent: Color, onClick: () -> Unit) {
    val isSelected = currentAccent.toArgb() == accent.toArgb()
    Surface(
        modifier = modifier.height(48.dp).clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) accent.copy(0.1f) else Color.White.copy(0.05f),
        border = BorderStroke(1.dp, if(isSelected) accent else Color.Transparent)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(name, color = if(isSelected) accent else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
fun GhostColorDialog(title: String, colors: List<Color>, onDismiss: () -> Unit, onSelect: (Color) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, confirmButton = {},
        title = { Text(title, color = Color.White, fontWeight = FontWeight.Bold) },
        containerColor = Color(0xFF1A1A1A),
        text = {
            LazyVerticalGrid(columns = GridCells.Fixed(5), modifier = Modifier.height(300.dp)) {
                items(colors) { c ->
                    Box(Modifier.size(44.dp).padding(6.dp).clip(CircleShape).background(c)
                        .clickable { onSelect(c); onDismiss() }
                        .border(1.dp, Color.White.copy(0.3f), CircleShape))
                }
            }
        }
    )
}
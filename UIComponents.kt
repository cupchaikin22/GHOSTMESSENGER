package io.ghostsoftware.ghostchat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// --- GHOST CHAT COMPONENTS ---

@Composable
fun MessageStatusIcon(status: Int, isMe: Boolean, chatAccent: Color) {
    if (!isMe) return
    val iconSize = 14.dp
    // Статус 3 = Прочитано
    val statusTint = if (status == 3) chatAccent else Color.White.copy(alpha = 0.5f)

    Row(modifier = Modifier.padding(start = 4.dp)) {
        when (status) {
            0 -> Icon(Icons.Default.AccessTime, null, Modifier.size(iconSize), tint = Color.White.copy(0.4f))
            1 -> Icon(Icons.Default.Check, null, Modifier.size(iconSize), tint = Color.White.copy(0.5f))
            2 -> Icon(Icons.Default.DoneAll, null, Modifier.size(iconSize), tint = Color.White.copy(0.5f))
            3 -> Icon(Icons.Default.DoneAll, null, Modifier.size(iconSize), tint = statusTint)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GhostMessageBubble(
    message: MessageEntity,
    chatAccent: Color,
    onLongClick: () -> Unit
) {
    val isMe = message.isFromMe
    val isPhoto = message.text.startsWith("GHOST_IMG:")
    val displayContent = if (isPhoto) message.text.removePrefix("GHOST_IMG:") else message.text

    val bubbleShape = if (isMe) {
        RoundedCornerShape(16.dp, 16.dp, 2.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 2.dp)
    }

    val backgroundColor = if (isMe) chatAccent.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.08f)
    val borderColor = if (isMe) chatAccent.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.05f)

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = backgroundColor,
            shape = bubbleShape,
            border = BorderStroke(0.5.dp, borderColor),
            modifier = Modifier
                .widthIn(max = 280.dp)
                .combinedClickable(
                    onClick = { /* ImageView Logic */ },
                    onLongClick = onLongClick
                )
        ) {
            Column(Modifier.padding(10.dp)) {
                // Блок ответа (Reply) - ИСПРАВЛЕНО НА rText
                message.rText?.let { reply ->
                    if (reply.isNotEmpty()) {
                        Text(
                            text = "⤴ $reply",
                            color = chatAccent.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }

                if (isPhoto) {
                    AsyncImage(
                        model = displayContent,
                        contentDescription = null,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(text = displayContent, color = Color.White, fontSize = 15.sp)
                }

                Row(
                    modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)),
                        color = Color.Gray,
                        fontSize = 10.sp
                    )
                    // Синхронизировано с currentStatus
                    MessageStatusIcon(message.currentStatus, isMe, chatAccent)
                }
            }
        }
    }
}

// --- STANDARD UI COMPONENTS ---

@Composable
fun SectionHeader(title: String, color: Color) {
    Text(
        text = title,
        color = color.copy(alpha = 0.7f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
fun UserItem(user: UserProfile, accent: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color.White.copy(0.05f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = user.profileImage.ifEmpty { "https://cdn-icons-png.flaticon.com/512/149/149071.png" },
                contentDescription = null,
                modifier = Modifier.size(52.dp).clip(CircleShape).border(1.5.dp, accent.copy(alpha = 0.4f), CircleShape),
                contentScale = ContentScale.Crop
            )
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Text(user.username, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(text = "Ghost ID: ${user.profileId}", color = accent.copy(alpha = 0.8f), fontSize = 11.sp)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null, tint = Color.Gray.copy(alpha = 0.3f), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun SettingsItem(icon: ImageVector, title: String, subtitle: String, accent: Color, isDestructive: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color.White.copy(0.05f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (isDestructive) Color.Red else accent)
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Text(title, color = if (isDestructive) Color.Red else Color.White, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Color.Gray, fontSize = 12.sp)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null, tint = Color.Gray.copy(alpha = 0.3f), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun SettingsSwitchItem(icon: ImageVector, title: String, subtitle: String, state: Boolean, accent: Color, onToggle: (Boolean) -> Unit) {
    Surface(
        color = Color.White.copy(0.05f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = accent)
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Color.Gray, fontSize = 12.sp)
            }
            Switch(checked = state, onCheckedChange = onToggle, colors = SwitchDefaults.colors(checkedThumbColor = accent))
        }
    }
}

@Composable
fun SettingsInputField(label: String, value: String, textColor: Color, accent: Color, onSave: (String) -> Unit) {
    var t by remember { mutableStateOf(value) }; LaunchedEffect(value) { t = value }
    OutlinedTextField(
        value = t, onValueChange = { t = it },
        label = { Text(label, fontSize = 10.sp, color = Color.Gray) },
        modifier = Modifier.fillMaxWidth(0.85f).padding(vertical = 8.dp),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        trailingIcon = { IconButton(onClick = { onSave(t) }) { Icon(Icons.Default.Done, null, tint = accent) } },
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor, focusedBorderColor = accent)
    )
}

@Composable
fun ColorPickerDialog(title: String, colors: List<Color>, onDismiss: () -> Unit, onSelect: (Color) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text(title, color = Color.White) },
        containerColor = Color(0xFF1A1A1A),
        text = {
            Box(Modifier.height(200.dp)) {
                LazyVerticalGrid(columns = GridCells.Fixed(4), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(colors) { color ->
                        Box(Modifier.size(45.dp).clip(CircleShape).background(color).clickable { onSelect(color); onDismiss() })
                    }
                }
            }
        }
    )
}
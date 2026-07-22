package io.ghostsoftware.ghostchat

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * ✅ ЭКРАН ВХОДЯЩЕГО ЗВОНКА
 *
 * Функции:
 * - Отображение информации о звонящем
 * - Анимация пульсации
 * - Кнопки ответа/отклонения
 * - Индикация типа звонка (аудио/видео)
 */
@Composable
fun IncomingCallScreen(
    callerName: String,
    callerImage: String?,
    callId: String,
    callerId: String,
    offerSdp: String,
    isVideoCall: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    // Анимация пульсации
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            // ✅ АВАТАР ЗВОНЯЩЕГО С АНИМАЦИЕЙ
            Box(
                modifier = Modifier.size(180.dp),
                contentAlignment = Alignment.Center
            ) {
                // Пульсирующие круги
                repeat(3) { index ->
                    Box(
                        modifier = Modifier
                            .size((180 + index * 30).dp)
                            .scale(scale)
                            .clip(CircleShape)
                            .background(Color(0xFF00FFCC).copy(alpha = 0.1f - index * 0.03f))
                    )
                }

                // Аватар
                AsyncImage(
                    model = callerImage?.ifEmpty {
                        "https://cdn-icons-png.flaticon.com/512/149/149071.png"
                    } ?: "https://cdn-icons-png.flaticon.com/512/149/149071.png",
                    contentDescription = null,
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color(0xFF00FFCC), CircleShape),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(Modifier.height(32.dp))

            // ✅ ИМЯ ЗВОНЯЩЕГО
            Text(
                text = callerName,
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            // ✅ ТИП ЗВОНКА
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isVideoCall) Icons.Default.Videocam else Icons.Default.Call,
                    contentDescription = null,
                    tint = Color(0xFF00FFCC),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isVideoCall) "Видеозвонок Ghost" else "Аудиозвонок Ghost",
                    color = Color(0xFF00FFCC),
                    fontSize = 16.sp
                )
            }

            Spacer(Modifier.height(16.dp))

            // ✅ ИНДИКАТОР ШИФРОВАНИЯ
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF00FFCC).copy(alpha = 0.1f)
                ),
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = Color(0xFF00FFCC),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "END-TO-END ENCRYPTED",
                        color = Color(0xFF00FFCC),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }

            Spacer(Modifier.height(64.dp))

            // ✅ КНОПКИ УПРАВЛЕНИЯ
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                // ОТКЛОНИТЬ
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        onClick = onDecline,
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        color = Color.Red
                    ) {
                        Box(
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CallEnd,
                                contentDescription = "Decline",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Отклонить",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }

                // ПРИНЯТЬ
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        onClick = onAccept,
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        color = Color(0xFF00FFCC)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isVideoCall) {
                                    Icons.Default.Videocam
                                } else {
                                    Icons.Default.Call
                                },
                                contentDescription = "Accept",
                                tint = Color.Black,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Ответить",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(Modifier.height(48.dp))

            // ✅ ДОПОЛНИТЕЛЬНАЯ ИНФОРМАЦИЯ
            Text(
                text = "Свайп вверх для быстрого ответа",
                color = Color.Gray.copy(alpha = 0.5f),
                fontSize = 12.sp
            )
        }
    }
}

/**
 * ✅ КОМПАКТНЫЙ ВАРИАНТ (для уведомления в чате)
 */
@Composable
fun IncomingCallBanner(
    callerName: String,
    isVideoCall: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A1A)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isVideoCall) Icons.Default.Videocam else Icons.Default.Call,
                    contentDescription = null,
                    tint = Color(0xFF00FFCC),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = callerName,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = if (isVideoCall) "Видеозвонок..." else "Звонок...",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            }

            Row {
                IconButton(
                    onClick = onDecline,
                    modifier = Modifier.size(40.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Red,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.CallEnd,
                                contentDescription = "Decline",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                IconButton(
                    onClick = onAccept,
                    modifier = Modifier.size(40.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF00FFCC),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isVideoCall) {
                                    Icons.Default.Videocam
                                } else {
                                    Icons.Default.Call
                                },
                                contentDescription = "Accept",
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
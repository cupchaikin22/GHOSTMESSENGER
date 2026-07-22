package io.ghostsoftware.ghostchat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * ЭКРАН АКТИВНОГО ЗВОНКА
 *
 * Использует eglBaseContext из WebRTCManager
 * вместо создания отдельного EglBase
 */
@Composable
fun CallScreen(
    webRTCManager: WebRTCManager,
    recipientName: String,
    onEndCall: () -> Unit
) {
    val callState by webRTCManager.callState.collectAsState()
    val remoteVideoTrack by webRTCManager.remoteVideoTrack.collectAsState()
    val localVideoTrack by webRTCManager.localVideoTrack.collectAsState()

    var isMicrophoneEnabled by remember { mutableStateOf(true) }
    var isCameraEnabled by remember { mutableStateOf(true) }
    var isSpeakerEnabled by remember { mutableStateOf(true) }
    var callDuration by remember { mutableStateOf(0L) }

    // Получаем EGL контекст из WebRTCManager
    val eglContext = webRTCManager.eglBaseContext

    // Таймер звонка
    LaunchedEffect(callState) {
        if (callState is CallState.Connected) {
            val startTime = System.currentTimeMillis()
            while (true) {
                kotlinx.coroutines.delay(1000)
                callDuration = (System.currentTimeMillis() - startTime) / 1000
            }
        }
    }

    // Автоматическое завершение при ошибке
    LaunchedEffect(callState) {
        if (callState is CallState.Error) {
            kotlinx.coroutines.delay(2000)
            onEndCall()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ══════════════════════════════════════
        // УДАЛЕННОЕ ВИДЕО (на весь экран)
        // ══════════════════════════════════════
        if (remoteVideoTrack != null && eglContext != null) {
            val currentRemoteTrack = remoteVideoTrack

            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        try {
                            init(eglContext, null)
                            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                            setEnableHardwareScaler(true)
                            currentRemoteTrack?.addSink(this)
                        } catch (e: Exception) {
                            android.util.Log.e("CallScreen", "Remote renderer init failed", e)
                        }
                    }
                },
                update = { renderer ->
                    try {
                        currentRemoteTrack?.addSink(renderer)
                    } catch (e: Exception) {
                        android.util.Log.e("CallScreen", "Remote renderer update failed", e)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { renderer ->
                    try {
                        currentRemoteTrack?.removeSink(renderer)
                        renderer.release()
                    } catch (e: Exception) {
                        android.util.Log.e("CallScreen", "Remote renderer release failed", e)
                    }
                }
            )
        } else {
            // Заглушка если нет видео
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1A1A1A)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Аватар-заглушка
                    Surface(
                        modifier = Modifier.size(120.dp),
                        shape = CircleShape,
                        color = Color(0xFF2A2A2A)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = Color.White.copy(alpha = 0.3f)
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    Text(
                        text = recipientName,
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(12.dp))

                    // Статус звонка
                    Text(
                        text = when (callState) {
                            is CallState.Calling -> "Вызов..."
                            is CallState.Connecting -> "Подключение..."
                            is CallState.Connected -> formatDuration(callDuration)
                            is CallState.Error -> "Ошибка: ${(callState as CallState.Error).message}"
                            CallState.Idle -> "Завершён"
                        },
                        color = when (callState) {
                            is CallState.Error -> Color.Red
                            is CallState.Connected -> Color(0xFF00FFCC)
                            else -> Color.Gray
                        },
                        fontSize = 16.sp
                    )

                    // Анимация вызова
                    if (callState is CallState.Calling || callState is CallState.Connecting) {
                        Spacer(Modifier.height(16.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color(0xFF00FFCC),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }

        // ══════════════════════════════════════
        // ЛОКАЛЬНОЕ ВИДЕО (маленькое окно в углу)
        // ══════════════════════════════════════
        if (localVideoTrack != null && isCameraEnabled && eglContext != null) {
            val currentLocalTrack = localVideoTrack

            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 80.dp, end = 16.dp)
                    .width(120.dp)
                    .height(160.dp),
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 8.dp,
                color = Color.Black
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).apply {
                            try {
                                init(eglContext, null)
                                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                                setEnableHardwareScaler(true)
                                setMirror(true)
                                currentLocalTrack?.addSink(this)
                            } catch (e: Exception) {
                                android.util.Log.e("CallScreen", "Local renderer init failed", e)
                            }
                        }
                    },
                    update = { renderer ->
                        try {
                            currentLocalTrack?.addSink(renderer)
                        } catch (e: Exception) {
                            android.util.Log.e("CallScreen", "Local renderer update failed", e)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp)),
                    onRelease = { renderer ->
                        try {
                            currentLocalTrack?.removeSink(renderer)
                            renderer.release()
                        } catch (e: Exception) {
                            android.util.Log.e("CallScreen", "Local renderer release failed", e)
                        }
                    }
                )
            }
        }

        // ══════════════════════════════════════
        // ИНФОРМАЦИЯ О ЗВОНКЕ (сверху слева)
        // ══════════════════════════════════════
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .statusBarsPadding(),
            color = Color.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = recipientName,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                // Статус / таймер
                Text(
                    text = when (callState) {
                        is CallState.Calling -> "Вызов..."
                        is CallState.Connecting -> "Подключение..."
                        is CallState.Connected -> formatDuration(callDuration)
                        is CallState.Error -> "Ошибка"
                        CallState.Idle -> ""
                    },
                    color = when (callState) {
                        is CallState.Connected -> Color(0xFF00FFCC)
                        is CallState.Error -> Color.Red
                        else -> Color.Gray
                    },
                    fontSize = 13.sp
                )

                // Индикатор шифрования
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(10.dp),
                        tint = Color(0xFF00FFCC)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "E2E ENCRYPTED",
                        color = Color(0xFF00FFCC),
                        fontSize = 9.sp,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // ══════════════════════════════════════
        // ЭЛЕМЕНТЫ УПРАВЛЕНИЯ (внизу)
        // ══════════════════════════════════════
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Подсказка
            if (callState is CallState.Connected) {
                Text(
                    text = "Ghost Call • Защищённое соединение",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 20.dp)
                )
            }

            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                // Переключение камеры
                CallControlButton(
                    icon = Icons.Default.Cameraswitch,
                    label = "Камера",
                    backgroundColor = Color.White.copy(alpha = 0.15f),
                    iconTint = Color.White,
                    onClick = { webRTCManager.switchCamera() }
                )

                // Микрофон
                CallControlButton(
                    icon = if (isMicrophoneEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                    label = if (isMicrophoneEnabled) "Микрофон" else "Выкл",
                    backgroundColor = if (isMicrophoneEnabled) {
                        Color.White.copy(alpha = 0.15f)
                    } else {
                        Color.Red.copy(alpha = 0.4f)
                    },
                    iconTint = Color.White,
                    onClick = {
                        isMicrophoneEnabled = !isMicrophoneEnabled
                        webRTCManager.toggleMicrophone(isMicrophoneEnabled)
                    }
                )

                // Завершить звонок
                CallControlButton(
                    icon = Icons.Default.CallEnd,
                    label = "Завершить",
                    backgroundColor = Color.Red,
                    iconTint = Color.White,
                    size = 64.dp,
                    onClick = {
                        webRTCManager.endCall()
                        onEndCall()
                    }
                )

                // Камера вкл/выкл
                CallControlButton(
                    icon = if (isCameraEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    label = if (isCameraEnabled) "Видео" else "Выкл",
                    backgroundColor = if (isCameraEnabled) {
                        Color.White.copy(alpha = 0.15f)
                    } else {
                        Color.Red.copy(alpha = 0.4f)
                    },
                    iconTint = Color.White,
                    onClick = {
                        isCameraEnabled = !isCameraEnabled
                        webRTCManager.toggleCamera(isCameraEnabled)
                    }
                )

                // Динамик
                CallControlButton(
                    icon = if (isSpeakerEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                    label = "Динамик",
                    backgroundColor = if (isSpeakerEnabled) {
                        Color.White.copy(alpha = 0.15f)
                    } else {
                        Color.Red.copy(alpha = 0.4f)
                    },
                    iconTint = Color.White,
                    onClick = {
                        isSpeakerEnabled = !isSpeakerEnabled
                        // TODO: переключение динамик/наушники через AudioManager
                    }
                )
            }
        }
    }
}

/**
 * КНОПКА УПРАВЛЕНИЯ ЗВОНКОМ С ПОДПИСЬЮ
 */
@Composable
fun CallControlButton(
    icon: ImageVector,
    label: String = "",
    backgroundColor: Color,
    iconTint: Color,
    size: Dp = 52.dp,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(size),
            shape = CircleShape,
            color = backgroundColor
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = iconTint,
                    modifier = Modifier.size(size / 2)
                )
            }
        }

        if (label.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp
            )
        }
    }
}

/**
 * ФОРМАТИРОВАНИЕ ДЛИТЕЛЬНОСТИ ЗВОНКА
 */
fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format("%02d:%02d", minutes, secs)
    }
}
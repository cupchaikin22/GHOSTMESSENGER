package io.ghostsoftware.ghostchat

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await  // ✅ ИСПРАВЛЕНО: Добавлен импорт

/**
 * ✅ ИСПРАВЛЕНО: Добавлены все необходимые импорты
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyVerificationScreen(
    recipientName: String,
    recipientPublicKey: String,
    myPublicKey: String,
    onVerified: () -> Unit,
    onBack: () -> Unit
) {
    var isVerified by remember { mutableStateOf(false) }
    var showQRCode by remember { mutableStateOf(false) }

    val myFingerprint = remember { ChatUtils.getKeyFingerprint(myPublicKey) }
    val theirFingerprint = remember { ChatUtils.getKeyFingerprint(recipientPublicKey) }

    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Верификация ключей") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Black
                )
            )
        },
        containerColor = androidx.compose.ui.graphics.Color.Black
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Предупреждение о важности
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = androidx.compose.ui.graphics.Color(0xFF1A1A1A)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color(0xFF00FFCC),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Зачем это нужно?",
                            color = androidx.compose.ui.graphics.Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Верификация защищает от атак типа \"человек посередине\"",
                            color = androidx.compose.ui.graphics.Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Сравните отпечатки ключей с $recipientName",
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(16.dp))

            KeyFingerprintCard(
                title = "Отпечаток $recipientName",
                fingerprint = theirFingerprint,
                color = androidx.compose.ui.graphics.Color(0xFF00FFCC)
            )

            Spacer(Modifier.height(16.dp))

            KeyFingerprintCard(
                title = "Ваш отпечаток",
                fingerprint = myFingerprint,
                color = androidx.compose.ui.graphics.Color.White
            )

            Spacer(Modifier.height(24.dp))

            OutlinedButton(
                onClick = { showQRCode = !showQRCode },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = androidx.compose.ui.graphics.Color.White
                )
            ) {
                Icon(
                    if (showQRCode) Icons.Default.Close else Icons.Default.QrCode,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(if (showQRCode) "Скрыть QR-код" else "Показать QR-код")
            }

            if (showQRCode) {
                Spacer(Modifier.height(16.dp))
                QRCodeDisplay(data = myFingerprint)
                Text(
                    text = "Попросите собеседника отсканировать этот код",
                    color = androidx.compose.ui.graphics.Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(Modifier.weight(1f))

            if (!isVerified) {
                Button(
                    onClick = {
                        scope.launch {
                            isVerified = true
                            saveVerificationStatus(recipientPublicKey, true)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFF00FFCC),
                        contentColor = androidx.compose.ui.graphics.Color.Black
                    ),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Ключи совпадают ✓", fontWeight = FontWeight.Bold)
                }
            } else {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFF00FFCC).copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Verified,
                            contentDescription = null,
                            tint = androidx.compose.ui.graphics.Color(0xFF00FFCC),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Контакт верифицирован",
                                color = androidx.compose.ui.graphics.Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Ваше соединение защищено",
                                color = androidx.compose.ui.graphics.Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        onVerified()
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = androidx.compose.ui.graphics.Color.White
                    ),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Text("Готово", color = androidx.compose.ui.graphics.Color.Black)
                }
            }

            TextButton(
                onClick = { /* TODO: Show compromise warning */ },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(
                    text = "Ключи не совпадают",
                    color = androidx.compose.ui.graphics.Color.Red
                )
            }
        }
    }
}

@Composable
fun KeyFingerprintCard(
    title: String,
    fingerprint: String,
    color: androidx.compose.ui.graphics.Color
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = androidx.compose.ui.graphics.Color(0xFF1A1A1A)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                color = androidx.compose.ui.graphics.Color.Gray,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = fingerprint,
                color = color,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        }
    }
}

@Composable
fun QRCodeDisplay(data: String) {
    val qrBitmap = remember(data) { generateQRCode(data) }

    qrBitmap?.let {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = androidx.compose.ui.graphics.Color.White
            ),
            modifier = Modifier
                .size(200.dp)
                .padding(8.dp)
        ) {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "QR Code",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            )
        }
    }
}

fun generateQRCode(data: String, size: Int = 512): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, size, size)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
            }
        }

        Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    } catch (e: Exception) {
        android.util.Log.e("QRCode", "Failed to generate QR code", e)
        null
    }
}

// ✅ ИСПРАВЛЕНО: Добавлен await
suspend fun saveVerificationStatus(publicKey: String, isVerified: Boolean) {
    try {
        val keyHash = publicKey.hashCode().toString()
        val ref = com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("verifications")
            .child(com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "")
            .child(keyHash)

        ref.setValue(mapOf(
            "verified" to isVerified,
            "timestamp" to System.currentTimeMillis()
        )).await()  // ✅ ДОБАВЛЕНО
    } catch (e: Exception) {
        android.util.Log.e("Verification", "Failed to save status", e)
    }
}

// ✅ ИСПРАВЛЕНО: Добавлен await
suspend fun isContactVerified(publicKey: String): Boolean {
    return try {
        val keyHash = publicKey.hashCode().toString()
        val ref = com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("verifications")
            .child(com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "")
            .child(keyHash)

        val snapshot = ref.get().await()  // ✅ ИСПРАВЛЕНО
        snapshot.child("verified").getValue(Boolean::class.java) ?: false
    } catch (e: Exception) {
        android.util.Log.e("Verification", "Failed to check status", e)
        false
    }
}
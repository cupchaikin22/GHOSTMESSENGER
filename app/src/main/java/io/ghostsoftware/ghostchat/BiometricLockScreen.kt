package io.ghostsoftware.ghostchat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ✅ ЭКРАН БИОМЕТРИЧЕСКОЙ БЛОКИРОВКИ
 * 
 * Отображается при:
 * - Первом запуске после включения биометрии
 * - Возврате в приложение после таймаута
 */
@Composable
fun BiometricLockScreen(
    onAuthenticate: () -> Unit
) {
    var showError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .blur(if (showError) 0.dp else 8.dp), // Размытие для конфиденциальности
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            // Иконка отпечатка
            Surface(
                shape = CircleShape,
                color = Color(0xFF00FFCC).copy(alpha = 0.2f),
                modifier = Modifier.size(120.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = "Fingerprint",
                        tint = Color(0xFF00FFCC),
                        modifier = Modifier.size(64.dp)
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // Заголовок
            Text(
                text = "GhostChat",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Приложение заблокировано",
                fontSize = 16.sp,
                color = Color.Gray
            )

            Spacer(Modifier.height(48.dp))

            // Кнопка разблокировки
            Button(
                onClick = onAuthenticate,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00FFCC),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Разблокировать",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Сообщение об ошибке
            if (showError) {
                Spacer(Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Red.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = errorMessage,
                        color = Color.Red,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // Индикатор безопасности
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(Color(0xFF00FFCC), CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "ЗАЩИЩЕННОЕ СОЕДИНЕНИЕ",
                    fontSize = 10.sp,
                    color = Color(0xFF00FFCC),
                    letterSpacing = 2.sp
                )
            }
        }
    }
}

/**
 * ✅ ПРИМЕР ИСПОЛЬЗОВАНИЯ
 * 
 * В MainActivity.kt:
 * 
 * setContent {
 *     val biometricAuth = remember { BiometricAuthManager(this) }
 *     var isUnlocked by remember { mutableStateOf(false) }
 *     
 *     if (!isUnlocked && biometricAuth.isBiometricEnabled()) {
 *         BiometricLockScreen(
 *             onAuthenticate = {
 *                 biometricAuth.authenticate(
 *                     onSuccess = { isUnlocked = true },
 *                     onError = { error ->
 *                         Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
 *                     },
 *                     onFailed = {
 *                         Toast.makeText(this, "Попробуйте снова", Toast.LENGTH_SHORT).show()
 *                     }
 *                 )
 *             }
 *         )
 *     } else {
 *         // Основной контент приложения
 *         MainContent()
 *     }
 * }
 */

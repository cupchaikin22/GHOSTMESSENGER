package io.ghostsoftware.ghostchat

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@Composable
fun AuthScreen(onAuthSuccess: () -> Unit) {
    var isLoginMode by remember { mutableStateOf(true) }
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val auth = FirebaseAuth.getInstance()
    val keyManager = remember { GhostKeyManager(context) }
    // Нужен для суспенд-вызова keyManager.ensureKeysExist() — сама AuthScreen
    // не suspend-функция, а Firebase-колбэки (addOnCompleteListener) тоже нет.
    val scope = rememberCoroutineScope()

    val passwordStrength = remember(password) {
        if (!isLoginMode) calculatePasswordStrength(password) else null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Ghost.im",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(8.dp))

        val isKeyReady = keyManager.hasKeys()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(
                        if (isKeyReady) Color(0xFF00FFCC) else Color.Red,
                        RoundedCornerShape(3.dp)
                    )
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (isKeyReady) "SECURE PROTOCOL ACTIVE" else "INITIALIZING IDENTITY...",
                fontSize = 9.sp,
                color = if (isKeyReady) Color(0xFF00FFCC) else Color.Red,
                letterSpacing = 2.sp
            )
        }

        Spacer(modifier = Modifier.height(40.dp))

        errorMessage?.let { error ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Red.copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = error,
                    color = Color.Red,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (!isLoginMode) {
            GhostInput(
                value = username,
                onValueChange = { username = it },
                label = "Ghost Name",
                error = if (username.isNotEmpty() && !isValidUsername(username)) {
                    "Только буквы и цифры, 3-20 символов"
                } else null
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        GhostInput(
            value = email,
            onValueChange = {
                email = it.trim()
                errorMessage = null
            },
            label = "Email",
            keyboardType = KeyboardType.Email,
            error = if (email.isNotEmpty() && !isValidEmail(email)) {
                "Некорректный email адрес"
            } else null
        )

        Spacer(modifier = Modifier.height(16.dp))

        GhostInput(
            value = password,
            onValueChange = {
                password = it.trim()
                errorMessage = null
            },
            label = "Password",
            isPassword = !passwordVisible,
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = "Toggle password visibility",
                        tint = Color.Gray
                    )
                }
            }
        )

        if (!isLoginMode && passwordStrength != null) {
            Spacer(modifier = Modifier.height(8.dp))
            PasswordStrengthIndicator(passwordStrength)
        }

        Spacer(modifier = Modifier.height(40.dp))

        if (isLoading) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Button(
                onClick = {
                    errorMessage = null

                    val validationError = validateInput(
                        isLoginMode = isLoginMode,
                        email = email,
                        password = password,
                        username = username
                    )

                    if (validationError != null) {
                        errorMessage = validationError
                        return@Button
                    }

                    isLoading = true

                    if (isLoginMode) {
                        handleLogin(
                            auth = auth,
                            email = email,
                            password = password,
                            keyManager = keyManager,
                            scope = scope,
                            onSuccess = {
                                isLoading = false
                                onAuthSuccess()
                            },
                            onError = { error ->
                                isLoading = false
                                errorMessage = error
                            }
                        )
                    } else {
                        handleRegistration(
                            auth = auth,
                            email = email,
                            password = password,
                            username = username,
                            keyManager = keyManager,
                            scope = scope,
                            onSuccess = {
                                isLoginMode = true
                                isLoading = false
                                Toast.makeText(
                                    context,
                                    "Проверьте email для подтверждения!",
                                    Toast.LENGTH_LONG
                                ).show()
                            },
                            onError = { error ->
                                isLoading = false
                                errorMessage = error
                            }
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                shape = RoundedCornerShape(28.dp),
                enabled = !isLoading
            ) {
                Text(
                    text = if (isLoginMode) "INITIALIZE" else "REGISTER",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = if (isLoginMode) "Нет аккаунта? Зарегистрироваться." else "Есть аккаунт? Войти.",
                color = Color.Gray,
                fontSize = 13.sp,
                modifier = Modifier.clickable {
                    isLoginMode = !isLoginMode
                    errorMessage = null
                }
            )
        }
    }
}

private fun validateInput(
    isLoginMode: Boolean,
    email: String,
    password: String,
    username: String
): String? {
    if (!isValidEmail(email)) {
        return "Некорректный email адрес"
    }

    if (isLoginMode) {
        if (password.isEmpty()) {
            return "Введите пароль"
        }
    } else {
        val passwordError = validatePassword(password)
        if (passwordError != null) {
            return passwordError
        }
    }

    if (!isLoginMode && !isValidUsername(username)) {
        return "Имя: 3-20 символов, только буквы и цифры"
    }

    return null
}

private fun validatePassword(password: String): String? {
    if (password.length < 12) {
        return "Пароль должен содержать минимум 12 символов"
    }

    val hasUpperCase = password.any { it.isUpperCase() }
    val hasLowerCase = password.any { it.isLowerCase() }
    val hasDigit = password.any { it.isDigit() }
    val hasSpecial = password.any { !it.isLetterOrDigit() }

    if (!hasUpperCase) return "Пароль должен содержать заглавные буквы"
    if (!hasLowerCase) return "Пароль должен содержать строчные буквы"
    if (!hasDigit) return "Пароль должен содержать цифры"
    if (!hasSpecial) return "Пароль должен содержать спецсимволы (!@#\$%^&* и т.д.)"

    return null
}

private fun calculatePasswordStrength(password: String): PasswordStrength {
    var score = 0

    if (password.length >= 12) score++
    if (password.length >= 16) score++
    if (password.any { it.isUpperCase() }) score++
    if (password.any { it.isLowerCase() }) score++
    if (password.any { it.isDigit() }) score++
    if (password.any { !it.isLetterOrDigit() }) score++
    if (password.length >= 20) score++

    return when {
        score < 3 -> PasswordStrength.WEAK
        score < 5 -> PasswordStrength.MEDIUM
        else -> PasswordStrength.STRONG
    }
}

private fun isValidEmail(email: String): Boolean {
    return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
}

private fun isValidUsername(username: String): Boolean {
    return username.length in 3..20 && username.matches(Regex("^[a-zA-Z0-9_]+\$"))
}

/**
 * Обработка входа.
 *
 * ФИКС: раньше публичный ключ писался в устаревший путь users/{uid}/publicKey.
 * GhostSessionManager.getRecipientPublicKey() и весь X3DH читают из
 * users/{uid}/keys/x25519 — рассинхрон путей означал, что собеседники могли
 * не находить актуальный identity-ключ. ensureKeysExist() — единственная
 * точка входа: сама решает, нужна ли регенерация (hasKeys() + валидность
 * unwrap из Keystore), и публикует в ПРАВИЛЬНЫЙ путь keys/x25519 + keys/ed25519.
 */
private fun handleLogin(
    auth: FirebaseAuth,
    email: String,
    password: String,
    keyManager: GhostKeyManager,
    scope: CoroutineScope,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    auth.signInWithEmailAndPassword(email, password)
        .addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val user = auth.currentUser
                if (user != null && user.isEmailVerified) {
                    scope.launch {
                        try {
                            keyManager.ensureKeysExist()
                        } catch (e: Exception) {
                            Log.e("AuthScreen", "ensureKeysExist on login failed: ${e.javaClass.simpleName}")
                        }
                        onSuccess()
                    }
                } else {
                    auth.signOut()
                    onError("Email не подтвержден. Проверьте почту.")
                }
            } else {
                val errorMsg = when (task.exception) {
                    is FirebaseAuthWeakPasswordException -> "Слишком слабый пароль"
                    else -> "Ошибка входа: ${task.exception?.message}"
                }
                onError(errorMsg)
            }
        }
}

/**
 * Обработка регистрации.
 *
 * ФИКС: раньше здесь был БЕЗУСЛОВНЫЙ keyManager.generateAndSaveKeys() — если
 * на устройстве уже лежал валидный identity-ключ (повторная попытка
 * регистрации после сетевого сбоя между createUser и этим блоком, либо
 * relogin в рамках одного процесса), он затирался НОВЫМ ключом. Все
 * ratchet-сессии, поднятые под старым identity-ключом с другими контактами,
 * моментально становились нерасшифровываемыми (AEADBadTagException), так
 * как X3DH у собеседника всё ещё считает актуальным старый публичный ключ.
 * ensureKeysExist() генерирует ключи ТОЛЬКО если их реально нет или они
 * повреждены (см. GhostKeyManager.ensureKeysExist).
 */
private fun handleRegistration(
    auth: FirebaseAuth,
    email: String,
    password: String,
    username: String,
    keyManager: GhostKeyManager,
    scope: CoroutineScope,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    auth.createUserWithEmailAndPassword(email, password)
        .addOnCompleteListener { regTask ->
            if (regTask.isSuccessful) {
                val uid = auth.currentUser?.uid ?: ""

                scope.launch {
                    try {
                        keyManager.ensureKeysExist()
                    } catch (e: Exception) {
                        Log.e("AuthScreen", "ensureKeysExist on register failed: ${e.javaClass.simpleName}")
                    }

                    val pubKey = keyManager.getX25519PublicKeyBase64() ?: ""

                    val userData = UserProfile(
                        uid = uid,
                        username = username,
                        email = email,
                        publicKey = pubKey
                    )

                    try {
                        FirebaseDatabase.getInstance()
                            .getReference("users")
                            .child(uid)
                            .setValue(userData)
                            .await()

                        // Публикуем ключи В ПРАВИЛЬНЫЙ путь keys/x25519, keys/ed25519 —
                        // именно оттуда их читает GhostKeyManager.getRecipientPublicKey().
                        keyManager.publishPublicKeysIfNeeded()

                        auth.currentUser?.sendEmailVerification()
                        onSuccess()
                    } catch (e: Exception) {
                        Log.e("AuthScreen", "registration data publish failed: ${e.javaClass.simpleName}")
                        onError("Ошибка регистрации: ${e.message}")
                    }
                }
            } else {
                val errorMsg = when (regTask.exception) {
                    is FirebaseAuthUserCollisionException -> "Email уже зарегистрирован"
                    is FirebaseAuthWeakPasswordException -> "Слишком слабый пароль"
                    else -> "Ошибка регистрации: ${regTask.exception?.message}"
                }
                onError(errorMsg)
            }
        }
}

@Composable
private fun PasswordStrengthIndicator(strength: PasswordStrength) {
    Column(modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = strength.progress,
            color = strength.color,
            modifier = Modifier.fillMaxWidth().height(4.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = strength.label,
            fontSize = 11.sp,
            color = strength.color
        )
    }
}

private enum class PasswordStrength(
    val progress: Float,
    val color: Color,
    val label: String
) {
    WEAK(0.33f, Color.Red, "Слабый пароль"),
    MEDIUM(0.66f, Color.Yellow, "Средний пароль"),
    STRONG(1f, Color.Green, "Надежный пароль")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GhostInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    error: String? = null,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, color = Color.Gray, fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            trailingIcon = trailingIcon,
            isError = error != null,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = Color(0xFF0A0A0A),
                unfocusedContainerColor = Color(0xFF0A0A0A),
                focusedBorderColor = if (error != null) Color.Red else Color.White,
                unfocusedBorderColor = if (error != null) Color.Red else Color.White.copy(0.1f)
            ),
            shape = RoundedCornerShape(28.dp)
        )

        if (error != null) {
            Text(
                text = error,
                color = Color.Red,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }
    }
}
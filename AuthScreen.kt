package io.ghostsoftware.ghostchat

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import com.google.firebase.database.FirebaseDatabase

@Composable
fun AuthScreen(onAuthSuccess: () -> Unit) {
    var isLoginMode by remember { mutableStateOf(true) }
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val auth = FirebaseAuth.getInstance()
    val encManager = remember { EncryptionManager() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Ghost.im", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)

        // Индикатор безопасности
        val isKeyReady = encManager.isKeyGenerated()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if(isKeyReady) Color(0xFF00FFCC) else Color.Red, RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (isKeyReady) "SECURE PROTOCOL ACTIVE" else "INITIALIZING IDENTITY...",
                fontSize = 9.sp, color = if(isKeyReady) Color(0xFF00FFCC) else Color.Red, letterSpacing = 2.sp
            )
        }

        Spacer(modifier = Modifier.height(50.dp))

        if (!isLoginMode) {
            GhostInput(value = username, onValueChange = { username = it }, label = "Ghost Name")
            Spacer(modifier = Modifier.height(16.dp))
        }

        GhostInput(value = email, onValueChange = { email = it.trim() }, label = "Email", keyboardType = KeyboardType.Email)
        Spacer(modifier = Modifier.height(16.dp))
        GhostInput(value = password, onValueChange = { password = it.trim() }, label = "Password", isPassword = true)

        Spacer(modifier = Modifier.height(40.dp))

        if (isLoading) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Button(
                onClick = {
                    if (email.isNotBlank() && password.length >= 6) {
                        isLoading = true
                        if (isLoginMode) {
                            auth.signInWithEmailAndPassword(email, password)
                                .addOnCompleteListener { task ->
                                    if (task.isSuccessful) {
                                        val user = auth.currentUser
                                        if (user != null && user.isEmailVerified) {
                                            // СИНХРОНИЗАЦИЯ КЛЮЧЕЙ ПРИ ВХОДЕ (Лечит BAD_DECRYPT)
                                            encManager.generateIdentityKeys()
                                            val currentPubKey = encManager.getPublicKeyString() ?: ""

                                            FirebaseDatabase.getInstance().getReference("users")
                                                .child(user.uid).child("publicKey").setValue(currentPubKey)
                                                .addOnCompleteListener {
                                                    onAuthSuccess()
                                                }
                                        } else {
                                            isLoading = false
                                            auth.signOut()
                                            Toast.makeText(context, "Verify your email!", Toast.LENGTH_LONG).show()
                                        }
                                    } else {
                                        isLoading = false
                                        Toast.makeText(context, "Auth Failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                        } else {
                            // РЕГИСТРАЦИЯ
                            if (username.isBlank()) {
                                Toast.makeText(context, "Enter name", Toast.LENGTH_SHORT).show()
                                isLoading = false
                                return@Button
                            }
                            auth.createUserWithEmailAndPassword(email, password)
                                .addOnCompleteListener { regTask ->
                                    if (regTask.isSuccessful) {
                                        val uid = auth.currentUser?.uid ?: ""
                                        encManager.generateIdentityKeys()
                                        val pubKey = encManager.getPublicKeyString() ?: ""

                                        val userData = UserProfile(
                                            uid = uid,
                                            username = username,
                                            email = email,
                                            publicKey = pubKey
                                        )

                                        FirebaseDatabase.getInstance().getReference("users").child(uid)
                                            .setValue(userData).addOnCompleteListener {
                                                auth.currentUser?.sendEmailVerification()
                                                Toast.makeText(context, "Check Email for Verification!", Toast.LENGTH_LONG).show()
                                                isLoginMode = true
                                                isLoading = false
                                            }
                                    } else {
                                        isLoading = false
                                        Toast.makeText(context, "Error: ${regTask.exception?.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                        }
                    } else {
                        Toast.makeText(context, "Invalid data", Toast.LENGTH_SHORT).show()
                        isLoading = false
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                shape = RoundedCornerShape(28.dp)
            ) {
                Text(if (isLoginMode) "INITIALIZE" else "REGISTER", color = Color.Black, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = if (isLoginMode) "No identity? Register." else "Have an identity? Login.",
                color = Color.Gray, fontSize = 13.sp,
                modifier = Modifier.clickable { isLoginMode = !isLoginMode }
            )
        }
    }
}

// --- ВСПОМОГАТЕЛЬНЫЙ КОМПОНЕНТ В ТОМ ЖЕ ФАЙЛЕ ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GhostInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label, color = Color.Gray, fontSize = 12.sp) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedContainerColor = Color(0xFF0A0A0A),
            unfocusedContainerColor = Color(0xFF0A0A0A),
            focusedBorderColor = Color.White,
            unfocusedBorderColor = Color.White.copy(0.1f)
        ),
        shape = RoundedCornerShape(28.dp)
    )
}
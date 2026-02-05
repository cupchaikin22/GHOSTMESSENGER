package io.ghostsoftware.ghostchat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.google.firebase.database.FirebaseDatabase
import okhttp3.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

class ProfileManager(private val context: Context) {
    private val client = OkHttpClient()
    private val db = FirebaseDatabase.getInstance().getReference("users")

    // 1. УНИВЕРСАЛЬНЫЙ МЕТОД (Теперь со сжатием)
    fun uploadImage(uri: Uri, onResult: (String?) -> Unit) {
        try {
            // --- LLM APDED: БЛОК СЖАТИЯ ---
            val inputStream = context.contentResolver.openInputStream(uri)
            val originalBitmap = BitmapFactory.decodeStream(inputStream)

            // Уменьшаем физический размер, если фото слишком огромное (например, больше 1200px)
            val scaledBitmap = if (originalBitmap.width > 1200) {
                val aspectRatio = originalBitmap.height.toFloat() / originalBitmap.width.toFloat()
                Bitmap.createScaledBitmap(originalBitmap, 1200, (1200 * aspectRatio).toInt(), true)
            } else {
                originalBitmap
            }

            val baos = ByteArrayOutputStream()

            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 38, baos)
            val bytes = baos.toByteArray()
            // ------------------------------

            val base64Image = Base64.encodeToString(bytes, Base64.NO_WRAP)

            val requestBody = FormBody.Builder()
                .add("key", BuildConfig.IMGBB_API_KEY)
                .add("image", base64Image)
                .build()

            val request = Request.Builder()
                .url("https://api.imgbb.com/1/upload")
                .post(requestBody)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = onResult(null)
                override fun onResponse(call: Call, response: Response) {
                    val data = response.body?.string()
                    if (response.isSuccessful && data != null) {
                        try {
                            val url = JSONObject(data).getJSONObject("data").getString("url")
                            onResult(url)
                        } catch (e: Exception) { onResult(null) }
                    } else onResult(null)
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
            onResult(null)
        }
    }

    fun uploadAvatar(uri: Uri, uid: String, onResult: (String?) -> Unit) {
        uploadImage(uri) { url ->
            if (url != null) {
                db.child(uid).child("profileImage").setValue(url)
                onResult(url)
            } else {
                onResult(null)
            }
        }
    }
}
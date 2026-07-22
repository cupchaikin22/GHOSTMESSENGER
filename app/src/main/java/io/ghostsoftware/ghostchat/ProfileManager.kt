package io.ghostsoftware.ghostchat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import okhttp3.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * ✅ ИСПРАВЛЕНО: Прямая загрузка на ImgBB с использованием BuildConfig
 */
class ProfileManager(private val context: Context) {

    companion object {
        // Лимиты для стабильности
        private const val MAX_IMAGE_SIZE = 5 * 1024 * 1024 // 5MB
        private const val MAX_DIMENSION = 1200
        private const val JPEG_QUALITY = 80
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private val db = FirebaseDatabase.getInstance().getReference("users")

    /**
     * Подготовка и сжатие изображения
     */
    fun uploadImage(uri: Uri, onResult: (String?) -> Unit) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (originalBitmap == null) {
                Log.e("GhostProfile", "Failed to decode image")
                onResult(null)
                return
            }

            // Умное масштабирование
            val ratio = if (originalBitmap.width > MAX_DIMENSION || originalBitmap.height > MAX_DIMENSION) {
                maxOf(originalBitmap.width.toFloat() / MAX_DIMENSION, originalBitmap.height.toFloat() / MAX_DIMENSION)
            } else 1f

            val scaledBitmap = if (ratio > 1f) {
                Bitmap.createScaledBitmap(
                    originalBitmap,
                    (originalBitmap.width / ratio).toInt(),
                    (originalBitmap.height / ratio).toInt(),
                    true
                )
            } else originalBitmap

            val baos = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
            val imageBytes = baos.toByteArray()

            if (scaledBitmap != originalBitmap) scaledBitmap.recycle()
            originalBitmap.recycle()

            // Идём напрямую в ImgBB
            uploadToImgBB(imageBytes, onResult)

        } catch (e: Exception) {
            Log.e("GhostProfile", "Processing failed", e)
            onResult(null)
        }
    }

    /**
     * ✅ ИСПРАВЛЕНО: Загрузка напрямую на ImgBB с ключом из BuildConfig
     */
    private fun uploadToImgBB(imageBytes: ByteArray, onResult: (String?) -> Unit) {
        val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        // Читаем ключ, который ты прописал в local.properties
        val apiKey = BuildConfig.IMGBB_API_KEY

        if (apiKey.isEmpty() || apiKey == "null") {
            Log.e("GhostProfile", "API KEY IS EMPTY! Check local.properties and Rebuild Project")
            onResult(null)
            return
        }

        val formBody = FormBody.Builder()
            .add("key", apiKey)
            .add("image", base64Image)
            .build()

        val request = Request.Builder()
            .url("https://api.imgbb.com/1/upload")
            .post(formBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("GhostProfile", "Network error", e)
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (response.isSuccessful && responseBody != null) {
                    try {
                        val url = JSONObject(responseBody).getJSONObject("data").getString("url")
                        Log.i("GhostProfile", "Upload successful: $url")
                        onResult(url)
                    } catch (e: Exception) {
                        Log.e("GhostProfile", "Parse error: $responseBody")
                        onResult(null)
                    }
                } else {
                    Log.e("GhostProfile", "Server error: ${response.code} - $responseBody")
                    onResult(null)
                }
            }
        })
    }

    /**
     * Загрузка аватара и обновление Firebase
     */
    fun uploadAvatar(uri: Uri, uid: String, onResult: (String?) -> Unit) {
        uploadImage(uri) { url ->
            if (url != null) {
                db.child(uid).child("profileImage").setValue(url)
                    .addOnSuccessListener { onResult(url) }
                    .addOnFailureListener { onResult(null) }
            } else {
                onResult(null)
            }
        }
    }
}
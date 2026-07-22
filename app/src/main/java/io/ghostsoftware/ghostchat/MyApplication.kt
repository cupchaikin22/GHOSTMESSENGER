package io.ghostsoftware.ghostchat

import android.app.Application
import net.zetetic.database.sqlcipher.SQLiteDatabase  // ← импорт для совместимости (если нужно)

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Самое первое — загружаем нативную часть SQLCipher
        System.loadLibrary("sqlcipher")

        // Здесь можно добавить другие глобальные вещи, если нужно
        // FirebaseApp.initializeApp(this) — можно перенести сюда, но не обязательно
    }
}
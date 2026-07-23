package io.ghostsoftware.ghostchat

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        MessageEntity::class,
        SettingsEntity::class,
        EncryptionKeyEntity::class,
        RatchetSessionEntity::class
    ],
    version = 27,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun ratchetSessionDao(): RatchetSessionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ghostchat_secure.db"
                )
                    .openHelperFactory(createFactory(context))
                    .addMigrations(MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        // ── MIGRATION 24 → 25: ИСПРАВЛЕНО — раньше тут не было CREATE TABLE между
        // RENAME и INSERT, миграция падала на "no such table: messages" у всех
        // пользователей со старой базой. Схема ниже соответствует MessageEntity/
        // SettingsEntity/EncryptionKeyEntity ДО добавления рачет-полей (они приедут
        // отдельной миграцией 26→27, чтобы не плодить столбцы тут, где их ещё не было).
        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // messages
                database.execSQL("ALTER TABLE messages RENAME TO messages_old")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        chatId TEXT NOT NULL,
                        senderId TEXT NOT NULL,
                        text TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        isFromMe INTEGER NOT NULL,
                        fKey TEXT NOT NULL,
                        rText TEXT,
                        isEdit INTEGER NOT NULL DEFAULT 0,
                        react TEXT,
                        currentStatus INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_messages_fKey ON messages(fKey)")
                database.execSQL("""
                    INSERT INTO messages (id, chatId, senderId, text, timestamp, isFromMe, fKey, rText, isEdit, react, currentStatus)
                    SELECT id, chatId, senderId, text, timestamp, isFromMe, fKey, rText, isEdit, react, currentStatus FROM messages_old
                """.trimIndent())
                database.execSQL("DROP TABLE messages_old")

                // settings
                database.execSQL("ALTER TABLE settings RENAME TO settings_old")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS settings (
                        id INTEGER PRIMARY KEY NOT NULL,
                        globalBackgroundColor INTEGER NOT NULL DEFAULT -16777216,
                        globalAccentColor INTEGER NOT NULL DEFAULT -16711712,
                        chatAccentColor INTEGER NOT NULL DEFAULT -16711712,
                        securityLevel INTEGER NOT NULL DEFAULT 0,
                        backgroundUri TEXT,
                        chatWallpaperUrl TEXT,
                        animationSpeed REAL NOT NULL DEFAULT 1.0,
                        uiAlpha REAL NOT NULL DEFAULT 1.0,
                        chatCornerRadius INTEGER NOT NULL DEFAULT 16,
                        fontSize INTEGER NOT NULL DEFAULT 16,
                        enableSounds INTEGER NOT NULL DEFAULT 1,
                        enableHaptic INTEGER NOT NULL DEFAULT 1,
                        readReceiptsEnabled INTEGER NOT NULL DEFAULT 1,
                        stealthMode INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO settings (id, globalBackgroundColor, globalAccentColor, chatAccentColor, securityLevel, backgroundUri, chatWallpaperUrl, animationSpeed, uiAlpha, chatCornerRadius, fontSize, enableSounds, enableHaptic, readReceiptsEnabled, stealthMode)
                    SELECT id, globalBackgroundColor, globalAccentColor, chatAccentColor, securityLevel, backgroundUri, chatWallpaperUrl, animationSpeed, uiAlpha, chatCornerRadius, fontSize, enableSounds, enableHaptic, readReceiptsEnabled, stealthMode FROM settings_old
                """.trimIndent())
                database.execSQL("DROP TABLE settings_old")

                // encryption_keys
                database.execSQL("ALTER TABLE encryption_keys RENAME TO encryption_keys_old")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS encryption_keys (
                        chatId TEXT PRIMARY KEY NOT NULL,
                        secretKey TEXT NOT NULL
                    )
                """.trimIndent())
                database.execSQL("INSERT INTO encryption_keys (chatId, secretKey) SELECT chatId, secretKey FROM encryption_keys_old")
                database.execSQL("DROP TABLE encryption_keys_old")
            }
        }

        // ── MIGRATION 25 → 26: таблица ratchet_sessions (новая, ранее не выпускалась —
        // сразу включаем pendingX3dhEphemeralPub, отдельная ALTER-миграция под неё не нужна)
        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
            CREATE TABLE IF NOT EXISTS ratchet_sessions (
                chatId TEXT NOT NULL PRIMARY KEY,
                rootKey TEXT NOT NULL,
                sendingChainKey TEXT NOT NULL,
                sendingSeqNum INTEGER NOT NULL,
                receivingChainKey TEXT NOT NULL,
                receivingSeqNum INTEGER NOT NULL,
                myRatchetPrivKeyWrapped TEXT NOT NULL,
                myRatchetPubKey TEXT NOT NULL,
           theirRatchetPubKey TEXT NOT NULL DEFAULT ''
                pendingX3dhEphemeralPub TEXT,
                skippedMessageKeys TEXT NOT NULL DEFAULT '{}',
                replayWindowStart INTEGER NOT NULL DEFAULT 0,
                replayWindowBits INTEGER NOT NULL DEFAULT 0,
                theirIdentityFingerprint TEXT NOT NULL DEFAULT '',
                isTofuVerified INTEGER NOT NULL DEFAULT 0,
                tofuTimestamp INTEGER NOT NULL DEFAULT 0,
                sessionVersion INTEGER NOT NULL DEFAULT 4,
                createdAt INTEGER NOT NULL,
                lastActivityAt INTEGER NOT NULL
            )
        """.trimIndent())
            }
        }

        // ── MIGRATION 26 → 27: рачет-метаданные на сообщении + флаг "уже plaintext".
        // ALTER TABLE ADD COLUMN безопасен в SQLite без пересборки таблицы, когда
        // не требуется NOT NULL без DEFAULT.
        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN seqNum INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE messages ADD COLUMN ratchetPubKey TEXT")
                database.execSQL("ALTER TABLE messages ADD COLUMN isPlaintextCached INTEGER NOT NULL DEFAULT 0")
            }
        }

        private fun createFactory(context: Context): SupportOpenHelperFactory {
            val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                context, "ghost_db_master", masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            var encodedPass = prefs.getString("db_passphrase", null)
            if (encodedPass == null) {
                val randomBytes = ByteArray(32).apply { java.security.SecureRandom().nextBytes(this) }
                encodedPass = android.util.Base64.encodeToString(randomBytes, android.util.Base64.NO_WRAP)
                prefs.edit().putString("db_passphrase", encodedPass).apply()
            }
            return SupportOpenHelperFactory(encodedPass.toByteArray(Charsets.UTF_8))
        }
    }
}
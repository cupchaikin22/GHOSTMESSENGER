package io.ghostsoftware.ghostchat

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 0, // Всегда 0, так как настройка одна на всё приложение

    // Цвета интерфейса
    val globalBackgroundColor: Int = -16777216, // Black
    val globalAccentColor: Int = -16711712,    // 0xFF00FFCC
    val chatAccentColor: Int = -16711712,

    // Безопасность и режим
    val securityLevel: Int = 0,         // 0 - LOW, 1 - MID, 2 - ULTRA
    val backgroundUri: String? = null,  // (Legacy) Старый путь к фону
    val chatWallpaperUrl: String? = null, // Актуальный путь к фону чата

    // Плавность и стиль
    val animationSpeed: Float = 1.0f,
    val uiAlpha: Float = 1.0f,
    val chatCornerRadius: Int = 16,     // Скругление пузырьков (dp)

    // LLM APDED: Добавленные поля для исправления ошибок
    val fontSize: Int = 16,             // Размер шрифта сообщений
    val enableSounds: Boolean = true,    // Звуки в приложении
    val enableHaptic: Boolean = true,    // Виброотклик

    // Дополнительный функционал
    val readReceiptsEnabled: Boolean = true,
    val stealthMode: Boolean = false     // Режим невидимки
)
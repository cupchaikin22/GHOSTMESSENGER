import java.util.Properties
import java.io.InputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

android {
    namespace = "io.ghostsoftware.ghostchat"
    compileSdk = 36

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")

    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { input: InputStream ->
            localProperties.load(input)
        }
    }

    defaultConfig {
        applicationId = "io.ghostsoftware.ghostchat"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.6.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "IMGBB_API_KEY", "\"${localProperties.getProperty("IMGBB_API_KEY") ?: ""}\"")
        buildConfigField("String", "FIREBASE_DATABASE_URL", "\"${localProperties.getProperty("FIREBASE_DATABASE_URL") ?: "https://ghostchat-e9e0c-default-rtdb.europe-west1.firebasedatabase.app"}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Тот самый блок, который лечит 16 КБ и UnsatisfiedLinkError
    packaging {
        jniLibs {
            // Запрещаем сжатие либ. Если они не сжаты, система Android 15+
            // сможет выровнять их по 16 КБ прямо внутри APK.
            useLegacyPackaging = false
        }
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation("androidx.security:security-crypto:1.1.0")

    val room_version = "2.8.4"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    ksp("androidx.room:room-compiler:$room_version")

    // Firebase (используем BOM для консистентности)
    implementation(platform("com.google.firebase:firebase-bom:34.16.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.firebase:firebase-storage")
    implementation("com.google.firebase:firebase-messaging")

    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("net.zetetic:sqlcipher-android:4.17.0") // Оставил только новую версию
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.sqlite:sqlite:2.7.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")

    // WebRTC - вернул стабильную для твоего конфига версию
    implementation("io.github.webrtc-sdk:android:144.7559.09")

    implementation("com.google.crypto.tink:tink-android:1.23.0")

}
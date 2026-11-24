// App aparte para medir Kiwi en el celular: cuánto tarda en cargar, cuánta memoria usa, qué tan rápido
// analiza y si acierta lo mismo que en el servidor. Se instala al lado de la app («Kiwi prueba», otro
// applicationId) y no toca nada de ella. Ver kiwiPrueba/README.md.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.jorge.koreandiary.kiwiprueba"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jorge.koreandiary.kiwiprueba"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "kiwi-${libs.versions.kiwi.get()}"
        // Kiwi trae su librería nativa para estas dos (celular y emulador).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        // Sin depuración, para medir la velocidad real. Sin R8: el código nativo de Kiwi busca sus
        // clases Java por nombre; las reglas para conservarlas se ven al integrarlo en la app.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Librería de Kiwi para Android, bajada por preparar.sh (no va en el repo).
    implementation(files("libs/kiwi-android.aar"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
}

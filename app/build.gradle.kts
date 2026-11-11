plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// La base de datos (Room + KSP) y su esquema viven en :shared (shared/schemas).

// En GitHub Actions el número de compilación es el versionCode: así el actualizador sabe qué es más nuevo.
// En compilaciones locales vale 1.
val buildNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").map { it.toInt() }.orElse(1).get()

android {
    namespace = "com.jorge.koreandiary"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jorge.koreandiary"
        minSdk = 26
        targetSdk = 37
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        // Keystore de depuración fijo en el repo: así cada APK de GitHub Actions se instala
        // encima del anterior sin desinstalar (y sin perder tus diarios).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Lógica, IA y base de datos compartidas con la versión de escritorio.
    implementation(project(":shared"))
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite.bundled)

    implementation(libs.mlkit.digital.ink)

    // Única excepción a "HTTP con HttpURLConnection": la conversación por voz necesita WebSocket.
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    // org.json real para los tests de JVM (el de android.jar es un stub que lanza excepciones).
    testImplementation(libs.json)
}

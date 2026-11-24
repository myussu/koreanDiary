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
        // Kiwi (analizador de oraciones): la versión de su librería y del modelo que se baja.
        buildConfigField("String", "KIWI_VERSION", "\"${libs.versions.kiwi.get()}\"")
        // Solo el celular (ARM64) y el emulador (x86_64): Kiwi no trae las de 32 bits, y las de
        // armeabi-v7a y x86 de las otras librerías sumaban ~17 MB que ningún equipo usaba.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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
        // La de depuración es la versión de prueba: otro applicationId y otro nombre («Diario prueba»,
        // en src/debug/res), así se instala al lado de la tuya sin tocar tus diarios, y la IA contesta
        // con respuestas fijas (BuildConfig.PRUEBA). Ver docs/PRUEBAS.md.
        debug {
            applicationIdSuffix = ".prueba"
            versionNameSuffix = "-prueba"
            buildConfigField("boolean", "PRUEBA", "true")
        }
        // Es la variante que publica GitHub Actions: sin depuración y con R8, que es como Compose
        // rinde de verdad (la de depuración corre mucho más lenta y gasta más batería).
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // La misma llave de siempre: Android solo instala una actualización encima de la app si
            // la firma coincide. Con otra llave habría que desinstalar y se perderían los diarios.
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "PRUEBA", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Las librerías nativas comprimidas dentro del APK: la de Kiwi pesa 18 MB por arquitectura y
    // comprimida ~5 MB. Android las descomprime al instalar.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    // Kiwi, el analizador de oraciones coreano (github.com/bab2min/Kiwi, Apache-2.0): el AAR oficial
    // del release, en el repo porque no está en Maven. El modelo no va: se baja desde Ajustes.
    implementation(files("libs/kiwi-android-v${libs.versions.kiwi.get()}.aar"))

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

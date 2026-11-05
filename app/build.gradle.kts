plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

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

    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.androidx.room3.compiler)

    implementation(libs.mlkit.digital.ink)

    testImplementation(libs.junit)
}

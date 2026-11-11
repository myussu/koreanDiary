import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Código común a Android y escritorio: lógica pura, IA, tinta, FSRS y la base de datos (Room).
// Solo hay destinos JVM (Android y escritorio), así que el código común puede usar la JDK
// (java.time, java.net, java.util.zip) y org.json como hasta ahora.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // AGP 9: com.android.library ya no se combina con KMP; este es su reemplazo.
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

kotlin {
    android {
        namespace = "com.jorge.koreandiary.shared"
        compileSdk = 37
        minSdk = 26
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    compilerOptions {
        // `expect object AppDatabaseConstructor` (Room): clases expect/actual siguen en Beta.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            // api: la app usa directamente las entidades, los DAOs y RoomDatabase.
            api(libs.androidx.room3.runtime)
            api(libs.androidx.sqlite.bundled)
            api(libs.kotlinx.coroutines.core)
            // Android ya trae org.json; en escritorio se agrega abajo.
            compileOnly(libs.json)
        }
        jvmMain.dependencies {
            implementation(libs.json)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}

// Room genera el código de la base para cada destino.
dependencies {
    add("kspAndroid", libs.androidx.room3.compiler)
    add("kspJvm", libs.androidx.room3.compiler)
}

// Room escribe aquí el esquema de cada versión de la base (1.json, 2.json...). Va al repo:
// es lo que permite las migraciones automáticas y lo que revisa GitHub Actions antes de publicar.
room3 {
    schemaDirectory("$projectDir/schemas")
}

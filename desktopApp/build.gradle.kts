import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Versión de escritorio (Windows) con Compose Multiplatform. La lógica y la base vienen de :shared.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// En GitHub Actions el número de compilación va en la versión del instalador.
val buildNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").map { it.toInt() }.orElse(1).get()

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.jetbrains.compose.runtime)
    implementation(libs.jetbrains.compose.foundation)
    implementation(libs.jetbrains.compose.ui)
    implementation(libs.jetbrains.compose.material3)
    // Dispatchers.Main en escritorio (hilo de Swing).
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "com.jorge.koreandiary.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "KoreanDiary"
            // MSI exige MAYOR.MENOR.COMPILACIÓN con MAYOR > 0.
            packageVersion = "1.0.$buildNumber"
            description = "Diario coreano escrito a mano"
            vendor = "Jorge"
            // Mientras se prueba: la JRE completa evita módulos faltantes (pesa más).
            includeAllModules = true
            windows {
                menuGroup = "Korean Diary"
                // Fijo: así un MSI nuevo actualiza al anterior en vez de instalarse al lado.
                upgradeUuid = "6f1b0c55-2a8e-4f3b-9d3e-5c1a7e0b9a41"
                shortcut = true
                perUserInstall = true
            }
        }
    }
}

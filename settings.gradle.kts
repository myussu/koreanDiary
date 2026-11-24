pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "KoreanDiary"
include(":app")
// Lógica, base de datos e IA comunes a Android y escritorio.
include(":shared")
// Versión de escritorio (Windows).
include(":desktopApp")
// App aparte para probar Kiwi (analizador morfológico) en el celular. Solo entra si ya se bajó la
// librería de Kiwi (kiwiPrueba/preparar.sh, lo corre el workflow kiwi-prueba.yml): así la compilación
// del APK y la de escritorio no la necesitan. Se borra cuando se decida si Kiwi entra en la app.
if (File(rootDir, "kiwiPrueba/libs/kiwi-android.aar").exists()) include(":kiwiPrueba")

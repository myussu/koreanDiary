package com.jorge.koreandiary

import android.app.Application
import com.jorge.koreandiary.ai.AzureOpenAIProvider
import com.jorge.koreandiary.ai.CorrectionProvider
import com.jorge.koreandiary.ai.GeminiProvider
import com.jorge.koreandiary.data.DiaryRepository
import com.jorge.koreandiary.data.StudyRepository
import com.jorge.koreandiary.data.db.AppDatabase
import com.jorge.koreandiary.data.standard.StandardCatalog
import com.jorge.koreandiary.ink.HandwritingRecognizer
import com.jorge.koreandiary.mascot.MascotPack
import com.jorge.koreandiary.settings.ProviderId
import com.jorge.koreandiary.settings.SecretStore
import com.jorge.koreandiary.settings.SettingsStore
import com.jorge.koreandiary.srs.SuggestionEngine
import com.jorge.koreandiary.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Inyección de dependencias manual: suficiente para una app de este tamaño. */
class AppContainer(val app: Application) {
    val database = AppDatabase.create(app)
    /** Catálogo estándar del 국립국어원 (en assets); se carga la primera vez que se usa. */
    val catalog = StandardCatalog(app.assets)
    val study = StudyRepository(database, catalog)
    val diary = DiaryRepository(database)
    val suggestions = SuggestionEngine(database.studyDao(), database.memoryDao(), catalog)
    val settings = SettingsStore(app)
    val secrets = SecretStore(app)
    val recognizer by lazy { HandwritingRecognizer() }
    val updater = Updater(app)
    val mascotPack = MascotPack(app)

    /** Para trabajos que deben terminar aunque se cierre la pantalla (p. ej. el guardado final). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Proveedor por API listo para usar, o null si le falta configuración. */
    fun apiProvider(id: String): CorrectionProvider? {
        val s = settings.state.value
        return when (id) {
            ProviderId.GEMINI -> secrets.get(SecretStore.GEMINI_KEY)?.let { GeminiProvider(it, s.geminiModel) }
            ProviderId.AZURE -> {
                val key = secrets.get(SecretStore.AZURE_KEY)
                if (key == null || s.azureEndpoint.isBlank() || s.azureDeployment.isBlank()) null
                else AzureOpenAIProvider(s.azureEndpoint, s.azureDeployment, key, s.azureEffort)
            }
            else -> null
        }
    }

    /** Chequeo barato para la UI (no descifra la clave). */
    fun isConfigured(id: String): Boolean {
        val s = settings.state.value
        return when (id) {
            ProviderId.SHARE -> true
            ProviderId.GEMINI -> secrets.has(SecretStore.GEMINI_KEY)
            ProviderId.AZURE -> secrets.has(SecretStore.AZURE_KEY) && s.azureEndpoint.isNotBlank() && s.azureDeployment.isNotBlank()
            else -> false
        }
    }
}

class KoreanDiaryApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

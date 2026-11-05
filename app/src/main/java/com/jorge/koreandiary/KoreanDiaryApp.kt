package com.jorge.koreandiary

import android.app.Application
import com.jorge.koreandiary.ai.CorrectionProvider
import com.jorge.koreandiary.ai.GeminiProvider
import com.jorge.koreandiary.data.DiaryRepository
import com.jorge.koreandiary.data.StudyRepository
import com.jorge.koreandiary.data.db.AppDatabase
import com.jorge.koreandiary.ink.HandwritingRecognizer
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
    val study = StudyRepository(database)
    val diary = DiaryRepository(database)
    val suggestions = SuggestionEngine(database.studyDao(), database.memoryDao())
    val settings = SettingsStore(app)
    val secrets = SecretStore(app)
    val recognizer by lazy { HandwritingRecognizer() }
    val updater = Updater(app)

    /** Para trabajos que deben terminar aunque se cierre la pantalla (p. ej. el guardado final). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Proveedor por API configurado, o null si no hay clave. */
    fun geminiProvider(): CorrectionProvider? {
        val key = secrets.get(SecretStore.GEMINI_KEY) ?: return null
        return GeminiProvider(key, settings.state.value.geminiModel)
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

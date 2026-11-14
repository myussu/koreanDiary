package com.jorge.koreandiary

import com.jorge.koreandiary.scene.SceneImages
import android.app.Application
import com.jorge.koreandiary.ai.AzureOpenAIProvider
import com.jorge.koreandiary.ai.CorrectionProvider
import com.jorge.koreandiary.ai.GeminiProvider
import com.jorge.koreandiary.backup.BackupManager
import com.jorge.koreandiary.backup.StartupRestore
import com.jorge.koreandiary.conversation.ConversationStore
import com.jorge.koreandiary.data.DiaryRepository
import com.jorge.koreandiary.data.ExplanationStore
import com.jorge.koreandiary.data.StudyRepository
import com.jorge.koreandiary.data.db.AppDatabase
import com.jorge.koreandiary.data.db.androidStandardCatalog
import com.jorge.koreandiary.data.db.create
import com.jorge.koreandiary.ink.HandwritingRecognizer
import com.jorge.koreandiary.listen.ListenAudioStore
import com.jorge.koreandiary.listen.ListenEngine
import com.jorge.koreandiary.mascot.MascotPack
import com.jorge.koreandiary.reminder.ReminderScheduler
import com.jorge.koreandiary.workbook.WorkbookStore
import com.jorge.koreandiary.settings.ProviderId
import com.jorge.koreandiary.settings.SecretStore
import com.jorge.koreandiary.settings.SettingsStore
import com.jorge.koreandiary.srs.SuggestionEngine
import com.jorge.koreandiary.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Inyección de dependencias manual: suficiente para una app de este tamaño. */
class AppContainer(val app: Application) {
    val database = AppDatabase.create(app)
    /** Catálogo estándar del 국립국어원 (en assets); se carga la primera vez que se usa. */
    val catalog = androidStandardCatalog(app.assets)
    val study = StudyRepository(database, catalog)
    val diary = DiaryRepository(database)
    val suggestions = SuggestionEngine(database.studyDao(), database.memoryDao(), catalog)
    val settings = SettingsStore(app)
    val secrets = SecretStore(app)
    val recognizer by lazy { HandwritingRecognizer() }
    val updater = Updater(app)
    val mascotPack = MascotPack(app)
    val workbook = WorkbookStore(app)
    val conversations = ConversationStore(app)
    /** Significado y ejemplos de las sugerencias ya pedidos a la IA. */
    val explanations = ExplanationStore(app)
    /** Modo Escuchar: vive aquí (no en la pantalla) para seguir con la pantalla apagada. */
    val listen by lazy { ListenEngine(this) }
    /** Audio de las últimas escuchas y las marcadas, para volver a oírlas. */
    val listenAudio by lazy { ListenAudioStore(app) }

    /** Para trabajos que deben terminar aunque se cierre la pantalla (p. ej. el guardado final). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** La imagen de cada escena: la pinta tu servidor de ComfyUI o la eliges tú. */
    val sceneImages = SceneImages(app, appScope) { settings.state.value }

    /** Exportar e importar todo a un archivo, y el estado de la copia automática de Google. */
    val backup = BackupManager(app, database, appScope, updater.currentVersionCode, updater.currentVersionName)

    init {
        // Antes de instalar cada actualización, una copia de todo (carpeta elegida o dentro de la app).
        updater.beforeInstall = { info -> backup.backupBeforeUpdate(info.versionName) }
    }

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

    /**
     * Proveedor de texto para el "coach" de la conversación (pistas, traducciones, revisión):
     * el de corrección por defecto si es una API; si no, la primera API configurada.
     */
    fun coachProvider(): Pair<String, CorrectionProvider>? {
        val s = settings.state.value
        val id = listOf(s.defaultProvider, ProviderId.GEMINI, ProviderId.AZURE)
            .firstOrNull { it != ProviderId.SHARE && isConfigured(it) } ?: return null
        return apiProvider(id)?.let { id to it }
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
        // Antes de abrir la base o leer ajustes: si hay una copia importada esperando, se pone en su lugar.
        StartupRestore.run(this)
        container = AppContainer(this)
        // Por si el sistema borró la alarma (p. ej. tras forzar el cierre): se vuelve a programar.
        ReminderScheduler.apply(this, container.settings.state.value)
        // Las fotos del workbook viven aparte de las sesiones para no ir a la copia de Google.
        container.appScope.launch { container.workbook.movePhotosOutOfSessions() }
    }
}

package com.jorge.koreandiary

import com.jorge.koreandiary.scene.SceneDrawings
import com.jorge.koreandiary.scene.SceneImages
import android.app.Application
import com.jorge.koreandiary.ai.AzureOpenAIProvider
import com.jorge.koreandiary.ai.CorrectionProvider
import com.jorge.koreandiary.ai.FixedProvider
import com.jorge.koreandiary.ai.GeminiProvider
import com.jorge.koreandiary.ai.HttpProviderException
import com.jorge.koreandiary.ai.ProviderException
import com.jorge.koreandiary.backup.BackupManager
import com.jorge.koreandiary.backup.StartupRestore
import com.jorge.koreandiary.conversation.ConversationStore
import com.jorge.koreandiary.data.DiaryRepository
import com.jorge.koreandiary.data.ExplanationStore
import com.jorge.koreandiary.data.StudyRepository
import com.jorge.koreandiary.data.db.AppDatabase
import com.jorge.koreandiary.data.db.androidKoreanDictionary
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
    /**
     * El 한국어기초사전 (en assets): verifica las palabras nuevas y da formas de diccionario y
     * significados sin IA. Su lista se carga en segundo plano al abrir la app.
     */
    val dictionary = androidKoreanDictionary(app.assets)
    val study = StudyRepository(database, catalog, dictionary)
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

    /** Lo nuevo de las escenas, dibujado por Claude: entra a la escena viva y a su diccionario. */
    val sceneDrawings = SceneDrawings(app, appScope, settings, secrets)

    /** Exportar e importar todo a un archivo, y el estado de la copia automática de Google. */
    val backup = BackupManager(app, database, appScope, updater.currentVersionCode, updater.currentVersionName)

    /**
     * Las páginas abiertas dejan aquí su «guarda ya». Lo usa el actualizador: al instalar, Android
     * cierra la app sin pasar por la pantalla, y lo escrito en los últimos segundos se perdía.
     */
    val openPages = java.util.concurrent.CopyOnWriteArraySet<suspend () -> Unit>()

    /** Guarda lo pendiente de cada página abierta. Un fallo en una no frena a las demás. */
    suspend fun saveOpenPages() {
        for (save in openPages) {
            try {
                save()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // La copia y la instalación siguen: la página se guarda igual al salir de ella.
            }
        }
    }

    init {
        // Antes de instalar cada actualización: primero se guarda lo que estés escribiendo y después
        // va una copia de todo (carpeta elegida o dentro de la app).
        updater.beforeInstall = { info ->
            saveOpenPages()
            backup.backupBeforeUpdate(info.versionName)
        }
        // Y otra vez justo antes de que Android reemplace la app, por lo escrito durante la copia.
        updater.beforeCommit = { saveOpenPages() }
        // La lista del diccionario (~51.000 entradas) se carga ya, para que la primera búsqueda no espere.
        appScope.launch { runCatching { dictionary.warmUp() } }
    }

    /** Proveedor por API listo para usar, o null si le falta configuración. */
    fun apiProvider(id: String): CorrectionProvider? {
        val s = settings.state.value
        // Versión de prueba: Gemini y Azure contestan con respuestas fijas, sin red ni claves.
        if (fixedAi(id)) return FixedProvider()
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

    /**
     * Para explicar palabras y gramática (desgloses, «Más sobre esto», tocar una palabra, las tarjetas
     * de sugerencias): un modelo más fuerte que el de corregir, porque un desglose inventado desvía el
     * estudio (Ajustes › IA › Explicaciones). Azure si lo elegiste y está configurado; si no, Gemini
     * con el modelo de explicaciones; si Gemini tampoco está, Azure. Sin ninguno, null.
     */
    fun explainProvider(): Pair<String, CorrectionProvider>? {
        val s = settings.state.value
        val azure = if (isConfigured(ProviderId.AZURE)) apiProvider(ProviderId.AZURE) else null
        if (s.explainProvider == ProviderId.AZURE && azure != null) return ProviderId.AZURE to azure
        if (isConfigured(ProviderId.GEMINI)) {
            if (fixedAi(ProviderId.GEMINI)) return ProviderId.GEMINI to FixedProvider()
            secrets.get(SecretStore.GEMINI_KEY)?.let { return ProviderId.GEMINI to GeminiProvider(it, s.explainModel) }
        }
        return azure?.let { ProviderId.AZURE to it }
    }

    /** Qué decir si falla el modelo de las explicaciones: sin cuota o sin acceso, dónde cambiarlo. */
    fun explainError(e: ProviderException, providerId: String): String {
        if (e.offline) return "Sin conexión."
        val code = (e as? HttpProviderException)?.code
        val model = settings.state.value.explainModel
        return when {
            providerId == ProviderId.GEMINI && code == 429 ->
                "Se acabó por hoy la cuota gratis de $model, el modelo de las explicaciones. Puedes usar Azure en Ajustes › IA › Explicaciones."
            providerId == ProviderId.GEMINI && (code == 403 || code == 404) ->
                "Tu clave de Gemini no puede usar $model: cámbialo en Ajustes › IA › Explicaciones."
            else -> e.message ?: "Error de la IA."
        }
    }

    /** ¿Este proveedor contesta con respuestas fijas? Solo en la versión de prueba y con el ajuste puesto. */
    private fun fixedAi(id: String): Boolean =
        BuildConfig.PRUEBA && settings.state.value.fixedAi && (id == ProviderId.GEMINI || id == ProviderId.AZURE)

    /** Chequeo barato para la UI (no descifra la clave). */
    fun isConfigured(id: String): Boolean {
        val s = settings.state.value
        if (fixedAi(id)) return true
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
        // Primero: si algo cierra la app, queda anotado para avisarlo en el inicio.
        CrashLog.install(this)
        // Antes de abrir la base o leer ajustes: si hay una copia importada esperando, se pone en su lugar.
        StartupRestore.run(this)
        container = AppContainer(this)
        // Por si el sistema borró la alarma (p. ej. tras forzar el cierre): se vuelve a programar.
        ReminderScheduler.apply(this, container.settings.state.value)
        // Las fotos del workbook viven aparte de las sesiones para no ir a la copia de Google.
        container.appScope.launch { container.workbook.movePhotosOutOfSessions() }
    }
}

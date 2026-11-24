package com.jorge.koreandiary

import com.jorge.koreandiary.scene.SceneDrawings
import com.jorge.koreandiary.scene.SceneImages
import android.app.Application
import com.jorge.koreandiary.ai.AzureOpenAIProvider
import com.jorge.koreandiary.ai.CorrectionProvider
import com.jorge.koreandiary.ai.FixedProvider
import com.jorge.koreandiary.ai.GeminiProvider
import com.jorge.koreandiary.ai.CorrectionRequest
import com.jorge.koreandiary.ai.HttpProviderException
import com.jorge.koreandiary.ai.ItemExplanation
import com.jorge.koreandiary.ai.ProviderException
import com.jorge.koreandiary.backup.BackupManager
import com.jorge.koreandiary.backup.StartupRestore
import com.jorge.koreandiary.conversation.ConversationParser
import com.jorge.koreandiary.conversation.ConversationPrompts
import com.jorge.koreandiary.conversation.ConversationStore
import com.jorge.koreandiary.data.DiaryRepository
import com.jorge.koreandiary.data.ExplanationStore
import com.jorge.koreandiary.data.StudyRepository
import com.jorge.koreandiary.data.db.AppDatabase
import com.jorge.koreandiary.data.db.androidKoreanDictionary
import com.jorge.koreandiary.data.db.androidStandardCatalog
import com.jorge.koreandiary.data.db.create
import com.jorge.koreandiary.dictionary.DictionaryExplain
import com.jorge.koreandiary.dictionary.SentenceBreakdown
import com.jorge.koreandiary.dictionary.SentenceLemmas
import com.jorge.koreandiary.pronunciation.SoundAlike
import com.jorge.koreandiary.kiwi.KiwiService
import com.jorge.koreandiary.ink.HandwritingRecognizer
import com.jorge.koreandiary.listen.ListenAudioStore
import com.jorge.koreandiary.listen.ListenEngine
import com.jorge.koreandiary.mascot.MascotPack
import com.jorge.koreandiary.reminder.ReminderScheduler
import com.jorge.koreandiary.workbook.WorkbookStore
import com.jorge.koreandiary.settings.ExplainChoice
import com.jorge.koreandiary.settings.ExplainOption
import com.jorge.koreandiary.settings.ProviderId
import com.jorge.koreandiary.settings.SecretStore
import com.jorge.koreandiary.settings.SettingsStore
import com.jorge.koreandiary.srs.SuggestionEngine
import com.jorge.koreandiary.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /**
     * Kiwi, el analizador de oraciones: se baja desde Ajustes › Escritura y se carga solo cuando una
     * pantalla lo pide. Sin él todo sigue con el lematizador.
     */
    val kiwi = KiwiService(app, appScope)

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

    /**
     * Otro modelo para explicar porque el elegido falló y pulsaste «Probar con …». Dura hasta cerrar
     * la app o cambiar las explicaciones en Ajustes.
     */
    val explainOverride = MutableStateFlow<ExplainOption?>(null)

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
        // Si cambias en Ajustes quién explica, vale lo que elegiste, no el modelo de reemplazo.
        appScope.launch {
            settings.state.map { it.explainProvider to it.explainModel }.distinctUntilChanged().drop(1).collect { explainOverride.value = null }
        }
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
        val o = currentExplainOption() ?: return null
        val provider = when (o.providerId) {
            ProviderId.AZURE -> apiProvider(ProviderId.AZURE)
            ProviderId.GEMINI -> if (fixedAi(ProviderId.GEMINI)) FixedProvider() else secrets.get(SecretStore.GEMINI_KEY)?.let { GeminiProvider(it, o.model) }
            else -> null
        }
        return provider?.let { o.providerId to it }
    }

    /** Lo elegido en Ajustes › IA › Explicaciones, o lo que haya configurado. */
    private fun preferredExplainOption(): ExplainOption? {
        val s = settings.state.value
        val azureReady = isConfigured(ProviderId.AZURE)
        return when {
            s.explainProvider == ProviderId.AZURE && azureReady -> ExplainOption(ProviderId.AZURE, s.azureDeployment)
            isConfigured(ProviderId.GEMINI) -> ExplainOption(ProviderId.GEMINI, s.explainModel)
            azureReady -> ExplainOption(ProviderId.AZURE, s.azureDeployment)
            else -> null
        }
    }

    /** Los modelos registrados con los que se puede explicar, el elegido primero. */
    fun explainOptions(): List<ExplainOption> {
        val s = settings.state.value
        return ExplainChoice.options(
            preferred = preferredExplainOption(),
            azureDeployment = s.azureDeployment.takeIf { isConfigured(ProviderId.AZURE) },
            geminiModels = if (isConfigured(ProviderId.GEMINI)) listOf(s.explainModel, s.geminiModel, s.listenScriptModel) else emptyList(),
        )
    }

    /** Con qué se explica ahora: el reemplazo si lo pediste (y sigue registrado), si no el elegido. */
    fun currentExplainOption(): ExplainOption? =
        explainOverride.value?.takeIf { it in explainOptions() } ?: preferredExplainOption()

    /** El siguiente modelo registrado, para el botón «Probar con …». Null si no hay otro. */
    fun explainAlternative(): ExplainOption? = ExplainChoice.next(explainOptions(), currentExplainOption())

    /** Pasa al siguiente modelo registrado hasta cerrar la app; si da la vuelta, vuelve al elegido. */
    fun useExplainAlternative(): ExplainOption? {
        val alt = explainAlternative() ?: return null
        explainOverride.value = alt.takeIf { it != preferredExplainOption() }
        return alt
    }

    /**
     * «Más sobre esto» y las tarjetas de sugerencias: primero el 한국어기초사전, sin IA; si no lo
     * tiene, la explicación de la IA ya guardada. Null: hay que preguntarle a la IA. [meaning] (lo
     * que dice tu lista) elige el homónimo y el sentido que estudias.
     */
    suspend fun knownExplanation(label: String, isGrammar: Boolean, meaning: String? = null): ItemExplanation? =
        dictionaryExplanation(label, isGrammar, meaning) ?: explanations.get(ExplanationStore.key(label, isGrammar))

    /** ¿El diccionario tiene esta palabra o gramática? Sin leer el detalle: para no pedírsela a la IA. */
    suspend fun dictionaryCovers(label: String, isGrammar: Boolean): Boolean =
        withContext(Dispatchers.Default) { runCatching { DictionaryExplain.covers(dictionary, label, isGrammar) }.getOrDefault(false) }

    /**
     * Lo que da el diccionario, o null si no tiene la palabra o la gramática. Si Kiwi ya está cargado
     * (el repaso lo carga), los ejemplos son solo los que de verdad la usan; aquí no se carga a propósito.
     */
    suspend fun dictionaryExplanation(label: String, isGrammar: Boolean, meaning: String? = null): ItemExplanation? =
        withContext(Dispatchers.Default) { runCatching { DictionaryExplain.explain(dictionary, label, isGrammar, meaning, kiwi.loadedOrNull()) }.getOrNull() }

    /**
     * La forma de diccionario de [word] mirando la oración entera (들어요 en «음악을 들어요» → 듣다), con
     * Kiwi si está bajado (lo carga, ~1 s la primera vez). null sin Kiwi o si no lo sabe.
     */
    suspend fun contextLemma(word: String, sentence: String): String? {
        val analyzer = kiwi.analyzer() ?: return null
        return withContext(Dispatchers.Default) { runCatching { SentenceLemmas.lemmaOfWord(analyzer, dictionary, sentence, word) }.getOrNull() }
    }

    /**
     * «Desglosar» una oración, sin IA: cómo suena y cada palabra por partes con Kiwi si está bajado (lo
     * carga); sin él, cómo suena y la forma de diccionario de cada palabra. null si algo falla.
     */
    suspend fun breakdown(sentence: String): SentenceBreakdown.Result? {
        val analyzer = kiwi.analyzer()
        return withContext(Dispatchers.Default) { runCatching { SentenceBreakdown.of(dictionary, sentence, analyzer) }.getOrNull() }
    }

    /** «Suena como»: la palabra del diccionario que se escribió como suena (para «Por revisar»). */
    val soundAlike by lazy { SoundAlike(dictionary) }

    /**
     * «¿De qué partes se forma?» de una palabra nativa (꿈꾸다, 일으키다), que el diccionario no
     * desglosa: la IA con el modelo de las explicaciones. Devuelve las partes, o null y por qué.
     */
    suspend fun askParts(word: String, sentence: String): Pair<String?, String?> {
        val provider = explainProvider() ?: return null to "Para pedir las partes, configura Gemini o Azure en Ajustes › IA."
        if (settings.usageToday(provider.first) >= settings.state.value.dailyLimit(provider.first)) return null to "Llegaste al límite diario de la IA."
        return try {
            val raw = provider.second.complete(CorrectionRequest(ConversationPrompts.word(word, sentence.ifBlank { word })))
            settings.registerUse(provider.first)
            (ConversationParser.word(raw, word).parts ?: "Es una palabra simple: no se forma de otras partes.") to null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ProviderException) {
            null to explainError(e, provider.first)
        } catch (e: Exception) {
            null to (e.message ?: e.javaClass.simpleName)
        }
    }

    /** Qué decir si falla el modelo de las explicaciones: sin cuota o sin acceso, dónde cambiarlo. */
    fun explainError(e: ProviderException, providerId: String): String {
        if (e.offline) return "Sin conexión."
        val code = (e as? HttpProviderException)?.code
        val name = currentExplainOption()?.label ?: settings.state.value.explainModel
        return when {
            providerId == ProviderId.GEMINI && code == 429 ->
                "Se acabó por ahora la cuota gratis de $name. Reintenta más tarde o prueba con otro modelo."
            code == 429 -> "$name llegó a su límite por ahora. Reintenta más tarde o prueba con otro modelo."
            providerId == ProviderId.GEMINI && (code == 403 || code == 404) ->
                "Tu clave de Gemini no puede usar $name: prueba con otro modelo o cámbialo en Ajustes › IA › Explicaciones."
            code != null && code >= 500 -> "$name está saturado o falló (error $code). Suele pasar en un rato: reintenta o prueba con otro modelo."
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

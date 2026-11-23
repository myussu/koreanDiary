package com.jorge.koreandiary.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.jorge.koreandiary.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object ThemeMode {
    const val SYSTEM = "sistema"
    const val LIGHT = "claro"
    const val DARK = "oscuro"
}

/** Fondo del lienzo en modo oscuro. */
object DarkPaper {
    /** Papel negro de caligrafía: negro cálido con fibras y líneas ocres. */
    const val CALLIGRAPHY = "caligrafia"
    /** Negro puro (#000), ideal para pantallas AMOLED. */
    const val PURE_BLACK = "negro"
}

object ProviderId {
    const val GEMINI = "gemini"
    const val AZURE = "azure"
    const val SHARE = "share"
}

data class AppSettings(
    val profile: String = "",
    val level: String = "3A",
    /** Niveles de gramática a sugerir, separados por coma. */
    val grammarLevels: String = "base,3A",
    val defaultProvider: String = ProviderId.SHARE,
    val geminiModel: String = "gemini-3.5-flash-lite",
    val geminiDailyLimit: Int = 30,
    val stylusOnly: Boolean = true,
    val themeMode: String = ThemeMode.SYSTEM,
    val darkPaper: String = DarkPaper.CALLIGRAPHY,
    /** Endpoint del recurso, p. ej. https://mi-recurso.openai.azure.com */
    val azureEndpoint: String = "",
    /** Nombre del despliegue (no del modelo), p. ej. "luna". */
    val azureDeployment: String = "",
    /** Esfuerzo de razonamiento: low, medium o high. */
    val azureEffort: String = "low",
    val azureDailyLimit: Int = 30,
    /**
     * Quién explica palabras y gramática (desgloses, «Más sobre esto», tocar una palabra): Azure o
     * Gemini con [explainModel], más fuertes que el modelo de corregir porque un desglose inventado
     * desvía el estudio (pedido por Jorge el 2026-11-20). Si Azure no está configurado, Gemini.
     */
    val explainProvider: String = ProviderId.AZURE,
    /** Modelo de Gemini para las explicaciones. */
    val explainModel: String = "gemini-3.8-flash",
    val showMascot: Boolean = true,
    /** Grosor del lápiz: fino, medio o grueso. */
    val penWidth: String = "medio",
    /** El trazo cambia de grosor según la presión del S Pen, como un pincel. */
    val penPressure: Boolean = true,
    /**
     * Alto del renglón como parte del ancho de la hoja; 0 = todavía sin fijar. La tinta se guarda
     * relativa al ancho, así que el renglón también: si fuera fijo en dp, en otro celular (o con otro
     * «zoom de pantalla») la letra ya escrita dejaría de caer sobre los renglones. Se fija la primera
     * vez que abres una página con el renglón de siempre (64 dp) y viaja con la copia de los ajustes.
     */
    val lineRatio: Float = 0f,
    /** Escenas: los bordes de la página reaccionan a lo que escribes (mar, pasto, viento…). */
    val liveScene: Boolean = true,
    /** Escenas: tu servidor de ComfyUI por Tailscale, p. ej. http://truenas:8188 ("" = sin servidor). */
    val imageServer: String = "",
    /** El modelo (checkpoint) que usa el servidor para pintar. */
    val imageCheckpoint: String = "",
    /** Pintar la escena sola al terminarla (revisada y sin correcciones pendientes). */
    val sceneImageAuto: Boolean = true,
    /** Mostrar la imagen de la escena de fondo, muy suave, detrás de la tinta. */
    val sceneImageBackground: Boolean = true,
    /** Claude: el modelo que dibuja lo nuevo de las escenas. */
    val claudeModel: String = "claude-sonnet-5-5",
    /** Al revisar una escena, Claude dibuja lo que se ve y la escena viva no sabía pintar. */
    val sceneDrawAuto: Boolean = true,
    /** Ocultar la tarjeta "Primeros pasos" del inicio. */
    val hideOnboarding: Boolean = false,
    /** La prueba corta de vocabulario de Estudio (JSON de `progress.VocabTest`); "" si no la hiciste. */
    val vocabTest: String = "",
    /** Solo en la versión de prueba: la IA contesta con respuestas fijas (`ai.FixedResponses`), sin red ni claves. */
    val fixedAi: Boolean = true,
    /** Recordatorio diario para escribir, solo si ese día aún no hay página. */
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
    /** Última gramática de la lección elegida en el workbook ("" = que la IA la detecte). */
    val lessonGrammar: String = "",
    /** Conversación: modelo de la Live API de Gemini (usa la misma clave que Gemini). */
    val voiceModel: String = "gemini-3.8-live",
    /** Nivel de razonamiento (LOW, MEDIUM, HIGH) cuando el modelo de voz es el de razonamiento extendido. */
    val voiceThinkingLevel: String = "MEDIUM",
    /** Voz prediseñada de Gemini (Kore, Puck, Charon...). */
    val voiceName: String = "Kore",
    /** "ptt" (pulsas para hablar) o "auto" (la IA detecta cuándo terminaste). */
    val voiceTurnMode: String = "ptt",
    /** Modo automático: silencio que espera antes de responder. Alto para poder pensar. */
    val voiceSilenceMs: Int = 2000,
    /** Duración por defecto de una sesión, en minutos. */
    val voiceSessionMinutes: Int = 10,
    /** Minutos de conversación por día como máximo (cuida la capa gratuita). */
    val voiceDailyMinutes: Int = 30,
    /** El texto de la IA aparece borroso hasta tocarlo: así practicas escuchar, no leer. */
    val voiceBlurText: Boolean = true,
    /** "suave" (repite tu idea bien dicha) o "repetir" (en errores importantes te pide repetir). */
    val voiceCorrection: String = "repetir",
    /** Pista automática si la IA terminó y no respondes en [voiceAutoHintSec] segundos. */
    val voiceAutoHint: Boolean = false,
    val voiceAutoHintSec: Int = 8,
    /** Escuchar: modelo que escribe el guion (si se acaba su cuota se usa [geminiModel]). */
    val listenScriptModel: String = "gemini-3.8-flash",
    /** Escuchar: modelo de voz del narrador. */
    val listenTtsModel: String = "gemini-3.8-flash-tts",
    /** Escuchar: "gemini" (voz de estudio, cuota diaria baja) o "sistema" (voz del celular). */
    val listenNarrator: String = "gemini",
    /** Escuchar: velocidad de la narración (0.7–1.0). */
    val listenSpeed: Float = 0.9f,
    /** Escuchar: segundos de pausa entre segmentos sin pregunta (0 = seguido). */
    val listenGapSec: Int = 2,
    /** En las pausas de Escuchar, usar el micrófono de los audífonos Bluetooth si están conectados. */
    val listenHeadsetMic: Boolean = true,
    /** Escuchar: buscar en Google para temas actuales (último valor elegido). */
    val listenSearch: Boolean = false,
    val listenMinutes: Int = 10,
    /** Escuchar: últimos temas, uno por línea. */
    val listenRecentTopics: String = "",
) {
    val recentTopics get() = listenRecentTopics.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** Grosor del trazo en dp (independiente de la densidad de la pantalla). */
    val penWidthDp: Float get() = when (penWidth) {
        "fino" -> 1.6f
        "grueso" -> 3.4f
        else -> 2.4f
    }

    val grammarLevelList get() = grammarLevels.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Nivel 1–6 del estándar del 국립국어원 a partir del nivel del libro ("3A" → 3). */
    val standardLevel: Int get() = level.firstOrNull { it.isDigit() }?.digitToInt()?.coerceIn(1, 6) ?: 3

    fun dailyLimit(provider: String): Int = when (provider) {
        ProviderId.GEMINI -> geminiDailyLimit
        ProviderId.AZURE -> azureDailyLimit
        else -> Int.MAX_VALUE
    }
}

/** Preferencias simples en SharedPreferences, expuestas como StateFlow para Compose. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun read() = AppSettings(
        profile = prefs.getString("profile", "") ?: "",
        level = prefs.getString("level", "3A") ?: "3A",
        grammarLevels = prefs.getString("grammarLevels", "base,3A") ?: "base,3A",
        // En la versión de prueba arranca con Gemini (que ahí contesta con respuestas fijas).
        defaultProvider = prefs.getString("defaultProvider", null) ?: if (BuildConfig.PRUEBA) ProviderId.GEMINI else ProviderId.SHARE,
        geminiModel = prefs.getString("geminiModel", "gemini-3.5-flash-lite") ?: "gemini-3.5-flash-lite",
        geminiDailyLimit = prefs.getInt("geminiDailyLimit", 30),
        stylusOnly = prefs.getBoolean("stylusOnly", true),
        themeMode = prefs.getString("themeMode", ThemeMode.SYSTEM) ?: ThemeMode.SYSTEM,
        darkPaper = prefs.getString("darkPaper", DarkPaper.CALLIGRAPHY) ?: DarkPaper.CALLIGRAPHY,
        azureEndpoint = prefs.getString("azureEndpoint", "") ?: "",
        azureDeployment = prefs.getString("azureDeployment", "") ?: "",
        azureEffort = prefs.getString("azureEffort", "low") ?: "low",
        azureDailyLimit = prefs.getInt("azureDailyLimit", 30),
        explainProvider = prefs.getString("explainProvider", ProviderId.AZURE) ?: ProviderId.AZURE,
        explainModel = prefs.getString("explainModel", "gemini-3.8-flash") ?: "gemini-3.8-flash",
        showMascot = prefs.getBoolean("showMascot", true),
        penWidth = prefs.getString("penWidth", "medio") ?: "medio",
        penPressure = prefs.getBoolean("penPressure", true),
        lineRatio = prefs.getFloat("lineRatio", 0f),
        liveScene = prefs.getBoolean("liveScene", true),
        imageServer = prefs.getString("imageServer", "") ?: "",
        imageCheckpoint = prefs.getString("imageCheckpoint", "") ?: "",
        sceneImageAuto = prefs.getBoolean("sceneImageAuto", true),
        sceneImageBackground = prefs.getBoolean("sceneImageBackground", true),
        claudeModel = prefs.getString("claudeModel", "claude-sonnet-5-5") ?: "claude-sonnet-5-5",
        sceneDrawAuto = prefs.getBoolean("sceneDrawAuto", true),
        hideOnboarding = prefs.getBoolean("hideOnboarding", false),
        vocabTest = prefs.getString("vocabTest", "") ?: "",
        fixedAi = prefs.getBoolean("fixedAi", true),
        reminderEnabled = prefs.getBoolean("reminderEnabled", false),
        reminderHour = prefs.getInt("reminderHour", 20),
        reminderMinute = prefs.getInt("reminderMinute", 0),
        lessonGrammar = prefs.getString("lessonGrammar", "") ?: "",
        voiceModel = prefs.getString("voiceModel", "gemini-3.8-live") ?: "gemini-3.8-live",
        voiceThinkingLevel = prefs.getString("voiceThinkingLevel", "MEDIUM") ?: "MEDIUM",
        voiceName = prefs.getString("voiceName", "Kore") ?: "Kore",
        voiceTurnMode = prefs.getString("voiceTurnMode", "ptt") ?: "ptt",
        voiceSilenceMs = prefs.getInt("voiceSilenceMs", 2000),
        voiceSessionMinutes = prefs.getInt("voiceSessionMinutes", 10),
        voiceDailyMinutes = prefs.getInt("voiceDailyMinutes", 30),
        voiceBlurText = prefs.getBoolean("voiceBlurText", true),
        voiceCorrection = prefs.getString("voiceCorrection", "repetir") ?: "repetir",
        voiceAutoHint = prefs.getBoolean("voiceAutoHint", false),
        voiceAutoHintSec = prefs.getInt("voiceAutoHintSec", 8),
        listenScriptModel = prefs.getString("listenScriptModel", "gemini-3.8-flash") ?: "gemini-3.8-flash",
        listenTtsModel = prefs.getString("listenTtsModel", "gemini-3.8-flash-tts") ?: "gemini-3.8-flash-tts",
        listenNarrator = prefs.getString("listenNarrator", "gemini") ?: "gemini",
        listenSpeed = prefs.getFloat("listenSpeed", 0.9f),
        listenGapSec = prefs.getInt("listenGapSec", 2),
        listenHeadsetMic = prefs.getBoolean("listenHeadsetMic", true),
        listenSearch = prefs.getBoolean("listenSearch", false),
        listenMinutes = prefs.getInt("listenMinutes", 10),
        listenRecentTopics = prefs.getString("listenRecentTopics", "") ?: "",
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_state.value)
        prefs.edit()
            .putString("profile", s.profile)
            .putString("level", s.level)
            .putString("grammarLevels", s.grammarLevels)
            .putString("defaultProvider", s.defaultProvider)
            .putString("geminiModel", s.geminiModel)
            .putInt("geminiDailyLimit", s.geminiDailyLimit)
            .putBoolean("stylusOnly", s.stylusOnly)
            .putString("themeMode", s.themeMode)
            .putString("darkPaper", s.darkPaper)
            .putString("azureEndpoint", s.azureEndpoint)
            .putString("azureDeployment", s.azureDeployment)
            .putString("azureEffort", s.azureEffort)
            .putInt("azureDailyLimit", s.azureDailyLimit)
            .putString("explainProvider", s.explainProvider)
            .putString("explainModel", s.explainModel)
            .putBoolean("showMascot", s.showMascot)
            .putString("penWidth", s.penWidth)
            .putBoolean("penPressure", s.penPressure)
            .putFloat("lineRatio", s.lineRatio)
            .putBoolean("liveScene", s.liveScene)
            .putString("imageServer", s.imageServer)
            .putString("imageCheckpoint", s.imageCheckpoint)
            .putBoolean("sceneImageAuto", s.sceneImageAuto)
            .putBoolean("sceneImageBackground", s.sceneImageBackground)
            .putString("claudeModel", s.claudeModel)
            .putBoolean("sceneDrawAuto", s.sceneDrawAuto)
            .putBoolean("hideOnboarding", s.hideOnboarding)
            .putString("vocabTest", s.vocabTest)
            .putBoolean("fixedAi", s.fixedAi)
            .putBoolean("reminderEnabled", s.reminderEnabled)
            .putInt("reminderHour", s.reminderHour)
            .putInt("reminderMinute", s.reminderMinute)
            .putString("lessonGrammar", s.lessonGrammar)
            .putString("voiceModel", s.voiceModel)
            .putString("voiceThinkingLevel", s.voiceThinkingLevel)
            .putString("voiceName", s.voiceName)
            .putString("voiceTurnMode", s.voiceTurnMode)
            .putInt("voiceSilenceMs", s.voiceSilenceMs)
            .putInt("voiceSessionMinutes", s.voiceSessionMinutes)
            .putInt("voiceDailyMinutes", s.voiceDailyMinutes)
            .putBoolean("voiceBlurText", s.voiceBlurText)
            .putString("voiceCorrection", s.voiceCorrection)
            .putBoolean("voiceAutoHint", s.voiceAutoHint)
            .putInt("voiceAutoHintSec", s.voiceAutoHintSec)
            .putString("listenScriptModel", s.listenScriptModel)
            .putString("listenTtsModel", s.listenTtsModel)
            .putString("listenNarrator", s.listenNarrator)
            .putFloat("listenSpeed", s.listenSpeed)
            .putInt("listenGapSec", s.listenGapSec)
            .putBoolean("listenHeadsetMic", s.listenHeadsetMic)
            .putBoolean("listenSearch", s.listenSearch)
            .putInt("listenMinutes", s.listenMinutes)
            .putString("listenRecentTopics", s.listenRecentTopics)
            .apply()
        _state.value = s
    }

    /** Uso diario por proveedor, para no agotar créditos prestados sin darse cuenta. */
    fun usageToday(provider: String): Int {
        val today = LocalDate.now().toString()
        return if (prefs.getString("usage_date_$provider", null) == today) prefs.getInt("usage_count_$provider", 0) else 0
    }

    fun registerUse(provider: String) {
        val today = LocalDate.now().toString()
        prefs.edit()
            .putString("usage_date_$provider", today)
            .putInt("usage_count_$provider", usageToday(provider) + 1)
            .apply()
    }

    companion object {
        /** La copia de seguridad (`backup/`) exporta y restaura este archivo entero. */
        const val PREFS_NAME = "settings"
    }
}

/**
 * Claves de API cifradas con AES-GCM. La clave AES vive en el Android Keystore
 * y nunca sale del hardware seguro; en SharedPreferences solo queda el texto cifrado.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    /**
     * Claves ya descifradas en esta sesión de la app. Cada pedido a la IA pide la clave, y descifrarla
     * es una ida al Keystore (lenta, a veces en el hilo principal): con esto se hace una vez por clave.
     * Solo vive en memoria.
     */
    private val decrypted = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun put(name: String, value: String?) {
        decrypted.remove(name)
        if (value.isNullOrBlank()) {
            prefs.edit().remove(name).apply()
            return
        }
        val blob = try {
            encrypt(value)
        } catch (e: Exception) {
            // La clave del Keystore quedó inservible (pasa tras algunas actualizaciones del sistema o al
            // restaurar el celular). Antes esto cerraba la app cada vez que pegabas una clave. Lo cifrado
            // con ella ya no se puede leer: se crea una nueva y las otras claves hay que pegarlas otra vez.
            resetKey()
            encrypt(value)
        }
        prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        decrypted[name] = value
    }

    /** El IV (12 bytes) y, a continuación, lo cifrado. */
    private fun encrypt(value: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return cipher.iv + encrypted
    }

    private fun resetKey() {
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
        // Así Ajustes no dice «Clave guardada» de una que ya no se puede descifrar.
        prefs.edit().clear().apply()
        decrypted.clear()
    }

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        decrypted[name]?.let { return it }
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_SIZE))
            String(cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE), Charsets.UTF_8).also { decrypted[name] = it }
        } catch (e: Exception) {
            null
        }
    }

    fun has(name: String) = prefs.contains(name)

    companion object {
        const val GEMINI_KEY = "gemini_api_key"
        const val AZURE_KEY = "azure_openai_api_key"
        const val CLAUDE_KEY = "claude_api_key"
        private const val ALIAS = "korean_diary_secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
    }
}

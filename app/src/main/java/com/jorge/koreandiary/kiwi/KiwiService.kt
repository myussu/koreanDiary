package com.jorge.koreandiary.kiwi

import android.content.Context
import android.util.Log
import com.jorge.koreandiary.BuildConfig
import com.jorge.koreandiary.dictionary.SentenceAnalyzer
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Kiwi en la app (decidido con Jorge el 2026-11-24): el modelo se baja una vez desde Ajustes al
 * almacenamiento que no entra en la copia (`noBackupFilesDir/kiwi/<versión>`: no es dato tuyo y se
 * puede volver a bajar) y se carga solo cuando una pantalla lo pide. Ocupa ~230 MB de memoria
 * mientras está cargado, así que se suelta solo tras [IDLE_MS] sin usarlo. Sin el modelo (o si este
 * celular no puede cargarlo), [analyzer] da null y todo sigue con el lematizador.
 */
class KiwiService(private val context: Context, private val scope: CoroutineScope) {

    /** En qué está el modelo, para Ajustes. */
    sealed interface Install {
        data object Missing : Install
        data class Downloading(val done: Long, val total: Long) : Install
        data object Unpacking : Install
        data class Ready(val bytes: Long) : Install
        data class Failed(val message: String) : Install
    }

    private val root get() = File(context.noBackupFilesDir, "kiwi")
    private val dir get() = File(root, VERSION)

    private val _install = MutableStateFlow(if (isInstalled()) Install.Ready(size(dir)) else Install.Missing)
    val install: StateFlow<Install> = _install.asStateFlow()

    private val loadLock = Mutex()
    // Kiwi se carga en su propio hilo: tarda ~1 s y no debe frenar ni la interfaz ni Default.
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "kiwi") }.asCoroutineDispatcher()
    @Volatile private var loaded: KiwiAnalyzer? = null
    /** Si cargar falló (librería que este celular no puede usar), no se reintenta hasta reabrir la app. */
    @Volatile private var unusable: String? = null
    private var idle: Job? = null
    private var download: Job? = null

    fun isInstalled(): Boolean = File(dir, READY).isFile

    /** ¿Ya está cargado? Para usarlo sin cargarlo (en el diario, donde 230 MB más no valen la pena). */
    fun loadedOrNull(): SentenceAnalyzer? = loaded?.also { touch() }

    /**
     * El analizador, cargándolo si hace falta (la primera vez ~1 s). null si el modelo no está bajado
     * o no se pudo cargar.
     */
    suspend fun analyzer(): SentenceAnalyzer? {
        loaded?.let {
            touch()
            return it
        }
        if (!isInstalled() || unusable != null) return null
        return loadLock.withLock {
            loaded ?: withContext(loader) {
                try {
                    KiwiAnalyzer.load(dir)
                } catch (e: Throwable) {
                    // UnsatisfiedLinkError (un celular de 32 bits) o el modelo roto: se avisa en Ajustes.
                    Log.w(TAG, "No se pudo cargar Kiwi", e)
                    unusable = e.message ?: e.javaClass.simpleName
                    _install.value = Install.Failed("No se pudo cargar el analizador en este celular: ${unusable}. Prueba «Quitar» y descargarlo otra vez.")
                    null
                }
            }?.also {
                loaded = it
                touch()
            }
        }
    }

    /** Un rato sin usarlo y se suelta (la memoria vuelve); se vuelve a cargar cuando se pida. */
    private fun touch() {
        idle?.cancel()
        idle = scope.launch {
            delay(IDLE_MS)
            loadLock.withLock {
                loaded?.close()
                loaded = null
            }
        }
    }

    /** Baja el modelo del release oficial, comprueba que es el archivo esperado y lo deja listo. */
    fun download() {
        if (download?.isActive == true || isInstalled()) return
        download = scope.launch(Dispatchers.IO) {
            val tmp = File(root, "descarga.tgz")
            val staging = File(root, "$VERSION.nuevo")
            try {
                root.mkdirs()
                _install.value = Install.Downloading(0, KiwiModel.SIZE)
                fetch(KiwiModel.url(VERSION), tmp)
                _install.value = Install.Unpacking
                staging.deleteRecursively()
                tmp.inputStream().use { KiwiModel.extract(it, staging) }
                File(staging, READY).writeText(VERSION)
                dir.deleteRecursively()
                if (!staging.renameTo(dir)) throw IOException("No pude dejar el modelo en su carpeta")
                // Las versiones anteriores ya no sirven.
                root.listFiles().orEmpty().filter { it.isDirectory && it.name != VERSION }.forEach { it.deleteRecursively() }
                unusable = null
                _install.value = Install.Ready(size(dir))
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Falló la descarga de Kiwi", e)
                staging.deleteRecursively()
                _install.value = Install.Failed(message(e))
            } finally {
                tmp.delete()
            }
        }
    }

    /** «Quitar»: suelta el analizador y borra el modelo (se puede volver a bajar). */
    fun remove() {
        download?.cancel()
        scope.launch(Dispatchers.IO) {
            loadLock.withLock {
                loaded?.close()
                loaded = null
            }
            root.deleteRecursively()
            unusable = null
            _install.value = Install.Missing
        }
    }

    private fun fetch(url: String, to: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            // GitHub manda a objects.githubusercontent.com: https a https se sigue solo.
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("el servidor respondió $code")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: KiwiModel.SIZE
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var shown = 0L
            connection.inputStream.use { input ->
                to.outputStream().buffered().use { out ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (done - shown > 512 * 1024) {
                            shown = done
                            _install.value = Install.Downloading(done, total)
                        }
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha != KiwiModel.SHA256) throw IOException("el archivo bajado no es el esperado (¿se cortó?)")
        } finally {
            connection.disconnect()
        }
    }

    private fun message(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "Sin conexión: conéctate a internet y vuelve a intentar."
        is java.net.SocketTimeoutException -> "La descarga se cortó por tiempo. Vuelve a intentar, mejor con Wi-Fi."
        else -> "No se pudo bajar el analizador: ${e.message ?: e.javaClass.simpleName}"
    }

    companion object {
        private const val TAG = "Kiwi"
        private const val READY = ".listo"
        const val IDLE_MS = 3 * 60_000L
        val VERSION: String = BuildConfig.KIWI_VERSION

        fun size(dir: File): Long = dir.listFiles().orEmpty().sumOf { it.length() }
    }
}

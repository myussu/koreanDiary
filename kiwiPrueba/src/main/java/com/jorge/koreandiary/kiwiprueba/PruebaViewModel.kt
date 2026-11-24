package com.jorge.koreandiary.kiwiprueba

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Debug
import android.system.Os
import android.system.OsConstants
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.pe.bab2min.Kiwi
import kr.pe.bab2min.KiwiBuilder

/** Lo que muestra la pantalla. El informe es lo que se copia para pegarlo en el chat. */
data class Estado(
    val informe: List<String> = emptyList(),
    val trabajando: String? = null,
    val cargado: Boolean = false,
    val progreso: Pair<Int, Int>? = null,
    val casosHechos: Boolean = false,
)

/** Memoria del proceso en MB: RSS (y su pico), la reservada por código nativo (Kiwi) y la PSS. */
private data class Memoria(val rss: Long, val pico: Long, val nativa: Long, val pss: Long) {
    override fun toString() = "RSS $rss MB (pico $pico) · nativa $nativa MB · PSS $pss MB"
}

/** Un tipo de caso: cuántos hay y cuántos acierta cada método. */
private class Conteo(val nombre: String) {
    var total = 0
    var soloKiwi = 0
    var conDiccionario = 0
}

class PruebaViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()

    // Kiwi no se usa desde dos hilos a la vez: todo lo suyo corre en este.
    private val ejecutor = Executors.newSingleThreadExecutor()
    private val hilo = ejecutor.asCoroutineDispatcher()

    private var builder: KiwiBuilder? = null
    private var kiwi: Kiwi? = null
    private var formas: Set<String> = emptySet()

    // Las mismas opciones que la prueba del servidor: sin multi.dict y el análisis por defecto de
    // kiwipiepy (Match.ALL con z_coda).
    private val opciones = KiwiBuilder.BuildOption.integrateAllomorph or
        KiwiBuilder.BuildOption.loadDefaultDict or KiwiBuilder.BuildOption.loadTypoDict
    private val analisis = Kiwi.AnalyzeOption(Kiwi.Match.all)

    var estado by mutableStateOf(Estado(informe = listOf(dispositivo())))
        private set

    private suspend fun anotar(vararg lineas: String) = withContext(Dispatchers.Main.immediate) {
        estado = estado.copy(informe = estado.informe + lineas)
    }

    private suspend fun cambiar(f: (Estado) -> Estado) = withContext(Dispatchers.Main.immediate) {
        estado = f(estado)
    }

    private fun dispositivo(): String {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val pagina = Os.sysconf(OsConstants._SC_PAGESIZE) / 1024
        return "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
            " · ${Build.SUPPORTED_ABIS.firstOrNull()} · RAM ${mi.totalMem / MB} MB · página $pagina KB" +
            " · app ${ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName}"
    }

    private fun medir(): Memoria {
        val status = File("/proc/self/status").readLines()
        fun kb(clave: String) = status.firstOrNull { it.startsWith(clave) }
            ?.filter { it.isDigit() }?.toLongOrNull() ?: 0L
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        return Memoria(
            rss = kb("VmRSS:") / 1024,
            pico = kb("VmHWM:") / 1024,
            nativa = Debug.getNativeHeapAllocatedSize() / MB,
            pss = mi.totalPss / 1024L,
        )
    }

    /** Copia el modelo del APK a la memoria del celular (una vez por versión instalada) y carga Kiwi. */
    fun cargar() {
        if (estado.trabajando != null || estado.cargado) return
        estado = estado.copy(trabajando = "Cargando Kiwi…")
        viewModelScope.launch(hilo) {
            try {
                val m0 = medir()
                anotar("", "— Carga —", "Memoria al abrir: $m0")

                val carpeta = File(ctx.noBackupFilesDir, "kiwi")
                val marca = File(carpeta, ".instalada")
                val instalada = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
                if (marca.exists() && marca.readText() == instalada) {
                    anotar("Modelo: ya estaba copiado (${tamano(carpeta)} MB)")
                } else {
                    cambiar { it.copy(trabajando = "Copiando el modelo (una vez)…") }
                    val t = System.nanoTime()
                    carpeta.deleteRecursively()
                    carpeta.mkdirs()
                    for (nombre in ctx.assets.list("kiwi").orEmpty()) {
                        ctx.assets.open("kiwi/$nombre").use { entrada ->
                            File(carpeta, nombre).outputStream().use { entrada.copyTo(it, 1 shl 16) }
                        }
                    }
                    marca.writeText(instalada)
                    anotar("Modelo copiado: ${tamano(carpeta)} MB en ${seg(System.nanoTime() - t)} (solo la primera vez)")
                }

                val tf = System.nanoTime()
                formas = ctx.assets.open("formas.txt").bufferedReader().useLines { it.toHashSet() }
                anotar("Diccionario: ${formas.size} formas en ${seg(System.nanoTime() - tf)}")
                val m1 = medir()

                cambiar { it.copy(trabajando = "Cargando Kiwi…") }
                val tc = System.nanoTime()
                val b = KiwiBuilder(carpeta.absolutePath, 1, opciones, KiwiBuilder.ModelType.cong)
                val k = b.build()
                val carga = System.nanoTime() - tc
                builder = b
                kiwi = k
                val m2 = medir()
                anotar(
                    "Carga de Kiwi ${Kiwi.getVersion()}: ${seg(carga)}",
                    "Memoria tras cargar: $m2",
                    "  Kiwi suma: RSS +${m2.rss - m1.rss} MB · nativa +${m2.nativa - m1.nativa} MB",
                )

                // El constructor guarda otra copia del diccionario; Kiwi ya no lo necesita.
                b.close()
                builder = null
                val m3 = medir()
                anotar("Tras soltar el constructor: $m3", "  Kiwi suma: RSS +${m3.rss - m1.rss} MB · nativa +${m3.nativa - m1.nativa} MB")

                val tp = System.nanoTime()
                k.tokenize("저는 어제 친구를 만났어요.", analisis)
                anotar("Primera oración (en frío): ${ms(System.nanoTime() - tp)}")
                cambiar { it.copy(trabajando = null, cargado = true) }
            } catch (e: Throwable) {
                fallo(e)
            }
        }
    }

    /** Corre todos los casos de la prueba del servidor y compara aciertos, tiempo y memoria. */
    fun correrCasos() {
        val k = kiwi ?: return
        if (estado.trabajando != null) return
        estado = estado.copy(trabajando = "Corriendo los casos…")
        viewModelScope.launch(hilo) {
            try {
                val filas = ctx.assets.open("casos.tsv").bufferedReader().readLines()
                    .map { it.split('\t') }.filter { it.size == 4 }
                val conteos = linkedMapOf(
                    "mano" to Conteo("Palabra sola (lista a mano)"),
                    "ambigua" to Conteo("Ambiguas en oración"),
                    "verbo" to Conteo("Verbos en oración"),
                    "sustantivo" to Conteo("Sustantivos en oración"),
                )
                val fallos = mutableListOf<String>()
                var analisisNs = 0L
                var diccionarioNs = 0L
                var maximo = 0L
                cambiar { it.copy(progreso = 0 to filas.size) }
                for ((n, fila) in filas.withIndex()) {
                    val (tipo, oracion, palabra, esperada) = fila
                    val t0 = System.nanoTime()
                    val tokens = k.tokenize(oracion, analisis)
                    val t1 = System.nanoTime()
                    val i = oracion.indexOf(palabra)
                    val solo = if (i >= 0) Lemas.soloKiwi(tokens, i, i + palabra.length) else ""
                    val t2 = System.nanoTime()
                    val hibrido = if (i >= 0) Lemas.conDiccionario(k, tokens, i, i + palabra.length, formas) else ""
                    val t3 = System.nanoTime()
                    analisisNs += t1 - t0
                    diccionarioNs += t3 - t2
                    maximo = maxOf(maximo, t1 - t0)
                    val c = conteos.getOrPut(tipo) { Conteo(tipo) }
                    c.total++
                    if (solo == esperada) c.soloKiwi++
                    if (hibrido == esperada) c.conDiccionario++
                    if (hibrido != esperada && (tipo == "ambigua" || tipo == "mano")) {
                        fallos += "  $palabra: esperaba $esperada, salió ${hibrido.ifEmpty { "(nada)" }}"
                    }
                    if (n % 100 == 99) cambiar { it.copy(progreso = n + 1 to filas.size) }
                }
                val m = medir()
                val lineas = mutableListOf("", "— Casos (${filas.size}) —", "Aciertos: celular · servidor")
                for ((tipo, c) in conteos) {
                    val ref = SERVIDOR[tipo]
                    lineas += "${c.nombre} (${c.total}):"
                    lineas += "  Solo Kiwi: ${c.soloKiwi} · ${ref?.first ?: "?"}${marca(c.soloKiwi, ref?.first)}"
                    lineas += "  Kiwi + diccionario: ${c.conDiccionario} · ${ref?.second ?: "?"}${marca(c.conDiccionario, ref?.second)}"
                }
                lineas += "Análisis: ${ms(analisisNs / filas.size)} por oración en promedio (la más lenta: ${ms(maximo)})"
                lineas += "Rearmar con el diccionario: ${ms(diccionarioNs / filas.size)} más por palabra"
                lineas += "Memoria al terminar: $m"
                if (fallos.isNotEmpty()) {
                    lineas += "Fallos de Kiwi + diccionario en palabra sola y ambiguas:"
                    lineas += fallos
                }
                anotar(*lineas.toTypedArray())
                cambiar { it.copy(trabajando = null, progreso = null, casosHechos = true) }
            } catch (e: Throwable) {
                fallo(e)
            }
        }
    }

    /** Analiza una oración escrita a mano: las piezas de Kiwi y la forma de diccionario de cada palabra. */
    fun analizar(oracion: String) {
        val k = kiwi ?: return
        val texto = oracion.trim()
        if (texto.isEmpty() || estado.trabajando != null) return
        viewModelScope.launch(hilo) {
            try {
                val t = System.nanoTime()
                val tokens = k.tokenize(texto, analisis)
                val tiempo = System.nanoTime() - t
                val lineas = mutableListOf("", "— Oración — $texto (${ms(tiempo)})")
                lineas += "  " + tokens.joinToString(" + ") { "${it.form}/${Lemas.etiquetaCompleta(it)}" }
                for (palabra in Regex("\\S+").findAll(texto)) {
                    val inicio = palabra.range.first
                    val fin = palabra.range.last + 1
                    val hibrido = Lemas.conDiccionario(k, tokens, inicio, fin, formas)
                    val forma = hibrido.ifEmpty { Lemas.soloKiwi(tokens, inicio, fin) + " (solo Kiwi, no está en el diccionario)" }
                    lineas += "  ${palabra.value} → $forma"
                }
                anotar(*lineas.toTypedArray())
            } catch (e: Throwable) {
                fallo(e)
            }
        }
    }

    fun informe(): String = estado.informe.joinToString("\n").trim()

    private suspend fun fallo(e: Throwable) {
        val pila = e.stackTrace.take(4).joinToString("\n") { "    en $it" }
        anotar("", "Error: $e", pila)
        cambiar { it.copy(trabajando = null, progreso = null) }
    }

    override fun onCleared() {
        // Se suelta en su propio hilo, después de lo que esté corriendo.
        ejecutor.execute {
            runCatching { kiwi?.close() }
            runCatching { builder?.close() }
        }
        ejecutor.shutdown()
    }

    private companion object {
        const val MB = 1024L * 1024L

        // Lo que dio el servidor sin multi.dict (kiwipiepy 0.25.1): aciertos solo Kiwi y con diccionario.
        val SERVIDOR = mapOf(
            "mano" to (73 to 73),
            "ambigua" to (27 to 27),
            "verbo" to (1157 to 1209),
            "sustantivo" to (1432 to 1494),
        )

        fun marca(celular: Int, servidor: Int?) = if (servidor == null || celular == servidor) "" else "  ← distinto"
        fun seg(ns: Long) = String.format(Locale.US, "%.2f s", ns / 1e9)
        fun ms(ns: Long) = String.format(Locale.US, "%.2f ms", ns / 1e6)
        fun tamano(carpeta: File) = (carpeta.listFiles().orEmpty().sumOf { it.length() }) / MB
    }
}

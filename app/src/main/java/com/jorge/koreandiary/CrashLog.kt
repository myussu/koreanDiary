package com.jorge.koreandiary

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * El último cierre por error, para poder contarlo: al cerrarse, la app escribe qué pasó y en qué
 * versión; al abrirla, el inicio lo avisa con «Copiar detalle». Va en `noBackupFilesDir`: no son
 * datos tuyos, no entra en ninguna copia y no viaja a otro celular.
 */
object CrashLog {
    private fun file(context: Context) = File(context.noBackupFilesDir, "cierre.txt")

    /** Se llama al abrir la app, antes que nada: después sigue el manejador de siempre (el que cierra). */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val text = buildString {
                    appendLine("Versión: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("Fecha: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                    appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Hilo: ${thread.name}")
                    appendLine()
                    append(error.stackTraceToString())
                }
                file(app).writeText(text.take(MAX_CHARS))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** El detalle del último cierre, o null si no hubo (o ya lo descartaste). */
    fun read(context: Context): String? = runCatching { file(context).takeIf { it.isFile }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private const val MAX_CHARS = 60_000
}

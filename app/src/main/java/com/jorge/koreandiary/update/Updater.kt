package com.jorge.koreandiary.update

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import com.jorge.koreandiary.KoreanDiaryApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Lo que publica GitHub Actions en version.json junto al APK del release "latest". */
data class ReleaseInfo(
    val versionCode: Long,
    val versionName: String,
    val commit: String,
    val notes: String,
    val sha256: String?,
    val size: Long,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: ReleaseInfo) : UpdateState
    /** Falta permitir "Instalar apps desconocidas" para esta app (una sola vez). */
    data class NeedsPermission(val info: ReleaseInfo) : UpdateState
    data class Downloading(val info: ReleaseInfo, val progress: Float) : UpdateState
    data class Installing(val info: ReleaseInfo) : UpdateState
    data class Error(val message: String, val info: ReleaseInfo? = null) : UpdateState
}

/**
 * Actualizaciones desde el release "latest" de GitHub, sin salir de la app:
 * 1. Lee version.json y compara su versionCode (número de compilación de Actions) con el instalado.
 * 2. Descarga el APK y verifica su SHA-256.
 * 3. Lo instala con PackageInstaller. Desde Android 12, al actualizarse a sí misma con
 *    UPDATE_PACKAGES_WITHOUT_USER_ACTION, el sistema puede instalar sin pedir confirmación.
 * Android además exige que el APK esté firmado con la misma llave que la app instalada.
 */
class Updater(private val app: Application) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersionCode: Long
    val currentVersionName: String

    init {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        currentVersionCode = PackageInfoCompat.getLongVersionCode(info)
        currentVersionName = info.versionName ?: "?"
    }

    /** Con [silent], los errores de red no se muestran (chequeo automático al abrir la app). */
    suspend fun check(silent: Boolean = false) {
        val busy = _state.value
        if (busy is UpdateState.Checking || busy is UpdateState.Downloading || busy is UpdateState.Installing) return
        _state.value = UpdateState.Checking
        _state.value = try {
            val info = fetchReleaseInfo()
            if (info.versionCode > currentVersionCode) UpdateState.Available(info) else UpdateState.UpToDate
        } catch (e: Exception) {
            if (silent) UpdateState.Idle else UpdateState.Error("No se pudo buscar actualizaciones: ${e.message}")
        }
    }

    suspend fun downloadAndInstall(info: ReleaseInfo) {
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(info)
            return
        }
        try {
            _state.value = UpdateState.Downloading(info, 0f)
            val apk = download(info)
            _state.value = UpdateState.Installing(info)
            install(apk)
        } catch (e: Exception) {
            _state.value = UpdateState.Error("La actualización falló: ${e.message}", info)
        }
    }

    /** Abre el ajuste del sistema para permitir que esta app instale actualizaciones. */
    fun permissionSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun dismiss() {
        val s = _state.value
        if (s is UpdateState.Error || s is UpdateState.UpToDate) _state.value = UpdateState.Idle
    }

    internal fun onInstallFailed(message: String?) {
        val info = (_state.value as? UpdateState.Installing)?.info
        _state.value = UpdateState.Error("Android no instaló la actualización: ${message ?: "motivo desconocido"}", info)
    }

    private suspend fun fetchReleaseInfo(): ReleaseInfo = withContext(Dispatchers.IO) {
        val conn = (URL(VERSION_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            useCaches = false
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            ReleaseInfo(
                versionCode = json.getLong("versionCode"),
                versionName = json.optString("versionName", "?"),
                commit = json.optString("commit"),
                notes = json.optString("notes").trim(),
                sha256 = json.optString("sha256").takeIf { it.length == 64 },
                size = json.optLong("size", -1),
            )
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun download(info: ReleaseInfo): File = withContext(Dispatchers.IO) {
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "koreandiary-${info.versionCode}.apk")
        val conn = (URL(APK_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            useCaches = false
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.size
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            var lastReported = 0f
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            val p = read.toFloat() / total
                            if (p - lastReported >= 0.01f) {
                                lastReported = p
                                _state.value = UpdateState.Downloading(info, p.coerceAtMost(1f))
                            }
                        }
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256 != null && !hash.equals(info.sha256, ignoreCase = true)) {
                file.delete()
                throw IOException("el archivo descargado está dañado (SHA-256 distinto)")
            }
            file
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("koreandiary.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val callback = PendingIntent.getBroadcast(
                app, sessionId, Intent(app, InstallResultReceiver::class.java), flags,
            )
            session.commit(callback.intentSender)
        }
    }

    companion object {
        private const val BASE = "https://github.com/myussu/koreanDiary/releases/download/latest"
        private const val VERSION_URL = "$BASE/version.json"
        private const val APK_URL = "$BASE/koreandiary.apk"
    }
}

/**
 * Resultado de PackageInstaller. Si el sistema pide confirmación, abre su diálogo.
 * Si la instalación funciona, Android cierra la app para reemplazarla; al abrirla ya es la nueva versión.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updater = (context.applicationContext as KoreanDiaryApp).container.updater
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else updater.onInstallFailed("no llegó el diálogo de confirmación")
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> updater.onInstallFailed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}

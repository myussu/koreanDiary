package com.jorge.koreandiary.listen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.jorge.koreandiary.KoreanDiaryApp
import com.jorge.koreandiary.MainActivity
import com.jorge.koreandiary.R
import com.jorge.koreandiary.voice.TurnMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Mantiene viva la sesión de escucha con la pantalla apagada (servicio en primer plano de
 * reproducción y micrófono) y la controla desde la notificación, la pantalla de bloqueo y los
 * botones de los audífonos:
 * - un toque: pausa; en una pausa, habla (pulsar para hablar) o sigue (modo automático);
 * - dos toques: sigue / siguiente segmento;
 * - tres toques: repite el segmento.
 */
class ListenService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var media: MediaSession
    private val handler = Handler(Looper.getMainLooper())
    private var clicks = 0
    private var started = false

    private val engine get() = (application as KoreanDiaryApp).container.listen

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Escuchar", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Controles de la sesión de escucha"
                setShowBadge(false)
            }
        )
        media = MediaSession(this, "koreandiary-escuchar").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(intent: Intent): Boolean {
                    val event = keyEvent(intent) ?: return super.onMediaButtonEvent(intent)
                    return when (event.keyCode) {
                        KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) countClick()
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_NEXT -> {
                            if (event.action == KeyEvent.ACTION_DOWN) engine.next()
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                            if (event.action == KeyEvent.ACTION_DOWN) engine.repeat()
                            true
                        }
                        else -> super.onMediaButtonEvent(intent)
                    }
                }

                // Controles de la pantalla de bloqueo y del panel de medios.
                override fun onPlay() = engine.resume()
                override fun onPause() = engine.pause()
                override fun onSkipToNext() = engine.next()
                override fun onSkipToPrevious() = engine.repeat()
                override fun onStop() = engine.finish()
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    when (action) {
                        ACTION_TALK -> engine.toggleTalk()
                        ACTION_END -> engine.finish()
                    }
                }
            })
            setSessionActivity(openAppIntent())
            isActive = true
        }
        goForeground(engine.state.value)
        scope.launch {
            engine.state
                .map { Snapshot(it.phase, it.status, it.script?.title.orEmpty(), it.talking, it.turnMode, it.connected) }
                .distinctUntilChanged()
                .collect { update(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) goForeground(engine.state.value)
        when (intent?.action) {
            ACTION_TOGGLE -> engine.togglePause()
            ACTION_REPEAT -> engine.repeat()
            ACTION_TALK -> engine.toggleTalk()
            ACTION_END -> engine.finish()
        }
        return START_NOT_STICKY
    }

    /** Un toque, dos o tres: se espera un momento para saber cuántos fueron. */
    private fun countClick() {
        clicks++
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            when (clicks) {
                1 -> engine.button()
                2 -> engine.next()
                else -> engine.repeat()
            }
            clicks = 0
        }, CLICK_WINDOW_MS)
    }

    private data class Snapshot(
        val phase: ListenPhase,
        val status: String,
        val title: String,
        val talking: Boolean,
        val turnMode: String,
        val connected: Boolean,
    )

    private fun update(s: Snapshot) {
        if (!s.phase.active) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        media.setPlaybackState(playbackState(s))
        media.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, s.title.ifBlank { "Escuchar coreano" })
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s.status)
                .build()
        )
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(s))
    }

    private fun goForeground(state: ListenState) {
        val s = Snapshot(state.phase, state.status, state.script?.title.orEmpty(), state.talking, state.turnMode, state.connected)
        val notification = notification(s)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else -> startForeground(NOTIFICATION_ID, notification)
        }
        started = true
    }

    private fun playbackState(s: Snapshot): PlaybackState {
        val playing = s.phase == ListenPhase.NARRATING || s.phase == ListenPhase.GAP || s.phase == ListenPhase.PREPARING
        val builder = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP
            )
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
        if (s.phase.interactive && s.turnMode == TurnMode.PUSH_TO_TALK && s.connected) {
            builder.addCustomAction(
                PlaybackState.CustomAction.Builder(ACTION_TALK, if (s.talking) "Listo" else "Hablar", android.R.drawable.ic_btn_speak_now).build()
            )
        }
        builder.addCustomAction(PlaybackState.CustomAction.Builder(ACTION_END, "Terminar", android.R.drawable.ic_menu_close_clear_cancel).build())
        return builder.build()
    }

    private fun notification(s: Snapshot): Notification {
        val playing = s.phase == ListenPhase.NARRATING || s.phase == ListenPhase.GAP
        val actions = buildList {
            add(action(android.R.drawable.ic_media_previous, "Repetir", ACTION_REPEAT))
            add(
                if (playing) action(android.R.drawable.ic_media_pause, "Pausa", ACTION_TOGGLE)
                else action(android.R.drawable.ic_media_play, "Seguir", ACTION_TOGGLE)
            )
            if (s.phase.interactive && s.turnMode == TurnMode.PUSH_TO_TALK && s.connected) {
                add(action(android.R.drawable.ic_btn_speak_now, if (s.talking) "Listo" else "Hablar", ACTION_TALK))
            } else {
                add(action(android.R.drawable.ic_menu_close_clear_cancel, "Terminar", ACTION_END))
            }
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ink)
            .setContentTitle(s.title.ifBlank { "Escuchar coreano" })
            .setContentText(s.status)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .apply { actions.forEach { addAction(it) } }
            .setStyle(Notification.MediaStyle().setMediaSession(media.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun action(icon: Int, title: String, action: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, action.hashCode(), Intent(this, ListenService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    @Suppress("DEPRECATION")
    private fun keyEvent(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        media.isActive = false
        media.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "escuchar"
        private const val NOTIFICATION_ID = 4_201
        private const val CLICK_WINDOW_MS = 450L
        private const val ACTION_TOGGLE = "com.jorge.koreandiary.escuchar.TOGGLE"
        private const val ACTION_REPEAT = "com.jorge.koreandiary.escuchar.REPEAT"
        private const val ACTION_TALK = "com.jorge.koreandiary.escuchar.TALK"
        private const val ACTION_END = "com.jorge.koreandiary.escuchar.END"

        /** Llamar con la app en primer plano (Android no deja abrir el micrófono en segundo plano si no). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ListenService::class.java))
        }
    }
}

package com.jorge.koreandiary.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jorge.koreandiary.KoreanDiaryApp
import com.jorge.koreandiary.MainActivity
import com.jorge.koreandiary.R
import com.jorge.koreandiary.mascot.MascotBrain
import com.jorge.koreandiary.settings.AppSettings
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Recordatorio diario. Usa una alarma con ventana de 10 minutos que se reprograma cada día:
 * no necesita el permiso de alarmas exactas y gasta lo mínimo de batería.
 */
object ReminderScheduler {
    private const val WINDOW_MS = 10 * 60 * 1000L

    fun apply(context: Context, settings: AppSettings) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context)
        alarm.cancel(pi)
        if (!settings.reminderEnabled) return
        val trigger = nextTrigger(settings.reminderHour, settings.reminderMinute, LocalDateTime.now())
        val millis = trigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarm.setWindow(AlarmManager.RTC_WAKEUP, millis, WINDOW_MS, pi)
    }

    /** Próxima ocurrencia de la hora elegida: hoy si aún no pasó, si no mañana. */
    fun nextTrigger(hour: Int, minute: Int, now: LocalDateTime): LocalDateTime {
        val today = now.toLocalDate().atTime(LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)))
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0, Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/** Al sonar: si hoy no hay página, avisa con una frase de la mascota. Luego programa el de mañana. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val container = (context.applicationContext as KoreanDiaryApp).container
        container.appScope.launch {
            try {
                val settings = container.settings.state.value
                val today = LocalDate.now()
                val diary = container.database.diaryDao()
                if (settings.reminderEnabled && diary.pageCountForDate(today.toString()) == 0) {
                    val dates = diary.pagesPerDay().mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
                    ReminderNotifier.show(context, MascotBrain.reminder(dates, today))
                }
                ReminderScheduler.apply(context, settings)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Las alarmas se borran al reiniciar el celular (y por si acaso al actualizar la app): se vuelven a programar. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as KoreanDiaryApp).container
        ReminderScheduler.apply(context, container.settings.state.value)
    }
}

object ReminderNotifier {
    private const val CHANNEL = "recordatorio"
    private const val ID = 1

    fun show(context: Context, line: com.jorge.koreandiary.mascot.MascotLine) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Recordatorio diario", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Aviso para escribir tu diario si aún no lo hiciste hoy"
            }
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ink)
            .setContentTitle("일기 쓸 시간이에요")
            .setContentText(line.korean)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${line.korean}\n${line.spanish}"))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ID, notification)
    }
}

package com.skofqq.boxy.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.skofqq.boxy.BoxyApp
import com.skofqq.boxy.MainActivity
import com.skofqq.boxy.R
import com.skofqq.boxy.util.withAppLocale

/**
 * Events sent by the module with `am broadcast` as root (box.tool notify_subs_failed).
 * Not exported: only root and the system can reach it.
 */
class ModuleEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SUBS_FAILED) return
        val app = BoxyApp.instance
        // Off in Settings → Notifications, or the user is looking at the app (the update page shows the error).
        if (!app.prefs.notifySubsFailed || BoxyApp.foreground) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val ctx = context.withAppLocale()
        createChannel(ctx)
        val text = ctx.getString(
            when (intent.getStringExtra("reason")) {
                "check" -> R.string.notify_subs_failed_check
                "format" -> R.string.notify_subs_failed_format
                else -> R.string.notify_subs_failed_download
            },
        )
        val detail = intent.getStringExtra("detail")?.trim()?.takeIf { it.isNotEmpty() }
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(ctx.getString(R.string.notify_subs_failed_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (detail != null) "$text\n$detail" else text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }

    companion object {
        const val ACTION_SUBS_FAILED = "com.skofqq.boxy.action.SUBS_FAILED"
        const val CHANNEL_ID = "module_events"
        private const val NOTIFICATION_ID = 2

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notify_channel_module), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }
}

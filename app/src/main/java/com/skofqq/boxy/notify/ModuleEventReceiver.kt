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
import kotlinx.coroutines.launch

/**
 * Events sent by the module with `am broadcast` as root (box.service notify_state, box.tool
 * notify_subs_failed). Not exported: only root and the system can reach it.
 */
class ModuleEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STATE_CHANGED) {
            // The service started or stopped: notification, tile and widgets follow at once.
            com.skofqq.boxy.service.BoxControl.changed(context)
            return
        }
        if (intent.action == ACTION_GEO_UPDATED) {
            // box.tool geox downloaded new geo files: rebuild mihomo's merged GeoSite.dat / GeoIP.dat or sing-box's
            // rule sets from them, then let the running core re-read them. Only on this event, no polling.
            val core = intent.getStringExtra("core") ?: return
            val pending = goAsync()
            work.launch {
                // Merging large .dat files can take longer than a receiver may run: the broadcast is let go after
                // a while and the work goes on in the process.
                val release = launch { kotlinx.coroutines.delay(RECEIVER_BUDGET_MS); runCatching { pending.finish() } }
                try {
                    val changed = com.skofqq.boxy.configs.GeoRuleSets.rebuild(core)
                    val state = runCatching { com.skofqq.boxy.root.BoxModule.state() }.getOrNull()
                    if (changed && state?.running == true && com.skofqq.boxy.root.BoxModule.unquote(state.core) == core) {
                        com.skofqq.boxy.root.BoxModule.reloadConfig(core)
                    }
                } finally {
                    if (release.isActive) { release.cancel(); runCatching { pending.finish() } }
                }
            }
            return
        }
        if (intent.action == ACTION_SUBS_LINKS) {
            // box.tool subs downloaded an Xray / V2Ray subscription: make outbounds of it, check, restart.
            val core = intent.getStringExtra("core") ?: return
            val pending = goAsync()
            work.launch {
                val release = launch { kotlinx.coroutines.delay(RECEIVER_BUDGET_MS); runCatching { pending.finish() } }
                try {
                    com.skofqq.boxy.configs.XraySubscription.apply(core)
                } finally {
                    if (release.isActive) { release.cancel(); runCatching { pending.finish() } }
                }
            }
            return
        }
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
        const val ACTION_STATE_CHANGED = "com.skofqq.boxy.action.STATE_CHANGED"
        const val ACTION_GEO_UPDATED = "com.skofqq.boxy.action.GEO_UPDATED"
        const val ACTION_SUBS_LINKS = "com.skofqq.boxy.action.SUBS_LINKS"
        const val CHANNEL_ID = "module_events"

        /** How long a receiver keeps its broadcast open (the system allows 10 s in the foreground). */
        private const val RECEIVER_BUDGET_MS = 8_000L

        /** Work started by module events that may outlast the broadcast. */
        private val work = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        private const val NOTIFICATION_ID = 2

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notify_channel_module), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }
}

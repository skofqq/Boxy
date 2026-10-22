package com.skofqq.boxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.PowerManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.skofqq.boxy.MainActivity
import com.skofqq.boxy.R
import com.skofqq.boxy.data.TrafficStats
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.ServiceState
import com.skofqq.boxy.util.withAppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Persistent notification with the Box service status and Stop / Restart actions
 * (Settings → Notifications). The module announces start and stop right away ([poke]); besides that the
 * module is polled every [POLL_MS] while the screen is on, and the notification is redrawn only when
 * something changed; the uptime is a system chronometer.
 */
class BoxStatusService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var busyText: Int? = null
    /** What the notification shows now (running, core, mode, pid); null forces a redraw. */
    private var shown: List<Any?>? = null
    private val screenOn = MutableStateFlow(true)
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn.value = intent.action == Intent.ACTION_SCREEN_ON
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withAppLocale())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            build(null),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        screenOn.value = getSystemService(PowerManager::class.java).isInteractive
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch {
            var tick = 0
            var last: Boolean? = null
            while (isActive) {
                // Nobody sees the notification with the screen off: wait instead of polling root.
                screenOn.first { it }
                val state = runCatching { BoxModule.state() }.getOrNull()
                if (busyText == null) show(state)
                // The widget and tile follow changes made elsewhere (module action button, boot, crash).
                if (state != null && state.running != last) {
                    if (last != null) BoxControl.changed(this@BoxStatusService)
                    last = state.running
                }
                // Once a minute.
                if (tick++ % 4 == 0) TrafficStats.sample(applicationContext)
                // Proxied traffic per app (screen on only, like this loop); at most every 8 s.
                if (state?.running == true) com.skofqq.boxy.data.AppTraffic.sample(applicationContext)
                // A start / stop announced by the module (or done by the app) wakes the loop early.
                withTimeoutOrNull(POLL_MS) { pokes.first() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> act(R.string.service_status_stopping) { BoxControl.stop(applicationContext) }
            ACTION_START -> act(R.string.service_status_starting) { BoxControl.start(applicationContext) }
            ACTION_RESTART -> act(R.string.service_status_restarting) { BoxControl.restart(applicationContext) }
        }
        return START_STICKY
    }

    private fun act(text: Int, block: suspend () -> Unit) {
        busyText = text
        shown = null
        notify(build(null))
        scope.launch {
            block()
            busyText = null
            shown = null
            show(BoxModule.state())
        }
    }

    /** Redraws the notification only when its content changes. */
    private fun show(state: ServiceState?) {
        // The in-app language is part of the key: a change shows up at the next poll.
        val key = listOf(state?.running, state?.core, state?.mode, state?.pid, com.skofqq.boxy.data.Prefs.storedLanguage(this))
        if (key == shown) return
        shown = key
        notify(build(state))
    }

    private fun notify(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }

    private fun build(state: ServiceState?): Notification {
        // The language may have changed in the app since the service started.
        val res = withAppLocale()
        val title = res.getString(
            busyText ?: when {
                state == null -> R.string.service_status_checking
                state.running -> R.string.service_status_running
                else -> R.string.service_status_stopped
            },
        )
        val text = if (state?.running == true) listOfNotNull(state.core, state.mode).joinToString(" · ") else null
        // Uptime as a chronometer drawn by the system, so the notification does not change every tick.
        val startedAt = state?.takeIf { it.running }?.uptimeSec?.let { System.currentTimeMillis() - it * 1000 }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (startedAt != null) {
            b.setWhen(startedAt).setShowWhen(true).setUsesChronometer(true)
        } else {
            b.setShowWhen(false)
        }
        if (busyText == null && state != null) {
            if (state.running) {
                b.addAction(0, res.getString(R.string.service_action_stop), action(ACTION_STOP))
                b.addAction(0, res.getString(R.string.service_action_restart), action(ACTION_RESTART))
            } else {
                b.addAction(0, res.getString(R.string.action_start), action(ACTION_START))
            }
        }
        return b.build()
    }

    private fun action(name: String): PendingIntent =
        PendingIntent.getService(this, name.hashCode(), Intent(this, BoxStatusService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE)

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "box_service"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.skofqq.boxy.STOP"
        private const val ACTION_START = "com.skofqq.boxy.START"
        private const val ACTION_RESTART = "com.skofqq.boxy.RESTART"
        private const val POLL_MS = 15_000L

        private val pokes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /** Re-reads the service state now instead of at the next poll. */
        fun poke() {
            pokes.tryEmit(Unit)
        }

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.service_channel_name), NotificationManager.IMPORTANCE_LOW),
            )
        }

        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, BoxStatusService::class.java)
            if (enabled) {
                runCatching { ContextCompat.startForegroundService(context, intent) }
            } else {
                context.stopService(intent)
            }
        }
    }
}

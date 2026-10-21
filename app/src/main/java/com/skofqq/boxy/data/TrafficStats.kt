package com.skofqq.boxy.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.skofqq.boxy.net.Net
import com.skofqq.boxy.root.BoxModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/** Traffic of one day or month: bytes downloaded and uploaded through the core. */
data class TrafficBucket(val down: Long, val up: Long) {
    val total get() = down + up
    operator fun plus(o: TrafficBucket) = TrafficBucket(down + o.down, up + o.up)

    companion object {
        val ZERO = TrafficBucket(0, 0)
    }
}

/**
 * Daily proxy traffic built from the core's own counters (Clash API /connections: downloadTotal,
 * uploadTotal since the core started). Each sample adds the growth since the previous sample to today;
 * a new core process (other PID, or counters that went down) starts from zero again.
 * Samples come from the open app, the status notification, the widget and a 15-minute alarm.
 */
object TrafficStats {
    private const val PREFS = "traffic"
    private const val KEEP_DAYS = 400L
    private val lock = Any()

    suspend fun sample(context: Context): Boolean {
        val state = runCatching { BoxModule.state() }.getOrNull() ?: return false
        if (!state.running) return false
        val api = Net.clashApi(state.core) ?: return false
        val (down, up) = Net.clashTotals(api) ?: return false
        add(context, state.pid ?: "", down, up)
        com.skofqq.boxy.widget.BoxWidget.refresh(context, state)
        return true
    }

    private fun add(context: Context, pid: String, down: Long, up: Long) = synchronized(lock) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val samePid = sp.getString("pid", null) == pid
        val lastDown = sp.getLong("down", 0)
        val lastUp = sp.getLong("up", 0)
        val (dDown, dUp) = if (samePid && down >= lastDown && up >= lastUp) down - lastDown to up - lastUp else down to up
        val key = "d" + LocalDate.now()
        val today = parse(sp.getString(key, null))
        val e = sp.edit()
            .putString("pid", pid)
            .putLong("down", down)
            .putLong("up", up)
            .putString(key, "${today.down + dDown},${today.up + dUp}")
        val oldest = LocalDate.now().minusDays(KEEP_DAYS)
        sp.all.keys.filter { it.startsWith("d") && runCatching { LocalDate.parse(it.drop(1)) }.getOrNull()?.isBefore(oldest) == true }
            .forEach { e.remove(it) }
        e.apply()
    }

    private fun parse(v: String?): TrafficBucket {
        val p = v?.split(',') ?: return TrafficBucket.ZERO
        return TrafficBucket(p.getOrNull(0)?.toLongOrNull() ?: 0, p.getOrNull(1)?.toLongOrNull() ?: 0)
    }

    /** Every recorded day. */
    fun days(context: Context): Map<LocalDate, TrafficBucket> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.all.mapNotNull { (k, v) ->
            if (!k.startsWith("d")) return@mapNotNull null
            val date = runCatching { LocalDate.parse(k.drop(1)) }.getOrNull() ?: return@mapNotNull null
            date to parse(v as? String)
        }.toMap()
    }

    fun months(days: Map<LocalDate, TrafficBucket>): Map<YearMonth, TrafficBucket> =
        days.entries.groupBy({ YearMonth.from(it.key) }, { it.value }).mapValues { (_, v) -> v.fold(TrafficBucket.ZERO) { a, b -> a + b } }

    /** Clears the history but keeps the counters, so the next sample only counts new traffic. */
    fun reset(context: Context) = synchronized(lock) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("d") }.forEach { e.remove(it) }
        e.apply()
    }

    /** Inexact 15-minute alarm that keeps sampling while the app is closed. */
    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(context, 0, Intent(context, SampleReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            pi,
        )
    }

    class SampleReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    sample(context.applicationContext)
                    Subscriptions.check(context.applicationContext)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}

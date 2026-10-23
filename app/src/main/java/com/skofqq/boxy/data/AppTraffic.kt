package com.skofqq.boxy.data

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import android.os.SystemClock
import com.skofqq.boxy.net.ClashApi
import com.skofqq.boxy.net.Net
import com.skofqq.boxy.root.BoxModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** Traffic of one app over a period: through a proxy (seen by the core) and direct. */
data class AppUsage(
    val pkg: String,
    val proxyDown: Long,
    val proxyUp: Long,
    val directDown: Long,
    val directUp: Long,
) {
    val proxy: Long get() = proxyDown + proxyUp
    val direct: Long get() = directDown + directUp
    val total: Long get() = proxy + direct
}

/**
 * Traffic per app, put together from two sources so nothing has to run in the background:
 * - Direct traffic: Android's own per-app counters (NetworkStatsManager), read only when the page is open.
 *   Boxy grants itself usage access with root once.
 * - Proxied traffic: Android counts it to the core (root), because the app's packets reach the core over
 *   loopback. The core's connection list says which app each connection belongs to; [sample] adds what each
 *   connection moved since the last look. It is called by things that already run (status notification
 *   every 5 s with the screen on, the open app), at most every [MIN_INTERVAL_MS], never with the screen off.
 */
object AppTraffic {
    private const val PREFS = "app_traffic"
    private const val KEEP_DAYS = 62L
    private const val MIN_INTERVAL_MS = 8000L
    /** Package key of traffic the core moved for itself or could not tie to an app. */
    const val CORE = "@core"

    private val lock = Any()
    private val last = HashMap<String, LongArray>()
    private var lastAt = 0L

    /** Whether the core reports apps in its connections (find-process-mode); null until known. */
    @Volatile
    var coreKnowsApps: Boolean? = null
        private set

    suspend fun sample(context: Context) = withContext(Dispatchers.IO) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (now - lastAt < MIN_INTERVAL_MS) return@withContext
            lastAt = now
        }
        val state = runCatching { BoxModule.state() }.getOrNull()
        if (state?.running != true) {
            synchronized(lock) { last.clear() }
            return@withContext
        }
        val core = state.core ?: "clash"
        val a = Net.clashApi(core) ?: return@withContext
        val conns = Net.clashConnections(a) ?: return@withContext
        if (conns.isNotEmpty()) coreKnowsApps = conns.any { it.app != null || it.uid != null }
        val add = HashMap<String, LongArray>()
        synchronized(lock) {
            val first = last.isEmpty()
            val seen = HashSet<String>(conns.size)
            for (c in conns) {
                seen += c.id
                val prev = last[c.id]
                last[c.id] = longArrayOf(c.upload, c.download)
                // The first look after start only sets the baseline (those bytes may be counted already).
                if (first && prev == null) continue
                val dUp = (c.upload - (prev?.get(0) ?: 0)).coerceAtLeast(0)
                val dDown = (c.download - (prev?.get(1) ?: 0)).coerceAtLeast(0)
                if (dUp == 0L && dDown == 0L) continue
                // On Android the core names the process "app_process64"; its uid tells the app.
                val key = c.uid?.let { packageOf(context, it) } ?: c.app?.takeIf { it != "app_process64" && it != "app_process" } ?: CORE
                val acc = add.getOrPut(key) { LongArray(4) }
                if (c.direct) {
                    acc[2] += dDown
                    acc[3] += dUp
                } else {
                    acc[0] += dDown
                    acc[1] += dUp
                }
            }
            last.keys.retainAll(seen)
            if (add.isEmpty()) return@withContext
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val e = sp.edit()
            val day = LocalDate.now().toString()
            add.forEach { (pkg, v) ->
                val key = "$day|$pkg"
                val old = parse(sp.getString(key, null))
                e.putString(key, (0..3).joinToString(",") { (old[it] + v[it]).toString() })
            }
            if (sp.getString("pruned", null) != day) {
                val oldest = LocalDate.now().minusDays(KEEP_DAYS)
                sp.all.keys.filter { k -> k.contains('|') && runCatching { LocalDate.parse(k.substringBefore('|')) }.getOrNull()?.isBefore(oldest) == true }
                    .forEach { e.remove(it) }
                e.putString("pruned", day)
            }
            e.apply()
        }
    }

    private val packages = HashMap<Int, String?>()

    /** Package of an app uid (any user); cached, uids do not change while installed. */
    private fun packageOf(context: Context, uid: Int): String? = synchronized(packages) {
        packages.getOrPut(uid) {
            val pm = context.packageManager
            when {
                uid % 100_000 == 0 -> null
                uid % 100_000 == Process.SYSTEM_UID -> "android"
                else -> packageFor(pm, uid)
            }
        }
    }

    /**
     * First package of [uid]. A uid of another user (work profile, private space, a second user) throws
     * SecurityException without INTERACT_ACROSS_USERS, so those fall back to the same app id in this user.
     */
    private fun packageFor(pm: android.content.pm.PackageManager, uid: Int): String? {
        fun lookup(u: Int) = runCatching { pm.getPackagesForUid(u)?.firstOrNull() }.getOrNull()
        return lookup(uid) ?: lookup(uid % 100_000)
    }

    private fun parse(v: String?): LongArray {
        val p = v?.split(',') ?: return LongArray(4)
        return LongArray(4) { p.getOrNull(it)?.toLongOrNull() ?: 0 }
    }

    /** Usage access for the system counters; granted with root when missing. */
    suspend fun ensureUsageAccess(context: Context): Boolean {
        if (hasUsageAccess(context)) return true
        BoxModule.exec("appops set ${context.packageName} GET_USAGE_STATS allow")
        return hasUsageAccess(context)
    }

    private fun hasUsageAccess(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /**
     * Apps over the last [days] days (today included), biggest first. Direct traffic from Android's counters
     * (when [system] access is there), proxied traffic from the samples. The core's own share in Android's
     * counters is what the samples could not tie to an app; it is shown as [CORE].
     */
    suspend fun usage(context: Context, days: Int, system: Boolean): List<AppUsage> = withContext(Dispatchers.IO) {
        val from = LocalDate.now().minusDays((days - 1).toLong())
        val sampled = HashMap<String, LongArray>()
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.all.forEach { (k, v) ->
            if (!k.contains('|')) return@forEach
            val date = runCatching { LocalDate.parse(k.substringBefore('|')) }.getOrNull() ?: return@forEach
            if (date.isBefore(from)) return@forEach
            val pkg = k.substringAfter('|').let { if (it == "app_process64" || it == "app_process") CORE else it }
            val acc = sampled.getOrPut(pkg) { LongArray(4) }
            parse(v as? String).forEachIndexed { i, x -> acc[i] += x }
        }
        val byPkg = HashMap<String, LongArray>()
        sampled.forEach { (pkg, v) -> byPkg[pkg] = v.copyOf() }
        if (system) {
            val start = from.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val counters = systemCounters(context, start, System.currentTimeMillis())
            var coreSystem = 0L to 0L
            val pm = context.packageManager
            counters.forEach { (uid, rxTx) ->
                val appId = uid % 100_000
                if (appId == 0) {
                    // root: the core's upstream traffic (the proxied apps' bytes, already counted from samples)
                    coreSystem = rxTx
                    return@forEach
                }
                val pkg = when {
                    uid < 0 -> "@uid$uid"
                    appId == Process.SYSTEM_UID -> "android"
                    else -> packageFor(pm, uid) ?: "@uid$appId"
                }
                val acc = byPkg.getOrPut(pkg) { LongArray(4) }
                acc[2] += rxTx.first
                acc[3] += rxTx.second
            }
            // What went through the core but was not tied to an app (find-process-mode off, samples missed).
            val attributed = byPkg.entries.filter { it.key != CORE }.sumOf { it.value[0] + it.value[1] }
            val unassigned = (coreSystem.first + coreSystem.second) - attributed
            val core = byPkg.getOrPut(CORE) { LongArray(4) }
            if (unassigned > core[0] + core[1]) {
                val ratio = if (coreSystem.first + coreSystem.second > 0) coreSystem.first.toDouble() / (coreSystem.first + coreSystem.second) else 0.5
                core[0] = (unassigned * ratio).toLong()
                core[1] = unassigned - core[0]
            }
        }
        byPkg.map { (pkg, v) -> AppUsage(pkg, v[0], v[1], v[2], v[3]) }
            .filter { it.total > 0 }
            .sortedByDescending { it.total }
    }

    /** uid -> (received, sent) bytes on Wi-Fi, mobile and Ethernet between [start] and [end]. */
    private fun systemCounters(context: Context, start: Long, end: Long): Map<Int, Pair<Long, Long>> {
        val nsm = context.getSystemService(NetworkStatsManager::class.java) ?: return emptyMap()
        val out = HashMap<Int, LongArray>()
        @Suppress("DEPRECATION")
        listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_ETHERNET).forEach { type ->
            runCatching {
                nsm.querySummary(type, null, start, end).use { stats ->
                    val b = NetworkStats.Bucket()
                    while (stats.hasNextBucket()) {
                        stats.getNextBucket(b)
                        val acc = out.getOrPut(b.uid) { LongArray(2) }
                        acc[0] += b.rxBytes
                        acc[1] += b.txBytes
                    }
                }
            }
        }
        return out.mapValues { it.value[0] to it.value[1] }
    }
}

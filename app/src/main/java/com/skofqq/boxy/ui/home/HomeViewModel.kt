package com.skofqq.boxy.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skofqq.boxy.BoxyApp
import com.skofqq.boxy.data.Prefs
import com.skofqq.boxy.data.TrafficStats
import com.skofqq.boxy.service.BoxControl
import com.skofqq.boxy.data.SubscriptionSource
import com.skofqq.boxy.net.GeoIp
import com.skofqq.boxy.net.LanAddress
import com.skofqq.boxy.net.Net
import com.skofqq.boxy.net.SubscriptionInfo
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.Environment
import com.skofqq.boxy.root.ServiceDetails
import com.skofqq.boxy.root.ServiceState
import com.skofqq.boxy.root.SystemEnvironment
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class Busy { NONE, STARTING, STOPPING, RESTARTING }

data class LatencyResult(val name: String, val ms: Long?)

enum class LatencyBadge { TEST, OK, PART, DOWN }

data class SpeedState(
    val down: Long = 0,
    val up: Long = 0,
    val history: List<Long> = emptyList(),
    val fastestDown: Long = 0,
    val fastestUp: Long = 0,
    val known: Boolean = false,
)

data class SystemState(val cpuPercent: Float, val rssBytes: Long)

class HomeViewModel(private val prefs: Prefs) : ViewModel() {
    var env by mutableStateOf<Environment?>(null)
        private set
    /** DNSCrypt (enabled, running); enabled is null when the module has no DNSCrypt. */
    var dnscrypt by mutableStateOf<Pair<Boolean?, Boolean>?>(null)
        private set
    var state by mutableStateOf<ServiceState?>(null)
        private set
    var busy by mutableStateOf(Busy.NONE)
        private set

    var latency by mutableStateOf<List<LatencyResult>>(emptyList())
        private set
    var latencyTesting by mutableStateOf(false)
        private set

    var lan by mutableStateOf<LanAddress?>(null)
        private set
    var wan by mutableStateOf<GeoIp?>(null)
        private set
    var geo4 by mutableStateOf<GeoIp?>(null)
        private set
    var geo6 by mutableStateOf<GeoIp?>(null)
        private set
    var geoLoading by mutableStateOf(false)
        private set

    var speed by mutableStateOf(SpeedState())
        private set
    var system by mutableStateOf<SystemState?>(null)
        private set
    var subscriptions by mutableStateOf(loadCachedSubscriptions())
        private set
    var subStore by mutableStateOf(false)
        private set

    var details by mutableStateOf<ServiceDetails?>(null)
        private set
    var systemEnv by mutableStateOf<SystemEnvironment?>(null)
        private set

    /** Emits one-off messages (reload result) for a toast. */
    var message by mutableStateOf<Int?>(null)

    private var lastRunning: Boolean? = null
    private var latencyJob: Job? = null

    val latencyBadge: LatencyBadge
        get() = when {
            latencyTesting || latency.isEmpty() -> LatencyBadge.TEST
            latency.all { it.ms != null } -> LatencyBadge.OK
            latency.none { it.ms != null } -> LatencyBadge.DOWN
            else -> LatencyBadge.PART
        }

    fun checkEnvironment() {
        viewModelScope.launch {
            env = null
            env = BoxModule.environment()
            if (env == Environment.READY) {
                refreshState()
                subStore = BoxModule.subStoreInstalled()
            }
        }
    }

    /** Runs while the home page is visible; every loop stops when the caller is cancelled. */
    suspend fun poll() = coroutineScope {
        if (env == null) env = BoxModule.environment()
        if (env != Environment.READY) return@coroutineScope
        subStore = BoxModule.subStoreInstalled()
        launch {
            val mirror = prefs.githubMirror
            if (mirror.isNotBlank() && com.skofqq.boxy.net.Mirrors.moduleMirror() != mirror) {
                com.skofqq.boxy.net.Mirrors.applyToModule(mirror)
            }
        }
        launch { while (true) { refreshState(); delay(2000) } }
        launch { while (true) { TrafficStats.sample(BoxyApp.instance); delay(60_000) } }
        launch { speedLoop() }
        launch { systemLoop() }
        launch { while (true) { testLatency(); delay(60_000) } }
        launch { refreshSubscription() }
    }

    private suspend fun refreshState() {
        if (busy != Busy.NONE) return
        val s = BoxModule.state()
        state = s
        dnscrypt = BoxModule.dnscryptStatus()
        if (s.running != lastRunning) {
            lastRunning = s.running
            refreshIp()
            if (lastRunning != null) testLatency()
        }
    }

    fun refreshIp() {
        viewModelScope.launch {
            lan = Net.lanAddress()
            wan = if (prefs.ipWan) Net.geoIp(false) else null
        }
    }

    fun loadGeoDetails() {
        viewModelScope.launch {
            geoLoading = true
            val (v4, v6) = listOf(async { Net.geoIp(false) }, async { Net.geoIp(true) }).awaitAll()
            geo4 = v4
            geo6 = v6
            geoLoading = false
        }
    }

    fun testLatency() {
        if (latencyJob?.isActive == true) return
        latencyJob = viewModelScope.launch {
            latencyTesting = true
            val targets = prefs.latencyTargets
            latency = targets.map { LatencyResult(it.name, null) }
            coroutineScope {
                targets.forEachIndexed { i, t ->
                    launch {
                        val ms = Net.latency(t.url)
                        latency = latency.toMutableList().also { if (i < it.size) it[i] = LatencyResult(t.name, ms) }
                    }
                }
            }
            latencyTesting = false
        }
    }

    private suspend fun speedLoop() {
        while (true) {
            val api = if (prefs.useClashApi && state?.running == true) Net.clashApi(state?.core) else null
            val filter = prefs.filterChainSet
            if (api != null && filter.isNotEmpty()) {
                // Sum traffic of connections whose chains are not filtered out (e.g. DIRECT).
                var prev: Pair<Long, Long>? = null
                repeat(30) {
                    val cur = Net.clashConnectionTotals(api, filter)
                    if (prev != null && cur != null) pushSpeed(cur.first - prev!!.first, cur.second - prev!!.second)
                    prev = cur
                    delay(1000)
                }
            } else if (api != null) {
                Net.clashTraffic(api).catch { }.collect { (down, up) -> pushSpeed(down, up) }
                delay(2000)
            } else {
                var prev = BoxModule.netCounters()
                var prevTime = System.nanoTime()
                repeat(30) {
                    delay(1000)
                    val cur = BoxModule.netCounters()
                    val now = System.nanoTime()
                    if (prev != null && cur != null) {
                        val secs = (now - prevTime) / 1e9
                        pushSpeed(((cur.first - prev!!.first) / secs).toLong(), ((cur.second - prev!!.second) / secs).toLong())
                    }
                    prev = cur
                    prevTime = now
                }
            }
        }
    }

    private fun pushSpeed(down: Long, up: Long) {
        val d = down.coerceAtLeast(0)
        val u = up.coerceAtLeast(0)
        val s = speed
        speed = s.copy(
            down = d,
            up = u,
            history = (s.history + (d + u)).takeLast(40),
            fastestDown = maxOf(s.fastestDown, d),
            fastestUp = maxOf(s.fastestUp, u),
            known = true,
        )
    }

    private suspend fun systemLoop() {
        var prev: BoxModule.ProcSample? = null
        while (true) {
            val pid = state?.pid
            if (pid == null) {
                system = null
                prev = null
            } else {
                val cur = BoxModule.sample(pid)
                if (cur != null && prev != null && cur.totalTicks > prev.totalTicks) {
                    val pct = 100f * (cur.procTicks - prev.procTicks) / (cur.totalTicks - prev.totalTicks) * cur.cpuCount
                    system = SystemState(pct.coerceIn(0f, 100f * cur.cpuCount), cur.rssBytes)
                } else if (cur != null && system == null) {
                    system = SystemState(0f, cur.rssBytes)
                }
                prev = cur
            }
            delay(2000)
        }
    }

    fun refreshSubscription() {
        viewModelScope.launch {
            val list = when (prefs.subscriptionSource) {
                SubscriptionSource.CORE_API -> Net.clashApi(state?.core ?: BoxModule.readSetting("bin_name"))
                    ?.let { Net.clashProviders(it) }.orEmpty()
                SubscriptionSource.PROVIDERS -> {
                    val urls = Net.providerUrls()
                    coroutineScope { urls.map { (name, url) -> async { Net.subscription(url)?.copy(name = name) } }.awaitAll() }.filterNotNull()
                }
                SubscriptionSource.URL -> {
                    val urls = BoxModule.parseArray(BoxModule.readSettingRaw("subscription_url_clash"))
                    coroutineScope { urls.map { url -> async { Net.subscription(url) } }.awaitAll() }.filterNotNull()
                }
            }
            if (list.isNotEmpty()) {
                subscriptions = list
                saveCachedSubscriptions(list)
            }
        }
    }

    fun loadDetails() {
        val s = state ?: return
        viewModelScope.launch { details = BoxModule.details(s) }
    }

    fun loadSystemEnvironment() {
        viewModelScope.launch { systemEnv = BoxModule.systemEnvironment() }
    }

    fun start() = act(Busy.STARTING) { BoxControl.start(BoxyApp.instance) }

    fun stop() = act(Busy.STOPPING) { BoxControl.stop(BoxyApp.instance) }

    fun restart() = act(Busy.RESTARTING) { BoxControl.restart(BoxyApp.instance) }

    fun reloadConfig() {
        viewModelScope.launch {
            val api = Net.clashApi(state?.core)
            val ok = (api != null && Net.clashReload(api)) || BoxModule.reloadConfig()
            message = if (ok) com.skofqq.boxy.R.string.home_reload_success else com.skofqq.boxy.R.string.home_reload_failed
        }
    }

    /** Core, mode and IPv6 can only change while the service is stopped. */
    var setup by mutableStateOf<com.skofqq.boxy.root.ModuleSetup?>(null)
        private set

    fun loadSetup() {
        viewModelScope.launch { setup = BoxModule.moduleSetup() }
    }

    /** Core / mode / IPv6 change; a running service is restarted with the new settings. */
    fun applySettings(settings: List<Pair<String, String>>) {
        if (state?.running == true) {
            act(Busy.RESTARTING) {
                com.skofqq.boxy.service.BoxControl.applySettings(BoxyApp.instance, settings)
                setup = BoxModule.moduleSetup()
            }
        } else {
            viewModelScope.launch {
                com.skofqq.boxy.service.BoxControl.applySettings(BoxyApp.instance, settings)
                setup = BoxModule.moduleSetup()
                state = BoxModule.state()
            }
        }
    }

    private fun act(kind: Busy, block: suspend () -> Unit) {
        if (busy != Busy.NONE) return
        busy = kind
        viewModelScope.launch {
            block()
            busy = Busy.NONE
            refreshState()
        }
    }

    private fun loadCachedSubscriptions(): List<SubscriptionInfo> = runCatching {
        val arr = org.json.JSONArray(prefs.subscriptionCache ?: return emptyList())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SubscriptionInfo(
                o.optString("name").ifBlank { null }, o.optLong("up"), o.optLong("down"),
                o.optLong("total"), o.optLong("expire"), o.optLong("at"),
            )
        }
    }.getOrDefault(emptyList())

    private fun saveCachedSubscriptions(list: List<SubscriptionInfo>) {
        val arr = org.json.JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("name", it.name ?: "").put("up", it.upload).put("down", it.download)
                    .put("total", it.total).put("expire", it.expire).put("at", it.updatedAt),
            )
        }
        prefs.subscriptionCache = arr.toString()
    }
}

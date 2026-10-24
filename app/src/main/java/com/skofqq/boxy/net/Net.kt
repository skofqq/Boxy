package com.skofqq.boxy.net

import com.skofqq.boxy.root.BoxModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

data class LanAddress(val ip: String, val iface: String)

data class GeoIp(
    val ip: String,
    val countryCode: String?,
    val country: String?,
    val region: String?,
    val city: String?,
    val isp: String?,
    val asn: String?,
) {
    val location: String get() = listOfNotNull(city, region, country).filter { it.isNotBlank() }.distinct().joinToString(", ")
}

data class SubscriptionInfo(
    val name: String?,
    val upload: Long,
    val download: Long,
    val total: Long,
    /** Unix seconds, 0 when unknown. */
    val expire: Long,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val used: Long get() = upload + download
    val remaining: Long get() = (total - used).coerceAtLeast(0)
}

data class ClashApi(val base: String, val secret: String?)

data class CoreConnection(val id: String, val app: String?, val uid: Int?, val upload: Long, val download: Long, val direct: Boolean)

data class ProxyNode(
    val name: String,
    val type: String,
    /** Last test in ms, 0 = timed out, null = never tested. */
    val delay: Int?,
    val udp: Boolean = false,
    val xudp: Boolean = false,
)

data class ProxyGroup(
    val name: String,
    val type: String,
    val now: String?,
    val all: List<String>,
    val icon: String? = null,
    /** `hidden: true` in the config: used by other groups but not shown (Zashboard hides them too). */
    val hidden: Boolean = false,
) {
    val selectable: Boolean get() = type == "Selector"
}

/** A proxy-provider of the config (subscription): its nodes, quota and last update. */
data class ProxyProvider(
    val name: String,
    /** HTTP, File, Inline. */
    val vehicle: String,
    /** Epoch ms of the last pull, null when unknown. */
    val updatedAt: Long?,
    val nodes: List<ProxyNode>,
    val sub: SubscriptionInfo?,
)

data class ProxySnapshot(val all: List<ProxyGroup>, val nodes: Map<String, ProxyNode>) {
    private val byName = all.associateBy { it.name }

    /** Groups to show: the config's hidden ones are left out but still resolve as members. */
    val groups: List<ProxyGroup> = all.filter { !it.hidden }

    /** The proxy a group finally uses, following nested groups. */
    fun leaf(group: String): String? {
        var cur = byName[group]?.now ?: return null
        repeat(8) { cur = byName[cur]?.now ?: return cur }
        return cur
    }

    fun group(name: String) = byName[name]
}

object Net {
    private const val TIMEOUT = 5000

    private fun open(url: String, method: String = "GET", ua: String = "BoxApp/Android"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", ua)
        }

    /** Time to first response of a tiny GET, in ms; null when unreachable. */
    suspend fun latency(url: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val start = System.nanoTime()
            val c = open(normalizeUrl(url), ua = "BoxApp/Latency")
            c.setRequestProperty("Range", "bytes=0-0")
            try {
                if (c.responseCode > 0) (System.nanoTime() - start) / 1_000_000 else null
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    fun normalizeUrl(s: String): String {
        val t = s.trim()
        return if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "https://$t"
    }

    fun lanAddress(): LanAddress? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .firstNotNullOfOrNull { ni ->
                ni.inetAddresses.toList().firstOrNull { it is Inet4Address }?.let { LanAddress(it.hostAddress ?: "-", ni.name) }
            }
    }.getOrNull()

    /** Public address details from ip.sb (goes through the proxy when the service runs). */
    suspend fun geoIp(v6: Boolean): GeoIp? = geoIpFrom(if (v6) "https://api-ipv6.ip.sb/geoip" else "https://api-ipv4.ip.sb/geoip")

    /** Details (country, provider) of any address, e.g. the resolver seen by the DNSCrypt check. */
    suspend fun geoIpOf(ip: String): GeoIp? = geoIpFrom("https://api.ip.sb/geoip/$ip")

    private suspend fun geoIpFrom(url: String): GeoIp? = withContext(Dispatchers.IO) {
        runCatching {
            val c = open(url)
            try {
                if (c.responseCode !in 200..299) return@runCatching null
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                GeoIp(
                    ip = j.optString("ip").ifBlank { return@runCatching null },
                    countryCode = j.optString("country_code").ifBlank { null },
                    country = j.optString("country").ifBlank { null },
                    region = j.optString("region").ifBlank { null },
                    city = j.optString("city").ifBlank { null },
                    isp = (j.optString("isp").ifBlank { j.optString("organization") }).ifBlank { null },
                    asn = j.optLong("asn", 0).takeIf { it > 0 }?.let { "AS$it" },
                )
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    /** Traffic quota from the subscription-userinfo header of a subscription URL. */
    suspend fun subscription(url: String): SubscriptionInfo? = withContext(Dispatchers.IO) {
        for (method in listOf("HEAD", "GET")) {
            val info = runCatching {
                val c = open(url, method, ua = "clash")
                c.setRequestProperty("Accept", "*/*")
                c.setRequestProperty("Cache-Control", "no-cache")
                if (method == "GET") c.setRequestProperty("Range", "bytes=0-0")
                try {
                    c.responseCode
                    parseUserInfo(c.getHeaderField("subscription-userinfo"), fileName(c.getHeaderField("content-disposition")))
                } finally {
                    c.disconnect()
                }
            }.getOrNull()
            if (info != null) return@withContext info
        }
        null
    }

    fun parseUserInfo(header: String?, name: String?): SubscriptionInfo? {
        if (header.isNullOrBlank()) return null
        val m = header.split(';').mapNotNull { part ->
            val k = part.substringBefore('=', "").trim().lowercase()
            val v = part.substringAfter('=').trim().toDoubleOrNull()?.toLong()
            if (k.isEmpty() || v == null) null else k to v
        }.toMap()
        if (m.isEmpty()) return null
        return SubscriptionInfo(name, m["upload"] ?: 0, m["download"] ?: 0, m["total"] ?: 0, m["expire"] ?: 0)
    }

    private fun fileName(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(disposition)?.let {
            return java.net.URLDecoder.decode(it.groupValues[1], "UTF-8")
        }
        return Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(disposition)?.groupValues?.get(1)
    }

    // ---- Clash API (mihomo / sing-box clash_api) ----

    private var apiCache: Triple<String, ClashApi, Long>? = null

    /**
     * external-controller and secret of the active core config. Kept for a few minutes per core, so the
     * screens, the status service and the samplers do not read the config with root every time; dropped by
     * [invalidateApi] when the service, the settings or a config file change.
     */
    suspend fun clashApi(core: String?): ClashApi? {
        val key = core ?: "clash"
        val now = android.os.SystemClock.elapsedRealtime()
        apiCache?.takeIf { it.first == key && now - it.third < API_TTL_MS }?.let { return it.second }
        return readClashApi(core)?.also { apiCache = Triple(key, it, now) }
    }

    /** Forgets the remembered API address; the next [clashApi] reads the config again. */
    fun invalidateApi() {
        apiCache = null
    }

    private const val API_TTL_MS = 10 * 60_000L

    private suspend fun readClashApi(core: String?): ClashApi? {
        val (addr, secret) = when (core) {
            "sing-box" -> {
                val name = BoxModule.readSetting("name_sing_config") ?: "config.json"
                val text = BoxModule.readFile("${BoxModule.BOX_DIR}/sing-box/$name") ?: return null
                runCatching {
                    val api = JSONObject(text).optJSONObject("experimental")?.optJSONObject("clash_api") ?: return null
                    api.optString("external_controller") to api.optString("secret")
                }.getOrNull() ?: return null
            }
            else -> {
                val name = BoxModule.readSetting("name_clash_config") ?: "config.yaml"
                val text = BoxModule.readFile("${BoxModule.BOX_DIR}/clash/$name") ?: return null
                yamlValue(text, "external-controller") to yamlValue(text, "secret")
            }
        }
        if (addr.isNullOrBlank()) return null
        val hostPort = addr.trim().let { if (it.startsWith(":")) "127.0.0.1$it" else it.replace("0.0.0.0", "127.0.0.1") }
        return ClashApi("http://$hostPort", secret?.takeIf { it.isNotBlank() })
    }

    private fun yamlValue(text: String, key: String): String? =
        Regex("^$key:\\s*['\"]?([^'\"#\\n]*)['\"]?", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim()

    internal fun openApi(api: ClashApi, path: String, method: String = "GET", timeout: Int = TIMEOUT): HttpURLConnection =
        open(api.base + path, method).apply {
            readTimeout = timeout
            api.secret?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    /** Live traffic from /traffic: pairs of (down, up) bytes per second. */
    fun clashTraffic(api: ClashApi): Flow<Pair<Long, Long>> = flow {
        val c = openApi(api, "/traffic", timeout = 0)
        try {
            c.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!currentCoroutineContext().isActive) break
                    val j = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    emit(j.optLong("down") to j.optLong("up"))
                }
            }
        } finally {
            c.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /** Subscription quotas reported by proxy providers. */
    suspend fun clashProviders(api: ClashApi): List<SubscriptionInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val c = openApi(api, "/providers/proxies")
            try {
                val providers = JSONObject(c.inputStream.bufferedReader().readText()).optJSONObject("providers") ?: return@runCatching emptyList()
                providers.keys().asSequence().mapNotNull { key ->
                    val p = providers.optJSONObject(key) ?: return@mapNotNull null
                    val s = p.optJSONObject("subscriptionInfo") ?: return@mapNotNull null
                    SubscriptionInfo(
                        name = p.optString("name", key),
                        upload = s.optLong("Upload"),
                        download = s.optLong("Download"),
                        total = s.optLong("Total"),
                        expire = s.optLong("Expire"),
                    )
                }.toList()
            } finally {
                c.disconnect()
            }
        }.getOrDefault(emptyList())
    }

    /** Download / upload bytes since the core started (/connections downloadTotal, uploadTotal). */
    suspend fun clashTotals(api: ClashApi): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        runCatching {
            val c = openApi(api, "/connections")
            try {
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                if (!j.has("downloadTotal")) null else j.optLong("downloadTotal") to j.optLong("uploadTotal")
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    /** Download / upload totals of live connections, skipping those routed through [excluded] chains. */
    suspend fun clashConnectionTotals(api: ClashApi, excluded: Set<String>): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        runCatching {
            val c = openApi(api, "/connections")
            try {
                val arr = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("connections") ?: return@runCatching 0L to 0L
                var down = 0L
                var up = 0L
                for (i in 0 until arr.length()) {
                    val conn = arr.getJSONObject(i)
                    val chains = conn.optJSONArray("chains")
                    val skip = chains != null && (0 until chains.length()).any { chains.optString(it) in excluded }
                    if (!skip) {
                        down += conn.optLong("download")
                        up += conn.optLong("upload")
                    }
                }
                down to up
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    /**
     * Live connections with the app that opened them (metadata.process, the package name on Android; empty
     * when the core does not look processes up) and whether the core sent them DIRECT.
     */
    suspend fun clashConnections(api: ClashApi): List<CoreConnection>? = withContext(Dispatchers.IO) {
        runCatching {
            val c = openApi(api, "/connections")
            try {
                val arr = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("connections") ?: return@runCatching emptyList()
                (0 until arr.length()).map { i ->
                    val conn = arr.getJSONObject(i)
                    val meta = conn.optJSONObject("metadata")
                    val chains = conn.optJSONArray("chains")
                    val first = chains?.optString(0).orEmpty()
                    CoreConnection(
                        id = conn.optString("id"),
                        app = meta?.optString("process")?.takeIf { it.isNotBlank() },
                        uid = meta?.optInt("uid", -1)?.takeIf { it >= 0 },
                        upload = conn.optLong("upload"),
                        download = conn.optLong("download"),
                        direct = first == "DIRECT" || first == "REJECT" || first.isEmpty(),
                    )
                }
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    // ---- Proxy groups ----

    internal fun enc(name: String) = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    /**
     * Proxy groups (in the config's order, taken from GLOBAL) and every proxy with its last delay.
     * Null when the API cannot be reached.
     */
    suspend fun clashProxies(api: ClashApi): ProxySnapshot? = withContext(Dispatchers.IO) {
        runCatching {
            val c = openApi(api, "/proxies")
            try {
                val all = JSONObject(c.inputStream.bufferedReader().readText()).optJSONObject("proxies") ?: return@runCatching null
                val nodes = mutableMapOf<String, ProxyNode>()
                val groups = mutableMapOf<String, ProxyGroup>()
                all.keys().forEach { key ->
                    val p = all.optJSONObject(key) ?: return@forEach
                    val name = p.optString("name", key)
                    val type = p.optString("type")
                    val history = p.optJSONArray("history")
                    val delay = history?.takeIf { it.length() > 0 }?.optJSONObject(history.length() - 1)?.optInt("delay")
                    nodes[name] = ProxyNode(name, type, delay, p.optBoolean("udp"), p.optBoolean("xudp"))
                    val members = p.optJSONArray("all")
                    if (members != null && type in GROUP_TYPES) {
                        groups[name] = ProxyGroup(
                            name,
                            type,
                            p.optString("now").ifBlank { null },
                            (0 until members.length()).map { members.optString(it) },
                            p.optString("icon").takeIf { it.startsWith("http") },
                            p.optBoolean("hidden"),
                        )
                    }
                }
                // Nodes from proxy-providers (subscriptions) are listed under /providers/proxies, not /proxies.
                runCatching {
                    val pc = openApi(api, "/providers/proxies")
                    try {
                        val providers = JSONObject(pc.inputStream.bufferedReader().readText()).optJSONObject("providers")
                        providers?.keys()?.forEach { key ->
                            val list = providers.optJSONObject(key)?.optJSONArray("proxies") ?: return@forEach
                            for (i in 0 until list.length()) {
                                val p = list.optJSONObject(i) ?: continue
                                val name = p.optString("name").ifBlank { continue }
                                if (name in nodes) continue
                                val history = p.optJSONArray("history")
                                val delay = history?.takeIf { it.length() > 0 }?.optJSONObject(history.length() - 1)?.optInt("delay")
                                nodes[name] = ProxyNode(name, p.optString("type"), delay, p.optBoolean("udp"), p.optBoolean("xudp"))
                            }
                        }
                    } finally {
                        pc.disconnect()
                    }
                }
                val order = groups["GLOBAL"]?.all.orEmpty()
                val sorted = groups.values.filter { it.name != "GLOBAL" }
                    .sortedWith(compareBy({ order.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.name }))
                ProxySnapshot(sorted, nodes)
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    /** Picks [name] in the selector [group]. */
    suspend fun clashSelect(api: ClashApi, group: String, name: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val c = openApi(api, "/proxies/${enc(group)}", "PUT")
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(JSONObject().put("name", name).toString().toByteArray()) }
            try {
                c.responseCode in 200..299
            } finally {
                c.disconnect()
            }
        }.getOrDefault(false)
    }

    /** Delay test of every member of [group] at once (mihomo); null when the core has no group test. */
    suspend fun clashGroupDelay(api: ClashApi, group: String): Map<String, Int>? = withContext(Dispatchers.IO) {
        runCatching {
            val c = openApi(api, "/group/${enc(group)}/delay?url=${enc(delayUrl)}&timeout=$delayTimeout", timeout = delayTimeout + 4000)
            try {
                if (c.responseCode !in 200..299) return@runCatching null
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                j.keys().asSequence().associateWith { j.optInt(it) }
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    /** Delay of one proxy in ms; 0 when it timed out, null when the request failed. */
    suspend fun clashProxyDelay(api: ClashApi, name: String): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val c = openApi(api, "/proxies/${enc(name)}/delay?url=${enc(delayUrl)}&timeout=$delayTimeout", timeout = delayTimeout + 2000)
            try {
                if (c.responseCode !in 200..299) 0 else JSONObject(c.inputStream.bufferedReader().readText()).optInt("delay")
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    /**
     * Proxy-providers declared in the config, in the core's order. The core also lists every group as a
     * "Compatible" provider (and "default"); those are left out, as in Zashboard.
     */
    suspend fun clashProviderList(api: ClashApi): List<ProxyProvider>? = withContext(Dispatchers.IO) {
        runCatching {
            val c = openApi(api, "/providers/proxies")
            try {
                val providers = JSONObject(c.inputStream.bufferedReader().readText()).optJSONObject("providers") ?: return@runCatching emptyList()
                providers.keys().asSequence().mapNotNull { key ->
                    val p = providers.optJSONObject(key) ?: return@mapNotNull null
                    val vehicle = p.optString("vehicleType")
                    if (vehicle == "Compatible") return@mapNotNull null
                    val list = p.optJSONArray("proxies")
                    val nodes = (0 until (list?.length() ?: 0)).mapNotNull { i ->
                        val n = list!!.optJSONObject(i) ?: return@mapNotNull null
                        val history = n.optJSONArray("history")
                        val delay = history?.takeIf { it.length() > 0 }?.optJSONObject(history.length() - 1)?.optInt("delay")
                        ProxyNode(n.optString("name"), n.optString("type"), delay, n.optBoolean("udp"), n.optBoolean("xudp"))
                    }
                    val updated = runCatching { java.time.Instant.parse(p.optString("updatedAt")).toEpochMilli() }.getOrNull()
                        ?.takeIf { it > 0 }
                    val sub = p.optJSONObject("subscriptionInfo")?.let { s ->
                        SubscriptionInfo(p.optString("name", key), s.optLong("Upload"), s.optLong("Download"), s.optLong("Total"), s.optLong("Expire"))
                    }
                    ProxyProvider(p.optString("name", key), vehicle, updated, nodes, sub)
                }.toList()
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    /** Pulls the provider [name] again (PUT /providers/proxies/{name}). */
    suspend fun clashUpdateProvider(api: ClashApi, name: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val c = openApi(api, "/providers/proxies/${enc(name)}", "PUT", timeout = 30_000)
            try {
                c.responseCode in 200..299
            } finally {
                c.disconnect()
            }
        }.getOrDefault(false)
    }

    /** Delay test of every node of the provider [name]; the results land in the nodes' history. */
    suspend fun clashCheckProvider(api: ClashApi, name: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val c = openApi(api, "/providers/proxies/${enc(name)}/healthcheck", timeout = delayTimeout + 25_000)
            try {
                c.responseCode in 200..299
            } finally {
                c.disconnect()
            }
        }.getOrDefault(false)
    }

    /** Routing mode of the core: rule, global or direct; null when it cannot be read. */
    suspend fun clashMode(api: ClashApi): String? = withContext(Dispatchers.IO) {
        runCatching {
            val c = openApi(api, "/configs")
            try {
                JSONObject(c.inputStream.bufferedReader().readText()).optString("mode").lowercase().ifBlank { null }
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    /** Switches the routing mode. */
    suspend fun clashSetMode(api: ClashApi, mode: String): Boolean = clashPatchConfigs(api, JSONObject().put("mode", mode))

    /**
     * Changes settings of the running core (PATCH /configs); they last until the core restarts. HttpURLConnection
     * has no PATCH, so the request goes over a plain socket; the API listens on loopback without TLS.
     */
    suspend fun clashPatchConfigs(api: ClashApi, changes: JSONObject): Boolean = clashPatch(api, "/configs", changes)

    /** PATCH [path] with a JSON body over a plain socket (see [clashPatchConfigs]). */
    suspend fun clashPatch(api: ClashApi, path: String, changes: JSONObject): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(api.base)
            val body = changes.toString().toByteArray()
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(url.host, if (url.port > 0) url.port else 80), TIMEOUT)
                s.soTimeout = TIMEOUT
                val head = buildString {
                    append("PATCH $path HTTP/1.1\r\n")
                    append("Host: ${url.host}\r\n")
                    api.secret?.let { append("Authorization: Bearer $it\r\n") }
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ${body.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }
                s.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
                val status = s.getInputStream().bufferedReader().readLine().orEmpty()
                status.split(' ').getOrNull(1)?.toIntOrNull() in 200..299
            }
        }.getOrDefault(false)
    }

    private val GROUP_TYPES = setOf("Selector", "URLTest", "Fallback", "LoadBalance", "Relay", "Smart")
    const val DEFAULT_DELAY_URL = "https://www.gstatic.com/generate_204"
    const val DEFAULT_DELAY_TIMEOUT = 5000

    /** URL and timeout of delay tests (the panel's settings; set from [com.skofqq.boxy.data.Prefs] at start). */
    @Volatile var delayUrl = DEFAULT_DELAY_URL
    @Volatile var delayTimeout = DEFAULT_DELAY_TIMEOUT

    /** Names and URLs of proxy-providers declared in the active Clash config. */
    suspend fun providerUrls(): List<Pair<String, String>> {
        val name = BoxModule.readSetting("name_clash_config") ?: "config.yaml"
        val text = BoxModule.readFile("${BoxModule.BOX_DIR}/clash/$name") ?: return emptyList()
        val result = mutableListOf<Pair<String, String>>()
        var inProviders = false
        var current: String? = null
        text.lines().forEach { raw ->
            val line = raw.substringBefore(" #")
            if (line.isNotBlank() && !line.startsWith(" ") && !line.startsWith("\t")) {
                inProviders = line.trim().startsWith("proxy-providers:")
                current = null
                return@forEach
            }
            if (!inProviders) return@forEach
            val indent = line.length - line.trimStart().length
            val t = line.trim()
            if (indent in 1..4 && t.endsWith(":") && !t.startsWith("-")) current = t.removeSuffix(":").trim('"', '\'')
            if (t.startsWith("url:")) {
                val url = t.removePrefix("url:").trim().trim('"', '\'')
                if (url.startsWith("http")) result += (current ?: url) to url
            }
        }
        return result
    }

    /** PUT /configs?force=true reloads the config of a running mihomo core. */
    suspend fun clashReload(api: ClashApi): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val c = openApi(api, "/configs?force=true", "PUT")
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write("{\"path\":\"\",\"payload\":\"\"}".toByteArray()) }
            try {
                c.responseCode in 200..299
            } finally {
                c.disconnect()
            }
        }.getOrDefault(false)
    }
}

/** Country code to flag emoji ("RU" -> 🇷🇺). */
fun flagEmoji(code: String?): String {
    val c = code?.uppercase()?.takeIf { it.length == 2 && it.all { ch -> ch in 'A'..'Z' } } ?: return ""
    return c.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}

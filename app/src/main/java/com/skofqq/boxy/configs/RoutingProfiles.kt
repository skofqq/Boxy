package com.skofqq.boxy.configs

import android.content.Context
import com.skofqq.boxy.data.GeoBase
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.CheckResult
import com.skofqq.boxy.root.ConfigCheck
import com.skofqq.boxy.root.CoreTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Routing profiles for Xray, in the spirit of Happ's: lists of sites / addresses, each sent to a target, plus the
 * global proxy switch, domain strategy and DNS. A target is "proxy" (the config's catch-all group, "⚡️ Fastest"),
 * "direct" ("🇷🇺 Без VPN"), "block" or any group / outbound of the config, so the panel template's groups stay usable.
 *
 * A profile works over the config Xray runs (a local one or the panel's), either on top of its rules ([Mode.OVER]:
 * the profile's rules come first, the template's stay below) or instead of them ([Mode.REPLACE]: only the template's
 * DNS plumbing stays). The config as it was loaded is kept as the base, so switching profiles or turning them off
 * always starts from it, and every config loaded from the subscription gets the active profile too.
 * Nothing here is Xray-specific except [applyXray] / [match]: an editor for sing-box would add its own pair.
 */
object RoutingProfiles {
    enum class Mode { OVER, REPLACE }

    data class Route(val id: String, val target: String, val entries: List<String>)

    data class Dns(val type: String, val address: String)

    data class Profile(
        val id: String,
        val name: String,
        val mode: Mode = Mode.OVER,
        val globalProxy: Boolean = true,
        val domainStrategy: String? = null,
        val remoteDns: Dns? = null,
        val domesticDns: Dns? = null,
        val fakeDns: Boolean = false,
        val routes: List<Route> = emptyList(),
    )

    data class Store(val active: String?, val profiles: List<Profile>)

    const val PROXY = "proxy"
    const val DIRECT = "direct"
    const val BLOCK = "block"
    private const val RULE_TAG = "boxy-route"
    private const val BOX = BoxModule.BOX_DIR

    /** DNS address presets per type (Xray's syntax). */
    val DNS_PRESETS = mapOf(
        "doh" to listOf("https://cloudflare-dns.com/dns-query" to "Cloudflare", "https://dns.google/dns-query" to "Google", "https://dns.quad9.net/dns-query" to "Quad9", "https://common.dot.dns.yandex.net/dns-query" to "Yandex"),
        "doq" to listOf("quic+local://dns.adguard-dns.com" to "AdGuard", "quic+local://dns.nextdns.io" to "NextDNS"),
        "tcp" to listOf("tcp://1.1.1.1" to "Cloudflare", "tcp://8.8.8.8" to "Google", "tcp://77.88.8.8" to "Yandex"),
        "udp" to listOf("1.1.1.1" to "Cloudflare", "8.8.8.8" to "Google", "9.9.9.9" to "Quad9", "77.88.8.8" to "Yandex"),
    )

    // ------------------------------------------------------------------ storage

    private fun file(context: Context) = File(context.filesDir, "routing_profiles.json")

    fun newId() = java.util.UUID.randomUUID().toString().substring(0, 8)

    fun load(context: Context): Store = runCatching { fromJson(JSONObject(file(context).readText())) }.getOrDefault(Store(null, emptyList()))

    fun save(context: Context, s: Store) {
        file(context).writeText(toJson(s).toString(2))
    }

    fun profileJson(p: Profile): JSONObject = JSONObject()
        .put("id", p.id).put("name", p.name).put("mode", p.mode.name.lowercase()).put("global", p.globalProxy)
        .put("domainStrategy", p.domainStrategy ?: JSONObject.NULL)
        .put("remoteDns", p.remoteDns?.let { JSONObject().put("type", it.type).put("address", it.address) } ?: JSONObject.NULL)
        .put("domesticDns", p.domesticDns?.let { JSONObject().put("type", it.type).put("address", it.address) } ?: JSONObject.NULL)
        .put("fakeDns", p.fakeDns)
        .put("routes", JSONArray(p.routes.map { r -> JSONObject().put("id", r.id).put("target", r.target).put("entries", JSONArray(r.entries)) }))

    fun profileFrom(o: JSONObject): Profile {
        fun dns(x: JSONObject?) = x?.let { Dns(it.optString("type", "doh"), it.optString("address")) }?.takeIf { it.address.isNotBlank() }
        val routes = o.optJSONArray("routes")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { r ->
                val e = r.optJSONArray("entries")
                Route(r.optString("id").ifBlank { newId() }, r.optString("target", PROXY), (0 until (e?.length() ?: 0)).map { e!!.optString(it) }.filter { it.isNotBlank() })
            }
        }.orEmpty()
        return Profile(
            o.optString("id").ifBlank { newId() }, o.optString("name").ifBlank { "Profile" },
            if (o.optString("mode") == "replace") Mode.REPLACE else Mode.OVER, o.optBoolean("global", true),
            o.optString("domainStrategy").takeIf { it.isNotBlank() && it != "null" },
            dns(o.optJSONObject("remoteDns")), dns(o.optJSONObject("domesticDns")), o.optBoolean("fakeDns"), routes,
        )
    }

    private fun toJson(s: Store) = JSONObject().put("active", s.active ?: JSONObject.NULL).put("profiles", JSONArray(s.profiles.map { profileJson(it) }))

    private fun fromJson(o: JSONObject): Store {
        val a = o.optJSONArray("profiles") ?: JSONArray()
        return Store(o.optString("active").takeIf { it.isNotBlank() && it != "null" }, (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { profileFrom(it) })
    }

    /** A profile as text to share (export / import). */
    fun export(p: Profile): String = JSONObject().put("boxyRouting", 1).put("profile", profileJson(p)).toString(2)

    /** A shared profile ([export]'s text); null when it is not one. A new id so it never replaces one by accident. */
    fun import(text: String): Profile? = runCatching {
        val o = JSONObject(text.trim())
        profileFrom(o.optJSONObject("profile") ?: o).copy(id = newId())
    }.getOrNull()

    fun active(context: Context): Profile? = load(context).let { s -> s.profiles.firstOrNull { it.id == s.active } }

    // ------------------------------------------------------------------ the config's targets

    data class Target(val tag: String, val balancer: Boolean)

    /** What a route can be sent to in [cfg]: "proxy", "direct", "block", then the groups and the servers. */
    fun targets(cfg: JSONObject): List<Target> {
        val bal = cfg.optJSONObject("routing")?.optJSONArray("balancers")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("tag") } }.orEmpty()
        val obs = cfg.optJSONArray("outbounds")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            .filter { it.optString("protocol") !in setOf("freedom", "blackhole", "dns") }.map { it.optString("tag") }
        return listOf(Target(PROXY, false), Target(DIRECT, false), Target(BLOCK, false)) + bal.map { Target(it, true) } + obs.filter { it.isNotBlank() }.map { Target(it, false) }
    }

    private fun outbounds(cfg: JSONObject) = cfg.optJSONArray("outbounds")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

    private fun rules(cfg: JSONObject) = cfg.optJSONObject("routing")?.optJSONArray("rules")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

    private val MATCH_KEYS = setOf("domain", "ip", "port", "sourcePort", "network", "source", "user", "inboundTag", "protocol", "attrs", "domains", "localPort", "localIP")

    /** A rule that matches everything (the template's last one, "Остальные сайты"). */
    private fun catchAll(r: JSONObject) = r.keys().asSequence().none { it in MATCH_KEYS && it != "network" }

    /** The DNS plumbing and Boxy's own rules a replacing profile keeps. */
    private fun system(r: JSONObject) = r.has("inboundTag") || r.optString("outboundTag").let { it == "dns-out" } || r.optString("ruleTag").startsWith("boxy-") && !r.optString("ruleTag").startsWith(RULE_TAG)

    private fun directTag(cfg: JSONObject) = outbounds(cfg).firstOrNull { it.optString("protocol") == "freedom" }?.optString("tag")

    /** Where "proxy" goes: the template's catch-all group, else the first group over servers, else the first server. */
    private fun proxyTarget(cfg: JSONObject): Pair<String, Boolean>? {
        rules(cfg).lastOrNull { catchAll(it) && !it.optString("ruleTag").startsWith("boxy-") }?.let { r ->
            r.optString("balancerTag").takeIf { it.isNotBlank() }?.let { return it to true }
            r.optString("outboundTag").takeIf { it.isNotBlank() && it != directTag(cfg) }?.let { return it to false }
        }
        val freedom = outbounds(cfg).filter { it.optString("protocol") == "freedom" }.map { it.optString("tag") }
        cfg.optJSONObject("routing")?.optJSONArray("balancers")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { b ->
                val sel = b.optJSONArray("selector")
                (0 until (sel?.length() ?: 0)).map { sel!!.optString(it) }.any { p -> freedom.none { it.startsWith(p) } }
            }?.optString("tag")?.let { return it to true }
        }
        return outbounds(cfg).firstOrNull { it.optString("protocol") !in setOf("freedom", "blackhole", "dns") }?.optString("tag")?.let { it to false }
    }

    // ------------------------------------------------------------------ entries

    enum class Kind { DOMAIN, IP, PORT, NETWORK }

    private val IPV4 = Regex("^(\\d{1,3})(\\.\\d{1,3}){3}(/\\d{1,2})?$")
    private val IPV6 = Regex("^[0-9a-fA-F:]+:[0-9a-fA-F:]*(/\\d{1,3})?$")
    private val DOMAIN = Regex("^(\\*\\.)?[\\p{L}0-9-]+(\\.[\\p{L}0-9-]+)+$")

    /** What an entry is, or null when Xray would not take it (shown as an error under the field). */
    fun kind(e: String): Kind? {
        val v = e.trim()
        return when {
            v.startsWith("geosite:") || v.startsWith("ext:geosite") || v.startsWith("domain:") || v.startsWith("full:") ||
                v.startsWith("keyword:") || v.startsWith("regexp:") -> if (v.substringAfter(':').isNotBlank()) Kind.DOMAIN else null
            v.startsWith("geoip:") || v.startsWith("ext:geoip") -> Kind.IP
            // "port:443/udp": the port for one network only (QUIC is UDP 443).
            v.startsWith("port:") -> if (Regex("^port:\\d+(-\\d+)?(,\\d+(-\\d+)?)*(/(tcp|udp))?$").matches(v)) Kind.PORT else null
            v.startsWith("network:") -> if (v.substringAfter(':') in setOf("tcp", "udp", "tcp,udp")) Kind.NETWORK else null
            IPV4.matches(v) || IPV6.matches(v) -> Kind.IP
            DOMAIN.matches(v) -> Kind.DOMAIN
            else -> null
        }
    }

    /** An entry as Xray reads it: a bare domain is a domain with its subdomains ("domain:"). */
    private fun xrayValue(e: String): String {
        val v = e.trim()
        return if (DOMAIN.matches(v)) "domain:" + v.removePrefix("*.") else v
    }

    // ------------------------------------------------------------------ applying

    /** Turns [cfg] (the base: the config as loaded) into the profile's routing, in place. Unknown targets are skipped. */
    fun applyXray(cfg: JSONObject, p: Profile): List<String> {
        val skipped = ArrayList<String>()
        val routing = cfg.optJSONObject("routing") ?: JSONObject().also { cfg.put("routing", it) }
        val old = rules(cfg)
        val proxy = proxyTarget(cfg)
        val direct = directTag(cfg) ?: DIRECT.also { cfg.optJSONArray("outbounds")?.put(JSONObject().put("tag", DIRECT).put("protocol", "freedom")) }
        val balancers = cfg.optJSONObject("routing")?.optJSONArray("balancers")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("tag") } }.orEmpty().toSet()
        val obTags = outbounds(cfg).map { it.optString("tag") }.toSet()
        fun block(): String = outbounds(cfg).firstOrNull { it.optString("protocol") == "blackhole" }?.optString("tag")
            ?: BLOCK.also { cfg.optJSONArray("outbounds")?.put(JSONObject().put("tag", BLOCK).put("protocol", "blackhole")) }
        fun point(r: JSONObject, target: String): Boolean {
            when {
                target == PROXY -> { val t = proxy ?: return false; if (t.second) r.put("balancerTag", t.first) else r.put("outboundTag", t.first) }
                target == DIRECT -> r.put("outboundTag", direct)
                target == BLOCK -> r.put("outboundTag", block())
                target in balancers -> r.put("balancerTag", target)
                target in obTags -> r.put("outboundTag", target)
                else -> return false
            }
            return true
        }
        val mine = ArrayList<JSONObject>()
        for (route in p.routes) {
            val byKind = route.entries.mapNotNull { e -> kind(e)?.let { it to xrayValue(e) } }.groupBy({ it.first }, { it.second })
            for ((k, values) in byKind) {
                val r = JSONObject().put("ruleTag", RULE_TAG)
                when (k) {
                    Kind.DOMAIN -> r.put("domain", JSONArray(values))
                    Kind.IP -> r.put("ip", JSONArray(values))
                    Kind.PORT -> {
                        // One rule per network: "port:443/udp" and "port:80" do not share a rule.
                        values.groupBy { it.substringAfter('/', "") }.forEach { (net, vs) ->
                            val pr = JSONObject().put("ruleTag", RULE_TAG).put("port", vs.joinToString(",") { it.removePrefix("port:").substringBefore('/') })
                            if (net.isNotEmpty()) pr.put("network", net)
                            if (point(pr, route.target)) mine += pr
                        }
                        continue
                    }
                    Kind.NETWORK -> r.put("network", values.first().removePrefix("network:"))
                }
                if (point(r, route.target)) mine += r else { skipped += route.target; break }
            }
        }
        val kept = old.filter { !it.optString("ruleTag").startsWith(RULE_TAG) }
        val sys = kept.filter { system(it) }
        val rest = kept.filterNot { system(it) }
        val list = ArrayList<JSONObject>()
        list += sys
        list += mine
        if (p.mode == Mode.OVER) list += rest.filterNot { catchAll(it) } else Unit
        // The last rule: the global proxy switch (on: the catch-all group; off: direct).
        val last = JSONObject().put("ruleTag", RULE_TAG).put("network", "tcp,udp")
        if (p.globalProxy) {
            val tmpl = rest.lastOrNull { catchAll(it) }
            if (tmpl != null) list += tmpl else if (point(last, PROXY)) list += last
        } else {
            last.put("outboundTag", direct)
            list += last
        }
        // Xray wants every ruleTag unique: Boxy's own are numbered.
        var k = 0
        list.forEach { r -> if (r.optString("ruleTag") == RULE_TAG) r.put("ruleTag", "$RULE_TAG-${++k}") }
        routing.put("rules", JSONArray(list))
        p.domainStrategy?.let { routing.put("domainStrategy", it) }
        applyDns(cfg, p)
        return skipped.distinct()
    }

    private fun applyDns(cfg: JSONObject, p: Profile) {
        if (p.remoteDns == null && p.domesticDns == null && !p.fakeDns) return
        val dns = cfg.optJSONObject("dns") ?: JSONObject().also { cfg.put("dns", it) }
        if (p.remoteDns != null || p.domesticDns != null) {
            val servers = JSONArray()
            if (p.fakeDns) servers.put("fakedns")
            p.domesticDns?.let { d ->
                servers.put(JSONObject().put("address", d.address).put("domains", JSONArray(listOf("geosite:category-ru", "domain:ru", "domain:su", "domain:xn--p1ai"))).put("skipFallback", true))
            }
            p.remoteDns?.let { servers.put(it.address) }
            // The template's own servers stay as the fallback when the profile sets only one side.
            if (p.remoteDns == null) dns.optJSONArray("servers")?.let { a -> for (i in 0 until a.length()) servers.put(a.get(i)) }
            dns.put("servers", servers)
        } else if (p.fakeDns) {
            val a = dns.optJSONArray("servers") ?: JSONArray()
            dns.put("servers", JSONArray(listOf<Any>("fakedns") + (0 until a.length()).map { a.get(it) }))
        }
        if (p.fakeDns) {
            cfg.put("fakedns", JSONArray().put(JSONObject().put("ipPool", "198.18.0.0/15").put("poolSize", 65535)))
            cfg.optJSONArray("inbounds")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("protocol") in setOf("dokodemo-door", "tunnel") }.forEach { ib ->
                    val sn = ib.optJSONObject("sniffing") ?: JSONObject().also { ib.put("sniffing", it) }
                    sn.put("enabled", true)
                    val d = sn.optJSONArray("destOverride")?.let { x -> (0 until x.length()).map { x.optString(it) } }.orEmpty().toMutableList()
                    if ("fakedns" !in d) d += "fakedns"
                    sn.put("destOverride", JSONArray(d.ifEmpty { listOf("http", "tls", "fakedns") }))
                }
            }
        }
    }

    // ------------------------------------------------------------------ the base and rebuilding

    private fun baseDir(context: Context) = File(context.filesDir, "routing-base").apply { mkdirs() }
    private fun sha(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun norm(text: String) = runCatching { JSONObject(text).toString() }.getOrNull()

    /** Remembers [base] as what [path] was before Boxy's changes, and [written] as what Boxy wrote there. */
    fun remember(context: Context, path: String, base: String, written: String) {
        val key = sha(path)
        File(baseDir(context), "$key.json").writeText(base)
        File(baseDir(context), "$key.sha").writeText(sha(norm(written) ?: written))
    }

    /** [path]'s base: the saved one while the file is still what Boxy wrote, else the file itself (it changed). */
    private fun base(context: Context, path: String, current: String): String {
        val key = sha(path)
        val saved = File(baseDir(context), "$key.json")
        val stamp = File(baseDir(context), "$key.sha")
        val same = stamp.isFile && stamp.readText() == sha(norm(current) ?: current)
        return if (same && saved.isFile) saved.readText() else current
    }

    /** Boxy's settings and the active profile over a config being installed (the base is the config as loaded). */
    fun applyAll(context: Context, cfg: JSONObject): List<String> {
        CoreSettings.applyXrayStored(context, cfg)
        return active(context)?.let { applyXray(cfg, it) }.orEmpty()
    }

    data class Outcome(val ok: Boolean, val error: String? = null, val skipped: List<String> = emptyList())

    /**
     * Rebuilds the active Xray config from its base with Boxy's settings and the active profile (none: the base as
     * loaded), checks it with Xray (the file comes back when rejected) and restarts Xray when it runs.
     */
    suspend fun rebuildXray(context: Context): Outcome = withContext(Dispatchers.IO) {
        val path = CoreTools.activeConfig("xray")
        val current = BoxModule.readFile(path) ?: return@withContext Outcome(false, path)
        if (current.split("\n").any { it.trimStart().startsWith("//") }) return@withContext Outcome(false, "comments")
        val baseText = base(context, path, current)
        val cfg = runCatching { JSONObject(baseText) }.getOrNull() ?: return@withContext Outcome(false, "JSON")
        val skipped = applyAll(context, cfg)
        val out = GeoRules.pretty(cfg)
        if (!BoxModule.writeFile(path, out)) return@withContext Outcome(false, "write")
        val check = ConfigCheck.check("xray", path)
        if (check is CheckResult.Failed) {
            // The rejected file is kept next to it for a look in Files.
            BoxModule.writeFile("$path.rejected", out)
            BoxModule.writeFile(path, current)
            return@withContext Outcome(false, check.output)
        }
        BoxModule.exec("rm -f '$path.rejected'")
        remember(context, path, baseText, out)
        val st = BoxModule.state()
        if (st.running && BoxModule.unquote(st.core) == "xray") BoxModule.restart()
        Outcome(true, skipped = skipped)
    }

    /** The active config's base (for the editor's targets and the preview). */
    suspend fun baseConfig(context: Context): JSONObject? = withContext(Dispatchers.IO) {
        val path = CoreTools.activeConfig("xray")
        val current = BoxModule.readFile(path) ?: return@withContext null
        runCatching { JSONObject(base(context, path, current)) }.getOrNull()
    }

    // ------------------------------------------------------------------ "Основной" from the template

    /** A profile with the template's own routing (its groups and lists), replacing it, to edit. */
    fun fromTemplate(cfg: JSONObject, name: String): Profile {
        val direct = directTag(cfg)
        val block = outbounds(cfg).firstOrNull { it.optString("protocol") == "blackhole" }?.optString("tag")
        val proxy = proxyTarget(cfg)?.first
        val byTarget = LinkedHashMap<String, MutableList<String>>()
        for (r in rules(cfg)) {
            if (system(r) || catchAll(r) || r.optString("ruleTag").startsWith(RULE_TAG)) continue
            val t = r.optString("balancerTag").ifBlank { r.optString("outboundTag") }
            val target = when (t) { direct -> DIRECT; block -> BLOCK; proxy -> PROXY; else -> t }
            if (target.isBlank()) continue
            val list = byTarget.getOrPut(target) { mutableListOf() }
            r.optJSONArray("domain")?.let { a -> for (i in 0 until a.length()) list += a.optString(i) }
            r.optJSONArray("ip")?.let { a -> for (i in 0 until a.length()) list += a.optString(i) }
            r.opt("port")?.toString()?.takeIf { it.isNotBlank() && !r.has("domain") && !r.has("ip") }?.let { port ->
                val net = r.optString("network").takeIf { it == "tcp" || it == "udp" }
                list += "port:$port" + (net?.let { "/$it" } ?: "")
            }
        }
        val routes = byTarget.filterValues { it.isNotEmpty() }.map { (t, e) -> Route(newId(), t, e.distinct()) }
        return Profile(newId(), name, Mode.REPLACE, globalProxy = true, routes = routes)
    }

    // ------------------------------------------------------------------ "where does it go"

    data class Match(val target: String, val rule: Int, val by: String?)

    private val siteCache = HashMap<String, GeoBase.DomainSet?>()
    private val ipCache = HashMap<String, List<String>?>()

    private fun geoFile(value: String, site: Boolean): Pair<String, String> {
        val kind = if (site) "geosite" else "geoip"
        return if (value.startsWith("ext:")) {
            val parts = value.removePrefix("ext:").split(':')
            "$BOX/xray/${parts[0]}" to parts.getOrElse(1) { "" }
        } else "$BOX/xray/$kind.dat" to value.substringAfter(':')
    }

    private fun domainMatch(host: String, v: String): Boolean = when {
        v.startsWith("full:") -> host == v.substringAfter(':')
        v.startsWith("domain:") -> v.substringAfter(':').let { host == it || host.endsWith(".$it") }
        v.startsWith("keyword:") -> v.substringAfter(':') in host
        v.startsWith("regexp:") -> runCatching { Regex(v.substringAfter(':')).containsMatchIn(host) }.getOrDefault(false)
        v.startsWith("geosite:") || v.startsWith("ext:geosite") -> {
            val (path, code) = geoFile(v, true)
            val name = code.substringBefore('@').lowercase()
            val set = siteCache.getOrPut("$path|$name") { runCatching { GeoBase.extractSite(path, name) }.getOrNull() }
            set != null && (host in set.full || set.suffix.any { host == it || host.endsWith(".$it") } || set.keyword.any { it in host } ||
                set.regex.any { r -> runCatching { Regex(r).containsMatchIn(host) }.getOrDefault(false) })
        }
        else -> host == v || host.endsWith(".$v")
    }

    private fun ipIn(ip: String, cidr: String): Boolean = runCatching {
        val (net, bits) = cidr.split('/').let { it[0] to (it.getOrNull(1)?.toInt() ?: if (':' in it[0]) 128 else 32) }
        val a = java.net.InetAddress.getByName(ip).address
        val b = java.net.InetAddress.getByName(net).address
        if (a.size != b.size) return false
        var left = bits
        for (i in a.indices) {
            if (left <= 0) break
            val m = if (left >= 8) 0xFF else (0xFF shl (8 - left)) and 0xFF
            if ((a[i].toInt() and m) != (b[i].toInt() and m)) return false
            left -= 8
        }
        true
    }.getOrDefault(false)

    private fun ipMatch(ip: String, v: String): Boolean = if (v.startsWith("geoip:") || v.startsWith("ext:geoip")) {
        val (path, code) = geoFile(v, false)
        val list = ipCache.getOrPut("$path|$code") { runCatching { GeoBase.extractIp(path, code.lowercase()) }.getOrNull() }
        list?.any { ipIn(ip, it) } == true
    } else ipIn(ip, if ('/' in v) v else v + if (':' in v) "/128" else "/32")

    /**
     * Where [input] (a domain or an IP, port 443) goes with [cfg]'s rules: the first rule that matches, as Xray
     * reads them top down (domain rules by the name, IP rules by an IP typed in; nothing is resolved here).
     */
    suspend fun match(cfg: JSONObject, input: String): Match? = withContext(Dispatchers.IO) {
        val host = input.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':').ifBlank { return@withContext null }
        val isIp = IPV4.matches(host) || IPV6.matches(host)
        rules(cfg).forEachIndexed { i, r ->
            if (r.has("inboundTag") || r.optString("outboundTag") == "dns-out") return@forEachIndexed
            r.opt("port")?.toString()?.takeIf { it.isNotBlank() }?.let { p ->
                val ok = p.split(',').any { s -> s.trim().let { x -> if ('-' in x) x.substringBefore('-').toInt() <= 443 && 443 <= x.substringAfter('-').toInt() else x == "443" } }
                if (!ok) return@forEachIndexed
            }
            if (r.optString("network").let { it.isNotBlank() && "tcp" !in it }) return@forEachIndexed
            val target = r.optString("balancerTag").ifBlank { r.optString("outboundTag") }
            val domains = r.optJSONArray("domain")
            val ips = r.optJSONArray("ip")
            when {
                domains != null -> if (!isIp) (0 until domains.length()).map { domains.optString(it) }.firstOrNull { domainMatch(host, it) }?.let { return@withContext Match(target, i, it) }
                ips != null -> if (isIp) (0 until ips.length()).map { ips.optString(it) }.firstOrNull { ipMatch(host, it) }?.let { return@withContext Match(target, i, it) }
                catchAll(r) -> return@withContext Match(target, i, null)
            }
        }
        null
    }
}

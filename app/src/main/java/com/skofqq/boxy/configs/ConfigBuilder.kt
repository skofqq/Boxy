package com.skofqq.boxy.configs

import android.content.Context
import com.skofqq.boxy.net.SubClient
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.CheckResult
import com.skofqq.boxy.root.ConfigCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * "Create your own config": a complete Xray config made of servers (share links from a subscription URL or pasted)
 * and groups the user defines, for anyone without a panel template. The routing comes from the active routing
 * profile (RoutingProfiles), which targets these groups by name; without one, everything goes through the first
 * group. The inbound matches the module (tproxy on its port), DNS and server checks are set up.
 * The plan is kept in the app (builder.json) so the config can be rebuilt after the servers change.
 * Only Xray for now; a sing-box writer would take the same [Plan].
 */
object ConfigBuilder {
    /** A group: [members] are server tags; empty = every server. */
    data class Group(val name: String, val members: List<String> = emptyList(), val strategy: String = "leastPing")

    data class Plan(
        val name: String,
        /** A subscription URL, or empty when [links] holds pasted share links. */
        val url: String = "",
        val links: String = "",
        val groups: List<Group> = emptyList(),
    )

    data class Servers(val outbounds: List<JSONObject>, val skipped: Int, val error: String? = null)

    private fun file(context: Context) = File(context.filesDir, "builder.json")

    fun load(context: Context): Plan? = runCatching {
        val o = JSONObject(file(context).readText())
        val g = o.optJSONArray("groups") ?: JSONArray()
        Plan(
            o.optString("name", "my-config"), o.optString("url"), o.optString("links"),
            (0 until g.length()).mapNotNull { g.optJSONObject(it) }.map { x ->
                val m = x.optJSONArray("members") ?: JSONArray()
                Group(x.optString("name"), (0 until m.length()).map { m.optString(it) }, x.optString("strategy", "leastPing"))
            },
        )
    }.getOrNull()

    fun save(context: Context, p: Plan) {
        file(context).writeText(
            JSONObject().put("name", p.name).put("url", p.url).put("links", p.links)
                .put("groups", JSONArray(p.groups.map { JSONObject().put("name", it.name).put("members", JSONArray(it.members)).put("strategy", it.strategy) }))
                .toString(2),
        )
    }

    /** The servers of [p]: downloaded from its URL (as share links) or parsed from the pasted links. */
    suspend fun servers(context: Context, p: Plan): Servers = withContext(Dispatchers.IO) {
        val text = if (p.url.isNotBlank()) {
            val r = SubClient.fetch(context, p.url.trim(), SubClient.Format.LINKS)
            if (r == null || !r.ok) return@withContext Servers(emptyList(), 0, "download")
            r.body.orEmpty()
        } else p.links
        val conv = ShareLinks.convert(text, "xray", "")
        Servers(conv.outbounds, conv.skipped.size, if (conv.outbounds.isEmpty()) "empty" else null)
    }

    /** File name of the config: letters, digits, dashes. */
    fun fileName(p: Plan) = "profiles/" + p.name.trim().lowercase().replace(Regex("[^a-z0-9а-яё_-]+"), "-").trim('-').ifBlank { "my-config" } + ".json"

    /** The whole Xray config for [p] with [servers]. */
    fun build(p: Plan, servers: List<JSONObject>, tproxyPort: Int): JSONObject {
        val tags = servers.map { it.optString("tag") }
        val groups = p.groups.ifEmpty { listOf(Group("⚡️ Fastest")) }
        val balancers = JSONArray()
        groups.forEach { g ->
            val members = g.members.filter { it in tags }.ifEmpty { tags }
            balancers.put(
                JSONObject().put("tag", g.name).put("selector", JSONArray(members))
                    .put("strategy", JSONObject().put("type", g.strategy).apply {
                        if (g.strategy == "leastLoad" || g.strategy == "leastPing") put("settings", JSONObject().put("maxRTT", "3s"))
                    })
                    .put("fallbackTag", members.first()),
            )
        }
        val outbounds = JSONArray()
        servers.forEach { outbounds.put(it) }
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        outbounds.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        outbounds.put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))
        val rules = JSONArray()
            .put(JSONObject().put("inboundTag", JSONArray(listOf("tproxy-in"))).put("port", 53).put("outboundTag", "dns-out"))
            .put(JSONObject().put("ip", JSONArray(listOf("geoip:private"))).put("outboundTag", "direct"))
            // Everything else: the first group (a routing profile puts its lists above this).
            .put(JSONObject().put("network", "tcp,udp").put("balancerTag", groups.first().name))
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("dns", JSONObject().put("servers", JSONArray(listOf("https://1.1.1.1/dns-query", "8.8.8.8"))).put("queryStrategy", "UseIPv4"))
            .put(
                "inbounds",
                JSONArray().put(
                    JSONObject().put("tag", "tproxy-in").put("port", tproxyPort).put("protocol", "dokodemo-door")
                        .put("settings", JSONObject().put("followRedirect", true).put("network", "tcp,udp"))
                        .put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray(listOf("http", "tls"))).put("routeOnly", true))
                        .put("streamSettings", JSONObject().put("sockopt", JSONObject().put("tproxy", "tproxy"))),
                ),
            )
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("balancers", balancers).put("rules", rules))
            .put(
                "burstObservatory",
                JSONObject().put("subjectSelector", JSONArray(tags))
                    .put("pingConfig", JSONObject().put("destination", "http://www.gstatic.com/generate_204").put("interval", "5m").put("sampling", 2).put("timeout", "5s")),
            )
    }

    data class Outcome(val ok: Boolean, val servers: Int = 0, val skipped: Int = 0, val path: String? = null, val error: String? = null)

    /**
     * Builds and installs [p]: the servers, the groups, Boxy's settings and the active routing profile over it, checked
     * by Xray (nothing is changed when it is rejected), then made the active Xray config. Xray restarts when it runs.
     */
    suspend fun create(context: Context, p: Plan): Outcome = withContext(Dispatchers.IO) {
        save(context, p)
        val s = servers(context, p)
        if (s.error != null) return@withContext Outcome(false, error = s.error)
        val port = BoxModule.unquote(BoxModule.readSetting("tproxy_port"))?.toIntOrNull() ?: 9898
        val cfg = build(p, s.outbounds, port)
        val baseText = GeoRules.pretty(cfg)
        RoutingProfiles.applyAll(context, cfg)
        val rel = fileName(p)
        val path = "${BoxModule.BOX_DIR}/xray/$rel"
        val old = BoxModule.readFile(path)
        val written = GeoRules.pretty(cfg)
        BoxModule.exec("mkdir -p '${BoxModule.BOX_DIR}/xray/profiles'")
        if (!BoxModule.writeFile(path, written)) return@withContext Outcome(false, error = "write")
        val check = ConfigCheck.check("xray", path)
        if (check is CheckResult.Failed) {
            if (old != null) BoxModule.writeFile(path, old) else BoxModule.exec("rm -f '$path'")
            return@withContext Outcome(false, error = check.output)
        }
        RoutingProfiles.remember(context, path, baseText, written)
        BoxModule.writeSetting("name_xray_config", rel)
        val st = BoxModule.state()
        if (st.running && BoxModule.unquote(st.core) == "xray") BoxModule.restart()
        Outcome(true, s.outbounds.size, s.skipped, path)
    }
}

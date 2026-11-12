package com.skofqq.boxy.configs

import android.net.Uri
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.common.FlowStyle

/**
 * Hysteria 2 client config as the module runs it: the server part (server, auth, tls, obfs, port hopping,
 * bandwidth) that profiles, links and the form edit, and the module's own blocks (tcpTProxy / udpTProxy /
 * tcpRedirect, socks5 / http) that are kept as they are.
 */
object HysteriaConfig {
    data class Client(
        val server: String = "",
        val auth: String = "",
        val sni: String = "",
        val insecure: Boolean = false,
        val pinSha256: String = "",
        val obfsPassword: String = "",
        val hopInterval: String = "",
        val up: String = "",
        val down: String = "",
    )

    data class Problem(val error: Boolean, val message: Int, val arg: String = "")

    /** Keys of the server part, written in this order at the top of the file. */
    private val CLIENT_KEYS = listOf("server", "auth", "tls", "obfs", "transport", "bandwidth")

    // ---- links ----

    /**
     * hysteria2://auth@host:port[,ports]/?sni=…&insecure=1&obfs=salamander&obfs-password=…&pinSHA256=…#name
     * (also hy2://). Returns the name (fragment) and the server part.
     */
    fun parseLink(link: String): Pair<String?, Client>? {
        val t = link.trim()
        val scheme = t.substringBefore("://").lowercase()
        if (scheme != "hysteria2" && scheme != "hy2") return null
        // The authority may carry a port list ("443,20000-30000"), which Uri does not parse as a port.
        val rest = t.substringAfter("://")
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        val auth = if ('@' in authority) Uri.decode(authority.substringBeforeLast('@')) else ""
        val hostPorts = authority.substringAfterLast('@')
        if (hostPorts.isBlank()) return null
        val server = if (hostPorts.startsWith("[") || hostPorts.count { it == ':' } == 1) hostPorts else "$hostPorts:443"
        val u = Uri.parse("http://x/?" + rest.substringAfter('?', "").substringBefore('#'))
        fun q(k: String) = u.getQueryParameter(k)?.takeIf { it.isNotBlank() }
        val name = rest.substringAfter('#', "").takeIf { it.isNotBlank() }?.let { Uri.decode(it) }
        return name to Client(
            server = server,
            auth = auth,
            sni = q("sni").orEmpty(),
            insecure = q("insecure") == "1" || q("insecure") == "true",
            pinSha256 = q("pinSHA256").orEmpty(),
            obfsPassword = if (q("obfs") == "salamander") q("obfs-password").orEmpty() else "",
            hopInterval = q("hopInterval").orEmpty(),
        )
    }

    // ---- YAML ----

    @Suppress("UNCHECKED_CAST")
    private fun load(text: String): MutableMap<String, Any?> {
        val o = Load(LoadSettings.builder().build()).loadFromString(text.ifBlank { "{}" })
        return LinkedHashMap((o as? Map<String, Any?>) ?: emptyMap())
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.map(k: String): Map<String, Any?>? = this[k] as? Map<String, Any?>

    private fun Any?.str(): String = this?.toString().orEmpty()

    fun read(text: String): Client {
        val m = runCatching { load(text) }.getOrDefault(LinkedHashMap())
        val tls = m.map("tls")
        val obfs = m.map("obfs")
        return Client(
            server = m["server"].str(),
            auth = m["auth"].str(),
            sni = tls?.get("sni").str(),
            insecure = tls?.get("insecure") == true,
            pinSha256 = tls?.get("pinSHA256").str(),
            obfsPassword = if (obfs?.get("type").str() == "salamander") obfs?.map("salamander")?.get("password").str() else "",
            hopInterval = m.map("transport")?.map("udp")?.get("hopInterval").str(),
            up = m.map("bandwidth")?.get("up").str(),
            down = m.map("bandwidth")?.get("down").str(),
        )
    }

    /**
     * [template] with its server part replaced by [c]; everything else (the module's tproxy / redirect blocks,
     * socks5, http, lazy…) is kept, and the module's blocks are added when the template has none.
     */
    fun write(template: String, c: Client, tproxyPort: String = "9898", redirPort: String = "9797"): String {
        val old = runCatching { load(template) }.getOrDefault(LinkedHashMap())
        val out = LinkedHashMap<String, Any?>()
        out["server"] = c.server.trim()
        if (c.auth.isNotBlank()) out["auth"] = c.auth
        val tls = LinkedHashMap<String, Any?>()
        if (c.sni.isNotBlank()) tls["sni"] = c.sni.trim()
        if (c.insecure) tls["insecure"] = true
        if (c.pinSha256.isNotBlank()) tls["pinSHA256"] = c.pinSha256.trim()
        if (tls.isNotEmpty()) out["tls"] = tls
        if (c.obfsPassword.isNotBlank()) out["obfs"] = linkedMapOf("type" to "salamander", "salamander" to linkedMapOf("password" to c.obfsPassword))
        if (c.hopInterval.isNotBlank()) out["transport"] = linkedMapOf("udp" to linkedMapOf("hopInterval" to c.hopInterval.trim()))
        if (c.up.isNotBlank() || c.down.isNotBlank()) {
            out["bandwidth"] = LinkedHashMap<String, Any?>().apply {
                if (c.up.isNotBlank()) put("up", c.up.trim())
                if (c.down.isNotBlank()) put("down", c.down.trim())
            }
        }
        old.forEach { (k, v) -> if (k !in CLIENT_KEYS) out[k] = v }
        if (!out.containsKey("tcpTProxy")) out["tcpTProxy"] = linkedMapOf("listen" to ":$tproxyPort")
        if (!out.containsKey("udpTProxy")) out["udpTProxy"] = linkedMapOf("listen" to ":$tproxyPort", "timeout" to "20s")
        if (!out.containsKey("tcpRedirect")) out["tcpRedirect"] = linkedMapOf("listen" to ":$redirPort")
        return Dump(DumpSettings.builder().setDefaultFlowStyle(FlowStyle.BLOCK).setIndent(2).build()).dumpToString(out)
    }

    // ---- check (H3) ----

    private val BANDWIDTH = Regex("^\\d+(\\.\\d+)?\\s*([kmgt]?bps|[kmgt]?b|[kmgt])?$", RegexOption.IGNORE_CASE)

    /** What hysteria (and the module) would trip over; errors stop the start, the rest are warnings. */
    fun check(text: String, networkMode: String?): List<Problem> {
        val m = try {
            load(text)
        } catch (e: Exception) {
            return listOf(Problem(true, com.skofqq.boxy.R.string.hy_check_yaml, e.message.orEmpty().lineSequence().firstOrNull().orEmpty()))
        }
        val out = ArrayList<Problem>()
        val server = m["server"].str()
        if (server.isBlank()) out.add(Problem(true, com.skofqq.boxy.R.string.hy_check_server))
        else if (!Regex("^(\\[[^]]+]|[^:\\s]+)(:[0-9,\\-]+)?$").matches(server)) out.add(Problem(true, com.skofqq.boxy.R.string.hy_check_server_format, server))
        if (m["auth"].str().isBlank()) out.add(Problem(false, com.skofqq.boxy.R.string.hy_check_auth))
        val obfs = m.map("obfs")
        if (obfs != null && obfs["type"].str() == "salamander" && obfs.map("salamander")?.get("password").str().toByteArray().size < 4) {
            // Hysteria refuses a Salamander password shorter than 4 bytes ("PSK must be at least 4 bytes").
            out.add(Problem(true, com.skofqq.boxy.R.string.hy_check_obfs))
        }
        m.map("transport")?.map("udp")?.get("hopInterval")?.str()?.let { v ->
            if (v.isNotBlank() && !Regex("^\\d+(\\.\\d+)?(ns|us|ms|s|m|h)$").matches(v.trim())) out.add(Problem(true, com.skofqq.boxy.R.string.hy_check_duration, v))
        }
        m.map("bandwidth")?.let { bw ->
            listOf("up", "down").forEach { k -> bw[k]?.str()?.let { v -> if (v.isNotBlank() && !BANDWIDTH.matches(v.trim())) out.add(Problem(true, com.skofqq.boxy.R.string.hy_check_bandwidth, "$k: $v")) } }
        }
        // The module points iptables at these listeners and syncs their ports at start.
        when (networkMode ?: "tproxy") {
            "redirect" -> if (m.map("tcpRedirect") == null) out.add(Problem(true, com.skofqq.boxy.R.string.hy_check_block, "tcpRedirect"))
            "enhance" -> {
                if (m.map("tcpRedirect") == null) out.add(Problem(true, com.skofqq.boxy.R.string.hy_check_block, "tcpRedirect"))
                if (m.map("udpTProxy") == null) out.add(Problem(true, com.skofqq.boxy.R.string.hy_check_block, "udpTProxy"))
            }
            else -> {
                if (m.map("tcpTProxy") == null) out.add(Problem(true, com.skofqq.boxy.R.string.hy_check_block, "tcpTProxy"))
                if (m.map("udpTProxy") == null) out.add(Problem(true, com.skofqq.boxy.R.string.hy_check_block, "udpTProxy"))
            }
        }
        return out
    }

    /** Speedtest result lines ("download complete … rate 123.4 Mbps") as up / down values for bandwidth. */
    fun parseSpeed(lines: List<String>): Pair<String?, String?> {
        fun find(dir: String): String? {
            val l = lines.lastOrNull { it.contains(dir, ignoreCase = true) && Regex("[0-9.]+\\s*[KMGT]?bps", RegexOption.IGNORE_CASE).containsMatchIn(it) } ?: return null
            val m = Regex("([0-9.]+)\\s*([KMGT]?)bps", RegexOption.IGNORE_CASE).findAll(l).lastOrNull() ?: return null
            val value = m.groupValues[1].toDoubleOrNull() ?: return null
            val mbps = when (m.groupValues[2].uppercase()) {
                "K" -> value / 1000
                "G" -> value * 1000
                "T" -> value * 1_000_000
                "" -> value / 1_000_000
                else -> value
            }
            return "${mbps.toInt().coerceAtLeast(1)} mbps"
        }
        return find("upload") to find("download")
    }
}

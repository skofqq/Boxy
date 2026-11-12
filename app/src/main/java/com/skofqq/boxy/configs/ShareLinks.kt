package com.skofqq.boxy.configs

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Share links (vless / vmess / trojan / ss / hysteria2) as Xray outbounds, for subscriptions of Xray and V2Ray.
 * A subscription body is base64 or plain text, one link per line. Links the target core cannot use are skipped
 * and counted: V2Ray has no REALITY, XTLS flow, XHTTP or Hysteria; Xray has no Salamander obfuscation.
 */
object ShareLinks {
    data class Result(val outbounds: List<JSONObject>, val skipped: Int)

    const val TAG_PREFIX = "sub-"

    /** The links of a subscription body (base64-encoded or plain). */
    fun links(body: String): List<String> {
        val text = body.trim()
        val plain = if (Regex("^[A-Za-z0-9+/=_\\-\\s]+$").matches(text) && !text.contains("://")) decode(text) ?: text else text
        return plain.lines().map { it.trim() }.filter { it.contains("://") }
    }

    /** Base64 in either alphabet, padded or not. */
    private fun decode(s: String): String? = runCatching {
        val clean = s.replace(Regex("\\s"), "").replace('-', '+').replace('_', '/').trimEnd('=')
        String(Base64.decode(clean + "=".repeat((4 - clean.length % 4) % 4), Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()

    fun convert(body: String, core: String): Result {
        val out = ArrayList<JSONObject>()
        var skipped = 0
        val used = HashSet<String>()
        for (link in links(body)) {
            val o = runCatching { outbound(link, core) }.getOrNull()
            if (o == null) { skipped++; continue }
            // Unique tags: the name from the link, numbered when it repeats.
            val base = TAG_PREFIX + (o.optString("tag").ifBlank { "node" })
            var tag = base
            var n = 2
            while (!used.add(tag)) tag = "$base-${n++}"
            o.put("tag", tag)
            out.add(o)
        }
        return Result(out, skipped)
    }

    private fun name(fragment: String?, host: String) =
        (fragment?.takeIf { it.isNotBlank() } ?: host).replace(Regex("[\\s\"\\\\]+"), "_").take(48)

    private fun outbound(link: String, core: String): JSONObject? {
        val scheme = link.substringBefore("://").lowercase()
        return when (scheme) {
            "vmess" -> vmess(link.substringAfter("://"), core)
            "vless" -> vless(Uri.parse(link), core)
            "trojan" -> trojan(Uri.parse(link), core)
            "ss" -> shadowsocks(link)
            "hysteria2", "hy2" -> if (core == "xray") hysteria2(Uri.parse(link)) else null
            else -> null
        }
    }

    private fun q(u: Uri, k: String) = u.getQueryParameter(k)?.takeIf { it.isNotBlank() }

    /** streamSettings from the common query parameters (type, security, sni, fp, alpn, pbk, sid, spx, path, host...). */
    private fun stream(net0: String?, sec0: String?, p: (String) -> String?, core: String): JSONObject? {
        val net = when (net0?.lowercase()) {
            null, "", "tcp", "raw" -> "tcp"
            "h2", "http" -> "http"
            "httpupgrade" -> "httpupgrade"
            "splithttp", "xhttp" -> "xhttp"
            else -> net0.lowercase()
        }
        val sec = sec0?.lowercase()?.takeIf { it.isNotBlank() } ?: "none"
        if (core != "xray" && (sec == "reality" || net == "xhttp")) return null
        val s = JSONObject().put("network", net).put("security", sec)
        val sni = p("sni") ?: p("peer")
        when (sec) {
            "tls" -> s.put(
                "tlsSettings",
                JSONObject().apply {
                    sni?.let { put("serverName", it) }
                    p("fp")?.let { if (core == "xray") put("fingerprint", it) }
                    p("alpn")?.let { put("alpn", JSONArray(it.split(','))) }
                },
            )
            "reality" -> s.put(
                "realitySettings",
                JSONObject().apply {
                    sni?.let { put("serverName", it) }
                    put("fingerprint", p("fp") ?: "chrome")
                    put("publicKey", p("pbk") ?: return null)
                    p("sid")?.let { put("shortId", it) }
                    p("spx")?.let { put("spiderX", it) }
                },
            )
        }
        val path = p("path")
        val host = p("host")
        when (net) {
            "ws" -> s.put("wsSettings", JSONObject().apply { path?.let { put("path", it) }; host?.let { put("host", it) } })
            "httpupgrade" -> s.put("httpupgradeSettings", JSONObject().apply { path?.let { put("path", it) }; host?.let { put("host", it) } })
            "xhttp" -> s.put("xhttpSettings", JSONObject().apply { path?.let { put("path", it) }; host?.let { put("host", it) }; p("mode")?.let { put("mode", it) } })
            "grpc" -> s.put("grpcSettings", JSONObject().put("serviceName", p("serviceName") ?: p("path") ?: "").put("multiMode", p("mode") == "multi"))
            "http" -> s.put("httpSettings", JSONObject().apply { path?.let { put("path", it) }; host?.let { put("host", JSONArray(it.split(','))) } })
            "tcp" -> if (p("headerType") == "http") {
                s.put(
                    "tcpSettings",
                    JSONObject().put(
                        "header",
                        JSONObject().put("type", "http").put(
                            "request",
                            JSONObject().put("path", JSONArray().put(path ?: "/")).apply { host?.let { put("headers", JSONObject().put("Host", JSONArray(it.split(',')))) } },
                        ),
                    ),
                )
            }
        }
        return s
    }

    private fun vless(u: Uri, core: String): JSONObject? {
        val host = u.host ?: return null
        val flow = q(u, "flow")
        if (core != "xray" && flow != null) return null
        val user = JSONObject().put("id", u.userInfo ?: return null).put("encryption", q(u, "encryption") ?: "none")
        flow?.let { user.put("flow", it) }
        val o = JSONObject().put("tag", name(u.fragment, host)).put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", host).put("port", u.port).put("users", JSONArray().put(user)))))
        o.put("streamSettings", stream(q(u, "type"), q(u, "security"), { q(u, it) }, core) ?: return null)
        return o
    }

    private fun trojan(u: Uri, core: String): JSONObject? {
        val host = u.host ?: return null
        val o = JSONObject().put("tag", name(u.fragment, host)).put("protocol", "trojan")
            .put("settings", JSONObject().put("servers", JSONArray().put(JSONObject().put("address", host).put("port", u.port).put("password", Uri.decode(u.userInfo ?: return null)))))
        // Trojan is TLS unless the link says otherwise.
        o.put("streamSettings", stream(q(u, "type"), q(u, "security") ?: "tls", { q(u, it) }, core) ?: return null)
        return o
    }

    private fun vmess(b64: String, core: String): JSONObject? {
        val j = JSONObject(decode(b64.substringBefore('#')) ?: return null)
        val host = j.optString("add").ifBlank { return null }
        val port = j.optString("port").toIntOrNull() ?: j.optInt("port")
        val user = JSONObject().put("id", j.optString("id")).put("security", j.optString("scy").ifBlank { "auto" })
        j.optString("aid").toIntOrNull()?.takeIf { it > 0 }?.let { user.put("alterId", it) }
        val o = JSONObject().put("tag", name(j.optString("ps"), host)).put("protocol", "vmess")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", host).put("port", port).put("users", JSONArray().put(user)))))
        val params = mapOf(
            "sni" to j.optString("sni"), "fp" to j.optString("fp"), "alpn" to j.optString("alpn"),
            "path" to j.optString("path"), "host" to j.optString("host"), "headerType" to j.optString("type"),
            "serviceName" to j.optString("path"),
        )
        val tls = j.optString("tls").let { if (it == "tls" || it == "reality") it else "none" }
        o.put("streamSettings", stream(j.optString("net"), tls, { params[it]?.takeIf { v -> v.isNotBlank() } }, core) ?: return null)
        return o
    }

    /** SIP002 (ss://base64(method:password)@host:port#name) and the old ss://base64(method:password@host:port)#name. */
    private fun shadowsocks(link: String): JSONObject? {
        val body = link.substringAfter("://").substringBefore('#')
        val fragment = link.substringAfter('#', "").let { Uri.decode(it) }
        val full = if ('@' in body) body else decode(body) ?: return null
        val cred = full.substringBeforeLast('@')
        val hostPort = full.substringAfterLast('@').substringBefore('?').substringBefore('/')
        val userInfo = if (':' in Uri.decode(cred)) Uri.decode(cred) else decode(cred) ?: return null
        val host = hostPort.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        val port = hostPort.substringAfterLast(':').toIntOrNull() ?: return null
        return JSONObject().put("tag", name(fragment, host)).put("protocol", "shadowsocks").put(
            "settings",
            JSONObject().put(
                "servers",
                JSONArray().put(JSONObject().put("address", host).put("port", port).put("method", userInfo.substringBefore(':')).put("password", userInfo.substringAfter(':'))),
            ),
        )
    }

    /** Xray's hysteria outbound (version 2). Salamander obfuscation is not supported there: such links are skipped. */
    private fun hysteria2(u: Uri): JSONObject? {
        if (q(u, "obfs") != null) return null
        val host = u.host ?: return null
        val port = u.port.takeIf { it > 0 } ?: 443
        val tls = JSONObject().apply { (q(u, "sni") ?: host).let { put("serverName", it) }; q(u, "alpn")?.let { put("alpn", JSONArray(it.split(','))) } }
        return JSONObject().put("tag", name(u.fragment, host)).put("protocol", "hysteria")
            .put("settings", JSONObject().put("version", 2).put("address", host).put("port", port))
            .put(
                "streamSettings",
                JSONObject().put("network", "hysteria").put("security", "tls").put("tlsSettings", tls)
                    .put("hysteriaSettings", JSONObject().put("version", 2).put("auth", Uri.decode(u.userInfo ?: ""))),
            )
    }
}

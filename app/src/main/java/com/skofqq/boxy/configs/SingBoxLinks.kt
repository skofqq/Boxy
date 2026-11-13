package com.skofqq.boxy.configs

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Share links (vless / vmess / trojan / ss / hysteria2) as sing-box outbounds, for a panel that serves sing-box
 * only a link list. Links sing-box cannot use (XHTTP, Xray's finalmask) are skipped with the reason, like
 * [ShareLinks] does for Xray. insecure=1 becomes tls.insecure, which sing-box still has.
 */
object SingBoxLinks {
    data class Result(val outbounds: List<JSONObject>, val skipped: List<ShareLinks.Skipped>)

    private fun decode(s: String): String? = runCatching {
        val clean = s.replace(Regex("\\s"), "").replace('-', '+').replace('_', '/').trimEnd('=')
        String(Base64.decode(clean + "=".repeat((4 - clean.length % 4) % 4), Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()

    fun convert(body: String): Result {
        val out = ArrayList<JSONObject>()
        val skipped = ArrayList<ShareLinks.Skipped>()
        val used = HashSet<String>()
        for (link in ShareLinks.links(body)) {
            val scheme = link.substringBefore("://").lowercase()
            val o = runCatching { outbound(link) }.getOrNull()
            val port = o?.optInt("server_port", 0) ?: 0
            if (o == null || port !in 1..65535) {
                val reason = when {
                    scheme !in setOf("vmess", "vless", "trojan", "ss", "hysteria2", "hy2") -> ShareLinks.SkipReason.SCHEME
                    o == null && unsupported(link) -> ShareLinks.SkipReason.SINGBOX
                    o != null -> ShareLinks.SkipReason.PORT
                    else -> ShareLinks.SkipReason.BROKEN
                }
                skipped += ShareLinks.Skipped(ShareLinks.linkName(link), scheme, reason)
                continue
            }
            val base = ShareLinks.linkName(link).replace(Regex("[\\s\"\\\\]+"), "_").take(48).ifBlank { "node" }
            var tag = base
            var n = 2
            while (!used.add(tag)) tag = "$base-${n++}"
            out += o.put("tag", tag)
        }
        return Result(out, skipped)
    }

    /** A link sing-box has no counterpart for (Xray's XHTTP transport, finalmask). */
    private fun unsupported(link: String): Boolean {
        val u = runCatching { Uri.parse(link) }.getOrNull() ?: return false
        val t = (u.getQueryParameter("type") ?: "").lowercase()
        return t == "xhttp" || t == "splithttp" || u.getQueryParameter("fm") != null
    }

    private fun q(u: Uri, k: String) = u.getQueryParameter(k)?.takeIf { it.isNotBlank() }

    private fun outbound(link: String): JSONObject? = when (link.substringBefore("://").lowercase()) {
        "vless" -> Uri.parse(link).let { u ->
            JSONObject().put("type", "vless").put("server", u.host ?: return null).put("server_port", u.port)
                .put("uuid", u.userInfo ?: return null)
                .apply { q(u, "flow")?.let { put("flow", it) } }
                .withStream({ q(u, it) }, q(u, "security"), q(u, "type")) ?: return null
        }
        "trojan" -> Uri.parse(link).let { u ->
            JSONObject().put("type", "trojan").put("server", u.host ?: return null).put("server_port", u.port)
                .put("password", Uri.decode(u.userInfo ?: return null))
                .withStream({ q(u, it) }, q(u, "security") ?: "tls", q(u, "type")) ?: return null
        }
        "vmess" -> {
            val j = JSONObject(decode(link.substringAfter("://").substringBefore('#')) ?: return null)
            val p = mapOf(
                "sni" to j.optString("sni"), "fp" to j.optString("fp"), "alpn" to j.optString("alpn"), "path" to j.optString("path"),
                "host" to j.optString("host"), "serviceName" to j.optString("path"), "insecure" to j.optString("allowInsecure"),
            )
            JSONObject().put("type", "vmess").put("server", j.optString("add").ifBlank { return null })
                .put("server_port", j.optString("port").toIntOrNull() ?: j.optInt("port"))
                .put("uuid", j.optString("id").ifBlank { return null }).put("security", j.optString("scy").ifBlank { "auto" })
                .apply { j.optString("aid").toIntOrNull()?.takeIf { it > 0 }?.let { put("alter_id", it) } }
                .withStream({ p[it]?.takeIf { v -> v.isNotBlank() } }, if (j.optString("tls") == "tls") "tls" else "none", j.optString("net"))
                ?: return null
        }
        "ss" -> {
            val body = link.substringAfter("://").substringBefore('#')
            val full = if ('@' in body) body else decode(body) ?: return null
            val cred = full.substringBeforeLast('@')
            val hostPort = full.substringAfterLast('@').substringBefore('?').substringBefore('/')
            val userInfo = if (':' in Uri.decode(cred)) Uri.decode(cred) else decode(cred) ?: return null
            JSONObject().put("type", "shadowsocks").put("server", hostPort.substringBeforeLast(':').removePrefix("[").removeSuffix("]"))
                .put("server_port", hostPort.substringAfterLast(':').toIntOrNull() ?: return null)
                .put("method", userInfo.substringBefore(':')).put("password", userInfo.substringAfter(':'))
        }
        "hysteria2", "hy2" -> Uri.parse(link).let { u ->
            if (q(u, "fm") != null) return null
            val tls = JSONObject().put("enabled", true).put("server_name", q(u, "sni") ?: u.host)
                .apply {
                    q(u, "alpn")?.let { put("alpn", JSONArray(it.split(','))) }
                    if (ShareLinks.insecureLink(link)) put("insecure", true)
                }
            JSONObject().put("type", "hysteria2").put("server", u.host ?: return null).put("server_port", u.port.takeIf { it > 0 } ?: 443)
                .put("password", Uri.decode(u.userInfo ?: "")).put("tls", tls)
                .apply {
                    val obfs = q(u, "obfs")
                    if (obfs != null) {
                        if (!obfs.equals("salamander", true)) return null
                        put("obfs", JSONObject().put("type", "salamander").put("password", q(u, "obfs-password") ?: return null))
                    }
                }
        }
        else -> null
    }

    /** tls / reality and the transport, sing-box style. null for what sing-box has no counterpart for. */
    private fun JSONObject.withStream(p: (String) -> String?, sec0: String?, net0: String?): JSONObject? {
        val sec = sec0?.lowercase()?.ifBlank { null } ?: "none"
        when (sec) {
            "tls", "reality" -> {
                val tls = JSONObject().put("enabled", true)
                (p("sni") ?: p("peer"))?.let { tls.put("server_name", it) }
                p("alpn")?.let { tls.put("alpn", JSONArray(it.split(','))) }
                p("fp")?.let { tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it)) }
                if (sec == "reality") {
                    if (!tls.has("utls")) tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", "chrome"))
                    tls.put("reality", JSONObject().put("enabled", true).put("public_key", p("pbk") ?: return null).apply { p("sid")?.let { put("short_id", it) } })
                } else if (p("insecure")?.lowercase() in setOf("1", "true") || p("allowInsecure")?.lowercase() in setOf("1", "true")) {
                    tls.put("insecure", true)
                }
                put("tls", tls)
            }
            "none" -> Unit
            else -> return null
        }
        val path = p("path")
        val host = p("host")
        when (net0?.lowercase()?.ifBlank { null } ?: "tcp") {
            "tcp", "raw" -> if (p("headerType") == "http") return null
            "ws" -> put("transport", JSONObject().put("type", "ws").apply { path?.let { put("path", it) }; host?.let { put("headers", JSONObject().put("Host", it)) } })
            "grpc" -> put("transport", JSONObject().put("type", "grpc").put("service_name", p("serviceName") ?: path ?: ""))
            "httpupgrade" -> put("transport", JSONObject().put("type", "httpupgrade").apply { path?.let { put("path", it) }; host?.let { put("host", it) } })
            "h2", "http" -> put("transport", JSONObject().put("type", "http").apply { path?.let { put("path", it) }; host?.let { put("host", JSONArray(it.split(','))) } })
            else -> return null
        }
        return this
    }

    /**
     * A whole sing-box config from [outbounds]: the module's example (without its comments) as the template, a
     * selector "proxy" (manual choice, "auto" first) and a urltest "auto" over all servers, route.final = proxy.
     */
    fun config(template: String?, outbounds: List<JSONObject>): String {
        val root = template?.let { t -> runCatching { JSONObject(t.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")) }.getOrNull() }
            ?: JSONObject()
                .put("log", JSONObject().put("level", "warn"))
                .put("dns", JSONObject().put("servers", JSONArray().put(JSONObject().put("type", "local").put("tag", "local"))))
                .put("inbounds", JSONArray().put(JSONObject().put("type", "tproxy").put("tag", "tproxy-in").put("listen", "::").put("listen_port", 9898)))
                .put("route", JSONObject().put("auto_detect_interface", true).put("default_domain_resolver", "local"))
        val tags = outbounds.map { it.optString("tag") }
        val obs = JSONArray()
        obs.put(JSONObject().put("type", "selector").put("tag", "proxy").put("outbounds", JSONArray(listOf("auto") + tags)).put("default", "auto"))
        obs.put(
            JSONObject().put("type", "urltest").put("tag", "auto").put("outbounds", JSONArray(tags))
                .put("url", "https://www.gstatic.com/generate_204").put("interval", "3m"),
        )
        outbounds.forEach { obs.put(it) }
        obs.put(JSONObject().put("type", "direct").put("tag", "direct"))
        root.put("outbounds", obs)
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        route.put("final", "proxy")
        return root.toString(2) + "\n"
    }
}

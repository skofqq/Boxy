package com.skofqq.boxy.net

import com.skofqq.boxy.root.BoxModule
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Delays of sing-box outbounds measured like mihomo's unified-delay. sing-box's own test (Clash API /delay, urltest)
 * times a whole new connection: TCP + TLS / REALITY (+ QUIC for hysteria) + the request, many times the delay of a
 * request over an open connection, while mihomo with unified-delay shows the latter. Here a short-lived sing-box (the
 * active config's outbounds and DNS, a local mixed port per outbound, the core's user so the proxy rules let it out)
 * gets two requests per outbound; the second one, over the open connection, is the delay.
 */
object SingBoxDelay {
    private const val BOX = BoxModule.BOX_DIR
    private const val TEST_PORT = 20900
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** ms per name (0 = failed); null when the test itself could not run (then the Clash API test is used). */
    suspend fun test(names: List<String>, url: String = Net.delayUrl, timeoutMs: Int = Net.delayTimeout): Map<String, Int>? = withContext(Dispatchers.IO) {
        if (names.isEmpty()) return@withContext emptyMap()
        val conf = BoxModule.unquote(BoxModule.readSetting("name_sing_config")).orEmpty().ifBlank { "config.json" }
        val src = BoxModule.readFile("$BOX/sing-box/$conf")?.let { t ->
            runCatching { JSONObject(t.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")) }.getOrNull()
        } ?: return@withContext null
        val outbounds = src.optJSONArray("outbounds") ?: return@withContext null
        val known = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it)?.optString("tag") }.toSet() +
            (src.optJSONArray("endpoints")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("tag") } }.orEmpty())
        val tested = names.filter { it in known }
        if (tested.isEmpty()) return@withContext null
        val inbounds = JSONArray()
        val rules = JSONArray()
        tested.forEachIndexed { i, n ->
            inbounds.put(JSONObject().put("type", "mixed").put("tag", "boxy-t$i").put("listen", "127.0.0.1").put("listen_port", TEST_PORT + i))
            rules.put(JSONObject().put("inbound", JSONArray().put("boxy-t$i")).put("outbound", n))
        }
        val cfg = JSONObject().put("log", JSONObject().put("disabled", true)).put("inbounds", inbounds).put("outbounds", outbounds)
        src.optJSONArray("endpoints")?.let { cfg.put("endpoints", it) }
        // DNS: the config's servers (the outbounds resolve their servers with them), without rules that need rule sets.
        val route = JSONObject().put("rules", rules)
        src.optJSONObject("dns")?.let { d ->
            val dns = JSONObject()
            d.optJSONArray("servers")?.let { dns.put("servers", it) }
            listOf("final", "strategy").forEach { k -> d.opt(k)?.let { dns.put(k, it) } }
            cfg.put("dns", dns)
        }
        src.optJSONObject("route")?.opt("default_domain_resolver")?.let { route.put("default_domain_resolver", it) }
        cfg.put("route", route)
        val file = "$BOX/run/boxy-delay-sb.json"
        if (!BoxModule.writeFile(file, cfg.toString())) return@withContext null
        val group = BoxModule.unquote(BoxModule.readSetting("box_user_group")).orEmpty().ifBlank { "root:net_admin" }
        val last = "%04X".format(TEST_PORT + tested.size - 1)
        val sec = (timeoutMs / 1000).coerceIn(2, 15)
        val curls = tested.indices.joinToString(" ") { i ->
            "( r=\$($BOX/bin/curl -s -o /dev/null -m $sec -x socks5h://127.0.0.1:${TEST_PORT + i} -w '%{http_code}:%{time_total} ' ${q(url)} ${q(url)}); echo \"$i \$r\" ) & w=\"\$w \$!\";"
        }
        val script = "( export SSL_CERT_DIR=/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts; " +
            "pkill -f boxy-delay-sb.json 2>/dev/null; " +
            "BB=busybox; for b in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do [ -x \$b ] && BB=\$b && break; done; " +
            "\$BB setuidgid ${q(group)} $BOX/bin/sing-box run -c $file >/dev/null 2>&1 & p=\$!; " +
            "n=0; while [ \$n -lt 40 ] && ! grep -qi '0100007F:$last 00000000:0000 0A' /proc/net/tcp; do sleep 0.1; n=\$((n+1)); done; " +
            "if grep -qi '0100007F:$last 00000000:0000 0A' /proc/net/tcp; then w=; $curls wait \$w; else echo fail; fi; " +
            "kill \$p 2>/dev/null; wait \$p 2>/dev/null; rm -f $file )"
        val out = Shell.cmd(script).exec().out
        if (out.any { it.trim() == "fail" }) return@withContext null
        val delays = HashMap<String, Int>()
        for (line in out) {
            val parts = line.trim().split(Regex("\\s+"))
            val i = parts.firstOrNull()?.toIntOrNull() ?: continue
            val name = tested.getOrNull(i) ?: continue
            val ok = parts.drop(1).mapNotNull { r ->
                val code = r.substringBefore(':').toIntOrNull() ?: 0
                val t = r.substringAfter(':').toDoubleOrNull() ?: 0.0
                if (code in 200..399 && t > 0) (t * 1000).toInt().coerceAtLeast(1) else null
            }
            delays[name] = ok.getOrNull(1) ?: ok.getOrNull(0) ?: 0
        }
        names.forEach { delays.putIfAbsent(it, 0) }
        delays
    }
}

package com.skofqq.boxy.configs

import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.CheckResult
import com.skofqq.boxy.root.ConfigCheck
import com.skofqq.boxy.root.CoreTools
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Xray 26 refuses to start with tlsSettings.allowInsecure ("migrated to pinnedPeerCertSha256 and
 * verifyPeerCertByName"). An outbound with allowInsecure: true gets pinnedPeerCertSha256 = the SHA-256 of the
 * certificate its server presents now (what `xray tls hash` computes: the leaf certificate's DER), so it keeps
 * connecting to a server with a self-signed certificate, and only to that one; allowInsecure: false is dropped.
 * A server that cannot be reached is left to normal certificate checks and named in the result.
 * The file is copied to <file>.allowinsecure.bak first; the edit is made in the text, so comments and layout stay,
 * and the core checks the result (the copy comes back when it is rejected).
 */
object XrayInsecureFix {
    private val KEY = Regex("\"allowInsecure\"\\s*:\\s*(true|false)")

    data class Outcome(val pinned: List<String>, val unreachable: List<String>, val failed: String? = null)

    /** Is [output] (a core check) the allowInsecure removal? */
    fun applies(output: String?): Boolean = output?.contains("allowInsecure") == true

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** The files Xray runs with for [config] (the active one when null) that still have allowInsecure. */
    suspend fun affectedFiles(config: String? = null): List<String> = withContext(Dispatchers.IO) {
        val files = runCatching { CoreTools.xrayFiles(config) }.getOrDefault(emptyList()).ifEmpty { listOfNotNull(config) }
        files.filter { f -> BoxModule.readFile(f)?.let { KEY.containsMatchIn(stripComments(it)) } == true }
    }

    /** Fixes every affected file of [config] and checks the result with the core. */
    suspend fun fix(config: String? = null): Outcome = withContext(Dispatchers.IO) {
        val pinned = ArrayList<String>()
        val unreachable = ArrayList<String>()
        val files = affectedFiles(config)
        val backups = ArrayList<String>()
        for (f in files) {
            val text = BoxModule.readFile(f) ?: continue
            val r = fixText(text) ?: return@withContext Outcome(pinned, unreachable, "$f: JSON")
            Shell.cmd("cp -f ${q(f)} ${q("$f.allowinsecure.bak")}").exec()
            backups += f
            if (!BoxModule.writeFile(f, r.first)) return@withContext restore(backups, Outcome(pinned, unreachable, "$f: write"))
            pinned += r.second
            unreachable += r.third
        }
        val active = config ?: CoreTools.activeConfig("xray")
        val check = ConfigCheck.check("xray", active)
        if (check is CheckResult.Failed) return@withContext restore(backups, Outcome(pinned, unreachable, check.output))
        Outcome(pinned, unreachable)
    }

    private fun restore(files: List<String>, o: Outcome): Outcome {
        files.forEach { f -> Shell.cmd("mv -f ${q("$f.allowinsecure.bak")} ${q(f)}").exec() }
        return o
    }

    /** Whole-line // comments blanked with spaces of the same length, so offsets in the result match the text. */
    private fun stripComments(text: String) = text.split("\n").joinToString("\n") { l -> if (l.trimStart().startsWith("//")) " ".repeat(l.length) else l }

    /** (new text, pinned outbound tags, unreachable outbound tags), null when the file is not JSON Boxy can read. */
    private fun fixText(text: String): Triple<String, List<String>, List<String>>? {
        val clean = stripComments(text)
        val root = runCatching { JSONObject(clean) }.getOrNull() ?: return null
        // The tlsSettings with allowInsecure, in the order they appear in the file.
        data class Item(val tls: JSONObject, val tag: String, val value: Boolean, val hash: String?)
        val items = ArrayList<Item>()
        root.optJSONArray("outbounds")?.let { obs ->
            for (i in 0 until obs.length()) {
                val ob = obs.optJSONObject(i) ?: continue
                collectTls(ob).forEach { tls ->
                    if (!tls.has("allowInsecure")) return@forEach
                    val on = tls.optBoolean("allowInsecure")
                    val tag = ob.optString("tag").ifBlank { ob.optString("protocol") }
                    val hash = if (on && !tls.has("pinnedPeerCertSha256")) server(ob, tls)?.let { (sni, host, port) -> certHash(sni, host, port) } else null
                    items += Item(tls, tag, on, hash)
                }
            }
        }
        val matches = KEY.findAll(clean).toList()
        val pinned = items.filter { it.value && it.hash != null }.map { it.tag }
        val unreachable = items.filter { it.value && it.hash == null && !it.tls.has("pinnedPeerCertSha256") }.map { it.tag }
        if (matches.size != items.size) {
            // Not only in outbounds (or written oddly): the whole file is rewritten from the parsed JSON.
            items.forEach { it.tls.remove("allowInsecure"); it.hash?.let { h -> it.tls.put("pinnedPeerCertSha256", h) } }
            if (KEY.containsMatchIn(root.toString())) return null
            return Triple(root.toString(2) + "\n", pinned, unreachable)
        }
        // The same positions in the original text (comment lines were blanked in place, so offsets match).
        val sb = StringBuilder(text)
        for ((i, m) in matches.withIndex().reversed()) {
            val item = items[i]
            var start = m.range.first
            var end = m.range.last + 1
            if (item.hash != null) {
                sb.replace(start, end, "\"pinnedPeerCertSha256\": \"${item.hash}\"")
                continue
            }
            // Removed with its comma: the one after it, or else the one before it (it was the last key).
            val after = Regex("^\\s*,").find(sb.substring(end))
            if (after != null) {
                end += after.value.length
                // The line it was on goes too when nothing else is left on it.
                val lineStart = sb.lastIndexOf("\n", start - 1) + 1
                if (sb.substring(lineStart, start).isBlank() && sb.substring(end).let { Regex("^[ \\t]*\\n").containsMatchIn(it) }) {
                    start = lineStart
                    end += Regex("^[ \\t]*\\n").find(sb.substring(end))!!.value.length
                }
            } else {
                val before = Regex(",\\s*$").find(sb.substring(0, start))
                if (before != null) start = before.range.first
            }
            sb.replace(start, end, "")
        }
        return Triple(sb.toString(), pinned, unreachable)
    }

    /**
     * Links with insecure=1 ([tags] of [outbounds]): each gets pinnedPeerCertSha256 of its server's certificate.
     * Returns the ones that could not be pinned (server unreachable), which keep normal certificate checks.
     */
    fun pinOutbounds(outbounds: List<JSONObject>, tags: List<String>): List<ShareLinks.Skipped> {
        if (tags.isEmpty()) return emptyList()
        val missed = ArrayList<ShareLinks.Skipped>()
        for (ob in outbounds) {
            val tag = ob.optString("tag")
            if (tag !in tags) continue
            val tls = ob.optJSONObject("streamSettings")?.optJSONObject("tlsSettings")
                ?: JSONObject().also { ob.optJSONObject("streamSettings")?.put("tlsSettings", it) }
            val hash = server(ob, tls)?.let { (sni, host, port) -> certHash(sni, host, port) }
            if (hash != null) tls.put("pinnedPeerCertSha256", hash)
            else missed += ShareLinks.Skipped(tag.removePrefix(ShareLinks.TAG_PREFIX), ob.optString("protocol"), ShareLinks.SkipReason.UNPINNED)
        }
        return missed
    }

    /** tlsSettings of an outbound: its streamSettings and the download half of an xhttp one. */
    private fun collectTls(ob: JSONObject): List<JSONObject> {
        val ss = ob.optJSONObject("streamSettings") ?: return emptyList()
        return listOfNotNull(
            ss.optJSONObject("tlsSettings"),
            ss.optJSONObject("xhttpSettings")?.optJSONObject("extra")?.optJSONObject("downloadSettings")?.optJSONObject("tlsSettings"),
        )
    }

    /** (SNI, address, port) of an outbound's server. */
    private fun server(ob: JSONObject, tls: JSONObject): Triple<String, String, Int>? {
        val s = ob.optJSONObject("settings") ?: JSONObject()
        val first: JSONObject? = (s.optJSONArray("vnext") ?: s.optJSONArray("servers"))?.optJSONObject(0)
        val host = (first?.optString("address") ?: s.optString("address")).takeIf { !it.isNullOrBlank() } ?: return null
        val port = first?.optInt("port", 0)?.takeIf { it > 0 } ?: s.optInt("port", 443).takeIf { it > 0 } ?: 443
        val sni = tls.optString("serverName").ifBlank { host }
        return Triple(sni, host, port)
    }

    /**
     * SHA-256 (hex) of the leaf certificate [host]:[port] presents for [sni], without checking it: only to pin
     * the one the config already trusted blindly. null when the server cannot be reached.
     */
    private fun certHash(sni: String, host: String, port: Int): String? = runCatching {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }
        (ctx.socketFactory.createSocket() as SSLSocket).use { s ->
            s.soTimeout = 8000
            s.connect(InetSocketAddress(host, port), 8000)
            if (sni.any { it.isLetter() }) {
                s.sslParameters = s.sslParameters.apply { serverNames = listOf(SNIHostName(sni)) }
            }
            s.startHandshake()
            val leaf = s.session.peerCertificates.first()
            MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
        }
    }.getOrNull()
}

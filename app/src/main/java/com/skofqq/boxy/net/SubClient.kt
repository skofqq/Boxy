package com.skofqq.boxy.net

import android.content.Context
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a subscription the way panels like Remnawave expect from a client app, and reads what they say about
 * it in the reply headers. Nothing of the URL, its token or the HWID is ever logged.
 *
 * Request: a User-Agent per format (panels pick the reply by it), and when allowed the HWID headers Remnawave's
 * device limit reads: x-hwid (a random id of this install, [a-zA-Z0-9=-]{10,64}), x-device-os, x-ver-os,
 * x-device-model.
 * Reply headers: subscription-userinfo (upload / download / total / expire), profile-title, profile-update-interval
 * (hours), support-url, profile-web-page-url, announce (plain, "base64:…" or Remnawave's "rwEncodeBase64:…"),
 * and Remnawave's device limit: x-hwid-max-devices-reached / x-hwid-not-supported (then the body is empty).
 */
object SubClient {
    /**
     * Which format to ask for. The sing-box and mihomo agents start the way panels' default rules expect
     * (Remnawave: "^sfa…" → sing-box JSON, "^clash.meta…" → mihomo YAML); a panel can match "Boxy/.*xray" (it has
     * no default Xray JSON rule) or "Boxy/.*sing-box" to serve Boxy's own templates (configs/remnawave-templates).
     */
    enum class Format(val userAgent: String) {
        SINGBOX("SFA/1.1 (sing-box; singbox; Boxy/1.1; Android)"),
        MIHOMO("clash.meta (mihomo; Boxy/1.1; Android)"),
        XRAY("Boxy/1.1 (xray; Android)"),
        LINKS("Boxy/1.1 (Android)"),
    }

    data class Profile(
        val title: String? = null,
        val info: SubscriptionInfo? = null,
        /** Hours between updates the panel suggests. */
        val updateHours: Int? = null,
        val supportUrl: String? = null,
        val webPageUrl: String? = null,
        val announce: String? = null,
    )

    enum class Limit { NONE, MAX_DEVICES, HWID_REQUIRED }

    data class Reply(val code: Int, val body: String?, val profile: Profile, val limit: Limit) {
        val ok get() = code in 200..299 && !body.isNullOrBlank() && limit == Limit.NONE
    }

    private const val PREFS = "subscription_client"

    /** Send the HWID headers (Settings → Subscriptions); on by default, since panels with a device limit need them. */
    fun sendHwid(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("send_hwid", true)

    fun setSendHwid(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("send_hwid", on).apply()

    /** A random id made once per install (not the Android ID or anything of the hardware), 32 hex characters. */
    fun hwid(context: Context): String {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.getString("hwid", null)?.let { return it }
        val id = java.util.UUID.randomUUID().toString().replace("-", "")
        sp.edit().putString("hwid", id).apply()
        return id
    }

    fun headers(context: Context, format: Format): Map<String, String> = buildMap {
        put("User-Agent", format.userAgent)
        put("Accept", "*/*")
        if (sendHwid(context)) {
            put("x-hwid", hwid(context))
            put("x-device-os", "Android")
            put("x-ver-os", Build.VERSION.RELEASE ?: "")
            put("x-device-model", listOf(Build.MANUFACTURER, Build.MODEL).filterNotNull().joinToString(" ").trim())
        }
    }

    suspend fun fetch(context: Context, url: String, format: Format, headOnly: Boolean = false): Reply? = withContext(Dispatchers.IO) {
        runCatching {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = if (headOnly) "HEAD" else "GET"
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                headers(context, format).forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                val code = c.responseCode
                val body = if (headOnly || code !in 200..299) null else c.inputStream.bufferedReader().readText()
                fun h(name: String) = c.getHeaderField(name)?.takeIf { it.isNotBlank() }
                val limit = when {
                    h("x-hwid-max-devices-reached") == "true" -> Limit.MAX_DEVICES
                    h("x-hwid-not-supported") == "true" -> Limit.HWID_REQUIRED
                    else -> Limit.NONE
                }
                val title = text(h("profile-title"))
                val profile = Profile(
                    title = title,
                    info = Net.parseUserInfo(h("subscription-userinfo"), title),
                    updateHours = h("profile-update-interval")?.trim()?.toIntOrNull()?.takeIf { it > 0 },
                    supportUrl = h("support-url"),
                    webPageUrl = h("profile-web-page-url"),
                    announce = text(h("announce")),
                )
                Reply(code, body, profile, limit)
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    /**
     * The module's own subscription downloads (box.tool) send the same id when /data/adb/box/.hwid exists (root only).
     * Called at start and when the setting changes.
     */
    suspend fun syncModule(context: Context) = withContext(Dispatchers.IO) {
        val f = "${com.skofqq.boxy.root.BoxModule.BOX_DIR}/.hwid"
        if (sendHwid(context)) {
            if (com.skofqq.boxy.root.BoxModule.readFile(f)?.trim() != hwid(context)) {
                com.skofqq.boxy.root.BoxModule.writeFile(f, hwid(context) + "\n")
                com.skofqq.boxy.root.BoxModule.exec("chmod 600 '$f'")
            }
        } else {
            com.skofqq.boxy.root.BoxModule.exec("rm -f '$f'")
        }
    }

    /** A header value that may be base64: "base64:…" or Remnawave's "rwEncodeBase64:…". */
    fun text(v: String?): String? {
        if (v.isNullOrBlank()) return null
        for (p in listOf("base64:", "rwEncodeBase64:")) {
            if (v.startsWith(p, ignoreCase = true)) {
                return runCatching { String(Base64.decode(v.substring(p.length).trim(), Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()?.trim()
            }
        }
        return v.trim()
    }
}

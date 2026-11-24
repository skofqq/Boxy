package com.skofqq.boxy.net

import com.skofqq.boxy.root.BoxModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppRelease(
    val version: String,
    val prerelease: Boolean,
    val date: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?,
    val commit: String?,
)

data class ModuleUpdate(
    val version: String,
    val versionCode: Long,
    val zipUrl: String?,
    val changelogUrl: String?,
)

/** A newer stable release of the core the module runs now ([title] is how the app names it). */
data class CoreUpdate(val binName: String, val title: String, val installed: String, val latest: String)

/** Update checks: the app's GitHub releases, the module's own updateJson and the active core. */
object Updates {
    const val REPO = "skofqq/Boxy"

    private fun get(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Boxy")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    /** Latest stable and prerelease builds, newest first; null when the request failed. */
    suspend fun appReleases(): List<AppRelease>? = withContext(Dispatchers.IO) {
        val body = get("https://api.github.com/repos/$REPO/releases?per_page=10") ?: return@withContext null
        runCatching {
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val r = arr.getJSONObject(i)
                val assets = r.optJSONArray("assets") ?: JSONArray()
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") }?.optString("browser_download_url")
                if (apk == null) return@mapNotNull null
                AppRelease(
                    version = r.optString("tag_name").removePrefix("v"),
                    prerelease = r.optBoolean("prerelease"),
                    date = r.optString("published_at").take(10),
                    notes = r.optString("body"),
                    pageUrl = r.optString("html_url"),
                    apkUrl = apk,
                    commit = r.optString("target_commitish").takeIf { it.length >= 7 && it.all { c -> c.isLetterOrDigit() } }?.take(7),
                )
            }
        }.getOrNull()
    }

    /**
     * Downloads the release APK (through the GitHub mirror) and installs it with root.
     * The install runs detached, because replacing the package stops this process; the app is reopened afterwards.
     */
    suspend fun installApp(context: android.content.Context, apkUrl: String, onProgress: (Float) -> Unit, onLog: (String) -> Unit): Boolean {
        val file = java.io.File(context.cacheDir, "boxy-update.apk")
        if (!Mirrors.download(apkUrl, file, onProgress, onLog)) return false
        val tmp = "/data/local/tmp/boxy-update.apk"
        val (ok, out) = BoxModule.exec("cp '${file.absolutePath}' $tmp && chmod 644 $tmp")
        file.delete()
        if (!ok) {
            out.forEach(onLog)
            return false
        }
        onLog("pm install")
        BoxModule.exec(
            "setsid sh -c 'pm install -r $tmp >/dev/null 2>&1; rm -f $tmp; am start -n ${context.packageName}/.MainActivity >/dev/null 2>&1' </dev/null >/dev/null 2>&1 &",
        )
        return true
    }

    /** Compares dotted versions like 0.1.0 and 0.2; true when [latest] is newer. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * Releases to offer when the app opens: a stable build newer than the installed one that was not
     * declined. At most one request per [LAUNCH_CHECK_MS], so reopening the app stays off GitHub's rate limit.
     */
    suspend fun launchOffer(context: android.content.Context, prefs: com.skofqq.boxy.data.Prefs): List<AppRelease>? {
        if (!prefs.updateAtLaunch || System.currentTimeMillis() - prefs.lastLaunchCheck < LAUNCH_CHECK_MS) return null
        val current = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: return null
        val releases = appReleases() ?: return null
        prefs.lastLaunchCheck = System.currentTimeMillis()
        val latest = releases.firstOrNull { !it.prerelease }?.version ?: return null
        return releases.takeIf { isNewer(latest, current) && latest != prefs.declinedUpdate }
    }

    private const val LAUNCH_CHECK_MS = 60 * 60_000L

    /** Reads updateJson from module.prop and returns the offered version when it is newer. */
    suspend fun moduleUpdate(): Pair<ModuleUpdate?, Long>? = withContext(Dispatchers.IO) {
        val (_, out) = BoxModule.exec("grep -E '^(updateJson|versionCode)=' ${BoxModule.MODULE_DIR}/module.prop")
        val kv = BoxModule.parseKv(out)
        val current = kv["versionCode"]?.toLongOrNull() ?: 0
        val url = kv["updateJson"] ?: return@withContext null
        val body = get(Mirrors.wrap(url)) ?: get(url) ?: return@withContext null
        runCatching {
            val j = JSONObject(body)
            val u = ModuleUpdate(
                j.optString("version"),
                j.optLong("versionCode"),
                j.optString("zipUrl").ifBlank { null },
                j.optString("changelog").ifBlank { null },
            )
            (if (u.versionCode > current) u else null) to current
        }.getOrNull()
    }

    /** GitHub repositories of the stable cores; Mihomo Smart and alpha builds are left out. */
    private val CORE_REPOS = mapOf(
        "clash" to ("Mihomo" to "MetaCubeX/mihomo"),
        "sing-box" to ("Sing-box" to "SagerNet/sing-box"),
        "xray" to ("Xray" to "XTLS/Xray-core"),
        "v2fly" to ("V2Ray" to "v2fly/v2ray-core"),
        "hysteria" to ("Hysteria" to "apernet/hysteria"),
    )

    private val VERSION = Regex("""\bv?(\d+\.\d+(?:\.\d+)?)\b""")

    /** First dotted version in a core's `version` output or a release tag ("app/v2.6.2" for Hysteria). */
    fun parseVersion(text: String?): String? = text?.let { VERSION.find(it)?.groupValues?.get(1) }

    /**
     * Checks the core selected in settings.ini (bin_name) against its latest stable GitHub release.
     * Returns null when it is up to date, the check failed, or the core is not one we follow
     * (Mihomo Smart, an alpha build).
     */
    suspend fun coreUpdate(): CoreUpdate? = withContext(Dispatchers.IO) {
        val bin = BoxModule.unquote(BoxModule.readSetting("bin_name"))?.takeIf { it.isNotBlank() } ?: return@withContext null
        val (title, repo) = CORE_REPOS[bin] ?: return@withContext null
        if (bin == "clash") {
            val option = BoxModule.unquote(BoxModule.readSetting("xclash_option"))
            if (!option.isNullOrBlank() && option != "mihomo") return@withContext null
        }
        val b = "${BoxModule.BOX_DIR}/bin/$bin"
        val (_, out) = BoxModule.exec(
            when (bin) {
                "clash" -> "\"$b\" -v | head -n1"
                "hysteria" -> "\"$b\" version | grep -i '^version' | head -n1"
                else -> "\"$b\" version | head -n1"
            },
        )
        val raw = out.joinToString(" ")
        if ("alpha" in raw.lowercase()) return@withContext null
        val installed = parseVersion(raw) ?: return@withContext null
        val body = get("https://api.github.com/repos/$repo/releases/latest") ?: return@withContext null
        val latest = runCatching { parseVersion(JSONObject(body).optString("tag_name")) }.getOrNull() ?: return@withContext null
        if (isNewer(latest, installed)) CoreUpdate(bin, title, installed, latest) else null
    }
}

package com.skofqq.boxy.configs

import android.content.Context
import android.net.Uri
import com.skofqq.boxy.net.SubClient
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.CheckResult
import com.skofqq.boxy.root.ConfigCheck
import com.skofqq.boxy.root.CoreTools
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * A config for another core from the subscription the user already has (on a core switch): the URL comes from
 * settings.ini (any core's subscription_url_*) or the active mihomo config's proxy-providers, and is asked for in
 * the target core's format:
 *  - mihomo: a full YAML config (User-Agent clash.meta);
 *  - sing-box: a full JSON config (SFA / singbox), or, when the panel serves only links, a config built from them
 *    on the module's example;
 *  - Xray: the links, as Boxy's Xray subscription (outbounds "sub-…" with the balancer "sub");
 *  - Hysteria: its hysteria2:// links as profiles.
 * The file is checked by the core before it becomes active; the previous config stays when it is not, and the URL
 * is saved as that core's subscription so later updates and the quota card work. URLs are never shown or logged.
 */
object CoreSubscription {
    private const val BOX = BoxModule.BOX_DIR

    data class Outcome(
        val ok: Boolean,
        val servers: Int = 0,
        val skipped: List<ShareLinks.Skipped> = emptyList(),
        /** Why it failed: the core's check output, or a code the dialog explains. */
        val error: String? = null,
        val limit: SubClient.Limit = SubClient.Limit.NONE,
        val profile: SubClient.Profile? = null,
    )

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Subscription URLs the user already has, each once (the active core's first). */
    suspend fun sources(): List<String> = withContext(Dispatchers.IO) {
        val active = BoxModule.unquote(BoxModule.readSetting("bin_name"))
        val keys = com.skofqq.boxy.data.Subscriptions.SUBSCRIPTION_KEYS
        val raw = BoxModule.readSettingsRaw(keys)
        val own = when (active) {
            "clash" -> "subscription_url_clash"
            "sing-box" -> "subscription_url_singbox"
            "xray" -> "subscription_url_xray"
            "hysteria" -> "subscription_url_hysteria"
            else -> null
        }
        val ordered = listOfNotNull(own) + keys.filter { it != own }
        val fromSettings = ordered.flatMap { BoxModule.parseArray(raw[it]) }
        val providers = Shell.cmd(
            "$BOX/bin/yq '.[\"proxy-providers\"][]?.url // \"\"' ${q(CoreTools.activeConfig("clash"))} 2>/dev/null",
        ).exec().out
        (fromSettings + providers).map { it.trim().trim('"') }
            .filter { (it.startsWith("https://") || it.startsWith("http://")) && !it.startsWith("http://127.0.0.1:12345") }
            .distinct()
    }

    private fun host(url: String) = runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.")?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: "subscription"

    suspend fun load(context: Context, target: String, url: String): Outcome = withContext(Dispatchers.IO) {
        val o = when (target) {
            "clash" -> clash(context, url)
            "sing-box" -> singBox(context, url)
            "xray" -> xray(context, url)
            "hysteria" -> hysteria(context, url)
            else -> Outcome(false, error = "core")
        }
        if (o.ok) o.profile?.updateHours?.let { applyUpdateInterval(it) }
        o
    }

    /** The module's default update schedule (settings.ini interva_update): it counts as "not set by the user". */
    private const val DEFAULT_INTERVAL = "0 0,6,12,18 * * *"

    /**
     * The panel's profile-update-interval (hours) becomes the module's update schedule, unless the user set one of
     * their own (anything but the module's default).
     */
    suspend fun applyUpdateInterval(hours: Int): Boolean {
        val current = BoxModule.unquote(BoxModule.readSetting("interva_update")).orEmpty().trim()
        if (current.isNotEmpty() && current != DEFAULT_INTERVAL) return false
        val cron = when {
            hours <= 0 -> return false
            hours < 24 -> "0 */$hours * * *"
            else -> "0 4 */${(hours / 24).coerceIn(1, 30)} * *"
        }
        if (cron == current) return true
        return BoxModule.writeSetting("interva_update", cron)
    }

    private fun failed(r: SubClient.Reply?): Outcome = when {
        r == null -> Outcome(false, error = "download")
        r.limit != SubClient.Limit.NONE -> Outcome(false, error = "limit", limit = r.limit, profile = r.profile)
        r.code !in 200..299 -> Outcome(false, error = "HTTP ${r.code}", profile = r.profile)
        else -> Outcome(false, error = "empty", profile = r.profile)
    }

    /** Writes [text] as [dir]/[name], checks it with the core and makes it active; nothing changes when it fails. */
    private suspend fun install(core: String, name: String, text: String): String? {
        val (dir, key) = coreConfig(core)
        val path = "$BOX/$dir/$name"
        val old = BoxModule.readFile(path)
        if (!BoxModule.writeFile(path, text)) return "write"
        val check = ConfigCheck.check(core, path)
        if (check is CheckResult.Failed) {
            if (old != null) BoxModule.writeFile(path, old) else Shell.cmd("rm -f ${q(path)}").exec()
            return check.output
        }
        return if (BoxModule.writeSetting(key, name)) null else "settings.ini"
    }

    private fun coreConfig(core: String) = CoreTools.configKey(core)

    private suspend fun clash(context: Context, url: String): Outcome {
        val r = SubClient.fetch(context, url, SubClient.Format.MIHOMO)
        if (r == null || !r.ok) return failed(r)
        val body = r.body!!
        if (!Regex("(?m)^(proxies|proxy-providers):").containsMatchIn(body)) return Outcome(false, error = "format", profile = r.profile)
        val name = "${host(url)}.yaml"
        install("clash", name, body)?.let { return Outcome(false, error = it, profile = r.profile) }
        // The module renews this config from the URL (renew=true: the whole file, not only its proxies).
        BoxModule.writeSettingRaw("subscription_url_clash", BoxModule.toArray(listOf(url)))
        BoxModule.writeSetting("renew", "true")
        val servers = Regex("(?m)^\\s*-\\s*\\{?\\s*name:").findAll(body).count()
        return Outcome(true, servers, profile = r.profile)
    }

    private suspend fun singBox(context: Context, url: String): Outcome {
        val r = SubClient.fetch(context, url, SubClient.Format.SINGBOX)
        if (r == null || !r.ok) return failed(r)
        val body = r.body!!.trim()
        val name = "${host(url)}.json"
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (json != null && json.has("outbounds")) {
            install("sing-box", name, body + "\n")?.let { return Outcome(false, error = it, profile = r.profile) }
            BoxModule.writeSetting("subscription_url_singbox", url)
            val local = setOf("direct", "block", "dns", "selector", "urltest")
            val n = json.optJSONArray("outbounds")?.let { a -> (0 until a.length()).count { a.optJSONObject(it)?.optString("type") !in local } } ?: 0
            // sing-box rejects unknown keys, so its template carries no icons: they come from the panel's Xray
            // template (same group names), one more request.
            runCatching {
                SubClient.fetch(context, url, SubClient.Format.XRAY)?.takeIf { it.ok }?.body?.trim()?.takeIf { it.startsWith("[") }?.let { t ->
                    com.skofqq.boxy.ui.proxies.GroupIcons.setPanel(context, org.json.JSONArray(t).optJSONObject(0)?.optJSONObject("boxy"))
                }
            }
            return Outcome(true, n, profile = r.profile)
        }
        // A link list: sing-box outbounds on the module's example (its inbounds / DNS / route), selector + urltest.
        val conv = SingBoxLinks.convert(body)
        if (conv.outbounds.isEmpty()) return Outcome(false, error = "format", skipped = conv.skipped, profile = r.profile)
        val template = BoxModule.readFile("$BOX/sing-box/config.example.jsonc")
        install("sing-box", name, SingBoxLinks.config(template, conv.outbounds))?.let {
            return Outcome(false, error = it, skipped = conv.skipped, profile = r.profile)
        }
        // The module's own update expects a full config from this URL: it is not saved as the sing-box subscription.
        return Outcome(true, conv.outbounds.size, conv.skipped, profile = r.profile)
    }

    private suspend fun xray(context: Context, url: String): Outcome {
        var r = SubClient.fetch(context, url, SubClient.Format.XRAY)
        if (r == null || !r.ok) return failed(r)
        var text = r.body!!.trim()
        // Links although Xray JSON was asked for (the panel has no response rule for Boxy's User-Agent): Remnawave
        // also serves its Xray JSON template at <subscription>/json, used when that is a full config.
        if (!text.startsWith("[") && !text.startsWith("{")) {
            jsonUrl(url)?.let { SubClient.fetch(context, it, SubClient.Format.XRAY) }
                ?.takeIf { it.ok && isXrayJson(it.body!!.trim()) }
                ?.let { r = it; text = it.body!!.trim() }
        }
        val reply = r!!
        // A panel template (Xray JSON): an array of full configs, one per server, each with every server (Remnawave's
        // injector); the first one becomes a profile.
        if (text.startsWith("[") || text.startsWith("{")) {
            val cfg = runCatching { if (text.startsWith("[")) org.json.JSONArray(text).getJSONObject(0) else JSONObject(text) }.getOrNull()
                ?: return Outcome(false, error = "format", profile = reply.profile)
            // The panel tags this config's own server "proxy" and the others with their names: it gets its name
            // too (the "remarks"), so the panel in Boxy shows names only. Rules pointing at "proxy" follow.
            val own = cfg.optString("remarks").replace(Regex("[\"\\\\]"), "").trim()
            val obs = cfg.optJSONArray("outbounds")
            val tags = obs?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("tag") } }.orEmpty()
            // Only a name starting with a flag: the template's balancers select servers by that (and by "proxy").
            if (own.isNotEmpty() && own.codePointAt(0) in 0x1F1E6..0x1F1FF && own !in tags && obs != null) {
                (0 until obs.length()).mapNotNull { obs.optJSONObject(it) }.firstOrNull { it.optString("tag") == "proxy" }?.put("tag", own)
                cfg.optJSONObject("routing")?.optJSONArray("rules")?.let { rules ->
                    (0 until rules.length()).mapNotNull { rules.optJSONObject(it) }.filter { it.optString("outboundTag") == "proxy" }.forEach { it.put("outboundTag", own) }
                }
                cfg.optJSONObject("routing")?.optJSONArray("balancers")?.let { bals ->
                    (0 until bals.length()).mapNotNull { bals.optJSONObject(it) }.filter { it.optString("fallbackTag") == "proxy" }.forEach { it.put("fallbackTag", own) }
                }
            }
            cfg.remove("remarks")
            cfg.remove("meta")
            // Group icons the template carries for Boxy (Xray ignores the key; it is not kept in the file).
            com.skofqq.boxy.ui.proxies.GroupIcons.setPanel(context, cfg.optJSONObject("boxy"))
            cfg.remove("boxy")
            val name = "profiles/${host(url)}.json"
            Shell.cmd("mkdir -p $BOX/xray/profiles").exec()
            // The template's own lists (a White List balancer selecting "wl-…") come from subscription_extra_xray.
            val extra = XraySubscription.refreshExtras(context)
            install("xray", name, cfg.toString(2) + "\n")?.let { return Outcome(false, error = it, profile = reply.profile) }
            val n = cfg.optJSONArray("outbounds")?.let { a -> (0 until a.length()).count { a.optJSONObject(it)?.optString("protocol") !in setOf("freedom", "blackhole", "dns", "loopback") } } ?: 0
            return Outcome(true, n + extra, profile = reply.profile)
        }
        if (ShareLinks.links(text).isEmpty()) return Outcome(false, error = "format", profile = reply.profile)
        val prevUrl = XraySubscription.url()
        if (!BoxModule.writeFile(XraySubscription.downloaded("xray"), reply.body)) return Outcome(false, error = "write")
        XraySubscription.setUrl(url)
        val res = XraySubscription.apply("xray")
        if (res.isFailure) {
            XraySubscription.setUrl(prevUrl)
            return Outcome(false, error = res.exceptionOrNull()?.message, profile = reply.profile)
        }
        // The servers carry traffic only through the balancer: the active config gets it (and the last rule).
        XraySubscription.ensureBalancer("xray", byDefault = true)
        val (servers, skipped) = res.getOrThrow()
        return Outcome(true, servers, skipped, profile = reply.profile)
    }


    /** Remnawave's Xray JSON path of a subscription URL: <path>/json, null when it is one already. */
    private fun jsonUrl(url: String): String? {
        val base = url.substringBefore('#').substringBefore('?').trimEnd('/')
        if (base.endsWith("/json") || base.endsWith("/v2ray-json") || base.count { it == '/' } < 3) return null
        val query = url.substringBefore('#').substringAfter('?', "")
        return "$base/json" + (if (query.isNotEmpty()) "?$query" else "")
    }

    /** A full Xray config, or an array of them. */
    private fun isXrayJson(text: String): Boolean = runCatching {
        val cfg = if (text.startsWith("[")) org.json.JSONArray(text).getJSONObject(0) else JSONObject(text)
        cfg.optJSONArray("outbounds")?.let { it.length() > 0 } == true
    }.getOrDefault(false)

    private suspend fun hysteria(context: Context, url: String): Outcome {
        val r = SubClient.fetch(context, url, SubClient.Format.LINKS)
        if (r == null || !r.ok) return failed(r)
        val links = ShareLinks.links(r.body!!)
        val skipped = ArrayList<ShareLinks.Skipped>()
        var first: String? = null
        var count = 0
        for (link in links) {
            val parsed = HysteriaConfig.parseLink(link)
            if (parsed == null) {
                skipped += ShareLinks.Skipped(ShareLinks.linkName(link), link.substringBefore("://").lowercase(), ShareLinks.SkipReason.CORE)
                continue
            }
            val file = com.skofqq.boxy.ui.tools.HysteriaProfiles.fileName(parsed.first ?: ShareLinks.linkName(link))
            if (com.skofqq.boxy.ui.tools.HysteriaProfiles.create(file, parsed.second, keepBandwidth = true)) {
                count++
                if (first == null) first = file
            }
        }
        val f = first ?: return Outcome(false, error = "format", skipped = skipped, profile = r.profile)
        val check = ConfigCheck.check("hysteria", "$BOX/hysteria/$f")
        if (check is CheckResult.Failed) return Outcome(false, error = check.output, skipped = skipped, profile = r.profile)
        BoxModule.writeSetting("name_hysteria_config", f)
        BoxModule.writeSetting("subscription_url_hysteria", url)
        return Outcome(true, count, skipped, profile = r.profile)
    }
}

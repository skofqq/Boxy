package com.skofqq.boxy.root

import com.skofqq.boxy.net.Net
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a core switch would run into: no binary, no config, or a config the core rejects. */
sealed interface CorePrecheck {
    data object Ok : CorePrecheck
    data object NoKernel : CorePrecheck
    data class NoConfig(val path: String) : CorePrecheck
    data class BadConfig(val path: String, val output: String) : CorePrecheck
}

/** Per-core operations that need the core's config or binary: reload, pre-checks, the find-process switch. */
object CoreTools {
    private const val BOX = BoxModule.BOX_DIR

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Config folder and settings key of each core. */
    fun configKey(core: String): Pair<String, String> = when (core) {
        "sing-box" -> "sing-box" to "name_sing_config"
        "xray" -> "xray" to "name_xray_config"
        "v2fly" -> "v2fly" to "name_v2fly_config"
        "hysteria" -> "hysteria" to "name_hysteria_config"
        else -> "clash" to "name_clash_config"
    }

    private fun defaultName(core: String) = if (core == "clash" || core == "hysteria") "config.yaml" else "config.json"

    /** Full path of the active config of [core] (settings.ini name_*_config). */
    suspend fun activeConfig(core: String): String {
        val (dir, key) = configKey(core)
        val name = BoxModule.unquote(BoxModule.readSetting(key))?.takeIf { it.isNotBlank() && !it.contains('$') } ?: defaultName(core)
        return "$BOX/$dir/$name"
    }

    /**
     * sing-box re-reads its config on SIGHUP: it runs the check itself, keeps the old config when the new one fails,
     * and keeps the same process (and the module's iptables). The check is run first so a bad config is reported.
     */
    suspend fun reloadSingBox(): Boolean = withContext(Dispatchers.IO) {
        val cfg = activeConfig("sing-box")
        Shell.cmd(
            // A subshell: "exit" in the shared root shell would close it.
            "(p=\$(cat $BOX/run/box.pid 2>/dev/null); [ -n \"\$p\" ] && kill -0 \"\$p\" 2>/dev/null || exit 1; " +
                "timeout 60 $BOX/bin/sing-box check -D $BOX/sing-box -c ${q(cfg)} >/dev/null 2>&1 || exit 1; " +
                "kill -HUP \"\$p\" && sleep 1 && kill -0 \"\$p\")",
        ).exec().isSuccess
    }

    /** Before switching to [choice]: the binary, the active config and the core's own check of it. */
    suspend fun precheck(choice: CoreChoice): CorePrecheck = withContext(Dispatchers.IO) {
        if (!Shell.cmd("[ -f $BOX/bin/${choice.binary} ]").exec().isSuccess) return@withContext CorePrecheck.NoKernel
        val core = choice.binName
        val cfg = activeConfig(core)
        if (!Shell.cmd("[ -s ${q(cfg)} ]").exec().isSuccess) return@withContext CorePrecheck.NoConfig(cfg)
        if (core == "hysteria") {
            val text = BoxModule.readFile(cfg).orEmpty()
            return@withContext if (Regex("(?m)^server:\\s*\\S").containsMatchIn(text)) CorePrecheck.Ok
            else CorePrecheck.BadConfig(cfg, "server: is missing")
        }
        val cmd = when (core) {
            "clash" -> "timeout 30 $BOX/bin/${choice.binary} -t -d $BOX/clash -f ${q(cfg)}"
            "sing-box" -> "timeout 30 $BOX/bin/sing-box check -D $BOX/sing-box -c ${q(cfg)}"
            "xray" -> "XRAY_LOCATION_ASSET=$BOX/xray timeout 30 $BOX/bin/xray -test -confdir $BOX/xray"
            else -> "V2RAY_LOCATION_ASSET=$BOX/v2fly timeout 30 $BOX/bin/v2fly test -d $BOX/v2fly"
        }
        val r = Shell.cmd("$cmd 2>&1").exec()
        if (r.isSuccess) CorePrecheck.Ok else CorePrecheck.BadConfig(cfg, errorLines(r.out))
    }

    private fun errorLines(lines: List<String>): String {
        val clean = lines.map { RootTask.stripAnsi(it).trim() }.filter { it.isNotEmpty() }
        val errors = clean.filter { l -> Regex("(?i)error|fatal|failed|invalid|yaml:|not found").containsMatchIn(l) }
        return errors.ifEmpty { clean }.takeLast(8).joinToString("\n").ifBlank { "exit code != 0" }
    }

    // ---- find-process: tie connections to apps (per-app traffic, PROCESS-NAME / package_name rules) ----

    /** On / off for mihomo (find-process-mode always / strict, "off" counts as off) and sing-box (route.find_process). */
    suspend fun findProcess(core: String): Boolean? {
        val text = BoxModule.readFile(activeConfig(core)) ?: return null
        return when (core) {
            "clash" -> Regex("(?m)^find-process-mode:\\s*\"?(\\w+)").find(text)?.groupValues?.get(1) == "always"
            "sing-box" -> Regex("\"find_process\"\\s*:\\s*true").containsMatchIn(routeBlock(text) ?: return false)
            else -> null
        }
    }

    /** Writes the switch into the active config and makes the running core re-read it. */
    suspend fun setFindProcess(core: String, on: Boolean): Boolean {
        val path = activeConfig(core)
        val text = BoxModule.readFile(path) ?: return false
        val edited = when (core) {
            "clash" -> setYamlTopLevel(text, "find-process-mode", if (on) "always" else "strict")
            "sing-box" -> setSingBoxRouteFlag(text, "find_process", on)
            else -> return false
        }
        if (edited == text) return true
        if (!BoxModule.writeFile(path, edited)) return false
        if (!BoxModule.state().running) return true
        if (core == "clash") Net.clashApi(core)?.let { if (Net.clashReload(it)) return true }
        return BoxModule.reloadConfig(core)
    }

    fun setYamlTopLevel(text: String, key: String, value: String): String {
        val re = Regex("(?m)^${Regex.escape(key)}:[^\\n]*")
        return if (re.containsMatchIn(text)) re.replaceFirst(text, "$key: $value")
        else "$key: $value\n$text"
    }

    /** The "route" object's text, or null when the config has none. */
    private fun routeBlock(text: String): String? {
        val m = Regex("\"route\"\\s*:\\s*\\{").find(text) ?: return null
        val end = matchingBrace(text, m.range.last) ?: return null
        return text.substring(m.range.last, end + 1)
    }

    /** Index of the brace closing the one at [open], skipping strings. */
    fun matchingBrace(text: String, open: Int): Int? {
        var depth = 0
        var i = open
        var inStr = false
        while (i < text.length) {
            val c = text[i]
            if (inStr) {
                if (c == '\\') i++ else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '{', '[' -> depth++
                '}', ']' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return null
    }

    /** Sets route.<flag> in a sing-box config without reformatting the rest of it. */
    fun setSingBoxRouteFlag(text: String, flag: String, on: Boolean): String {
        val m = Regex("\"route\"\\s*:\\s*\\{").find(text)
        if (m == null) {
            val first = text.indexOf('{')
            if (first < 0) return text
            return text.substring(0, first + 1) + "\n  \"route\": {\n    \"$flag\": $on\n  }," + text.substring(first + 1)
        }
        val end = matchingBrace(text, m.range.last) ?: return text
        val block = text.substring(m.range.last, end + 1)
        val re = Regex("\"${Regex.escape(flag)}\"\\s*:\\s*(true|false)")
        val newBlock = if (re.containsMatchIn(block)) {
            re.replaceFirst(block, "\"$flag\": $on")
        } else {
            val empty = block.substring(1, block.length - 1).isBlank()
            "{\n    \"$flag\": $on" + (if (empty) "\n  }" else "," + block.substring(1))
        }
        return text.substring(0, m.range.last) + newBlock + text.substring(end + 1)
    }

    // ---- GeoIP / GeoSite source (settings.ini geo_source, module v1.10.9+) ----

    /** The primary source (geo_source) and the extra ones (geo_extra), without the primary. */
    suspend fun geoSources(): Pair<com.skofqq.boxy.configs.GeoSource, List<com.skofqq.boxy.configs.GeoSource>> {
        val raw = BoxModule.readSettingsRaw(listOf("geo_source", "geo_extra"))
        val primary = com.skofqq.boxy.configs.GeoSources.byId(BoxModule.unquote(raw["geo_source"]))
        val extras = BoxModule.parseArray(raw["geo_extra"]).mapNotNull { id -> com.skofqq.boxy.configs.GeoSources.ALL.firstOrNull { it.id == id } }
            .filter { it.id != primary.id }.distinct()
        return primary to extras
    }

    suspend fun setGeoSources(primary: String, extras: List<String>): Boolean =
        BoxModule.writeSetting("geo_source", primary) && BoxModule.writeSettingRaw("geo_extra", BoxModule.toArray(extras.filter { it != primary }))

    /** mihomo reads GeoIP from Country.mmdb unless geodata-mode is on: extra GeoIP sources then do nothing. */
    suspend fun mihomoGeodataMode(): Boolean? {
        val text = BoxModule.readFile(activeConfig("clash")) ?: return null
        return Regex("(?m)^geodata-mode:\\s*true").containsMatchIn(text)
    }

    suspend fun enableMihomoGeodataMode(): Boolean {
        val path = activeConfig("clash")
        val text = BoxModule.readFile(path) ?: return false
        return BoxModule.writeFile(path, setYamlTopLevel(text, "geodata-mode", "true"))
    }

    /** Whether the installed module reads geo_source (older box.tool ignores it). */
    suspend fun geoSourceSupported(): Boolean = withContext(Dispatchers.IO) {
        Shell.cmd("grep -q geo_source ${BoxModule.SCRIPTS}/box.tool").exec().isSuccess
    }
}

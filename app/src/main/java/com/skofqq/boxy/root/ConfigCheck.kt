package com.skofqq.boxy.root

import com.skofqq.boxy.util.withAppLocale
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface CheckResult {
    data object Ok : CheckResult

    /** The core has no check command (hysteria) or its binary is missing. */
    data object Unsupported : CheckResult

    data class Failed(val output: String) : CheckResult
}

/** Runs the same config test box.service runs before starting a core. */
object ConfigCheck {
    private const val BOX = BoxModule.BOX_DIR
    private val EXTENSIONS = setOf("yaml", "yml", "json")

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Core whose config folder holds [path] directly (sub-folders such as rule-sets are not configs). */
    fun coreFor(path: String): String? {
        val parent = path.substringBeforeLast('/')
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext !in EXTENSIONS) return null
        if (parent == SINGBOX_OVERRIDES) return "sing-box"
        return CORES.firstOrNull { parent == "$BOX/$it" }
    }

    /** sing-box user overrides, merged over the config with -C (see box.service). */
    const val SINGBOX_OVERRIDES = "$BOX/sing-box/.overrides"

    /** "-C <dir>" when the overrides folder has files, for every sing-box check. */
    const val OVERRIDES_ARG = "\$(ls $SINGBOX_OVERRIDES/*.json >/dev/null 2>&1 && echo '-C $SINGBOX_OVERRIDES')"

    /** sing-box's "deprecated" warnings from the last passed check (empty for the other cores). */
    @Volatile
    var lastWarnings: List<String> = emptyList()
        private set

    suspend fun check(core: String, file: String): CheckResult = withContext(Dispatchers.IO) {
        val dir = "$BOX/$core"
        val cmd = when (core) {
            "clash" -> "b=$BOX/bin/clash; [ -x \"\$b\" ] || b=$BOX/bin/xclash/mihomo; " +
                "[ -x \"\$b\" ] || exit 127; timeout 30 \"\$b\" -t -d ${q(dir)} -f ${q(file)} 2>&1"
            "sing-box" -> "b=$BOX/bin/sing-box; [ -x \"\$b\" ] || exit 127; timeout 30 \"\$b\" check -D ${q(dir)} -c ${q(file)} $OVERRIDES_ARG 2>&1"
            "xray" -> "b=$BOX/bin/xray; [ -x \"\$b\" ] || exit 127; " +
                // A profile is checked with Boxy's files and the subscriptions it uses, as it would run.
                "XRAY_LOCATION_ASSET=${q(dir)} timeout 30 \"\$b\" run -test " +
                (if (CoreTools.isXrayProfile(file)) CoreTools.xrayArgs(file) else "-c ${q(file)}") + " 2>&1"
            "v2fly" -> "b=$BOX/bin/v2fly; [ -x \"\$b\" ] || exit 127; " +
                "V2RAY_LOCATION_ASSET=${q(dir)} timeout 30 \"\$b\" test -c ${q(file)} 2>&1"
            // hysteria has no check command: Boxy checks the YAML, the server part and the module's blocks itself.
            "hysteria" -> return@withContext checkHysteria(file)
            else -> return@withContext CheckResult.Unsupported
        }
        val r = Shell.cmd(cmd).exec()
        // sing-box warns about deprecated settings even when the check passes: kept for the editor.
        lastWarnings = if (core == "sing-box" && r.isSuccess) com.skofqq.boxy.configs.SingBoxLint.coreWarnings(r.out + r.err) else emptyList()
        when {
            r.code == 127 -> CheckResult.Unsupported
            r.isSuccess -> CheckResult.Ok
            else -> CheckResult.Failed(errorText(r.out + r.err))
        }
    }

    private suspend fun checkHysteria(file: String): CheckResult {
        val text = BoxModule.readFile(file) ?: return CheckResult.Unsupported
        val mode = BoxModule.unquote(BoxModule.readSetting("network_mode"))
        val ctx = com.skofqq.boxy.BoxyApp.instance.withAppLocale()
        val errors = com.skofqq.boxy.configs.HysteriaConfig.check(text, mode).filter { it.error }
        return if (errors.isEmpty()) CheckResult.Ok
        else CheckResult.Failed(errors.joinToString("\n") { p -> ctx.getString(p.message, p.arg) })
    }

    /** Checks unsaved text: writes it next to the original (relative includes still resolve), tests, deletes. */
    suspend fun checkText(core: String, path: String, content: String): CheckResult {
        if (path.substringBeforeLast('/') == SINGBOX_OVERRIDES) return checkOverrides(path, content)
        val ext = path.substringAfterLast('.', "yaml")
        val tmp = "${path.substringBeforeLast('/')}/.boxy-check.$ext"
        if (!BoxModule.writeFile(tmp, content)) return CheckResult.Unsupported
        return try {
            check(core, tmp)
        } finally {
            withContext(Dispatchers.IO) { Shell.cmd("rm -f ${q(tmp)}").exec() }
        }
    }

    /**
     * An overrides file is checked merged with the active config, as the module runs it: the edited text goes into
     * a scratch folder together with the other override files.
     */
    private suspend fun checkOverrides(path: String, content: String): CheckResult = withContext(Dispatchers.IO) {
        val tmp = "$BOX/sing-box/.boxy-overrides-check"
        val name = path.substringAfterLast('/')
        Shell.cmd("rm -rf ${q(tmp)}; mkdir -p ${q(tmp)}; for f in $SINGBOX_OVERRIDES/*.json; do [ -f \"\$f\" ] && [ \"\${f##*/}\" != ${q(name)} ] && cp \"\$f\" ${q(tmp)}/; done").exec()
        if (!BoxModule.writeFile("$tmp/$name", content)) return@withContext CheckResult.Unsupported
        try {
            val cfg = CoreTools.activeConfig("sing-box")
            val r = Shell.cmd("[ -x $BOX/bin/sing-box ] && timeout 30 $BOX/bin/sing-box check -D $BOX/sing-box -c ${q(cfg)} -C ${q(tmp)} 2>&1").exec()
            when {
                r.isSuccess -> CheckResult.Ok
                r.out.isEmpty() -> CheckResult.Unsupported
                else -> CheckResult.Failed(errorText(r.out + r.err))
            }
        } finally {
            Shell.cmd("rm -rf ${q(tmp)}").exec()
        }
    }

    /** The lines that explain the failure: mihomo / sing-box print a lot of info lines first. */
    private fun errorText(lines: List<String>): String {
        val clean = lines.map { RootTask.stripAnsi(it).trim() }.filter { it.isNotEmpty() }
        val errors = clean.filter { l ->
            val low = l.lowercase()
            "error" in low || "fatal" in low || "failed" in low || "yaml:" in low || "invalid" in low
        }
        return (errors.ifEmpty { clean }).takeLast(12).joinToString("\n")
            .replace(Regex("time=\"[^\"]*\"\\s*"), "")
            .ifBlank { "exit code != 0" }
    }
}

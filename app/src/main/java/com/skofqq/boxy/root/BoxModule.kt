package com.skofqq.boxy.root

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the device offers for running the Box service. */
enum class Environment { READY, NO_ROOT, NO_MODULE, NO_SCRIPTS }

data class ServiceState(
    val running: Boolean,
    val pid: String?,
    val core: String?,
    val mode: String?,
    val ipv6: Boolean?,
    val moduleVersion: String?,
    /** Seconds since the pid file was written, null when stopped. */
    val uptimeSec: Long?,
)

data class ServiceDetails(
    val pid: String?,
    val coreVersion: String?,
    val memoryBytes: Long?,
    val pssBytes: Long?,
    val ussBytes: Long?,
    val currentCpu: String?,
    val cpuAffinity: String?,
)

/** Resolver addresses seen by whoami.akamai.net (see [BoxModule.dnsWhoami]); null when there was no answer. */
data class DnsWhoami(val viaCore: String?, val viaDnscrypt: String?, val direct: String?) {
    /** The core answered with a fake-ip address, so its upstream cannot be seen. */
    val coreFakeIp: Boolean get() = viaCore?.let { it.startsWith("198.18.") || it.startsWith("198.19.") } == true
}

/** Optional DNSCrypt of the module (ru.5+). [enabled] is null when the module has no dnscrypt setting. */
data class DnsCryptState(
    val enabled: Boolean?,
    val version: String?,
    val running: Boolean,
    val port: String,
)

data class SystemEnvironment(
    val android: String,
    val kernel: String?,
    val totalMemoryBytes: Long?,
    val ipset: IpsetStatus,
)

enum class IpsetStatus { AVAILABLE, MISSING_BINARY, NOT_SUPPORTED }

/** Cores the module can run and the routing modes it supports (see settings.ini). */
val CORES = listOf("clash", "sing-box", "xray", "v2fly", "hysteria")
val NETWORK_MODES = listOf("redirect", "tproxy", "mixed", "enhance", "tun")

/** A core as the user picks it; clash comes as two builds (xclash_option). [binary] is relative to bin/. */
data class CoreChoice(val id: String, val title: String, val binName: String, val xclash: String?, val binary: String)

val CORE_CHOICES = listOf(
    CoreChoice("mihomo", "Clash Mihomo", "clash", "mihomo", "xclash/mihomo"),
    CoreChoice("premium", "Clash Premium", "clash", "premium", "xclash/premium"),
    CoreChoice("sing-box", "Sing-Box", "sing-box", null, "sing-box"),
    CoreChoice("xray", "Xray", "xray", null, "xray"),
    CoreChoice("v2fly", "V2Ray", "v2fly", null, "v2fly"),
    CoreChoice("hysteria", "Hysteria", "hysteria", null, "hysteria"),
)

/** Core, network mode and IPv6 from settings.ini, plus the cores whose binaries are present. */
data class ModuleSetup(val core: CoreChoice?, val mode: String?, val ipv6: Boolean?, val installed: Set<String>)

/**
 * Thin wrapper around the Box for Root module files and scripts.
 * Paths and commands follow the module's own action.sh / box.service / box.tool.
 */
object BoxModule {
    const val BOX_DIR = "/data/adb/box"
    const val MODULE_DIR = "/data/adb/modules/box_for_root"
    const val SCRIPTS = "$BOX_DIR/scripts"
    const val RUN_DIR = "$BOX_DIR/run"
    const val SETTINGS = "$BOX_DIR/settings.ini"
    private const val PID_FILE = "$RUN_DIR/box.pid"

    suspend fun environment(): Environment = withContext(Dispatchers.IO) {
        // A shell opened before the user answered the root prompt stays non-root; reopen it once.
        var shell = Shell.getShell()
        if (!shell.isRoot) {
            shell.close()
            shell = Shell.getShell()
        }
        if (!shell.isRoot) return@withContext Environment.NO_ROOT
        when {
            !exists(MODULE_DIR) -> Environment.NO_MODULE
            !exists("$SCRIPTS/box.service") -> Environment.NO_SCRIPTS
            else -> Environment.READY
        }
    }

    suspend fun state(): ServiceState = withContext(Dispatchers.IO) {
        val out = Poll.run(
            """
            p=${'$'}(cat $PID_FILE 2>/dev/null)
            if [ -n "${'$'}p" ] && [ -e /proc/${'$'}p ]; then echo "pid=${'$'}p"; echo "age=${'$'}(( ${'$'}(date +%s) - ${'$'}(stat -c %Y $PID_FILE) ))"; fi
            grep -E '^(bin_name|network_mode|ipv6)=' $SETTINGS 2>/dev/null
            grep '^version=' $MODULE_DIR/module.prop 2>/dev/null
            """.trimIndent(),
        )
        val kv = parseKv(out)
        val pid = kv["pid"]
        ServiceState(
            running = pid != null,
            pid = pid,
            core = kv["bin_name"],
            mode = kv["network_mode"],
            ipv6 = kv["ipv6"]?.let { it == "true" },
            moduleVersion = kv["version"],
            uptimeSec = kv["age"]?.toLongOrNull(),
        )
    }

    suspend fun moduleSetup(): ModuleSetup = withContext(Dispatchers.IO) {
        val checks = CORE_CHOICES.joinToString("; ") { "[ -f $BOX_DIR/bin/${it.binary} ] && echo inst_${it.id}=1" }
        val kv = parseKv(sh("grep -E '^(bin_name|xclash_option|network_mode|ipv6)=' $SETTINGS 2>/dev/null; $checks"))
        val bin = unquote(kv["bin_name"])
        val xclash = unquote(kv["xclash_option"])?.ifBlank { null } ?: "mihomo"
        ModuleSetup(
            core = CORE_CHOICES.firstOrNull { it.binName == bin && (it.xclash == null || it.xclash == xclash) },
            mode = unquote(kv["network_mode"]),
            ipv6 = unquote(kv["ipv6"])?.let { it == "true" },
            installed = CORE_CHOICES.filter { kv["inst_${it.id}"] == "1" }.map { it.id }.toSet(),
        )
    }

    /** Same order as the module's action button: service first, then iptables. */
    suspend fun start(): Boolean = run("$SCRIPTS/box.service start && $SCRIPTS/box.iptables enable")

    suspend fun stop(): Boolean = run("$SCRIPTS/box.iptables disable; $SCRIPTS/box.service stop")

    suspend fun restart(): Boolean = run("$SCRIPTS/box.service restart")

    /** Asks the running core to re-read its config (box.tool reload). */
    suspend fun reloadConfig(): Boolean = run("$SCRIPTS/box.tool reload")

    suspend fun readSetting(key: String): String? = withContext(Dispatchers.IO) {
        parseKv(sh("grep -m1 '^$key=' $SETTINGS 2>/dev/null"))[key]
    }

    /** Raw right-hand side of key=... (keeps bash arrays intact). */
    suspend fun readSettingRaw(key: String): String? = withContext(Dispatchers.IO) {
        sh("grep -m1 '^$key=' $SETTINGS 2>/dev/null").firstOrNull()?.substringAfter('=')?.trim()
    }

    /** Raw values of several keys in one call; missing keys are absent from the map. */
    suspend fun readSettingsRaw(keys: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        val pattern = keys.joinToString("|")
        sh("grep -E '^($pattern)=' $SETTINGS 2>/dev/null").mapNotNull { line ->
            val k = line.substringBefore('=', "")
            if (k.isEmpty()) null else k to line.substringAfter('=').trim()
        }.toMap()
    }

    /** Unquoted scalar from a raw settings value. */
    fun unquote(raw: String?): String? = raw?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")

    /** Writes key="value" into settings.ini, replacing the line or appending it. */
    suspend fun writeSetting(key: String, value: String): Boolean = writeSettingRaw(key, "\"$value\"")

    /** Writes key=<raw> (raw may be a quoted string or a bash array). */
    suspend fun writeSettingRaw(key: String, raw: String): Boolean = withContext(Dispatchers.IO) {
        val sedValue = raw.replace("\\", "\\\\").replace("/", "\\/").replace("&", "\\&").replace("'", "'\\''")
        val echoValue = raw.replace("'", "'\\''")
        Shell.cmd(
            "if grep -q '^$key=' $SETTINGS; then sed -i 's/^$key=.*/$key=$sedValue/' $SETTINGS; " +
                "else echo '$key=$echoValue' >> $SETTINGS; fi",
        ).exec().isSuccess
    }

    suspend fun details(state: ServiceState): ServiceDetails = withContext(Dispatchers.IO) {
        val pid = state.pid ?: return@withContext ServiceDetails(null, null, null, null, null, null, null)
        val core = state.core ?: "clash"
        val out = Poll.run(
            """
            b=$BOX_DIR/bin/$core
            [ -x "${'$'}b" ] || b=${'$'}(readlink /proc/$pid/exe)
            case "$core" in
              clash) v=${'$'}("${'$'}b" -v 2>/dev/null | head -n1) ;;
              *) v=${'$'}("${'$'}b" version 2>/dev/null | head -n1) ;;
            esac
            echo "ver=${'$'}v"
            echo "rss=${'$'}(grep '^VmRSS:' /proc/$pid/status | awk '{print ${'$'}2}')"
            awk '/^Pss:/{p=${'$'}2} /^Private_(Clean|Dirty):/{u+=${'$'}2} END{print "pss=" p; print "uss=" u}' /proc/$pid/smaps_rollup 2>/dev/null
            echo "cpu=${'$'}(awk '{print ${'$'}39}' /proc/$pid/stat)"
            echo "aff=${'$'}(grep '^Cpus_allowed_list:' /proc/$pid/status | awk '{print ${'$'}2}')"
            """.trimIndent(),
        )
        val kv = parseKv(out)
        ServiceDetails(
            pid = pid,
            coreVersion = kv["ver"]?.substringBefore(" linux")?.substringBefore(" android")?.trim()?.takeIf { it.isNotBlank() },
            memoryBytes = kv["rss"]?.toLongOrNull()?.times(1024),
            pssBytes = kv["pss"]?.toLongOrNull()?.times(1024),
            ussBytes = kv["uss"]?.toLongOrNull()?.times(1024),
            currentCpu = kv["cpu"]?.takeIf { it.isNotBlank() },
            cpuAffinity = kv["aff"]?.takeIf { it.isNotBlank() },
        )
    }

    /** CPU ticks of the process and of the whole system, plus resident memory, for CPU% sampling. */
    data class ProcSample(val procTicks: Long, val totalTicks: Long, val rssBytes: Long, val cpuCount: Int)

    suspend fun sample(pid: String): ProcSample? = withContext(Dispatchers.IO) {
        val out = Poll.run(
            "awk '{print ${'$'}14+${'$'}15}' /proc/$pid/stat 2>/dev/null; " +
                "head -n1 /proc/stat; grep '^VmRSS:' /proc/$pid/status | awk '{print ${'$'}2}'; grep -c '^processor' /proc/cpuinfo",
        )
        if (out.size < 4) return@withContext null
        val proc = out[0].trim().toLongOrNull() ?: return@withContext null
        val total = out[1].trim().split(Regex("\\s+")).drop(1).sumOf { it.toLongOrNull() ?: 0L }
        ProcSample(proc, total, (out[2].trim().toLongOrNull() ?: 0L) * 1024, out[3].trim().toIntOrNull() ?: 1)
    }

    suspend fun systemEnvironment(): SystemEnvironment = withContext(Dispatchers.IO) {
        val out = Poll.run(
            """
            echo "kernel=${'$'}(uname -r)"
            echo "mem=${'$'}(grep '^MemTotal:' /proc/meminfo | awk '{print ${'$'}2}')"
            if [ -r /proc/config.gz ] && (zcat /proc/config.gz 2>/dev/null || gzip -dc /proc/config.gz 2>/dev/null) | grep -q '^CONFIG_IP_SET=[ym]${'$'}'; then k=yes; else k=no; fi
            if command -v ipset >/dev/null 2>&1 || [ -x /system/bin/ipset ]; then b=yes; else b=no; fi
            echo "ipset=${'$'}k,${'$'}b"
            """.trimIndent(),
        )
        val kv = parseKv(out)
        val flags = (kv["ipset"] ?: "no,no").split(',').map { it == "yes" }
        val kernelOk = flags.getOrElse(0) { false }
        val binOk = flags.getOrElse(1) { false }
        SystemEnvironment(
            android = "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
            kernel = kv["kernel"],
            totalMemoryBytes = kv["mem"]?.toLongOrNull()?.times(1024),
            ipset = when {
                !kernelOk -> IpsetStatus.NOT_SUPPORTED
                !binOk -> IpsetStatus.MISSING_BINARY
                else -> IpsetStatus.AVAILABLE
            },
        )
    }

    /** Total bytes received / sent on all interfaces except loopback. */
    suspend fun netCounters(): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        val lines = runCatching { java.io.File("/proc/net/dev").readLines() }.getOrNull()
            ?.takeIf { it.size > 2 } ?: Poll.run("cat /proc/net/dev 2>/dev/null")
        var rx = 0L
        var tx = 0L
        var any = false
        lines.drop(2).forEach { line ->
            val name = line.substringBefore(':').trim()
            if (name.isEmpty() || name == "lo" || name.startsWith("dummy")) return@forEach
            val f = line.substringAfter(':').trim().split(Regex("\\s+"))
            if (f.size >= 9) {
                rx += f[0].toLongOrNull() ?: 0L
                tx += f[8].toLongOrNull() ?: 0L
                any = true
            }
        }
        if (any) rx to tx else null
    }

    /**
     * Hotspot / tethering clients are proxied through the "allow <iface>" lines of ap.list.cfg.
     * Turning it off comments those lines out ("#allow ..."), so the choice survives restarts and module updates.
     */
    suspend fun hotspotProxyEnabled(): Boolean = withContext(Dispatchers.IO) {
        Shell.cmd("grep -q '^allow ' $BOX_DIR/ap.list.cfg").exec().isSuccess
    }

    suspend fun setHotspotProxy(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val sed = if (enabled) "s/^#allow /allow /" else "s/^allow /#allow /"
        val ok = Shell.cmd("sed -i '$sed' $BOX_DIR/ap.list.cfg").exec().isSuccess
        // Apply right away when the service runs.
        if (ok) Shell.cmd("[ -f $PID_FILE ] && $SCRIPTS/box.iptables renew >/dev/null 2>&1").exec()
        ok
    }

    /** Start at boot unless /data/adb/box/manual exists (the module's manual mode). */
    suspend fun autostartEnabled(): Boolean = withContext(Dispatchers.IO) { !exists("$BOX_DIR/manual") }

    suspend fun setAutostart(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        Shell.cmd(if (enabled) "rm -f $BOX_DIR/manual" else "touch $BOX_DIR/manual").exec().isSuccess
    }

    /** dns_hijack in settings.ini (ru.3+): null when the installed module does not have it. */
    suspend fun dnsHijack(): Boolean? = readSetting("dns_hijack")?.let { unquote(it) != "false" }

    suspend fun setDnsHijack(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val ok = writeSettingRaw("dns_hijack", enabled.toString())
        if (ok) Shell.cmd("[ -f $PID_FILE ] && $SCRIPTS/box.iptables renew >/dev/null 2>&1").exec()
        ok
    }

    suspend fun dnscrypt(): DnsCryptState = withContext(Dispatchers.IO) {
        val out = sh(
            "grep -E '^(dnscrypt|dnscrypt_port)=' $SETTINGS 2>/dev/null; " +
                "[ -x $BOX_DIR/bin/dnscrypt-proxy ] && echo \"ver=$($BOX_DIR/bin/dnscrypt-proxy -version 2>/dev/null | head -n1)\"; " +
                "p=$(cat $RUN_DIR/dnscrypt.pid 2>/dev/null); [ -n \"${'$'}p\" ] && [ -e /proc/${'$'}p ] && echo run=1",
        )
        val kv = parseKv(out)
        DnsCryptState(
            enabled = kv["dnscrypt"]?.let { unquote(it) == "true" },
            version = kv["ver"]?.takeIf { it.isNotBlank() },
            running = kv["run"] == "1",
            port = unquote(kv["dnscrypt_port"])?.takeIf { it.isNotBlank() } ?: "5354",
        )
    }

    suspend fun setDnscrypt(enabled: Boolean): Boolean = writeSetting("dnscrypt", enabled.toString())

    /** Cheap DNSCrypt status for polling: (enabled or null without the setting, dnscrypt-proxy running). */
    suspend fun dnscryptStatus(): Pair<Boolean?, Boolean> = withContext(Dispatchers.IO) {
        val kv = parseKv(
            sh(
                "grep -m1 '^dnscrypt=' $SETTINGS 2>/dev/null; " +
                    "p=$(cat $RUN_DIR/dnscrypt.pid 2>/dev/null); [ -n \"${'$'}p\" ] && [ -e /proc/${'$'}p ] && echo run=1",
            ),
        )
        kv["dnscrypt"]?.let { unquote(it) == "true" } to (kv["run"] == "1")
    }

    /**
     * Which resolver the outside world sees: whoami.akamai.net answers with the address the query came from.
     * [viaCore] is asked like an app does (1.1.1.1:53, taken over by the DNS hijack), [viaDnscrypt] straight at
     * dnscrypt-proxy, [direct] from the core's user and group, which the module's rules let past the proxy.
     */
    suspend fun dnsWhoami(dnscryptPort: String): DnsWhoami = withContext(Dispatchers.IO) {
        val out = sh(
            "BB=; for b in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do [ -x \$b ] && BB=\$b && break; done; " +
                "[ -z \"\$BB\" ] && exit 0; " +
                "ug=\$(grep -m1 '^box_user_group=' $SETTINGS | cut -d= -f2 | tr -d '\"'); " +
                "q() { \$BB timeout 8 \"\$@\" 2>/dev/null | \$BB awk '/^Name:/{n=1} n&&/^Address/{print \$3; exit}'; }; " +
                "echo core=\$(q \$BB nslookup whoami.akamai.net 1.1.1.1); " +
                "echo crypt=\$(q \$BB nslookup whoami.akamai.net 127.0.0.1:$dnscryptPort); " +
                "echo direct=\$(q \$BB setuidgid \${ug:-root:net_admin} \$BB nslookup whoami.akamai.net 1.1.1.1)",
        )
        val kv = parseKv(out)
        DnsWhoami(kv["core"]?.ifBlank { null }, kv["crypt"]?.ifBlank { null }, kv["direct"]?.ifBlank { null })
    }

    suspend fun subStoreInstalled(): Boolean = withContext(Dispatchers.IO) { exists("/data/adb/modules/sub_store") }

    suspend fun readFile(path: String): String? = withContext(Dispatchers.IO) {
        val r = Shell.cmd("cat '$path' 2>/dev/null").exec()
        if (r.isSuccess) r.out.joinToString("\n") else null
    }

    /** Writes text to a root-owned file (content goes through base64, so any characters are safe). */
    suspend fun writeFile(path: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val b64 = android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)
        val job = Shell.getShell().newJob()
        // Long files are sent in chunks to stay well under the shell's line limit.
        job.add("rm -f '$path.boxy.tmp'")
        b64.chunked(8000).forEach { job.add("printf '%s' '$it' >> '$path.boxy.tmp'") }
        job.add("base64 -d '$path.boxy.tmp' > '$path' && rm -f '$path.boxy.tmp'")
        job.exec().isSuccess
    }

    /** Runs a root command and returns its output lines (stdout and stderr). */
    suspend fun exec(cmd: String): Pair<Boolean, List<String>> = withContext(Dispatchers.IO) {
        val r = Shell.cmd("$cmd 2>&1").exec()
        r.isSuccess to r.out
    }

    private suspend fun run(cmd: String): Boolean = withContext(Dispatchers.IO) {
        Shell.cmd("$cmd >/dev/null 2>&1").exec().isSuccess
    }

    fun parseKv(lines: List<String>): Map<String, String> = lines.mapNotNull { line ->
        val key = line.substringBefore('=', "").trim()
        val value = line.substringAfter('=').trim().trim('"', '\'')
        key.takeIf { it.isNotEmpty() }?.let { it to value }
    }.toMap()

    /** Parses a bash array value like ("a" "b") or a plain value into a list. */
    fun parseArray(raw: String?): List<String> {
        val s = raw?.trim() ?: return emptyList()
        val inner = s.removePrefix("(").removeSuffix(")").trim()
        if (inner.isEmpty()) return emptyList()
        val quoted = Regex("\"([^\"]*)\"").findAll(inner).map { it.groupValues[1] }.toList()
        return (quoted.ifEmpty { inner.split(Regex("\\s+")) }).map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }
    }

    /** Formats a list as a bash array: ("a" "b"). */
    fun toArray(items: List<String>): String = items.joinToString(" ", "(", ")") { "\"${it.replace("\"", "")}\"" }

    private fun exists(path: String): Boolean = Shell.cmd("[ -e '$path' ]").exec().isSuccess

    private fun sh(cmd: String): List<String> = Shell.cmd(cmd).exec().out
}

/**
 * Separate root shell for frequent read-only polling, so status and metrics
 * keep updating while the main shell is busy starting or stopping the service.
 */
object Poll {
    private var shell: Shell? = null

    @Synchronized
    private fun get(): Shell {
        val s = shell
        if (s != null && s.isAlive) return s
        return Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER).setTimeout(10).build().also { shell = it }
    }

    @Synchronized
    fun run(cmd: String): List<String> = runCatching {
        val sh = get()
        if (!sh.isRoot) return emptyList()
        sh.newJob().add(cmd).to(ArrayList(), null).exec().out
    }.getOrDefault(emptyList())
}

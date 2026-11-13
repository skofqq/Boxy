package com.skofqq.boxy.configs

import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.CheckResult
import com.skofqq.boxy.root.ConfigCheck
import com.skofqq.boxy.root.CoreTools
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Logins on the proxy ports (mixed / SOCKS / HTTP) of the active core, so other apps on the phone (or on the
 * network) cannot use or detect them without one. Every core keeps them where an update of the config does not
 * reach them:
 *  - mihomo: clash/.proxy-auth.yaml (authentication, skip-auth-prefixes), put into config.yaml by the module at
 *    every start and reload, and right away here (yq keeps the config's comments);
 *  - sing-box: the proxy inbounds with their users in sing-box/.overrides/proxy-auth.json (an inbound there
 *    replaces the config's one with the same tag);
 *  - Xray: the proxy inbounds with their accounts in xray/zz-boxy-auth.json (a later file's inbound replaces the
 *    one with the same tag);
 *  - hysteria: socks5 / http username and password in its config (it has no subscriptions).
 * Every change is checked by the core before it stays, then the running core re-reads it. Passwords are never
 * logged: they go to root files through base64 (BoxModule.writeFile) or to yq through the environment.
 */
object ProxyAuth {
    private const val BOX = BoxModule.BOX_DIR
    private const val YQ = "$BOX/bin/yq"
    const val CLASH_STORE = "$BOX/clash/.proxy-auth.yaml"
    const val SINGBOX_FILE = "${ConfigCheck.SINGBOX_OVERRIDES}/proxy-auth.json"
    const val XRAY_FILE = "$BOX/xray/zz-boxy-auth.json"

    /** Port 7890 (mixed) / 10808 (socks) on 127.0.0.1, added by [addPort] under these tags. */
    private const val ADDED_MIXED = "boxy-mixed"
    private const val ADDED_SOCKS = "boxy-socks"

    val CORES = setOf("clash", "sing-box", "xray", "hysteria")

    /** A proxy port: [key] is its tag (sing-box, Xray), setting (mihomo) or block (hysteria). */
    data class Port(val key: String, val type: String, val address: String, val ownUsers: Boolean = false)

    data class User(val name: String, val password: String)

    data class State(
        val core: String,
        val ports: List<Port>,
        val users: List<User>,
        /** mihomo: networks that need no login. */
        val skip: List<String> = emptyList(),
        /** Proxy inbounds without a tag: they cannot be replaced, so not protected. */
        val untagged: Int = 0,
    ) {
        val enabled get() = users.isNotEmpty()
    }

    sealed interface Result {
        data object Ok : Result
        data class Failed(val output: String) : Result
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun sh(cmd: String): Pair<Boolean, String> {
        val r = Shell.cmd(cmd).exec()
        return r.isSuccess to (r.out + r.err).joinToString("\n")
    }

    /** JSON with whole-line // comments (sing-box, Xray allow them) read as JSON. */
    private fun parseJson(text: String?): JSONObject? = text?.let {
        runCatching { JSONObject(it.lines().filterNot { l -> l.trimStart().startsWith("//") }.joinToString("\n")) }.getOrNull()
    }

    private fun JSONArray?.objects(): List<JSONObject> = this?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

    suspend fun load(core: String): State? = withContext(Dispatchers.IO) {
        when (core) {
            "clash" -> loadClash()
            "sing-box" -> loadSingBox()
            "xray" -> loadXray()
            "hysteria" -> loadHysteria()
            else -> null
        }
    }

    /** Saves [users] (none = no login) and, for mihomo, [skip]; the core checks the result and reloads it. */
    suspend fun save(core: String, users: List<User>, skip: List<String> = emptyList()): Result = withContext(Dispatchers.IO) {
        val r = when (core) {
            "clash" -> saveClash(users, skip)
            "sing-box" -> saveSingBox(users)
            "xray" -> saveXray(users)
            "hysteria" -> saveHysteria(users)
            else -> Result.Failed("unsupported core")
        }
        if (r == Result.Ok && BoxModule.state().running) {
            if (core == "clash" || core == "sing-box") BoxModule.reloadConfig(core) else BoxModule.restart()
        }
        r
    }

    /** A login on the ports is valid when it has a name without ":" (mihomo keeps "name:password") and a password. */
    fun valid(users: List<User>): Boolean = users.all { it.name.isNotBlank() && ':' !in it.name && it.password.isNotEmpty() }

    // ---- mihomo ----

    private suspend fun loadClash(): State? {
        val cfg = CoreTools.activeConfig("clash")
        val expr = "{\"mixed\": .[\"mixed-port\"], \"http\": .port, \"socks\": .[\"socks-port\"], \"lan\": .[\"allow-lan\"]," +
            " \"bind\": .[\"bind-address\"], \"auth\": .authentication, \"skip\": .[\"skip-auth-prefixes\"]," +
            " \"listeners\": [.listeners[]? | select(.type == \"mixed\" or .type == \"socks\" or .type == \"http\")" +
            " | {\"name\": .name, \"type\": .type, \"port\": .port, \"listen\": .listen, \"users\": ((.users // []) | length)}]}"
        val (ok, out) = sh("$YQ -o=json -I=0 ${q(expr)} ${q(cfg)} 2>/dev/null")
        if (!ok) return null
        val o = runCatching { JSONObject(out.trim()) }.getOrNull() ?: return null
        val lan = o.optBoolean("lan", false)
        val host = if (lan) o.optString("bind").takeIf { it.isNotBlank() && it != "null" && it != "*" } ?: "0.0.0.0" else "127.0.0.1"
        val ports = buildList {
            for ((k, t) in listOf("mixed" to "mixed", "socks" to "socks", "http" to "http")) {
                val p = o.optInt(k, 0)
                if (p > 0) add(Port(if (k == "http") "port" else "$k-port", t, "$host:$p"))
            }
            o.optJSONArray("listeners").objects().forEach { l ->
                add(Port(l.optString("name"), l.optString("type"), "${l.optString("listen").ifBlank { "0.0.0.0" }}:${l.optInt("port")}", ownUsers = l.optInt("users") > 0))
            }
        }
        // The store wins: it is what the module puts into the config.
        val store = BoxModule.readFile(CLASH_STORE)
        val src = if (!store.isNullOrBlank()) {
            sh("$YQ -o=json -I=0 '{\"auth\": .authentication, \"skip\": .[\"skip-auth-prefixes\"]}' ${q(CLASH_STORE)} 2>/dev/null").second
                .let { runCatching { JSONObject(it.trim()) }.getOrNull() } ?: o
        } else o
        val users = (src.optJSONArray("auth")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty())
            .filter { ':' in it }.map { User(it.substringBefore(':'), it.substringAfter(':')) }
        val skip = src.optJSONArray("skip")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        return State("clash", ports, users, skip)
    }

    private suspend fun saveClash(users: List<User>, skip: List<String>): Result {
        val cfg = CoreTools.activeConfig("clash")
        val oldStore = BoxModule.readFile(CLASH_STORE)
        sh("cp -f ${q(cfg)} ${q("$cfg.authbak")}")
        val applied = if (users.isEmpty()) {
            sh("rm -f ${q(CLASH_STORE)}")
            sh("$YQ -i 'del(.authentication) | del(.[\"skip-auth-prefixes\"])' ${q(cfg)}").first
        } else {
            val store = "# Users of the proxy ports, set by Boxy. The module puts them into the config at every start,\n" +
                "# so a subscription update does not drop them.\n" +
                "authentication: " + JSONArray(users.map { "${it.name}:${it.password}" }).toString() + "\n" +
                "skip-auth-prefixes: " + JSONArray(skip.map { it.trim() }.filter { it.isNotEmpty() }).toString() + "\n"
            BoxModule.writeFile(CLASH_STORE, store) && sh("chmod 600 ${q(CLASH_STORE)}").first &&
                sh(
                    "$YQ -i '.authentication = load(\"$CLASH_STORE\").authentication | .[\"skip-auth-prefixes\"] = load(\"$CLASH_STORE\").[\"skip-auth-prefixes\"]" +
                        " | del(.[\"skip-auth-prefixes\"] | select(. == null or length == 0))' ${q(cfg)}",
                ).first
        }
        val check = if (applied) ConfigCheck.check("clash", cfg) else CheckResult.Failed("yq")
        if (check is CheckResult.Failed) {
            sh("mv -f ${q("$cfg.authbak")} ${q(cfg)}")
            if (oldStore != null) BoxModule.writeFile(CLASH_STORE, oldStore) else sh("rm -f ${q(CLASH_STORE)}")
            return Result.Failed(check.output)
        }
        sh("rm -f ${q("$cfg.authbak")}")
        return Result.Ok
    }

    // ---- sing-box ----

    private val PROXY_TYPES = setOf("mixed", "socks", "http")

    private suspend fun singBoxInbounds(): Pair<List<JSONObject>, Int> {
        val cfg = parseJson(BoxModule.readFile(CoreTools.activeConfig("sing-box")))
        val ov = parseJson(BoxModule.readFile(SINGBOX_FILE))
        val own = ov?.optJSONArray("inbounds").objects().filter { it.optString("type") in PROXY_TYPES }
        val base = cfg?.optJSONArray("inbounds").objects().filter { it.optString("type") in PROXY_TYPES }
        val untagged = base.count { it.optString("tag").isBlank() }
        val ownTags = own.map { it.optString("tag") }.toSet()
        return (own + base.filter { it.optString("tag").isNotBlank() && it.optString("tag") !in ownTags }) to untagged
    }

    private suspend fun loadSingBox(): State {
        val (inbounds, untagged) = singBoxInbounds()
        val ports = inbounds.map { Port(it.optString("tag"), it.optString("type"), "${it.optString("listen").ifBlank { "::" }}:${it.optInt("listen_port")}") }
        val users = inbounds.firstNotNullOfOrNull { ib -> ib.optJSONArray("users")?.takeIf { it.length() > 0 } }.objects()
            .map { User(it.optString("username"), it.optString("password")) }
        return State("sing-box", ports, users, untagged = untagged)
    }

    private suspend fun saveSingBox(users: List<User>, extra: JSONObject? = null): Result {
        val cfgPath = CoreTools.activeConfig("sing-box")
        val old = BoxModule.readFile(SINGBOX_FILE)
        val inbounds = singBoxInbounds().first.toMutableList()
        if (extra != null) inbounds += extra
        val arr = JSONArray()
        inbounds.forEach { ib ->
            val c = JSONObject(ib.toString())
            if (users.isEmpty()) c.remove("users")
            else c.put("users", JSONArray(users.map { JSONObject().put("username", it.name).put("password", it.password) }))
            arr.put(c)
        }
        val text = "// The proxy inbounds with their users, set by Boxy (Proxy authentication): they replace the config's\n" +
            "// inbounds with the same tag, so a subscription update keeps the logins.\n" +
            JSONObject().put("inbounds", arr).toString(2) + "\n"
        sh("cp -f ${q(cfgPath)} ${q("$cfgPath.authbak")}; mkdir -p ${q(ConfigCheck.SINGBOX_OVERRIDES)}")
        if (!BoxModule.writeFile(SINGBOX_FILE, text)) return Result.Failed("write")
        sh("chmod 600 ${q(SINGBOX_FILE)}")
        // The module drops the config's inbounds the overrides replace (as at start), then the whole is checked.
        sh("${BoxModule.SCRIPTS}/box.service prepare >/dev/null 2>&1")
        val check = ConfigCheck.check("sing-box", cfgPath)
        if (check is CheckResult.Failed) {
            sh("mv -f ${q("$cfgPath.authbak")} ${q(cfgPath)}")
            if (old != null) BoxModule.writeFile(SINGBOX_FILE, old) else sh("rm -f ${q(SINGBOX_FILE)}")
            return Result.Failed(check.output)
        }
        sh("rm -f ${q("$cfgPath.authbak")}")
        return Result.Ok
    }

    // ---- Xray ----

    /** The proxy inbounds of the merged config Xray runs (all its files), and how many have no tag. */
    private suspend fun xrayInbounds(): Pair<List<JSONObject>, Int>? {
        val args = CoreTools.xrayArgs()
        val (ok, out) = sh("XRAY_LOCATION_ASSET=$BOX/xray timeout 30 $BOX/bin/xray run -dump $args 2>/dev/null")
        if (!ok) return null
        val o = runCatching { JSONObject(out.substring(out.indexOf('{').coerceAtLeast(0))) }.getOrNull() ?: return null
        val all = o.optJSONArray("inbounds").objects().filter { it.optString("protocol") in PROXY_TYPES }
        return all.filter { it.optString("tag").isNotBlank() } to all.count { it.optString("tag").isBlank() }
    }

    private suspend fun loadXray(): State? {
        val (inbounds, untagged) = xrayInbounds() ?: return null
        val ports = inbounds.map { Port(it.optString("tag"), it.optString("protocol"), "${it.optString("listen").ifBlank { "0.0.0.0" }}:${it.opt("port")}") }
        val own = parseJson(BoxModule.readFile(XRAY_FILE))?.optJSONArray("inbounds").objects()
        val users = own.firstNotNullOfOrNull { ib -> ib.optJSONObject("settings")?.optJSONArray("accounts")?.takeIf { it.length() > 0 } }.objects()
            .map { User(it.optString("user"), it.optString("pass")) }
        return State("xray", ports, users, untagged = untagged)
    }

    private suspend fun saveXray(users: List<User>, extra: JSONObject? = null): Result {
        val old = BoxModule.readFile(XRAY_FILE)
        val inbounds = (xrayInbounds()?.first ?: return Result.Failed("xray -dump")).toMutableList()
        if (extra != null) inbounds += extra
        // Without logins only an inbound Boxy added stays in the file; the config's own come back as they are.
        val keep = if (users.isEmpty()) inbounds.filter { it.optString("tag").startsWith("boxy-") } else inbounds
        val arr = JSONArray()
        keep.forEach { ib ->
            val c = JSONObject(ib.toString())
            val s = c.optJSONObject("settings") ?: JSONObject().also { c.put("settings", it) }
            s.remove("accounts")
            if (c.optString("protocol") == "socks") s.put("auth", if (users.isEmpty()) "noauth" else "password")
            if (users.isNotEmpty()) s.put("accounts", JSONArray(users.map { JSONObject().put("user", it.name).put("pass", it.password) }))
            arr.put(c)
        }
        if (arr.length() == 0) sh("rm -f ${q(XRAY_FILE)}")
        else {
            if (!BoxModule.writeFile(XRAY_FILE, JSONObject().put("inbounds", arr).toString(2) + "\n")) return Result.Failed("write")
            sh("chmod 600 ${q(XRAY_FILE)}")
        }
        val check = ConfigCheck.check("xray", CoreTools.activeConfig("xray"))
        if (check is CheckResult.Failed) {
            if (old != null) BoxModule.writeFile(XRAY_FILE, old) else sh("rm -f ${q(XRAY_FILE)}")
            return Result.Failed(check.output)
        }
        return Result.Ok
    }

    // ---- hysteria ----

    private suspend fun loadHysteria(): State? {
        val cfg = CoreTools.activeConfig("hysteria")
        val (ok, out) = sh("$YQ -o=json -I=0 '{\"socks5\": .socks5, \"http\": .http}' ${q(cfg)} 2>/dev/null")
        if (!ok) return null
        val o = runCatching { JSONObject(out.trim()) }.getOrNull() ?: return null
        val blocks = listOf("socks5" to "socks", "http" to "http").mapNotNull { (k, t) -> o.optJSONObject(k)?.let { Triple(k, t, it) } }
        val ports = blocks.map { (k, t, b) -> Port(k, t, b.optString("listen")) }
        val user = blocks.firstNotNullOfOrNull { (_, _, b) ->
            b.optString("username").takeIf { it.isNotBlank() && it != "null" }?.let { User(it, b.optString("password")) }
        }
        return State("hysteria", ports, listOfNotNull(user))
    }

    private suspend fun saveHysteria(users: List<User>): Result {
        val cfg = CoreTools.activeConfig("hysteria")
        sh("cp -f ${q(cfg)} ${q("$cfg.authbak")}")
        val u = users.firstOrNull()
        val edit = if (u == null) {
            "$YQ -i 'with(.socks5 | select(. != null); del(.username) | del(.password)) | with(.http | select(. != null); del(.username) | del(.password))' ${q(cfg)}"
        } else {
            // Through the environment: nothing of it is on a command line a process list shows for long.
            "BOXY_U=${q(u.name)} BOXY_P=${q(u.password)} $YQ -i 'with(.socks5 | select(. != null); .username = strenv(BOXY_U) | .password = strenv(BOXY_P))" +
                " | with(.http | select(. != null); .username = strenv(BOXY_U) | .password = strenv(BOXY_P))' ${q(cfg)}"
        }
        val applied = sh(edit).first
        val check = if (applied) ConfigCheck.check("hysteria", cfg) else CheckResult.Failed("yq")
        if (check is CheckResult.Failed) {
            sh("mv -f ${q("$cfg.authbak")} ${q(cfg)}")
            return Result.Failed(check.output)
        }
        sh("rm -f ${q("$cfg.authbak")}")
        return Result.Ok
    }

    // ---- a port when there is none ----

    /** What [addPort] adds: (type, address). */
    fun portToAdd(core: String): Pair<String, String> = when (core) {
        "xray", "hysteria" -> "socks" to "127.0.0.1:" + (if (core == "xray") "10808" else "1080")
        // mihomo listens where its allow-lan / bind-address say, the same for every port.
        "clash" -> "mixed" to "7890"
        else -> "mixed" to "127.0.0.1:7890"
    }

    /** Adds a proxy port for apps on this phone (127.0.0.1) with the current [users], checked and reloaded. */
    suspend fun addPort(core: String, users: List<User>): Result = withContext(Dispatchers.IO) {
        val r = when (core) {
            "clash" -> {
                val cfg = CoreTools.activeConfig("clash")
                sh("cp -f ${q(cfg)} ${q("$cfg.authbak")}")
                val ok = sh("$YQ -i '.[\"mixed-port\"] = 7890' ${q(cfg)}").first
                val check = if (ok) ConfigCheck.check("clash", cfg) else CheckResult.Failed("yq")
                if (check is CheckResult.Failed) {
                    sh("mv -f ${q("$cfg.authbak")} ${q(cfg)}")
                    Result.Failed(check.output)
                } else {
                    sh("rm -f ${q("$cfg.authbak")}")
                    Result.Ok
                }
            }
            "sing-box" -> saveSingBox(users, JSONObject().put("type", "mixed").put("tag", ADDED_MIXED).put("listen", "127.0.0.1").put("listen_port", 7890))
            "xray" -> saveXray(
                users,
                JSONObject().put("tag", ADDED_SOCKS).put("protocol", "socks").put("listen", "127.0.0.1").put("port", 10808)
                    .put("settings", JSONObject().put("udp", true)),
            )
            "hysteria" -> {
                val cfg = CoreTools.activeConfig("hysteria")
                sh("cp -f ${q(cfg)} ${q("$cfg.authbak")}")
                val ok = sh("$YQ -i '.socks5.listen = \"127.0.0.1:1080\"' ${q(cfg)}").first
                if (ok) {
                    sh("rm -f ${q("$cfg.authbak")}")
                    if (users.isNotEmpty()) saveHysteria(users) else Result.Ok
                } else {
                    sh("mv -f ${q("$cfg.authbak")} ${q(cfg)}")
                    Result.Failed("yq")
                }
            }
            else -> Result.Failed("unsupported core")
        }
        if (r == Result.Ok && BoxModule.state().running) {
            if (core == "clash" || core == "sing-box") BoxModule.reloadConfig(core) else BoxModule.restart()
        }
        r
    }
}

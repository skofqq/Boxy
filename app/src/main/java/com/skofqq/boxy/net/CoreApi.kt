package com.skofqq.boxy.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One connection of the core as Zashboard lists it. */
data class CoreConn(
    val id: String,
    /** host:port, or ip:port when the core knows no host name. */
    val target: String,
    /** TProxy, Redir, Tun… */
    val inbound: String,
    /** tcp / udp */
    val network: String,
    /** Group first, node last (the core lists them the other way round). */
    val chain: List<String>,
    val rule: String,
    val process: String?,
    val upload: Long,
    val download: Long,
    /** Epoch ms the connection opened. */
    val start: Long,
)

/** A line of the core's own log (/logs). */
data class CoreLogLine(val time: Long, val level: String, val text: String)

data class CoreRule(val type: String, val payload: String, val proxy: String)

/** A rule-provider of the config: a rule set the core downloads. */
data class RuleProvider(val name: String, val behavior: String, val vehicle: String, val count: Int, val updatedAt: Long?)

/** The parts of the Clash API behind the panel's Connections, Logs and Rules tabs. */
object CoreApi {
    /** Live connections; null when the API cannot be reached. */
    suspend fun connections(api: ClashApi): List<CoreConn>? = withContext(Dispatchers.IO) {
        runCatching {
            val c = Net.openApi(api, "/connections")
            try {
                val arr = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("connections") ?: return@runCatching emptyList()
                (0 until arr.length()).map { i ->
                    val conn = arr.getJSONObject(i)
                    val m = conn.optJSONObject("metadata") ?: JSONObject()
                    val host = m.optString("host").ifBlank { m.optString("sniffHost") }.ifBlank { m.optString("destinationIP") }
                    val chains = conn.optJSONArray("chains")
                    CoreConn(
                        id = conn.optString("id"),
                        target = "$host:${m.optString("destinationPort")}",
                        inbound = m.optString("type"),
                        network = m.optString("network"),
                        chain = (0 until (chains?.length() ?: 0)).map { chains!!.optString(it) }.reversed(),
                        rule = conn.optString("rule").let { r ->
                            conn.optString("rulePayload").takeIf { it.isNotBlank() }?.let { "$r: $it" } ?: r
                        },
                        process = m.optString("process").ifBlank { null },
                        upload = conn.optLong("upload"),
                        download = conn.optLong("download"),
                        start = runCatching { java.time.Instant.parse(conn.optString("start")).toEpochMilli() }.getOrDefault(System.currentTimeMillis()),
                    )
                }
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    /** Closes one connection, or every one when [id] is null. */
    suspend fun close(api: ClashApi, id: String?): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val c = Net.openApi(api, if (id == null) "/connections" else "/connections/${Net.enc(id)}", "DELETE")
            try {
                c.responseCode in 200..299
            } finally {
                c.disconnect()
            }
        }.getOrDefault(false)
    }

    /** The core's log from now on, at [level] (debug, info, warning, error) and above. */
    fun logs(api: ClashApi, level: String): Flow<CoreLogLine> = flow {
        val c = Net.openApi(api, "/logs?level=$level", timeout = 0)
        try {
            c.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!currentCoroutineContext().isActive) break
                    val j = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    emit(CoreLogLine(System.currentTimeMillis(), j.optString("type"), j.optString("payload")))
                }
            }
        } finally {
            c.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun rules(api: ClashApi): List<CoreRule>? = withContext(Dispatchers.IO) {
        runCatching {
            val c = Net.openApi(api, "/rules")
            try {
                val arr = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("rules") ?: return@runCatching emptyList()
                (0 until arr.length()).map { i ->
                    val r = arr.getJSONObject(i)
                    CoreRule(r.optString("type"), r.optString("payload"), r.optString("proxy"))
                }
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    suspend fun ruleProviders(api: ClashApi): List<RuleProvider> = withContext(Dispatchers.IO) {
        runCatching {
            val c = Net.openApi(api, "/providers/rules")
            try {
                val providers = JSONObject(c.inputStream.bufferedReader().readText()).optJSONObject("providers") ?: return@runCatching emptyList()
                providers.keys().asSequence().mapNotNull { key ->
                    val p = providers.optJSONObject(key) ?: return@mapNotNull null
                    RuleProvider(
                        p.optString("name", key),
                        p.optString("behavior"),
                        p.optString("vehicleType"),
                        p.optInt("ruleCount"),
                        runCatching { java.time.Instant.parse(p.optString("updatedAt")).toEpochMilli() }.getOrNull()?.takeIf { it > 0 },
                    )
                }.toList()
            } finally {
                c.disconnect()
            }
        }.getOrDefault(emptyList())
    }

    /** "v1.19.33" (mihomo) or the version sing-box reports; null when it cannot be read. */
    suspend fun version(api: ClashApi): String? = withContext(Dispatchers.IO) {
        runCatching {
            val c = Net.openApi(api, "/version")
            try {
                JSONObject(c.inputStream.bufferedReader().readText()).optString("version").ifBlank { null }
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    /** Settings of the running core (GET /configs). */
    suspend fun configs(api: ClashApi): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val c = Net.openApi(api, "/configs")
            try {
                JSONObject(c.inputStream.bufferedReader().readText())
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    /** One of the core's maintenance calls: restart, update GEO databases, flush the FakeIP or DNS cache. */
    suspend fun maintain(api: ClashApi, action: Maintenance): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val c = Net.openApi(api, action.path, "POST", timeout = 60_000)
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write("{}".toByteArray()) }
            try {
                c.responseCode in 200..299
            } finally {
                c.disconnect()
            }
        }.getOrDefault(false)
    }

    enum class Maintenance(val path: String) {
        RESTART("/restart"),
        UPDATE_GEO("/configs/geo"),
        FLUSH_FAKEIP("/cache/fakeip/flush"),
        FLUSH_DNS("/cache/dns/flush"),
    }

    /** Downloads the rule-provider [name] again. */
    suspend fun updateRuleProvider(api: ClashApi, name: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val c = Net.openApi(api, "/providers/rules/${Net.enc(name)}", "PUT", timeout = 30_000)
            try {
                c.responseCode in 200..299
            } finally {
                c.disconnect()
            }
        }.getOrDefault(false)
    }
}

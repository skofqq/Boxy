package com.skofqq.boxy.configs

import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.ConfigCheck
import com.skofqq.boxy.root.RootFiles

/**
 * The user's own sing-box settings, kept apart from the config so a subscription update does not wipe them.
 * The module passes the folder with -C: sing-box merges files sorted by path and the first one wins for plain
 * values, so ".overrides" wins over config.json; items with a tag the overrides define replace the config's own.
 */
object SingBoxOverrides {
    const val FILE = "${ConfigCheck.SINGBOX_OVERRIDES}/overrides.json"

    private val TEMPLATE = """
        {
          // Your settings over the sing-box config, including one from a subscription: they survive its updates.
          // Plain values here win; inbounds / outbounds / DNS servers / rule sets with the same tag replace the
          // config's own. Boxy checks the merged result on save.
          "log": {
            "level": "warn"
          },
          "experimental": {
            "cache_file": {
              "enabled": true
            }
          }
        }
    """.trimIndent() + "\n"

    /** Creates the file with a short template when it is missing. */
    suspend fun ensure(): Boolean {
        if (RootFiles.exists(FILE)) return true
        BoxModule.exec("mkdir -p '${ConfigCheck.SINGBOX_OVERRIDES}'")
        return RootFiles.write(FILE, TEMPLATE)
    }

    /** The overrides text, null when there is none. */
    suspend fun read(): String? = BoxModule.readFile(FILE)?.takeIf { it.isNotBlank() }

    private val CACHE_ON = Regex("\"cache_file\"\\s*:\\s*\\{[^}]*\"enabled\"\\s*:\\s*true")
    private val CACHE_OFF = Regex("(\"cache_file\"\\s*:\\s*\\{[^}]*\"enabled\"\\s*:\\s*)false")

    /** Whether experimental.cache_file is on, overrides first (they win); null when the config cannot be read. */
    suspend fun cacheFileOn(): Boolean? {
        val ov = read()
        if (ov != null) {
            if (CACHE_ON.containsMatchIn(ov)) return true
            if (CACHE_OFF.containsMatchIn(ov)) return false
        }
        val cfg = BoxModule.readFile(com.skofqq.boxy.root.CoreTools.activeConfig("sing-box")) ?: return null
        return CACHE_ON.containsMatchIn(cfg)
    }

    /**
     * Turns cache_file on in the overrides (so a subscription update does not undo it) and reloads sing-box.
     * The template already has it; an existing file gets it set or added.
     */
    suspend fun enableCacheFile(): Boolean {
        val existing = read()
        val ok = when {
            existing == null -> ensure()
            CACHE_OFF.containsMatchIn(existing) -> RootFiles.write(FILE, CACHE_OFF.replace(existing) { it.groupValues[1] + "true" })
            CACHE_ON.containsMatchIn(existing) -> true
            else -> runCatching {
                val o = org.json.JSONObject(existing)
                val exp = o.optJSONObject("experimental") ?: org.json.JSONObject().also { o.put("experimental", it) }
                val cache = exp.optJSONObject("cache_file") ?: org.json.JSONObject().also { exp.put("cache_file", it) }
                cache.put("enabled", true)
                GeoRules.pretty(o)
            }.getOrNull()?.let { RootFiles.write(FILE, it) } ?: false
        }
        if (!ok) return false
        return !BoxModule.state().running || BoxModule.reloadConfig("sing-box")
    }
}

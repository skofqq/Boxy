package com.skofqq.boxy.data

import android.content.Context
import android.net.Uri
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.RootFiles
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class BackupScope { MODULES, APPS, BOTH }

/**
 * Backup archive: module settings and configs (modules/…), this app's preferences (apps/prefs.json) and
 * its collected data (apps/<name>.json: traffic history, traffic per app, subscription checks).
 * Large binary data (cores, geo databases, dashboards, logs) is not included.
 */
object Backup {
    private val ROOT_FILES = listOf("settings.ini", "package.list.cfg", "ap.list.cfg", "gid.list.cfg", "crontab.cfg")
    // Core configs and the dnscrypt-proxy config (dnscrypt-proxy.toml with the picked servers, block lists).
    private val CORE_DIRS = listOf("clash", "sing-box", "xray", "v2fly", "hysteria", "dnscrypt")
    private val CONFIG_EXT = setOf("yaml", "yml", "json", "txt", "list", "conf", "toml")
    // Preference files with data the app collects (TrafficStats, AppTraffic, Subscriptions).
    private val DATA_PREFS = listOf("traffic", "app_traffic", "subscriptions")

    suspend fun export(context: Context, uri: Uri, prefs: Prefs, scope: BackupScope = BackupScope.BOTH): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { os ->
                ZipOutputStream(os).use { zip ->
                    fun put(name: String, bytes: ByteArray) {
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                    if (scope != BackupScope.APPS) {
                        val files = ROOT_FILES.map { "${BoxModule.BOX_DIR}/$it" } +
                            CORE_DIRS.flatMap { dir ->
                                RootFiles.list("${BoxModule.BOX_DIR}/$dir").filter { !it.isDir && it.extension in CONFIG_EXT && it.size < 5_000_000 }.map { it.path }
                            }
                        files.forEach { path ->
                            readBytes(path)?.let { put("modules/" + path.removePrefix(BoxModule.BOX_DIR + "/"), it) }
                        }
                    }
                    if (scope != BackupScope.MODULES) {
                        put("apps/prefs.json", encode(prefs.exportAll()).toString(2).toByteArray())
                        DATA_PREFS.forEach { name ->
                            val all = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
                            if (all.isNotEmpty()) put("apps/$name.json", encode(all).toString().toByteArray())
                        }
                    }
                    put("manifest.json", JSONObject().put("app", "Boxy").put("created", System.currentTimeMillis()).toString().toByteArray())
                }
            } != null
        }.getOrDefault(false)
    }

    /** What the archive contains, or null when it is not a Boxy / BFR backup. */
    suspend fun detect(context: Context, uri: Uri): BackupScope? = withContext(Dispatchers.IO) {
        runCatching {
            var modules = false
            var apps = false
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (e.name.startsWith("modules/")) modules = true
                        if (e.name.startsWith("apps/")) apps = true
                    }
                }
            }
            when {
                modules && apps -> BackupScope.BOTH
                modules -> BackupScope.MODULES
                apps -> BackupScope.APPS
                else -> null
            }
        }.getOrNull()
    }

    suspend fun restore(context: Context, uri: Uri, scope: BackupScope, prefs: Prefs): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            var ok = true
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        val bytes = zip.readBytes()
                        when {
                            e.name.startsWith("modules/") && scope != BackupScope.APPS -> {
                                val rel = e.name.removePrefix("modules/")
                                if (rel.contains("..") || rel.isBlank()) continue
                                ok = writeBytes("${BoxModule.BOX_DIR}/$rel", bytes) && ok
                            }
                            e.name == "apps/prefs.json" && scope != BackupScope.MODULES -> {
                                val map = decode(JSONObject(String(bytes)))
                                withContext(Dispatchers.Main) { prefs.importAll(map) }
                            }
                            e.name.startsWith("apps/") && scope != BackupScope.MODULES -> {
                                val name = e.name.removePrefix("apps/").removeSuffix(".json")
                                if (name in DATA_PREFS) writePrefs(context, name, decode(JSONObject(String(bytes))))
                            }
                        }
                    }
                }
            }
            ok
        }.getOrDefault(false)
    }

    private fun encode(values: Map<String, *>): JSONObject {
        val json = JSONObject()
        values.forEach { (k, v) -> json.put(k, JSONObject().put("t", typeOf(v)).put("v", v)) }
        return json
    }

    private fun decode(json: JSONObject): Map<String, Any?> = json.keys().asSequence().associateWith { k ->
        val o = json.getJSONObject(k)
        when (o.optString("t")) {
            "b" -> o.getBoolean("v")
            "i" -> o.getInt("v")
            "l" -> o.getLong("v")
            "f" -> o.getDouble("v").toFloat()
            else -> o.optString("v")
        }
    }

    /** Replaces the whole preference file [name] with [values]. */
    private fun writePrefs(context: Context, name: String, values: Map<String, Any?>) {
        val e = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        values.forEach { (k, v) ->
            when (v) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is String -> e.putString(k, v)
            }
        }
        e.commit()
    }

    private fun typeOf(v: Any?): String = when (v) {
        is Boolean -> "b"
        is Int -> "i"
        is Long -> "l"
        is Float -> "f"
        else -> "s"
    }

    private fun readBytes(path: String): ByteArray? {
        val r = Shell.cmd("[ -f '$path' ] && base64 '$path' | tr -d '\\n'").exec()
        if (!r.isSuccess) return null
        return runCatching { android.util.Base64.decode(r.out.joinToString(""), android.util.Base64.DEFAULT) }.getOrNull()
    }

    private suspend fun writeBytes(path: String, bytes: ByteArray): Boolean {
        val dir = path.substringBeforeLast('/')
        Shell.cmd("mkdir -p '$dir'").exec()
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val job = Shell.getShell().newJob()
        job.add("rm -f '$path.boxy.tmp'")
        b64.chunked(8000).forEach { job.add("printf '%s' '$it' >> '$path.boxy.tmp'") }
        job.add("base64 -d '$path.boxy.tmp' > '$path' && rm -f '$path.boxy.tmp'")
        return job.exec().isSuccess
    }
}

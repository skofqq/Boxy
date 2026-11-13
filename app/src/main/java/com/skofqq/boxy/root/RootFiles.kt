package com.skofqq.boxy.root

import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RootFile(val path: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

/** File operations on root-owned paths (the module lives in /data/adb). */
object RootFiles {
    private fun q(path: String) = "'" + path.replace("'", "'\\''") + "'"

    suspend fun list(dir: String): List<RootFile> = withContext(Dispatchers.IO) {
        val d = dir.trimEnd('/')
        val out = Shell.cmd(
            "for f in ${q(d)}/* ${q(d)}/.[!.]*; do [ -e \"\$f\" ] && stat -c '%F|%s|%Y|%n' \"\$f\"; done 2>/dev/null",
        ).exec().out
        out.mapNotNull { line ->
            val parts = line.split('|', limit = 4)
            if (parts.size < 4) return@mapNotNull null
            val path = parts[3]
            RootFile(
                path = path,
                name = path.substringAfterLast('/'),
                isDir = parts[0].contains("directory"),
                size = parts[1].toLongOrNull() ?: 0,
                modified = (parts[2].toLongOrNull() ?: 0) * 1000,
            )
        }.sortedWith(compareBy<RootFile>({ !it.isDir }, { it.name.lowercase() }))
    }

    /** Recursive search by name under a directory (not into the module's backups of earlier versions). */
    suspend fun search(dir: String, query: String): List<RootFile> = withContext(Dispatchers.IO) {
        val pattern = "*" + query.replace("'", "") + "*"
        val out = Shell.cmd(
            "find ${q(dir)} -path '${BoxModule.BOX_DIR}/backups' -prune -o -iname '$pattern' -print 2>/dev/null | head -n 300 | while read -r f; do stat -c '%F|%s|%Y|%n' \"\$f\"; done",
        ).exec().out
        out.mapNotNull { line ->
            val parts = line.split('|', limit = 4)
            if (parts.size < 4) null else RootFile(parts[3], parts[3].substringAfterLast('/'), parts[0].contains("directory"), parts[1].toLongOrNull() ?: 0, (parts[2].toLongOrNull() ?: 0) * 1000)
        }
    }

    suspend fun read(path: String): String? = BoxModule.readFile(path)

    suspend fun write(path: String, content: String): Boolean = BoxModule.writeFile(path, content)

    suspend fun createFile(path: String): Boolean = run("[ ! -e ${q(path)} ] && touch ${q(path)}")

    suspend fun createDir(path: String): Boolean = run("[ ! -e ${q(path)} ] && mkdir -p ${q(path)}")

    suspend fun rename(path: String, newName: String): Boolean {
        val target = path.substringBeforeLast('/') + "/" + newName
        return run("[ ! -e ${q(target)} ] && mv ${q(path)} ${q(target)}")
    }

    suspend fun delete(path: String): Boolean = run("rm -rf ${q(path)}")

    suspend fun exists(path: String): Boolean = run("[ -e ${q(path)} ]")

    /** Size of a file in bytes, -1 when missing. */
    suspend fun size(path: String): Long = withContext(Dispatchers.IO) {
        Shell.cmd("stat -c %s ${q(path)} 2>/dev/null").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: -1
    }

    /** Last [bytes] of a text file (logs can be large). */
    suspend fun tail(path: String, bytes: Int): String = withContext(Dispatchers.IO) {
        Shell.cmd("tail -c $bytes ${q(path)} 2>/dev/null").exec().out.joinToString("\n")
    }

    private suspend fun run(cmd: String): Boolean = withContext(Dispatchers.IO) { Shell.cmd(cmd).exec().isSuccess }
}

/** Runs a long root command and streams its output lines. */
object RootTask {
    suspend fun run(cmd: String, onLine: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val callback = object : CallbackList<String>() {
            override fun onAddElement(e: String) {
                onLine(stripAnsi(e))
            }
        }
        val ok = Shell.cmd("$cmd 2>&1").to(callback).exec().isSuccess
        // libsu hands the lines to the main thread by posting them: wait until the last one arrived, so callers can
        // read the whole output once this returns (speed test result, kernel check, TLS verdict).
        withContext(Dispatchers.Main) { }
        ok
    }

    fun stripAnsi(s: String): String = s.replace(Regex("\u001B\\[[0-9;]*[A-Za-z]"), "")
}

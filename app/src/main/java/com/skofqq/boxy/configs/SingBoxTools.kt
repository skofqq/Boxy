package com.skofqq.boxy.configs

import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.RootTask
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The editor's sing-box tools: `sing-box format` of the text, `sing-box rule-set compile` of a rule-set source. */
object SingBoxTools {
    private const val BIN = "${BoxModule.BOX_DIR}/bin/sing-box"

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun failure(lines: List<String>) = IllegalStateException(
        lines.map { RootTask.stripAnsi(it).trim() }.filter { it.isNotEmpty() }.takeLast(8).joinToString("\n").ifBlank { "exit code != 0" },
    )

    /** The text as `sing-box format` writes it (comments are not kept). The scratch file sits next to the original. */
    suspend fun format(path: String, text: String): Result<String> = withContext(Dispatchers.IO) {
        val tmp = "${path.substringBeforeLast('/')}/.boxy-format.json"
        if (!BoxModule.writeFile(tmp, text)) return@withContext Result.failure(IllegalStateException("write"))
        try {
            val r = Shell.cmd("[ -x $BIN ] && timeout 30 $BIN format -c ${q(tmp)} 2>&1").exec()
            if (r.isSuccess && r.out.isNotEmpty()) Result.success(r.out.joinToString("\n") + "\n") else Result.failure(failure(r.out))
        } finally {
            Shell.cmd("rm -f ${q(tmp)}").exec()
        }
    }

    /** Compiles a rule-set source (JSON with "version" and "rules") into <name>.srs next to it; returns that path. */
    suspend fun compile(path: String, text: String): Result<String> = withContext(Dispatchers.IO) {
        val out = path.removeSuffix(".json") + ".srs"
        val tmp = "${path.substringBeforeLast('/')}/.boxy-compile.json"
        if (!BoxModule.writeFile(tmp, text)) return@withContext Result.failure(IllegalStateException("write"))
        try {
            val r = Shell.cmd("[ -x $BIN ] && timeout 60 $BIN rule-set compile --output ${q(out)} ${q(tmp)} 2>&1 && chmod 644 ${q(out)}").exec()
            if (r.isSuccess) Result.success(out) else Result.failure(failure(r.out))
        } finally {
            Shell.cmd("rm -f ${q(tmp)}").exec()
        }
    }
}

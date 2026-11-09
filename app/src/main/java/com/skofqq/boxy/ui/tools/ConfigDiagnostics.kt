package com.skofqq.boxy.ui.tools

import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Compose
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException

/** Syntax check that runs while typing. Meaning is still the core's job: see ConfigCheck, which runs on save. */
internal object ConfigDiagnostics {
    data class Problem(val line: Int, val column: Int, val message: String)

    /** Beyond this the check would lag behind typing; the core still checks on save. */
    private const val MAX_CHARS = 512 * 1024

    fun check(kind: ConfigLanguage.Kind, text: String): Problem? {
        if (text.length > MAX_CHARS) return null
        return when (kind) {
            ConfigLanguage.Kind.YAML -> yaml(text)
            ConfigLanguage.Kind.JSON -> json(text)
            ConfigLanguage.Kind.TOML -> null
        }
    }

    private fun yaml(text: String): Problem? = try {
        Compose(LoadSettings.builder().build()).composeAllFromString(text).forEach { _ -> }
        null
    } catch (e: MarkedYamlEngineException) {
        val mark = e.problemMark.orElse(null)
        Problem(mark?.line ?: 0, mark?.column ?: 0, e.problem ?: e.message.orEmpty())
    } catch (e: RuntimeException) {
        Problem(0, 0, e.message.orEmpty())
    }

    private class JsonError(val at: Int, override val message: String) : Exception(message)

    /** Strict enough to catch typos, but accepts the comments and trailing commas the cores accept. */
    private fun json(text: String): Problem? {
        try {
            JsonParser(text).document()
        } catch (e: JsonError) {
            val at = e.at.coerceIn(0, text.length)
            val line = text.substring(0, at).count { it == '\n' }
            val column = at - (if (at == 0) 0 else text.lastIndexOf('\n', at - 1) + 1)
            return Problem(line, column, e.message)
        }
        return null
    }

    private class JsonParser(val s: String) {
        var i = 0

        fun document() {
            skip()
            if (i >= s.length) return
            value(0)
            skip()
            if (i < s.length) throw JsonError(i, "Unexpected text after the end of the document")
        }

        private fun skip() {
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() -> i++
                    s.startsWith("//", i) || c == '#' -> i = s.indexOf('\n', i).let { if (it < 0) s.length else it }
                    s.startsWith("/*", i) -> {
                        val end = s.indexOf("*/", i + 2)
                        if (end < 0) throw JsonError(i, "Unterminated comment")
                        i = end + 2
                    }
                    else -> return
                }
            }
        }

        private fun value(depth: Int) {
            if (depth > 200) throw JsonError(i, "Nesting is too deep")
            skip()
            if (i >= s.length) throw JsonError(s.length, "Unexpected end of file")
            when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()
                else -> if (c == '-' || c.isDigit()) number() else word()
            }
        }

        private fun obj(depth: Int) {
            val open = i++
            while (true) {
                skip()
                if (i >= s.length) throw JsonError(open, "'{' is never closed")
                if (s[i] == '}') { i++; return }
                if (s[i] != '"') throw JsonError(i, "Expected a quoted key or '}'")
                string()
                skip()
                if (i >= s.length || s[i] != ':') throw JsonError(i, "Expected ':' after the key")
                i++
                value(depth + 1)
                skip()
                if (i >= s.length) throw JsonError(open, "'{' is never closed")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return }
                    else -> throw JsonError(i, "Expected ',' or '}'")
                }
            }
        }

        private fun arr(depth: Int) {
            val open = i++
            while (true) {
                skip()
                if (i >= s.length) throw JsonError(open, "'[' is never closed")
                if (s[i] == ']') { i++; return }
                value(depth + 1)
                skip()
                if (i >= s.length) throw JsonError(open, "'[' is never closed")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return }
                    else -> throw JsonError(i, "Expected ',' or ']'")
                }
            }
        }

        private fun string() {
            val open = i++
            while (i < s.length && s[i] != '"') {
                if (s[i] == '\n') throw JsonError(open, "String is not closed on this line")
                if (s[i] == '\\') i++
                i++
            }
            if (i >= s.length) throw JsonError(open, "String is never closed")
            i++
        }

        private fun number() {
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] in "+-.")) i++
            if (s.substring(start, i).toDoubleOrNull() == null) throw JsonError(start, "Invalid number")
        }

        private fun word() {
            val start = i
            while (i < s.length && s[i].isLetterOrDigit()) i++
            val w = s.substring(start, i)
            if (w != "true" && w != "false" && w != "null") throw JsonError(start, "Unexpected value (strings need quotes)")
        }
    }

    private val CORE_LINE = Regex("""(?i)\b(?:line|row)\b[ :=]*(\d+)""")
    private val CORE_COLUMN = Regex("""(?i)\bcol(?:umn)?\b[ :=]*(\d+)""")

    /** 0-based line named in a core's error output, with its column if given. */
    fun lineInCoreOutput(output: String): Pair<Int, Int>? {
        val line = CORE_LINE.find(output)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val column = CORE_COLUMN.find(output)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return (line - 1).coerceAtLeast(0) to (column - 1).coerceAtLeast(0)
    }
}

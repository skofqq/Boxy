package com.skofqq.boxy.ui.tools

import android.os.Bundle
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandleResult
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.lang.styling.CodeBlock
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * Light-weight highlighting for the module's config files: YAML (Clash, Hysteria), JSON (sing-box, Xray, V2Ray)
 * and TOML / INI style "key = value" files (dnscrypt-proxy.toml, settings.ini, *.cfg).
 */
class ConfigLanguage(val kind: Kind, private val schema: ConfigAssist.Schema? = null) : EmptyLanguage() {
    enum class Kind { YAML, JSON, TOML }

    private val manager = object : SimpleAnalyzeManager<Unit>() {
        override fun analyze(text: StringBuilder, delegate: Delegate<Unit>): Styles {
            val spans = MappedSpans.Builder()
            var line = 0
            var start = 0
            while (start <= text.length) {
                if (delegate.isCancelled) break
                val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
                val s = text.substring(start, end)
                when (kind) {
                    Kind.JSON -> jsonLine(spans, line, s)
                    Kind.YAML -> yamlLine(spans, line, s)
                    Kind.TOML -> tomlLine(spans, line, s)
                }
                line++
                start = end + 1
            }
            spans.determine(maxOf(0, line - 1))
            val styles = Styles(spans.build())
            if (!delegate.isCancelled) {
                when (kind) {
                    Kind.YAML -> yamlBlocks(text, styles)
                    Kind.JSON -> jsonBlocks(text, styles)
                    Kind.TOML -> Unit
                }
                styles.finishBuilding()
            }
            return styles
        }
    }

    override fun getAnalyzeManager(): AnalyzeManager = manager

    override fun useTab() = false

    override fun getIndentAdvance(content: ContentReference, line: Int, column: Int): Int =
        ConfigAssist.indentAdvance(kind, content.getLine(line).take(column))

    private val yamlList = object : NewlineHandler {
        override fun matchesRequirement(text: Content, position: CharPosition, style: Styles?) =
            ConfigAssist.yamlListContinuation(text.getLine(position.line).toString(), position.column) != null

        override fun handleNewline(text: Content, position: CharPosition, style: Styles?, tabSize: Int) =
            NewlineHandleResult(ConfigAssist.yamlListContinuation(text.getLine(position.line).toString(), position.column)!!, 0)
    }

    /** Enter between an empty pair of brackets opens it up, leaving the closing one on its own line. */
    private val jsonPair = object : NewlineHandler {
        override fun matchesRequirement(text: Content, position: CharPosition, style: Styles?): Boolean {
            val s = text.getLine(position.line).toString()
            val before = s.take(position.column).trimEnd()
            val after = s.drop(position.column).trimStart()
            return (before.endsWith("{") && after.startsWith("}")) || (before.endsWith("[") && after.startsWith("]"))
        }

        override fun handleNewline(text: Content, position: CharPosition, style: Styles?, tabSize: Int): NewlineHandleResult {
            val s = text.getLine(position.line).toString()
            val indent = " ".repeat(s.length - s.trimStart().length)
            return NewlineHandleResult("\n$indent  \n$indent", indent.length + 1)
        }
    }

    override fun getNewlineHandlers(): Array<NewlineHandler> = when (kind) {
        Kind.YAML -> arrayOf(yamlList)
        Kind.JSON -> arrayOf(jsonPair)
        Kind.TOML -> emptyArray()
    }

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle,
    ) {
        val s = schema ?: return
        val items = when (kind) {
            Kind.YAML -> ConfigAssist.completeYaml(s, { content.getLine(it) }, position.line, position.column)
            Kind.JSON -> {
                val before = StringBuilder()
                for (l in 0 until position.line) before.append(content.getLine(l)).append('\n')
                val current = content.getLine(position.line)
                before.append(current, 0, position.column)
                ConfigAssist.completeJson(s, before, current.substring(position.column))
            }
            Kind.TOML -> emptyList()
        }
        publisher.addItems(items.map { SimpleCompletionItem(it.label, it.prefixLength, it.commit) })
    }

    companion object {
        /** Highlighting for a file name, or null for plain text. */
        fun forPath(path: String): ConfigLanguage? {
            val name = path.substringAfterLast('/').lowercase()
            return when (name.substringAfterLast('.', "")) {
                "json" -> ConfigLanguage(Kind.JSON, if ("/sing-box/" in path) ConfigAssist.Schema.SINGBOX else null)
                "yaml", "yml" -> ConfigLanguage(Kind.YAML, if ("/hysteria/" in path) null else ConfigAssist.Schema.MIHOMO)
                "toml", "ini", "cfg", "conf", "prop" -> ConfigLanguage(Kind.TOML)
                else -> null
            }
        }

        private val NORMAL = TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)
        private val KEY = TextStyle.makeStyle(EditorColorScheme.KEYWORD)
        private val STRING = TextStyle.makeStyle(EditorColorScheme.LITERAL)
        private val NUMBER = TextStyle.makeStyle(EditorColorScheme.FUNCTION_NAME)
        private val PUNCT = TextStyle.makeStyle(EditorColorScheme.OPERATOR)
        private val COMMENT = TextStyle.makeStyle(EditorColorScheme.COMMENT, 0, false, true, false)

        private val SCALAR_WORDS = setOf("true", "false", "null", "~", "yes", "no", "on", "off")

        private fun addBlock(styles: Styles, startLine: Int, endLine: Int, column: Int, toBottom: Boolean = false) {
            styles.addCodeBlock(
                CodeBlock().also {
                    it.startLine = startLine
                    it.startColumn = column
                    it.endLine = endLine
                    it.endColumn = column
                    it.toBottomOfEndLine = toBottom
                },
            )
        }

        private class Open(val line: Int, val indent: Int, val rank: Int)

        /** Indent guides for YAML: every line owns the more indented lines below it. */
        private fun yamlBlocks(text: CharSequence, styles: Styles) {
            val open = ArrayList<Open>()
            var last = -1
            var line = 0
            var start = 0
            fun close(rank: Int) {
                while (open.isNotEmpty() && open.last().rank >= rank) {
                    val o = open.removeAt(open.size - 1)
                    if (last > o.line) addBlock(styles, o.line, last, o.indent, toBottom = true)
                }
            }
            while (start <= text.length) {
                var end = start
                while (end < text.length && text[end] != '\n') end++
                var i = start
                while (i < end && text[i] == ' ') i++
                if (i < end && text[i] != '#' && text[i] != '\r') {
                    // A list item may sit at its key's indent, yet still belongs to that key.
                    val dash = text[i] == '-' && (i + 1 == end || text[i + 1] == ' ')
                    val indent = i - start
                    val rank = indent * 2 + if (dash) 1 else 0
                    close(rank)
                    open += Open(line, indent, rank)
                    last = line
                }
                line++
                start = end + 1
            }
            close(Int.MIN_VALUE)
        }

        /** Indent guides for JSON: multi-line objects and arrays. */
        private fun jsonBlocks(text: CharSequence, styles: Styles) {
            val open = ArrayList<IntArray>()
            var line = 0
            var lineStart = 0
            var i = 0
            while (i < text.length) {
                when (text[i]) {
                    '\n' -> { line++; lineStart = i + 1 }
                    '"' -> {
                        i++
                        while (i < text.length && text[i] != '"' && text[i] != '\n') {
                            if (text[i] == '\\') i++
                            i++
                        }
                        if (i < text.length && text[i] == '\n') continue
                    }
                    '{', '[' -> {
                        var indent = 0
                        while (lineStart + indent < i && text[lineStart + indent] == ' ') indent++
                        open += intArrayOf(line, indent)
                    }
                    '}', ']' -> if (open.isNotEmpty()) {
                        val o = open.removeAt(open.size - 1)
                        if (line > o[0]) addBlock(styles, o[0], line, o[1])
                    }
                }
                i++
            }
        }

        private fun yamlLine(b: MappedSpans.Builder, line: Int, s: String) {
            b.addIfNeeded(line, 0, NORMAL)
            var i = 0
            while (i < s.length && s[i] == ' ') i++
            if (i < s.length && s[i] == '#') {
                b.addIfNeeded(line, i, COMMENT)
                return
            }
            // List markers
            while (i + 1 <= s.length && i < s.length && s[i] == '-' && (i + 1 == s.length || s[i + 1] == ' ')) {
                b.addIfNeeded(line, i, NORMAL)
                i++
                while (i < s.length && s[i] == ' ') i++
            }
            // key: value
            val colon = keyColon(s, i)
            if (colon > i) {
                b.addIfNeeded(line, i, KEY)
                b.addIfNeeded(line, colon, PUNCT)
                i = colon + 1
                b.addIfNeeded(line, i, NORMAL)
            }
            value(b, line, s, i)
        }

        /** Index of the ':' ending a mapping key that starts at [from], or -1. */
        internal fun keyColon(s: String, from: Int): Int {
            var i = from
            var quote: Char? = null
            if (i < s.length && (s[i] == '"' || s[i] == '\'')) quote = s[i].also { i++ }
            while (i < s.length) {
                val c = s[i]
                if (quote != null) {
                    if (c == quote) quote = null
                } else {
                    if (c == ':' && (i + 1 == s.length || s[i + 1] == ' ')) return i
                    if (c == '#' || c == '{' || c == '[') return -1
                }
                i++
            }
            return -1
        }

        /**
         * YAML scalar after "key:" or "- ": quoted and plain strings are teal, numbers green,
         * booleans / null stay plain, a trailing " #" starts a comment.
         */
        private fun value(b: MappedSpans.Builder, line: Int, s: String, from: Int) {
            var i = from
            while (i < s.length && s[i] == ' ') i++
            if (i >= s.length) return
            if (s[i] == '#') {
                b.addIfNeeded(line, i, COMMENT)
                return
            }
            // Find where a comment starts (outside quotes).
            var quote: Char? = null
            var commentAt = -1
            var j = i
            while (j < s.length) {
                val c = s[j]
                if (quote != null) {
                    if (c == quote) quote = null
                } else if (c == '"' || c == '\'') {
                    quote = c
                } else if (c == '#' && s[j - 1] == ' ') {
                    commentAt = j
                    break
                }
                j++
            }
            val valueEnd = if (commentAt >= 0) commentAt else s.length
            val raw = s.substring(i, valueEnd).trimEnd()
            val style = when {
                raw.isEmpty() -> NORMAL
                raw.lowercase() in SCALAR_WORDS -> NORMAL
                raw.toDoubleOrNull() != null -> NUMBER
                raw == "|" || raw == ">" || raw == "|-" || raw == ">-" -> PUNCT
                else -> STRING
            }
            b.addIfNeeded(line, i, style)
            if (commentAt >= 0) {
                b.addIfNeeded(line, i + raw.length, NORMAL)
                b.addIfNeeded(line, commentAt, COMMENT)
            }
        }

        private fun jsonLine(b: MappedSpans.Builder, line: Int, s: String) {
            b.addIfNeeded(line, 0, NORMAL)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    (c == '/' && i + 1 < s.length && s[i + 1] == '/') || (c == '#' && s.substring(0, i).isBlank()) -> {
                        b.addIfNeeded(line, i, COMMENT)
                        return
                    }
                    c == '"' -> {
                        var j = i + 1
                        while (j < s.length && s[j] != '"') {
                            if (s[j] == '\\') j++
                            j++
                        }
                        val end = minOf(j + 1, s.length)
                        var k = end
                        while (k < s.length && s[k] == ' ') k++
                        b.addIfNeeded(line, i, if (k < s.length && s[k] == ':') KEY else STRING)
                        b.addIfNeeded(line, end, NORMAL)
                        i = end
                        continue
                    }
                    c.isDigit() || c == '-' || c.isLetter() -> {
                        var j = i
                        while (j < s.length && (s[j].isLetterOrDigit() || s[j] in ".-+eE")) j++
                        if (s.substring(i, j).toDoubleOrNull() != null) {
                            b.addIfNeeded(line, i, NUMBER)
                            b.addIfNeeded(line, j, NORMAL)
                        }
                        i = maxOf(j, i + 1)
                        continue
                    }
                    c in "{}[],:" -> {
                        b.addIfNeeded(line, i, PUNCT)
                        b.addIfNeeded(line, i + 1, NORMAL)
                    }
                }
                i++
            }
        }
        /** TOML / INI: [section] headers, key = value, strings, numbers, arrays and # comments. */
        private fun tomlLine(b: MappedSpans.Builder, line: Int, s: String) {
            b.addIfNeeded(line, 0, NORMAL)
            var i = 0
            while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
            if (i >= s.length) return
            if (s[i] == '#' || s[i] == ';') {
                b.addIfNeeded(line, i, COMMENT)
                return
            }
            if (s[i] == '[' && s.indexOf('=') < 0) {
                var j = i
                while (j < s.length && s[j] == '[') j++
                b.addIfNeeded(line, i, PUNCT)
                val close = s.indexOf(']', j).let { if (it < 0) s.length else it }
                b.addIfNeeded(line, j, KEY)
                b.addIfNeeded(line, close, PUNCT)
                val hash = s.indexOf('#', close)
                if (hash >= 0) b.addIfNeeded(line, hash, COMMENT)
                return
            }
            val eq = tomlEquals(s, i)
            if (eq > i) {
                b.addIfNeeded(line, i, KEY)
                b.addIfNeeded(line, eq, PUNCT)
                b.addIfNeeded(line, eq + 1, NORMAL)
                i = eq + 1
            }
            tomlValue(b, line, s, i)
        }

        /** Index of the '=' after a bare or quoted key starting at [from], or -1. */
        private fun tomlEquals(s: String, from: Int): Int {
            var i = from
            var quote: Char? = null
            while (i < s.length) {
                val c = s[i]
                if (quote != null) {
                    if (c == quote) quote = null
                } else when (c) {
                    '"', '\'' -> quote = c
                    '=' -> return i
                    '#', '[', '{', '(', ',' -> return -1
                }
                i++
            }
            return -1
        }

        private fun tomlValue(b: MappedSpans.Builder, line: Int, s: String, from: Int) {
            var i = from
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '#' -> {
                        b.addIfNeeded(line, i, COMMENT)
                        return
                    }
                    c == '"' || c == '\'' -> {
                        var j = i + 1
                        while (j < s.length && s[j] != c) {
                            if (c == '"' && s[j] == '\\') j++
                            j++
                        }
                        val end = minOf(j + 1, s.length)
                        b.addIfNeeded(line, i, STRING)
                        b.addIfNeeded(line, end, NORMAL)
                        i = end
                        continue
                    }
                    c.isDigit() || c == '-' || c == '+' || c.isLetter() -> {
                        var j = i
                        while (j < s.length && (s[j].isLetterOrDigit() || s[j] in ".-+_:")) j++
                        val word = s.substring(i, j)
                        if (word.replace("_", "").toDoubleOrNull() != null) {
                            b.addIfNeeded(line, i, NUMBER)
                            b.addIfNeeded(line, j, NORMAL)
                        } else if (word != "true" && word != "false" && from > 0) {
                            b.addIfNeeded(line, i, STRING)
                            b.addIfNeeded(line, j, NORMAL)
                        }
                        i = maxOf(j, i + 1)
                        continue
                    }
                    c in "[]{}(),=" -> {
                        b.addIfNeeded(line, i, PUNCT)
                        b.addIfNeeded(line, i + 1, NORMAL)
                    }
                }
                i++
            }
        }
    }
}

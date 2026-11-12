package com.skofqq.boxy.ui.tools

/** Text logic behind the editor's helpers. It only looks at strings, so nothing here touches the widget. */
object ConfigAssist {
    enum class Schema { MIHOMO, SINGBOX, XRAY }

    data class Section(val line: Int, val title: String)
    data class Suggestion(val label: String, val commit: String, val prefixLength: Int)

    private const val MAX_SUGGESTIONS = 40
    private val WORD = Regex("[A-Za-z0-9_.-]*$")

    // ---- Sections ----

    /** Top-level blocks of the file, for the "go to section" list. */
    fun sections(kind: ConfigLanguage.Kind, text: String): List<Section> = when (kind) {
        ConfigLanguage.Kind.YAML -> yamlSections(text)
        ConfigLanguage.Kind.JSON -> jsonSections(text)
        ConfigLanguage.Kind.TOML -> tomlSections(text)
    }

    private fun yamlSections(text: String): List<Section> {
        val out = ArrayList<Section>()
        text.lineSequence().forEachIndexed { i, s ->
            if (s.isEmpty() || s[0] == ' ' || s[0] == '#' || s[0] == '-' || s[0] == '\t') return@forEachIndexed
            val colon = ConfigLanguage.keyColon(s, 0)
            // Only keys that open a block: "mode: rule" is a setting, not a section.
            if (colon > 0 && s.substring(colon + 1).substringBefore(" #").isBlank()) out += Section(i, s.substring(0, colon).trim().trim('"', '\''))
        }
        return out
    }

    private fun tomlSections(text: String): List<Section> {
        val out = ArrayList<Section>()
        text.lineSequence().forEachIndexed { i, raw ->
            val s = raw.trim()
            if (s.startsWith("[") && '=' !in s) out += Section(i, s.trim('[', ']', ' '))
        }
        return out
    }

    private fun jsonSections(text: String): List<Section> {
        val out = ArrayList<Section>()
        var depth = 0
        var line = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\n' -> line++
                c == '{' || c == '[' -> depth++
                c == '}' || c == ']' -> depth--
                c == '/' && text.startsWith("//", i) -> {
                    i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    continue
                }
                c == '"' -> {
                    val start = i + 1
                    i = start
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\') i++
                        i++
                    }
                    if (depth == 1) {
                        var k = i + 1
                        while (k < text.length && text[k].isWhitespace()) k++
                        if (k < text.length && text[k] == ':') out += Section(line, text.substring(start, minOf(i, text.length)))
                    }
                }
            }
            i++
        }
        return out
    }

    // ---- Enter key ----

    private fun stripYamlComment(s: String): String {
        var quote: Char? = null
        for (i in s.indices) {
            val c = s[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '#' && (i == 0 || s[i - 1] == ' ')) {
                return s.substring(0, i)
            }
        }
        return s
    }

    /** Spaces to add, beyond the line's own indent, for a new line opened after [before]. */
    fun indentAdvance(kind: ConfigLanguage.Kind, before: String): Int = when (kind) {
        ConfigLanguage.Kind.JSON -> before.trimEnd().let { if (it.endsWith("{") || it.endsWith("[")) 2 else 0 }
        ConfigLanguage.Kind.TOML -> 0
        ConfigLanguage.Kind.YAML -> {
            val t = stripYamlComment(before).trimEnd()
            val indent = t.length - t.trimStart().length
            var content = indent
            while (content + 1 < t.length && t[content] == '-' && t[content + 1] == ' ') {
                content++
                while (content < t.length && t[content] == ' ') content++
            }
            val colon = ConfigLanguage.keyColon(t, content)
            val listShift = content - indent
            when {
                colon > 0 && colon == t.length - 1 -> listShift + 2
                colon > 0 -> listShift
                else -> 0
            }
        }
    }

    /** "- item" with the cursor at its end: the text Enter inserts to continue the list, else null. */
    fun yamlListContinuation(line: String, column: Int): String? {
        if (line.substring(column).isNotBlank()) return null
        val t = stripYamlComment(line.substring(0, column)).trimEnd()
        val indent = t.length - t.trimStart().length
        if (!t.startsWith("- ", indent)) return null
        var content = indent + 1
        while (content < t.length && t[content] == ' ') content++
        if (content >= t.length) return null
        if (ConfigLanguage.keyColon(t, content) > 0) return null
        return "\n" + " ".repeat(indent) + "- "
    }

    // ---- Completion ----

    fun completeYaml(schema: Schema, lineAt: (Int) -> String, line: Int, column: Int): List<Suggestion> {
        if (schema != Schema.MIHOMO) return emptyList()
        val before = lineAt(line).substring(0, column)
        val prefix = WORD.find(before)?.value.orEmpty()
        if (prefix.isEmpty()) return emptyList()
        val lead = before.substring(0, before.length - prefix.length)
        val isDash = lead.trimStart().startsWith("- ")
        val blank = lead.isBlank() || lead.replace(" ", "").all { it == '-' }
        val chain = yamlChain(lineAt, line, lead)

        if (blank) {
            if (isDash) ConfigSchema.mihomoRules(chain)?.let { rules -> return match(rules, prefix) { "$it," } }
            val keys = ConfigSchema.mihomoKeys(chain) ?: return emptyList()
            return match(keys, prefix) { if (ConfigSchema.mihomoIsBlock(it)) "$it:" else "$it: " }
        }
        // key: val
        val colon = lead.indexOf(": ")
        if (colon < 0 || lead.substring(colon + 2).isNotBlank()) return emptyList()
        if ('#' in lead || '{' in lead || '[' in lead) return emptyList()
        val key = lead.substring(0, colon).trimStart(' ', '-').trim('"', '\'')
        val values = ConfigSchema.mihomoValues(chain, key) ?: return emptyList()
        return match(values.filter { it != prefix }, prefix) { it }
    }

    /** Keys enclosing the position, outermost first. [lead] is the text before the word being typed. */
    private fun yamlChain(lineAt: (Int) -> String, line: Int, lead: String): List<String> {
        val chain = ArrayList<String>()
        var limit = lead.length - lead.trimStart().length
        // A sequence may sit at the same indent as its key, so "- x" also accepts a key at equal indent.
        var allowEqual = lead.trimStart().startsWith("-")
        var l = line - 1
        while (l >= 0 && (limit > 0 || allowEqual)) {
            val s = lineAt(l)
            l--
            val indent = s.length - s.trimStart().length
            if (indent == s.length || s[indent] == '#') continue
            if (s.startsWith("- ", indent) || (s.length == indent + 1 && s[indent] == '-')) {
                if (indent < limit) {
                    limit = indent
                    allowEqual = true
                }
                continue
            }
            if (indent < limit || (allowEqual && indent == limit)) {
                val colon = ConfigLanguage.keyColon(s, indent)
                if (colon > indent) chain += s.substring(indent, colon).trim('"', '\'')
                limit = indent
                allowEqual = false
            }
        }
        chain.reverse()
        return chain
    }

    /** [text] runs from the start of the file to the cursor, [after] is what follows the cursor on the line. */
    fun completeJson(schema: Schema, text: CharSequence, after: String): List<Suggestion> {
        if (schema != Schema.SINGBOX && schema != Schema.XRAY) return emptyList()
        val xray = schema == Schema.XRAY
        val ctx = jsonContext(text) ?: return emptyList()
        val prefix = ctx.prefix
        if (prefix.isEmpty() || WORD.matchEntire(prefix) == null) return emptyList()
        val closed = after.startsWith("\"")
        if (ctx.isKey) {
            val keys = (if (xray) ConfigSchema.xrayKeys(ctx.chain) else ConfigSchema.singboxKeys(ctx.chain)) ?: return emptyList()
            return match(keys, prefix) { if (closed) it else "$it\": " }
        }
        val key = ctx.key ?: return emptyList()
        val values = (if (xray) ConfigSchema.xrayValues(ctx.chain, key) else ConfigSchema.singboxValues(ctx.chain, key)) ?: return emptyList()
        return match(values, prefix) { if (closed) it else "$it\"" }
    }

    private class Frame(val isObject: Boolean, var key: String? = null)
    private class JsonContext(val chain: List<String>, val key: String?, val isKey: Boolean, val prefix: String)

    /** Null unless the cursor is inside a string. */
    private fun jsonContext(text: CharSequence): JsonContext? {
        val stack = ArrayList<Frame>()
        var expectKey = false
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '{' -> { stack += Frame(true); expectKey = true }
                '[' -> { stack += Frame(false); expectKey = false }
                '}', ']' -> { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1); expectKey = false }
                ',' -> expectKey = stack.lastOrNull()?.isObject == true
                ':' -> expectKey = false
                '/' -> if (i + 1 < text.length && text[i + 1] == '/') {
                    while (i < text.length && text[i] != '\n') i++
                    continue
                }
                '"' -> {
                    val start = ++i
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\') i++
                        i++
                    }
                    if (i >= text.length) {
                        return JsonContext(
                            stack.dropLast(1).mapNotNull { it.key },
                            stack.lastOrNull()?.key,
                            expectKey,
                            text.subSequence(start, text.length).toString(),
                        )
                    }
                    if (expectKey) stack.lastOrNull()?.key = text.subSequence(start, i).toString()
                    expectKey = false
                }
            }
            i++
        }
        return null
    }

    private inline fun match(words: List<String>, prefix: String, commit: (String) -> String): List<Suggestion> {
        val starts = words.filter { it.startsWith(prefix, ignoreCase = true) }
        val inside = if (prefix.length >= 2) words.filter { it.contains(prefix, ignoreCase = true) && it !in starts } else emptyList()
        return (starts + inside).take(MAX_SUGGESTIONS).map { Suggestion(it, commit(it), prefix.length) }
    }
}

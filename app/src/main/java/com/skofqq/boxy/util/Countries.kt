package com.skofqq.boxy.util

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Country of a proxy node from its name: a flag emoji, a country name (English or Russian), a well-known city
 * or an upper-case ISO code ("DE", "US-1"). Used for the flags in the Proxies tab and the country filter.
 */
object Countries {
    private val cache = ConcurrentHashMap<String, String>()
    private const val NONE = ""

    /** Upper-case ISO 3166 codes that are also common words or protocol names in node names. */
    private val AMBIGUOUS_CODES = setOf("AD", "AI", "AM", "AS", "AT", "BE", "BY", "DO", "GO", "IN", "IS", "IT", "ME", "MY", "NO", "ON", "OR", "SO", "TO", "TV", "UP", "WS")

    private val CODES: Set<String> by lazy { Locale.getISOCountries().toSet() }

    /** Whole-word aliases (lower case) → ISO code: names in English and Russian plus common short forms and cities. */
    private val ALIASES: List<Pair<String, String>> by lazy {
        val map = LinkedHashMap<String, String>()
        val ru = Locale.forLanguageTag("ru")
        for (code in Locale.getISOCountries()) {
            val l = Locale("", code)
            listOf(l.getDisplayCountry(Locale.ENGLISH), l.getDisplayCountry(ru)).forEach { n ->
                val key = normalize(n).trim()
                if (key.length >= 4 && key !in map) map[key] = code
            }
        }
        EXTRA.forEach { (alias, code) -> map[normalize(alias).trim()] = code }
        // Longer names first, so "south korea" wins over "korea" and "united states" over "states".
        map.entries.sortedByDescending { it.key.length }.map { it.key to it.value }
    }

    private val EXTRA = listOf(
        "usa" to "US", "america" to "US", "united states of america" to "US", "сша" to "US", "америка" to "US",
        "uk" to "GB", "britain" to "GB", "great britain" to "GB", "england" to "GB", "англия" to "GB", "британия" to "GB",
        "holland" to "NL", "голландия" to "NL", "korea" to "KR", "south korea" to "KR", "корея" to "KR", "южная корея" to "KR",
        "uae" to "AE", "emirates" to "AE", "оаэ" to "AE", "эмираты" to "AE", "turkey" to "TR", "turkiye" to "TR", "türkiye" to "TR",
        "czechia" to "CZ", "чехия" to "CZ", "russia" to "RU", "россия" to "RU", "рф" to "RU", "hongkong" to "HK", "гонконг" to "HK",
        "taiwan" to "TW", "тайвань" to "TW", "vietnam" to "VN", "вьетнам" to "VN", "moldova" to "MD", "молдова" to "MD",
        // Cities that hosting providers name nodes after.
        "amsterdam" to "NL", "амстердам" to "NL", "rotterdam" to "NL", "frankfurt" to "DE", "франкфурт" to "DE", "berlin" to "DE",
        "берлин" to "DE", "munich" to "DE", "nuremberg" to "DE", "falkenstein" to "DE", "london" to "GB", "лондон" to "GB",
        "manchester" to "GB", "paris" to "FR", "париж" to "FR", "marseille" to "FR", "gravelines" to "FR", "roubaix" to "FR",
        "stockholm" to "SE", "стокгольм" to "SE", "helsinki" to "FI", "хельсинки" to "FI", "warsaw" to "PL", "варшава" to "PL",
        "riga" to "LV", "рига" to "LV", "tallinn" to "EE", "таллин" to "EE", "vilnius" to "LT", "вильнюс" to "LT",
        "istanbul" to "TR", "стамбул" to "TR", "vienna" to "AT", "вена" to "AT", "zurich" to "CH", "цюрих" to "CH",
        "milan" to "IT", "милан" to "IT", "madrid" to "ES", "мадрид" to "ES", "prague" to "CZ", "прага" to "CZ",
        "moscow" to "RU", "москва" to "RU", "petersburg" to "RU", "питер" to "RU", "санкт петербург" to "RU",
        "almaty" to "KZ", "алматы" to "KZ", "astana" to "KZ", "tbilisi" to "GE", "тбилиси" to "GE", "yerevan" to "AM", "ереван" to "AM",
        "new york" to "US", "нью йорк" to "US", "los angeles" to "US", "лос анджелес" to "US", "chicago" to "US", "dallas" to "US",
        "miami" to "US", "seattle" to "US", "san jose" to "US", "silicon valley" to "US", "ashburn" to "US", "toronto" to "CA",
        "tokyo" to "JP", "токио" to "JP", "osaka" to "JP", "seoul" to "KR", "сеул" to "KR", "singapore" to "SG", "сингапур" to "SG",
        "dubai" to "AE", "дубай" to "AE", "sydney" to "AU", "сидней" to "AU", "mumbai" to "IN", "sao paulo" to "BR",
    )

    /** ISO code of the country a node name points to, or null. */
    fun of(name: String): String? = cache.getOrPut(name) { detect(name) ?: NONE }.ifEmpty { null }

    /** Flag emoji of an ISO code. */
    fun flag(code: String): String {
        val c = code.uppercase()
        if (c.length != 2 || c == "UN") return "\uD83C\uDFF3" // white flag
        return String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
    }

    /** True when the name already starts with (or holds) a flag emoji. */
    fun hasFlag(name: String): Boolean = flagCode(name) != null

    /** The node name with the country's flag in front, unless it already shows one. */
    fun withFlag(name: String): String {
        if (hasFlag(name)) return name
        val code = of(name) ?: return name
        return "${flag(code)} $name"
    }

    /** Name of the country in the app's language. */
    fun displayName(code: String, locale: Locale = Locale.getDefault()): String =
        Locale("", code).getDisplayCountry(locale).ifBlank { code }

    /**
     * A regular expression that matches node names of these countries the way [of] does: the flag, the names
     * and the upper-case code. Written for mihomo (regexp2): case-insensitive names, the codes case-sensitive.
     */
    fun regexFor(codes: Collection<String>): String {
        if (codes.isEmpty()) return ""
        val words = ArrayList<String>()
        codes.forEach { code ->
            words += flag(code)
            ALIASES.filter { it.second == code }.forEach { words += Regex.escape(it.first).let(::unquote).replace(" ", "[ _.-]?") }
        }
        val names = words.distinct().joinToString("|")
        val upper = codes.filter { it !in AMBIGUOUS_CODES }.joinToString("|")
        return if (upper.isEmpty()) "(?i)($names)" else "(?i)($names)|(?-i:\\b($upper)\\b)"
    }

    /** Regex.escape wraps in \Q…\E, which mihomo understands but is hard to read; plain names need no escaping. */
    private fun unquote(s: String): String = if (s.startsWith("\\Q") && s.endsWith("\\E")) {
        val inner = s.substring(2, s.length - 2)
        if (inner.all { it.isLetterOrDigit() || it == ' ' }) inner else s
    } else {
        s
    }

    private fun detect(name: String): String? {
        flagCode(name)?.let { return it }
        val norm = " " + normalize(name) + " "
        ALIASES.firstOrNull { (alias, _) -> norm.contains(" $alias ") }?.let { return it.second }
        // Upper-case two-letter codes between non-letters: "DE-1", "[US]", "NL 03".
        Regex("(?<![A-Za-z])([A-Z]{2})(?![A-Za-z])").findAll(name).forEach { m ->
            val c = m.groupValues[1].let { if (it == "UK") "GB" else it }
            if (c in CODES && c !in AMBIGUOUS_CODES) return c
        }
        return null
    }

    /** ISO code of the first regional-indicator pair in the text. */
    private fun flagCode(name: String): String? {
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            val n = Character.charCount(cp)
            if (cp in 0x1F1E6..0x1F1FF && i + n < name.length) {
                val cp2 = name.codePointAt(i + n)
                if (cp2 in 0x1F1E6..0x1F1FF) {
                    return "${'A' + (cp - 0x1F1E6)}${'A' + (cp2 - 0x1F1E6)}"
                }
            }
            i += n
        }
        return null
    }

    private fun normalize(s: String): String =
        s.lowercase().replace('ё', 'е').map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").replace(Regex(" +"), " ")
}

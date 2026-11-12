package com.skofqq.boxy.configs

import com.skofqq.boxy.R
import com.skofqq.boxy.root.CoreTools

/**
 * sing-box settings that are deprecated or already removed, found in the config text with their line, so the
 * editor can list them with "go to line". sing-box's own "deprecated" warnings from the check are added by the
 * caller (they name the feature, not the line).
 */
object SingBoxLint {
    /** [line] is 0-based; [message] a string resource; [since] the version that deprecated or removed it. */
    data class Finding(val line: Int, val message: Int, val since: String)

    private data class Rule(val regex: Regex, val message: Int, val since: String, val section: String? = null)

    private val RULES = listOf(
        // Geo databases were removed in 1.12: rule sets instead.
        Rule(Regex("\"(geosite|geoip|source_geoip)\"\\s*:"), R.string.sb_lint_geo, "1.12"),
        // Inbound sniffing and domain strategy moved to route rule actions in 1.11, removed in 1.13.
        Rule(Regex("\"(sniff|sniff_override_destination|sniff_timeout|domain_strategy)\"\\s*:"), R.string.sb_lint_inbound_sniff, "1.11", "inbounds"),
        // Special outbounds block / dns: rule actions reject / hijack-dns since 1.11, removed in 1.13.
        Rule(Regex("\"type\"\\s*:\\s*\"(block|dns)\""), R.string.sb_lint_special_outbound, "1.11", "outbounds"),
        Rule(Regex("\"outbound\"\\s*:\\s*\"dns-out\""), R.string.sb_lint_dns_out, "1.11", "route"),
        // DNS servers with "address": the typed server format of 1.12, the old one removed in 1.14.
        Rule(Regex("\"address\"\\s*:"), R.string.sb_lint_legacy_dns, "1.12", "dns"),
        // Fake-IP settings moved into a fakeip DNS server in 1.12.
        Rule(Regex("\"fakeip\"\\s*:\\s*\\{"), R.string.sb_lint_legacy_fakeip, "1.12", "dns"),
        // Endpoint-style WireGuard: the wireguard outbound was deprecated in 1.11.
        Rule(Regex("\"type\"\\s*:\\s*\"wireguard\""), R.string.sb_lint_wireguard_outbound, "1.11", "outbounds"),
    )

    fun lint(text: String): List<Finding> {
        val out = ArrayList<Finding>()
        val sections = HashMap<String, IntRange?>()
        fun section(name: String): IntRange? = sections.getOrPut(name) {
            val m = Regex("\"${Regex.escape(name)}\"\\s*:\\s*([\\[{])").find(text) ?: return@getOrPut null
            val open = m.groups[1]!!.range.first
            val close = CoreTools.matchingBrace(text, open) ?: return@getOrPut null
            open..close
        }
        for (r in RULES) {
            val range = r.section?.let { section(it) ?: continue }
            for (m in r.regex.findAll(text)) {
                if (range != null && m.range.first !in range) continue
                if (inComment(text, m.range.first)) continue
                out.add(Finding(text.substring(0, m.range.first).count { it == '\n' }, r.message, r.since))
            }
        }
        // Remote rule sets download through an implicit HTTP client only until 1.16: http_clients must be defined.
        if (!Regex("\"(http_clients|default_http_client)\"\\s*:").containsMatchIn(text)) {
            val rs = section("rule_set")
            Regex("\"type\"\\s*:\\s*\"remote\"").findAll(text).firstOrNull { rs != null && it.range.first in rs }?.let { m ->
                out.add(Finding(text.substring(0, m.range.first).count { it == '\n' }, R.string.sb_lint_http_client, "1.14"))
            }
        }
        return out.sortedBy { it.line }.distinctBy { it.line to it.message }
    }

    private fun inComment(text: String, at: Int): Boolean {
        val lineStart = text.lastIndexOf('\n', at - 1) + 1
        val before = text.substring(lineStart, at)
        return before.contains("//") || before.trimStart().startsWith("#")
    }

    /** sing-box's own deprecation warnings from a check run ("... is deprecated in sing-box 1.x ..."). */
    fun coreWarnings(output: List<String>): List<String> = output
        .map { it.replace(Regex("\u001B\\[[0-9;]*m"), "").trim() }
        .filter { it.contains("deprecated", ignoreCase = true) }
        .map { it.replace(Regex("^(WARN|WARNING)\\[\\d+]\\s*"), "") }
        .distinct()
}

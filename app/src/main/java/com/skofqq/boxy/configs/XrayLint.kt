package com.skofqq.boxy.configs

import com.skofqq.boxy.R

/**
 * Xray settings that Xray 26 refuses to start with (checked against the core: "The feature … has been removed"),
 * with their line, for the editor's list. Plus one hint: a Linux build of Xray / V2Ray looks for /etc/resolv.conf,
 * which Android does not have, so without a "dns" block it cannot resolve domains.
 */
object XrayLint {
    private data class Rule(val regex: Regex, val message: Int, val since: String)

    private val RULES = listOf(
        Rule(Regex("\"allowInsecure\"\\s*:"), R.string.xr_lint_allow_insecure, "26"),
        Rule(Regex("\"flow\"\\s*:\\s*\"xtls-rprx-(direct|origin|splice)"), R.string.xr_lint_flow, "1.8"),
        Rule(Regex("\"security\"\\s*:\\s*\"xtls\""), R.string.xr_lint_xtls, "1.8"),
        Rule(Regex("\"network\"\\s*:\\s*\"(quic|http|h2|domainsocket)\""), R.string.xr_lint_transport, "25"),
        Rule(Regex("(?m)^\\s{0,4}\"transport\"\\s*:\\s*\\{"), R.string.xr_lint_global_transport, "25"),
    )

    /** [xray] false for V2Ray, which still accepts these; [linuxBuild] when the core binary is a Linux build. */
    fun lint(text: String, xray: Boolean, linuxBuild: Boolean): List<SingBoxLint.Finding> {
        val out = ArrayList<SingBoxLint.Finding>()
        if (xray) {
            for (r in RULES) {
                for (m in r.regex.findAll(text)) {
                    val lineStart = text.lastIndexOf('\n', m.range.first - 1) + 1
                    if (text.substring(lineStart, m.range.first).contains("//")) continue
                    out.add(SingBoxLint.Finding(text.substring(0, m.range.first).count { it == '\n' }, r.message, r.since))
                }
            }
        }
        if (linuxBuild && !Regex("\"dns\"\\s*:\\s*\\{").containsMatchIn(text)) {
            out.add(SingBoxLint.Finding(0, R.string.xr_lint_dns, ""))
        }
        return out.sortedBy { it.line }.distinctBy { it.line to it.message }
    }
}

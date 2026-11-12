package com.skofqq.boxy.root

import com.skofqq.boxy.R

/**
 * Why a core did not start, read from what it printed (the module copies it into runs.log). The cores word their
 * errors differently: mihomo and sing-box ("FATAL ... decode config"), xray ("Failed to start: main: failed to load
 * config files"), v2fly ("failed to load config"), hysteria (zap JSON: "fatal ... {"error": "..."}").
 * The first matching reason wins; [UNKNOWN] keeps the generic text.
 */
enum class StartFailure(val message: Int) {
    NO_KERNEL(R.string.start_failed_core_missing),
    NO_CONFIG(R.string.start_reason_no_config),
    PORT_IN_USE(R.string.start_reason_port),
    AUTH(R.string.start_reason_auth),
    SERVER_UNREACHABLE(R.string.start_reason_unreachable),
    TLS(R.string.start_reason_tls),
    GEO_MISSING(R.string.start_reason_geo),
    MISSING_FILE(R.string.start_reason_file),
    BAD_CONFIG(R.string.start_reason_config),
    UNKNOWN(R.string.start_failed_body),
    ;

    companion object {
        private fun re(s: String) = Regex(s, RegexOption.IGNORE_CASE)

        private val RULES: List<Pair<StartFailure, Regex>> = listOf(
            NO_KERNEL to re("kernel .* is missing|bin/\\S+ is not executable|version information not available"),
            NO_CONFIG to re("configuration file \\S+ not found|no configuration file found|failed to read (client|server) config|config file not found"),
            PORT_IN_USE to re("address already in use|bind: address|failed to listen (tcp|udp)|listen tcp .*: bind"),
            // hysteria: "authentication error, HTTP status code: 404"; xray / v2fly: bad user id; sing-box: auth failed.
            AUTH to re("authentication (error|failed)|auth(entication)? error|invalid user|invalid (uuid|user id|password)|unauthorized|HTTP status code: 40[13]"),
            TLS to re("x509:|certificate (signed by unknown|is not valid|has expired)|tls: |handshake failure|REALITY: (processed invalid|failed)"),
            SERVER_UNREACHABLE to re("no recent network activity|no such host|server misbehaving|connect(ion)? (error|refused)|i/o timeout|no route to host|network is unreachable|dial (tcp|udp) .*: (timeout|connect)"),
            GEO_MISSING to re("geo(site|ip)(\\.dat|\\.db)?\\b.*(no such file|not found|failed to (load|open))|(code|list) not found in geo|failed to load (geosite|geoip)|country\\.mmdb.*(no such|not found)|can't find (geosite|geoip)"),
            MISSING_FILE to re("no such file or directory|failed to open file|cannot find the file|open \\S+: no such"),
            BAD_CONFIG to re("decode config|failed to load config|failed to parse|invalid character|yaml: |unmarshal|unknown field|json: cannot|invalid config|config error|parse config|unexpected end of json|failed to build|missing (server|auth)"),
        )

        fun of(raw: String?): StartFailure {
            if (raw.isNullOrBlank()) return UNKNOWN
            return RULES.firstOrNull { (_, r) -> r.containsMatchIn(raw) }?.first ?: UNKNOWN
        }
    }
}

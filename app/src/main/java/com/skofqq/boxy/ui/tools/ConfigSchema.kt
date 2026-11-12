package com.skofqq.boxy.ui.tools

/** Keys and enum values offered by the editor's completion. Hand-kept: extend it when the cores add options. */
internal object ConfigSchema {
    private fun w(s: String) = s.trim().split(Regex("\\s+"))

    private val BOOL = listOf("true", "false")

    // ---- mihomo ----

    /** Keys whose value is a nested block, so completion stops after the colon. */
    private val MIHOMO_BLOCKS = w(
        """dns tun sniffer profile geox-url hosts proxies proxy-groups proxy-providers rule-providers rules sub-rules
        listeners tunnels experimental ntp fallback-filter nameserver-policy default-nameserver nameserver fallback
        proxy-server-nameserver fake-ip-filter sniff skip-domain force-domain skip-src-address skip-dst-address
        ws-opts grpc-opts h2-opts http-opts reality-opts smux health-check override headers use filter-groups
        route-address route-exclude-address include-package exclude-package dns-hijack alpn""",
    ).toSet()

    private val MIHOMO_KEYS: Map<String, List<String>> = mapOf(
        "" to w(
            """mixed-port port socks-port redir-port tproxy-port allow-lan bind-address mode log-level ipv6
            external-controller external-controller-tls external-ui external-ui-name external-ui-url secret
            unified-delay tcp-concurrent find-process-mode global-client-fingerprint keep-alive-interval
            keep-alive-idle disable-keep-alive routing-mark interface-name authentication skip-auth-prefixes
            lan-allowed-ips lan-disallowed-ips geodata-mode geodata-loader geosite-matcher geo-auto-update
            geo-update-interval etag-support profile geox-url hosts dns tun sniffer tunnels listeners ntp experimental
            proxies proxy-groups proxy-providers rule-providers rules sub-rules""",
        ),
        "dns" to w(
            """enable cache-algorithm prefer-h3 listen ipv6 ipv6-timeout use-hosts use-system-hosts respect-rules
            enhanced-mode fake-ip-range fake-ip-range6 fake-ip-filter fake-ip-filter-mode fake-ip-ttl
            default-nameserver nameserver fallback proxy-server-nameserver direct-nameserver
            direct-nameserver-follow-policy nameserver-policy fallback-filter""",
        ),
        "fallback-filter" to w("geoip geoip-code geosite ipcidr domain"),
        "tun" to w(
            """enable stack device dns-hijack auto-route auto-redirect auto-detect-interface strict-route mtu gso
            gso-max-size udp-timeout endpoint-independent-nat route-address route-exclude-address
            route-address-set route-exclude-address-set include-interface exclude-interface include-uid
            exclude-uid include-android-user include-package exclude-package""",
        ),
        "sniffer" to w(
            "enable force-dns-mapping parse-pure-ip override-destination sniff force-domain skip-domain skip-src-address skip-dst-address",
        ),
        "sniff" to w("HTTP TLS QUIC"),
        "HTTP" to w("ports override-destination"),
        "TLS" to w("ports override-destination"),
        "QUIC" to w("ports override-destination"),
        "profile" to w("store-selected store-fake-ip"),
        "geox-url" to w("geoip geosite mmdb asn"),
        "experimental" to w("quic-go-disable-gso quic-go-disable-ecn dialer-ip4p-convert"),
        "ntp" to w("enable write-to-system server port interval"),
        "proxies" to w(
            """name type server port udp ip-version interface-name routing-mark tfo mptcp dialer-proxy smux
            uuid alterId cipher password username flow network tls sni servername alpn fingerprint skip-cert-verify
            client-fingerprint reality-opts ws-opts grpc-opts h2-opts http-opts packet-encoding encryption
            ports hop-interval up down obfs obfs-password congestion-controller reduce-rtt token
            private-key public-key ip ipv6 allowed-ips reserved mtu remote-dns-resolve dns
            idle-session-check-interval idle-session-timeout min-idle-session""",
        ),
        "ws-opts" to w("path headers max-early-data early-data-header-name v2ray-http-upgrade v2ray-http-upgrade-fast-open"),
        "grpc-opts" to w("grpc-service-name"),
        "h2-opts" to w("host path"),
        "http-opts" to w("method path headers"),
        "reality-opts" to w("public-key short-id"),
        "smux" to w("enabled protocol max-connections min-streams max-streams padding statistic only-tcp"),
        "proxy-groups" to w(
            """name type proxies use url interval lazy timeout max-failed-times tolerance strategy filter
            exclude-filter exclude-type include-all include-all-proxies include-all-providers
            disable-udp interface-name routing-mark hidden icon expected-status""",
        ),
        "health-check" to w("enable url interval timeout lazy expected-status"),
        "override" to w(
            "udp down up skip-cert-verify dialer-proxy interface-name routing-mark ip-version additional-prefix additional-suffix",
        ),
        "listeners" to w("name type port listen rule proxy udp users"),
    )

    private val MIHOMO_PROXY_PROVIDER = w(
        "type url path interval proxy size-limit header health-check filter exclude-filter exclude-type override payload",
    )
    private val MIHOMO_RULE_PROVIDER = w("type behavior format url path interval proxy size-limit payload")

    private val MIHOMO_RULES = w(
        """DOMAIN DOMAIN-SUFFIX DOMAIN-KEYWORD DOMAIN-WILDCARD DOMAIN-REGEX GEOSITE IP-CIDR IP-CIDR6 IP-SUFFIX IP-ASN
        GEOIP SRC-GEOIP SRC-IP-ASN SRC-IP-CIDR SRC-IP-SUFFIX DST-PORT SRC-PORT IN-PORT IN-TYPE IN-USER IN-NAME
        PROCESS-PATH PROCESS-PATH-REGEX PROCESS-NAME PROCESS-NAME-REGEX UID NETWORK DSCP RULE-SET AND OR NOT
        SUB-RULE MATCH""",
    )

    private val PROXY_TYPES = w("ss vmess vless trojan hysteria hysteria2 tuic wireguard ssh anytls socks5 http snell mieru direct dns")
    private val FINGERPRINTS = w("chrome firefox safari ios android edge 360 qq random")

    /** Booleans listed once: the same word means the same thing in every section. */
    private val MIHOMO_BOOLS = w(
        """allow-lan ipv6 unified-delay tcp-concurrent geo-auto-update geodata-mode etag-support disable-keep-alive
        enable prefer-h3 use-hosts use-system-hosts respect-rules auto-route auto-redirect auto-detect-interface
        strict-route gso endpoint-independent-nat force-dns-mapping parse-pure-ip override-destination
        store-selected store-fake-ip udp tfo mptcp tls skip-cert-verify lazy include-all include-all-proxies
        include-all-providers disable-udp hidden geoip reduce-rtt enabled padding statistic only-tcp
        direct-nameserver-follow-policy""",
    ).toSet()

    private val MIHOMO_VALUES: Map<String, List<String>> = mapOf(
        "mode" to w("rule global direct"),
        "log-level" to w("silent error warning info debug"),
        "find-process-mode" to w("off strict always"),
        "enhanced-mode" to w("fake-ip redir-host normal"),
        "fake-ip-filter-mode" to w("blacklist whitelist rule"),
        "cache-algorithm" to w("arc lru"),
        "stack" to w("system gvisor mixed"),
        "network" to w("tcp ws http h2 grpc"),
        "strategy" to w("consistent-hashing round-robin sticky-sessions"),
        "client-fingerprint" to FINGERPRINTS,
        "global-client-fingerprint" to FINGERPRINTS,
        "ip-version" to w("dual ipv4 ipv6 ipv4-prefer ipv6-prefer"),
        "packet-encoding" to w("packetaddr xudp"),
        "flow" to w("xtls-rprx-vision"),
        "behavior" to w("domain ipcidr classical"),
        "format" to w("yaml text mrs"),
        "geodata-loader" to w("memconservative standard"),
        "geosite-matcher" to w("succinct mph"),
        "cipher" to w(
            """aes-128-gcm aes-192-gcm aes-256-gcm chacha20-ietf-poly1305 xchacha20-ietf-poly1305
            2022-blake3-aes-128-gcm 2022-blake3-aes-256-gcm 2022-blake3-chacha20-poly1305 auto none""",
        ),
    )

    fun mihomoKeys(chain: List<String>): List<String>? {
        val root = chain.firstOrNull() ?: return MIHOMO_KEYS[""]
        if (chain.size == 2 && root == "proxy-providers") return MIHOMO_PROXY_PROVIDER
        if (chain.size == 2 && root == "rule-providers") return MIHOMO_RULE_PROVIDER
        return MIHOMO_KEYS[chain.last()]
    }

    fun mihomoIsBlock(key: String) = key in MIHOMO_BLOCKS

    fun mihomoRules(chain: List<String>): List<String>? =
        if (chain.firstOrNull() == "rules" || chain.firstOrNull() == "sub-rules") MIHOMO_RULES else null

    fun mihomoValues(chain: List<String>, key: String): List<String>? {
        if (key == "type") {
            return when (chain.firstOrNull()) {
                "proxies" -> PROXY_TYPES
                "proxy-groups" -> w("select url-test fallback load-balance relay")
                "proxy-providers", "rule-providers" -> w("http file inline")
                else -> null
            }
        }
        return MIHOMO_VALUES[key] ?: if (key in MIHOMO_BOOLS) BOOL else null
    }

    // ---- sing-box ----

    private val SINGBOX_RULE = w(
        """inbound ip_version network auth_user protocol client domain domain_suffix domain_keyword domain_regex
        source_ip_cidr source_ip_is_private ip_cidr ip_is_private source_port source_port_range port port_range
        process_name process_path process_path_regex package_name user user_id clash_mode network_type
        network_is_expensive network_is_constrained wifi_ssid wifi_bssid rule_set rule_set_ip_cidr_match_source
        invert action outbound server method override_address override_port udp_disable_domain_unmapping
        udp_connect sniffer type mode rules""",
    )

    private val SINGBOX_KEYS: Map<String, List<String>> = mapOf(
        "" to w("log dns ntp certificate endpoints inbounds outbounds route experimental"),
        "log" to w("disabled level output timestamp"),
        "dns" to w(
            "servers rules final strategy disable_cache disable_expire independent_cache cache_capacity reverse_mapping client_subnet fakeip",
        ),
        "fakeip" to w("enabled inet4_range inet6_range"),
        "servers" to w(
            "type tag server server_port path address address_resolver address_strategy strategy detour client_subnet domain_resolver tls headers interface inet4_range inet6_range",
        ),
        "ntp" to w("enabled server server_port interval"),
        "certificate" to w("store certificate certificate_path certificate_directory_path"),
        "inbounds" to w(
            """type tag listen listen_port tcp_fast_open tcp_multi_path udp_fragment udp_timeout detour sniff
            sniff_override_destination sniff_timeout domain_strategy users tls transport multiplex method password
            interface_name address mtu auto_route auto_redirect strict_route route_address route_exclude_address
            route_address_set route_exclude_address_set include_interface exclude_interface include_uid exclude_uid
            include_android_user include_package exclude_package endpoint_independent_nat stack platform
            set_system_proxy network""",
        ),
        "users" to w("name username password uuid flow alterId"),
        "outbounds" to w(
            """type tag server server_port uuid password username method flow security alter_id global_padding
            authenticated_length network tls transport multiplex packet_encoding plugin plugin_opts version
            up_mbps down_mbps obfs auth_str congestion_control udp_relay_mode udp_over_stream zero_rtt_handshake
            heartbeat outbounds default url interval tolerance idle_timeout interrupt_exist_connections
            detour bind_interface inet4_bind_address inet6_bind_address routing_mark reuse_addr connect_timeout
            tcp_fast_open tcp_multi_path udp_fragment domain_strategy domain_resolver fallback_delay""",
        ),
        "tls" to w(
            "enabled disable_sni server_name insecure alpn min_version max_version cipher_suites certificate certificate_path key key_path utls reality ech fragment record_fragment",
        ),
        "utls" to w("enabled fingerprint"),
        "reality" to w("enabled public_key short_id handshake private_key max_time_difference"),
        "ech" to w("enabled config config_path"),
        "transport" to w(
            "type host path method headers idle_timeout ping_timeout max_early_data early_data_header_name service_name permit_without_stream",
        ),
        "multiplex" to w("enabled protocol max_connections min_streams max_streams padding brutal"),
        "obfs" to w("type password"),
        "route" to w(
            """rules rule_set final auto_detect_interface override_android_vpn default_interface default_mark
            default_domain_resolver default_network_strategy find_process""",
        ),
        "rules" to SINGBOX_RULE,
        "rule_set" to w("type tag format url path download_detour update_interval rules"),
        "experimental" to w("cache_file clash_api v2ray_api"),
        "cache_file" to w("enabled path cache_id store_fakeip store_rdrc rdrc_timeout"),
        "clash_api" to w(
            "external_controller external_ui external_ui_download_url external_ui_download_detour secret default_mode access_control_allow_origin access_control_allow_private_network",
        ),
    )

    private val SINGBOX_BOOLS = w(
        """disabled timestamp enabled disable_cache disable_expire independent_cache reverse_mapping auto_route
        auto_redirect strict_route sniff sniff_override_destination endpoint_independent_nat insecure disable_sni
        tcp_fast_open tcp_multi_path udp_fragment zero_rtt_handshake interrupt_exist_connections reuse_addr
        padding auto_detect_interface override_android_vpn find_process invert ip_is_private source_ip_is_private
        store_fakeip store_rdrc""",
    ).toSet()

    private val SINGBOX_STRATEGY = w("prefer_ipv4 prefer_ipv6 ipv4_only ipv6_only")
    private val SINGBOX_VALUES: Map<String, List<String>> = mapOf(
        "level" to w("trace debug info warn error fatal panic"),
        "strategy" to SINGBOX_STRATEGY,
        "domain_strategy" to SINGBOX_STRATEGY,
        "address_strategy" to SINGBOX_STRATEGY,
        "stack" to w("system gvisor mixed"),
        "network" to w("tcp udp"),
        "action" to w("route route-options reject hijack-dns sniff resolve bypass"),
        "format" to w("binary source"),
        "clash_mode" to w("rule global direct"),
        "default_mode" to w("rule global direct"),
        "fingerprint" to w("chrome firefox edge safari 360 qq ios android random randomized"),
        "flow" to w("xtls-rprx-vision"),
        "packet_encoding" to w("xudp packetaddr none"),
        "min_version" to w("1.0 1.1 1.2 1.3"),
        "max_version" to w("1.0 1.1 1.2 1.3"),
        "congestion_control" to w("cubic new_reno bbr"),
        "udp_relay_mode" to w("native quic"),
        "protocol" to w("http tls quic dns bittorrent dtls ssh rdp ntp stun"),
    )

    fun singboxKeys(chain: List<String>): List<String>? = SINGBOX_KEYS[chain.lastOrNull() ?: ""]

    fun singboxValues(chain: List<String>, key: String): List<String>? {
        if (key == "type") {
            return when (chain.lastOrNull()) {
                "outbounds" -> w("direct block socks http shadowsocks vmess trojan naive hysteria shadowtls vless tuic hysteria2 anytls tor ssh selector urltest")
                "inbounds" -> w("tun mixed socks http redirect tproxy shadowsocks vmess trojan naive hysteria shadowtls vless tuic hysteria2 anytls direct")
                "transport" -> w("http ws quic grpc httpupgrade")
                "rule_set" -> w("local remote inline")
                "servers" -> w("local hosts tcp udp tls quic https h3 dhcp fakeip tailscale resolved")
                "rules" -> w("default logical")
                "obfs" -> w("salamander")
                else -> null
            }
        }
        return SINGBOX_VALUES[key] ?: if (key in SINGBOX_BOOLS) BOOL else null
    }

    // ---- Xray (and V2Ray's v4 JSON) ----

    private val XRAY_STREAM = w(
        """network security tlsSettings realitySettings rawSettings tcpSettings xhttpSettings kcpSettings grpcSettings
        wsSettings httpupgradeSettings hysteriaSettings sockopt""",
    )
    private val XRAY_RULE = w(
        "type domain ip port sourcePort network source user inboundTag protocol attrs outboundTag balancerTag ruleTag localIP localPort process",
    )

    private val XRAY_KEYS: Map<String, List<String>> = mapOf(
        "" to w("log api dns routing policy inbounds outbounds transport stats reverse fakedns metrics observatory burstObservatory"),
        "log" to w("access error loglevel dnsLog maskAddress"),
        "api" to w("tag listen services"),
        "dns" to w("servers hosts clientIp queryStrategy disableCache disableFallback disableFallbackIfMatch tag"),
        "servers" to w("address port domains expectIPs skipFallback clientIP queryStrategy timeoutMs"),
        "routing" to w("domainStrategy rules balancers"),
        "rules" to XRAY_RULE,
        "balancers" to w("tag selector fallbackTag strategy"),
        "strategy" to w("type settings"),
        "policy" to w("levels system"),
        "system" to w("statsInboundUplink statsInboundDownlink statsOutboundUplink statsOutboundDownlink"),
        "inbounds" to w("listen port protocol settings streamSettings tag sniffing allocate"),
        "sniffing" to w("enabled destOverride metadataOnly domainsExcluded routeOnly"),
        "outbounds" to w("sendThrough protocol settings tag streamSettings proxySettings mux"),
        "settings" to w(
            "vnext servers clients decryption fallbacks network followRedirect domainStrategy redirect address port users id flow encryption password method level email udp version",
        ),
        "vnext" to w("address port users"),
        "users" to w("id flow encryption security level email alterId"),
        "streamSettings" to XRAY_STREAM,
        "tlsSettings" to w("serverName alpn fingerprint minVersion maxVersion certificates pinnedPeerCertSha256 verifyPeerCertByName rejectUnknownSni enableSessionResumption echConfigList"),
        "realitySettings" to w("show target dest serverNames privateKey shortIds fingerprint serverName publicKey password shortId spiderX mldsa65Verify"),
        "wsSettings" to w("path host headers heartbeatPeriod"),
        "grpcSettings" to w("serviceName multiMode idle_timeout health_check_timeout permit_without_stream"),
        "xhttpSettings" to w("host path mode extra"),
        "httpupgradeSettings" to w("path host headers"),
        "sockopt" to w("mark tcpFastOpen tproxy domainStrategy dialerProxy tcpKeepAliveInterval tcpcongestion interface"),
        "mux" to w("enabled concurrency xudpConcurrency xudpProxyUDP443"),
        "observatory" to w("subjectSelector probeUrl probeInterval enableConcurrency"),
        "metrics" to w("tag listen"),
    )

    private val XRAY_VALUES: Map<String, List<String>> = mapOf(
        "loglevel" to w("debug info warning error none"),
        "protocol" to w("vless vmess trojan shadowsocks socks http freedom blackhole dns dokodemo-door hysteria wireguard loopback"),
        "network" to w("raw tcp xhttp kcp grpc ws httpupgrade hysteria"),
        "security" to w("none tls reality"),
        "fingerprint" to w("chrome firefox safari ios android edge 360 qq random randomized"),
        "flow" to w("xtls-rprx-vision xtls-rprx-vision-udp443"),
        "domainStrategy" to w("AsIs IPIfNonMatch IPOnDemand UseIP UseIPv4 UseIPv6"),
        "queryStrategy" to w("UseIP UseIPv4 UseIPv6"),
        "mode" to w("auto packet-up stream-up stream-one"),
        "services" to w("HandlerService LoggerService StatsService RoutingService ObservatoryService ReflectionService"),
        "destOverride" to w("http tls quic fakedns"),
    )

    private val XRAY_BOOLS = w(
        """enabled udp metadataOnly routeOnly multiMode tcpFastOpen statsInboundUplink statsInboundDownlink
        statsOutboundUplink statsOutboundDownlink disableCache disableFallback enableConcurrency show
        rejectUnknownSni followRedirect""",
    ).toSet()

    fun xrayKeys(chain: List<String>): List<String>? = XRAY_KEYS[chain.lastOrNull() ?: ""]

    fun xrayValues(chain: List<String>, key: String): List<String>? {
        if (key == "type") {
            return when (chain.lastOrNull()) {
                "rules" -> w("field")
                "strategy" -> w("random roundRobin leastPing leastLoad")
                else -> null
            }
        }
        return XRAY_VALUES[key] ?: if (key in XRAY_BOOLS) BOOL else null
    }
}

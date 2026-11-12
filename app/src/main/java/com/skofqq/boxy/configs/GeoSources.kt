package com.skofqq.boxy.configs

import com.skofqq.boxy.R

/**
 * GeoIP / GeoSite sources the module can download (box.tool geo_src_url). Each is a geosite.dat (+ geoip.dat) pair.
 * [prefix] marks an extra source's categories wherever they share one namespace with the primary's:
 * mihomo's merged GeoSite.dat (rf-ru-blocked) and sing-box rule set tags (geosite-rf-ru-blocked).
 */
data class GeoSource(val id: String, val title: String, val prefix: String, val description: Int, val hasGeoip: Boolean)

object GeoSources {
    val ALL = listOf(
        GeoSource("metacubex", "MetaCubeX", "mc", R.string.geo_source_metacubex, true),
        GeoSource("runetfreedom", "runetfreedom", "rf", R.string.geo_source_runetfreedom, true),
        GeoSource("loyalsoldier", "Loyalsoldier", "ls", R.string.geo_source_loyalsoldier, true),
        GeoSource("v2fly", "v2fly", "v2", R.string.geo_source_v2fly, false),
        GeoSource("itdoginfo", "itdoginfo", "itdog", R.string.geo_source_itdoginfo, false),
    )

    fun byId(id: String?): GeoSource = ALL.firstOrNull { it.id == id } ?: ALL[0]
}

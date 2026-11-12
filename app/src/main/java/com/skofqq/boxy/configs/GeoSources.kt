package com.skofqq.boxy.configs

import com.skofqq.boxy.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * GeoIP / GeoSite sources the module can download (box.tool geo_src_url). Each is a geosite.dat (+ geoip.dat) pair.
 * [prefix] marks an extra source's categories wherever they share one namespace with the primary's:
 * mihomo's merged GeoSite.dat (rf-ru-blocked) and sing-box rule set tags (geosite-rf-ru-blocked).
 * [files] are the URLs box.tool downloads for it, used here only to show the download size.
 */
data class GeoSource(
    val id: String,
    val title: String,
    val prefix: String,
    val description: Int,
    val hasGeoip: Boolean,
    val files: List<String> = emptyList(),
)

object GeoSources {
    private const val MC = "https://github.com/MetaCubeX/meta-rules-dat/releases/latest/download"
    private const val RF = "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download"
    private const val LS = "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download"

    val ALL = listOf(
        GeoSource("metacubex", "MetaCubeX", "mc", R.string.geo_source_metacubex, true, listOf("$MC/geosite.dat", "$MC/geoip-lite.dat")),
        GeoSource("runetfreedom", "runetfreedom", "rf", R.string.geo_source_runetfreedom, true, listOf("$RF/geosite.dat", "$RF/geoip.dat")),
        GeoSource("loyalsoldier", "Loyalsoldier", "ls", R.string.geo_source_loyalsoldier, true, listOf("$LS/geosite.dat", "$LS/geoip.dat")),
        GeoSource("v2fly", "v2fly", "v2", R.string.geo_source_v2fly, false, listOf("https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat")),
        GeoSource("itdoginfo", "itdoginfo", "itdog", R.string.geo_source_itdoginfo, false, listOf("https://github.com/itdoginfo/allow-domains/releases/latest/download/geosite.dat")),
    )

    fun byId(id: String?): GeoSource = ALL.firstOrNull { it.id == id } ?: ALL[0]

    /** Above this a source is worth a warning before turning it on (runetfreedom: ~105 MB). */
    const val LARGE_BYTES = 50L * 1024 * 1024

    private val sizes = HashMap<String, Long>()

    /** Download size of each source (HEAD requests, only when the sources sheet is open; kept for the process). */
    suspend fun sizes(): Map<String, Long> = withContext(Dispatchers.IO) {
        coroutineScope {
            ALL.map { s ->
                async {
                    synchronized(sizes) { sizes[s.id] }?.let { return@async s.id to it }
                    val total = s.files.map { u -> async { head(u) } }.awaitAll()
                    if (total.any { it == null }) null else (s.id to total.sumOf { it!! }).also { (id, n) -> synchronized(sizes) { sizes[id] = n } }
                }
            }.awaitAll().filterNotNull().toMap()
        }
    }

    private fun head(url: String): Long? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"
        c.instanceFollowRedirects = true
        c.connectTimeout = 8000
        c.readTimeout = 8000
        try {
            c.contentLengthLong.takeIf { it > 0 }
        } finally {
            c.disconnect()
        }
    }.getOrNull()
}

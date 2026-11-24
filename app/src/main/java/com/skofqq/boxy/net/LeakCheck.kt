package com.skofqq.boxy.net

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.skofqq.boxy.root.BoxModule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

enum class LeakVerdict { OK, WARN, LEAK, UNKNOWN }

/** Whether the addresses found can be judged as proxied or not. */
enum class LeakScope { PROXIED, SERVICE_OFF, BOXY_EXCLUDED }

data class LeakResult(
    val scope: LeakScope,
    /** The addresses of this connection without the proxy; empty when they could not be found. */
    val real: List<GeoIp>,
    val ipv4: GeoIp?,
    val ipv6: GeoIp?,
    val dns: List<GeoIp>,
    val webRtc: List<GeoIp>,
    val ipv4Verdict: LeakVerdict,
    val ipv6Verdict: LeakVerdict,
    val dnsVerdict: LeakVerdict,
    val webRtcVerdict: LeakVerdict,
    /** network_mode of the module, for the hint when UDP cannot be proxied. */
    val networkMode: String? = null,
    /** IPv6 is switched off in the module. */
    val moduleIpv6Off: Boolean = false,
    /** The module can block WebRTC past the core, but block_webrtc is off. */
    val webRtcBlockOff: Boolean = false,
) {
    /** Likely causes of the leaks found, as string resources. */
    val hints: List<Int>
        get() = buildList {
            if (webRtcVerdict == LeakVerdict.LEAK) {
                add(if (networkMode == "redirect") com.skofqq.boxy.R.string.leak_hint_redirect else com.skofqq.boxy.R.string.leak_hint_udp)
                if (webRtcBlockOff) add(com.skofqq.boxy.R.string.leak_hint_webrtc_block)
            } else if (webRtcVerdict == LeakVerdict.WARN) {
                add(com.skofqq.boxy.R.string.leak_hint_webrtc_other)
                if (webRtcBlockOff) add(com.skofqq.boxy.R.string.leak_hint_webrtc_block)
            }
            if (ipv6Verdict == LeakVerdict.LEAK && moduleIpv6Off) add(com.skofqq.boxy.R.string.leak_hint_ipv6)
        }

    val overall: LeakVerdict
        get() = listOf(ipv4Verdict, ipv6Verdict, dnsVerdict, webRtcVerdict).let { all ->
            when {
                LeakVerdict.LEAK in all -> LeakVerdict.LEAK
                LeakVerdict.WARN in all -> LeakVerdict.WARN
                LeakVerdict.UNKNOWN in all -> LeakVerdict.UNKNOWN
                else -> LeakVerdict.OK
            }
        }
}

/** A one-shot check of what websites see: exit addresses, DNS servers and WebRTC candidates. */
object LeakCheck {
    /** The last result in this process, for the card on the home page; never saved. */
    var last by androidx.compose.runtime.mutableStateOf<LeakResult?>(null)

    private const val WEBRTC_TIMEOUT_MS = 6000L
    private const val DNS_PROBES = 4

    suspend fun run(context: Context): LeakResult = coroutineScope {
        val state = async { runCatching { BoxModule.state() }.getOrNull() }
        val excluded = async { BoxModule.packageExcluded(context.packageName) }
        val rtcBlock = async { runCatching { BoxModule.blockWebrtc() }.getOrNull() }
        val direct = async { BoxModule.directAddresses() }
        val ipv4 = async { Net.geoIp(false) }
        val ipv6 = async { Net.geoIp(true) }
        val dns = async { dnsResolvers() }
        val webRtc = async { webRtcAddresses(context.applicationContext) }

        val scope = when {
            state.await()?.running != true -> LeakScope.SERVICE_OFF
            excluded.await() -> LeakScope.BOXY_EXCLUDED
            else -> LeakScope.PROXIED
        }
        val v4 = ipv4.await()
        val v6 = ipv6.await()
        val realIps = direct.await()
        val realGeo = async { describe(realIps) }
        val dnsGeo = async { describe(dns.await()) }
        val rtcGeo = async { describe(webRtc.await()) }
        val real = realGeo.await()
        val dnsSeen = dnsGeo.await()
        val rtc = rtcGeo.await()
        val judged = scope == LeakScope.PROXIED && real.isNotEmpty()

        fun isReal(g: GeoIp) = g.ip in realIps || real.any { it.asn != null && it.asn == g.asn }
        fun verdict(seen: GeoIp?, missing: LeakVerdict): LeakVerdict = when {
            !judged -> LeakVerdict.UNKNOWN
            seen == null -> missing
            isReal(seen) -> LeakVerdict.LEAK
            else -> LeakVerdict.OK
        }
        val homeCountries = real.mapNotNull { it.countryCode }.toSet()
        val exitAbroad = v4?.countryCode != null && v4.countryCode !in homeCountries
        val dnsVerdict = when {
            !judged || dnsSeen.isEmpty() -> LeakVerdict.UNKNOWN
            dnsSeen.any(::isReal) -> LeakVerdict.LEAK
            exitAbroad && dnsSeen.any { it.countryCode in homeCountries } -> LeakVerdict.WARN
            else -> LeakVerdict.OK
        }
        val rtcVerdict = when {
            !judged -> LeakVerdict.UNKNOWN
            // The phone's own addresses, or anything from its own country, are a leak. A foreign address other than
            // the exit is most likely another node of the proxy (rule groups may send the IP check and the STUN
            // server through different nodes): worth a look, not an alarm.
            rtc.any { g -> isReal(g) || g.countryCode == null || g.countryCode in homeCountries } -> LeakVerdict.LEAK
            rtc.any { g -> listOfNotNull(v4, v6).none { it.ip == g.ip || (it.asn != null && it.asn == g.asn) } } -> LeakVerdict.WARN
            else -> LeakVerdict.OK
        }
        LeakResult(
            scope = scope,
            real = real,
            ipv4 = v4,
            ipv6 = v6,
            dns = dnsSeen,
            webRtc = rtc,
            ipv4Verdict = verdict(v4, LeakVerdict.UNKNOWN),
            ipv6Verdict = verdict(v6, LeakVerdict.OK),
            dnsVerdict = dnsVerdict,
            webRtcVerdict = rtcVerdict,
            networkMode = state.await()?.mode,
            moduleIpv6Off = state.await()?.ipv6 == false,
            webRtcBlockOff = rtcBlock.await() == false,
        ).also { last = it }
    }

    private suspend fun describe(ips: List<String>): List<GeoIp> = coroutineScope {
        ips.distinct().map { ip ->
            async { Net.geoIpOf(ip) ?: GeoIp(ip, null, null, null, null, null, null) }
        }.awaitAll()
    }

    /** Several unique names, because a phone may query more than one resolver. */
    private suspend fun dnsResolvers(): List<String> = coroutineScope {
        List(DNS_PROBES) { async { Net.dnsResolverSeen() } }.awaitAll().filterNotNull().distinct()
    }

    /** Public addresses the WebView's own WebRTC stack finds, the same ones a website could read. */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun webRtcAddresses(context: Context): List<String> = withContext(Dispatchers.Main) {
        val found = linkedSetOf<String>()
        val done = CompletableDeferred<Unit>()
        // The application context: a WebView created on an activity's context resets that activity's locale.
        val view = runCatching { WebView(context) }.getOrNull() ?: return@withContext emptyList()
        try {
            view.settings.javaScriptEnabled = true
            view.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    val text = m.message()
                    if (text == "done") {
                        done.complete(Unit)
                    } else if (text.startsWith("c:")) {
                        publicAddress(text.drop(2))?.let(found::add)
                    }
                    return true
                }
            }
            view.loadDataWithBaseURL("https://boxy.invalid/", WEBRTC_PAGE, "text/html", "utf-8", null)
            withTimeoutOrNull(WEBRTC_TIMEOUT_MS) { done.await() }
            found.toList()
        } finally {
            view.destroy()
        }
    }

    /** The address of an ICE candidate line when it is a public one; mDNS names and relays are not. */
    private fun publicAddress(candidate: String): String? {
        val parts = candidate.trim().split(' ')
        val ip = parts.getOrNull(4) ?: return null
        if (parts.getOrNull(7) == "relay" || !LITERAL.matches(ip)) return null
        val a = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return null
        val b = a.address
        val first = b[0].toInt() and 0xff
        val second = b[1].toInt() and 0xff
        val local = a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isAnyLocalAddress ||
            (b.size == 4 && first == 100 && second in 64..127) ||
            (b.size == 4 && first == 198 && second in 18..19) ||
            (b.size == 16 && first and 0xfe == 0xfc)
        return ip.takeUnless { local }
    }

    private val LITERAL = Regex("[0-9a-fA-F:.]+")

    private const val WEBRTC_PAGE = """<script>
const pc = new RTCPeerConnection({iceServers: [{urls: ['stun:stun.l.google.com:19302', 'stun:stun.cloudflare.com:3478']}]});
pc.createDataChannel('x');
pc.onicecandidate = e => console.log(e.candidate ? 'c:' + e.candidate.candidate : 'done');
pc.createOffer().then(o => pc.setLocalDescription(o)).catch(() => console.log('done'));
</script>"""
}

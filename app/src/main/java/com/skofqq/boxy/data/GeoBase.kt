package com.skofqq.boxy.data

import com.skofqq.boxy.configs.GeoSource
import com.skofqq.boxy.configs.GeoSources
import com.skofqq.boxy.root.BoxModule
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * The GeoSite / GeoIP databases on the device, read without network:
 *  - v2ray .dat (protobuf): GeoSiteList / GeoIPList, repeated entry = 1, each { country_code = 1; domain / cidr = 2 };
 *  - sing-box geosite.db (before 1.12): a header with the codes (names only);
 *  - mmdb (Country.mmdb) has no list of names: the ISO country codes are offered instead.
 * Besides the names for the editor's picker it extracts one category (sing-box rule sets) and merges several .dat
 * files into one with prefixed names (mihomo, which reads a single GeoSite.dat / GeoIP.dat).
 */
object GeoBase {
    enum class Kind { SITE, IP }

    /** A category as offered in the picker; [path] is the file its contents come from. */
    data class Category(val name: String, val kind: Kind, val source: GeoSource, val primary: Boolean, val path: String?)

    data class DomainSet(val full: List<String>, val suffix: List<String>, val keyword: List<String>, val regex: List<String>) {
        val isEmpty get() = full.isEmpty() && suffix.isEmpty() && keyword.isEmpty() && regex.isEmpty()
    }

    private val cache = HashMap<String, List<String>>()
    private const val BOX = BoxModule.BOX_DIR

    /** Paths of the files that hold [source]'s categories for [core] (geosite, geoip), the core's own files for the primary. */
    fun files(core: String, source: GeoSource, primary: Boolean, extras: Boolean): Pair<List<String>, List<String>> {
        val dir = "$BOX/$core"
        val pairSite = "$dir/geosite_${source.id}.dat"
        val pairIp = "$dir/geoip_${source.id}.dat"
        if (!primary) return listOf(pairSite) to listOf(pairIp)
        return when (core) {
            // With extra sources mihomo's GeoSite.dat is the merged file: the primary's own pair holds its names.
            "clash" -> if (extras) listOf(pairSite, "$dir/GeoSite.dat") to listOf(pairIp, "$dir/GeoIP.dat", "$dir/Country.mmdb")
            else listOf("$dir/GeoSite.dat", pairSite) to listOf("$dir/GeoIP.dat", "$dir/Country.mmdb", pairIp)
            "sing-box" -> listOf(pairSite, "$dir/geosite.db") to listOf(pairIp)
            else -> listOf("$dir/geosite.dat", pairSite) to listOf("$dir/geoip.dat", pairIp)
        }
    }

    /** All categories of the enabled sources for [core], primary first. */
    suspend fun load(core: String, primary: GeoSource, extras: List<GeoSource>): List<Category> = withContext(Dispatchers.IO) {
        val sources = listOf(primary) + extras.filter { it.id != primary.id }
        val wanted = sources.associateWith { files(core, it, it == primary, extras.isNotEmpty()) }
        val all = wanted.values.flatMap { it.first + it.second }.distinct()
        val stamps = Shell.cmd(all.joinToString("; ") { "[ -s '$it' ] && echo \"$it \$(stat -c '%s.%Y' '$it')\"" })
            .exec().out.associate { l -> l.substringBeforeLast(' ') to l.substringAfterLast(' ') }
        val out = ArrayList<Category>()
        for (src in sources) {
            val (sites, ips) = wanted.getValue(src)
            val isPrimary = src == primary
            sites.firstOrNull { it in stamps }?.let { p ->
                namesOf(p, stamps.getValue(p)).forEach { out.add(Category(it, Kind.SITE, src, isPrimary, p.takeIf { !it.endsWith(".db") })) }
            }
            val ip = ips.firstOrNull { it in stamps }
            when {
                ip == null -> Unit
                ip.endsWith(".mmdb") -> countryCodes().forEach { out.add(Category(it, Kind.IP, src, isPrimary, null)) }
                else -> namesOf(ip, stamps.getValue(ip)).forEach { out.add(Category(it, Kind.IP, src, isPrimary, ip)) }
            }
        }
        // Without any file of the primary source (sing-box before the first geo update) the names of another
        // core's .dat still let the user pick; the rule set is then built from that file.
        if (out.none { it.primary && it.kind == Kind.SITE }) {
            val other = listOf("clash/GeoSite.dat", "xray/geosite.dat", "v2fly/geosite.dat", "hysteria/geosite.dat").map { "$BOX/$it" }
            val found = Shell.cmd(other.joinToString("; ") { "[ -s '$it' ] && echo \"$it \$(stat -c '%s.%Y' '$it')\"" }).exec().out.firstOrNull()
            if (found != null) {
                val p = found.substringBeforeLast(' ')
                namesOf(p, found.substringAfterLast(' ')).forEach { out.add(0, Category(it, Kind.SITE, primary, true, p)) }
            }
        }
        out
    }

    private fun namesOf(path: String, stamp: String): List<String> = cached("$path@$stamp") {
        if (path.endsWith(".db")) readSingBoxDb(path) else rootStream(path) { parseNames(it) }
    }

    private fun cached(key: String, read: () -> List<String>): List<String> =
        synchronized(cache) { cache[key] } ?: runCatching(read).getOrDefault(emptyList()).also { list ->
            if (list.isNotEmpty()) synchronized(cache) { cache[key] = list }
        }

    /** ISO 3166 codes plus the private range, lower case as the cores write them. */
    fun countryCodes(): List<String> = (Locale.getISOCountries().map { it.lowercase(Locale.ROOT) } + "private").sorted()

    /** The file's bytes through a root process (binary-safe, unlike the line-based shell output). */
    fun <T> rootStream(path: String, read: (InputStream) -> T): T {
        val p = ProcessBuilder("su", "-c", "cat '${path.replace("'", "'\\''")}'").start()
        try {
            return BufferedInputStream(p.inputStream, 1 shl 16).use(read)
        } finally {
            p.destroy()
        }
    }

    // ---- protobuf primitives ----

    private fun InputStream.varint(): Long {
        var shift = 0
        var result = 0L
        while (shift < 64) {
            val b = read()
            if (b < 0) throw EOFException()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw IllegalStateException("bad varint")
    }

    private fun InputStream.bytes(n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(b, off, n - off)
            if (r < 0) throw EOFException()
            off += r
        }
        return b
    }

    private fun OutputStream.varint(v: Long) {
        var x = v
        while (x and 0x7FL.inv() != 0L) {
            write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
        write(x.toInt())
    }

    private fun skipField(input: InputStream, wire: Int) {
        when (wire) {
            0 -> input.varint()
            1 -> input.bytes(8)
            2 -> input.bytes(input.varint().toInt())
            5 -> input.bytes(4)
            else -> throw IllegalStateException("wire $wire")
        }
    }

    /** Calls [entry] with each top-level entry's bytes (GeoSite / GeoIP message). */
    private fun forEachEntry(input: InputStream, entry: (ByteArray) -> Boolean) {
        while (true) {
            val tag = try { input.varint() } catch (e: EOFException) { return }
            val wire = (tag and 7).toInt()
            if (tag ushr 3 != 1L || wire != 2) { skipField(input, wire); continue }
            val len = input.varint().toInt()
            if (!entry(input.bytes(len))) return
        }
    }

    /** country_code (field 1) of an entry. */
    private fun codeOf(entry: ByteArray): String? {
        val s = ByteArrayInputStream(entry)
        while (true) {
            val tag = try { s.varint() } catch (e: EOFException) { return null }
            val wire = (tag and 7).toInt()
            if (tag ushr 3 == 1L && wire == 2) return String(s.bytes(s.varint().toInt()), Charsets.UTF_8)
            skipField(s, wire)
        }
    }

    fun parseNames(input: InputStream): List<String> {
        val out = ArrayList<String>()
        forEachEntry(input) { e -> codeOf(e)?.let { out.add(it.lowercase(Locale.ROOT)) }; true }
        return out.distinct().sorted()
    }

    private fun findEntry(path: String, code: String): ByteArray? = rootStream(path) { input ->
        var found: ByteArray? = null
        forEachEntry(input) { e ->
            if (codeOf(e)?.equals(code, ignoreCase = true) == true) { found = e; false } else true
        }
        found
    }

    /** Domains of a GeoSite category: Domain { type = 1 (0 keyword, 1 regex, 2 domain/suffix, 3 full); value = 2 }. */
    fun extractSite(path: String, code: String): DomainSet? {
        val e = findEntry(path, code) ?: return null
        val full = ArrayList<String>(); val suffix = ArrayList<String>(); val keyword = ArrayList<String>(); val regex = ArrayList<String>()
        val s = ByteArrayInputStream(e)
        while (true) {
            val tag = try { s.varint() } catch (x: EOFException) { break }
            val wire = (tag and 7).toInt()
            if (tag ushr 3 != 2L || wire != 2) { skipField(s, wire); continue }
            val d = ByteArrayInputStream(s.bytes(s.varint().toInt()))
            var type = 0L
            var value: String? = null
            while (true) {
                val t = try { d.varint() } catch (x: EOFException) { break }
                val w = (t and 7).toInt()
                when {
                    t ushr 3 == 1L && w == 0 -> type = d.varint()
                    t ushr 3 == 2L && w == 2 -> value = String(d.bytes(d.varint().toInt()), Charsets.UTF_8)
                    else -> skipField(d, w)
                }
            }
            val v = value ?: continue
            when (type) {
                0L -> keyword.add(v)
                1L -> regex.add(v)
                2L -> suffix.add(v)
                else -> full.add(v)
            }
        }
        return DomainSet(full.distinct(), suffix.distinct(), keyword.distinct(), regex.distinct())
    }

    /** CIDRs of a GeoIP category: CIDR { ip = 1 (4 or 16 bytes); prefix = 2 }. */
    fun extractIp(path: String, code: String): List<String>? {
        val e = findEntry(path, code) ?: return null
        val out = ArrayList<String>()
        val s = ByteArrayInputStream(e)
        while (true) {
            val tag = try { s.varint() } catch (x: EOFException) { break }
            val wire = (tag and 7).toInt()
            if (tag ushr 3 != 2L || wire != 2) { skipField(s, wire); continue }
            val c = ByteArrayInputStream(s.bytes(s.varint().toInt()))
            var ip: ByteArray? = null
            var prefix = 0L
            while (true) {
                val t = try { c.varint() } catch (x: EOFException) { break }
                val w = (t and 7).toInt()
                when {
                    t ushr 3 == 1L && w == 2 -> ip = c.bytes(c.varint().toInt())
                    t ushr 3 == 2L && w == 0 -> prefix = c.varint()
                    else -> skipField(c, w)
                }
            }
            val a = ip ?: continue
            if (a.size != 4 && a.size != 16) continue
            out.add((java.net.InetAddress.getByAddress(a).hostAddress ?: continue) + "/" + prefix)
        }
        return out
    }

    /**
     * Writes one .dat holding every entry of [inputs] (path to name prefix, null keeps the names). Repeated
     * protobuf fields concatenate, so only renamed entries are re-encoded. Missing inputs are skipped.
     * Returns the number of entries written.
     */
    fun merge(inputs: List<Pair<String, String?>>, out: OutputStream): Int {
        var n = 0
        val seen = HashSet<String>()
        for ((path, prefix) in inputs) {
            runCatching {
                rootStream(path) { input ->
                    forEachEntry(input) { e ->
                        val code = codeOf(e) ?: return@forEachEntry true
                        val name = if (prefix == null) code else "$prefix-$code"
                        if (!seen.add(name.lowercase(Locale.ROOT))) return@forEachEntry true
                        val body = if (prefix == null) e else rename(e, name)
                        out.write(0x0A)
                        out.varint(body.size.toLong())
                        out.write(body)
                        n++
                        true
                    }
                }
            }
        }
        return n
    }

    /** The entry with field 1 replaced by [name], other fields as they were. */
    private fun rename(entry: ByteArray, name: String): ByteArray {
        val rest = ByteArrayOutputStream(entry.size + 16)
        val s = ByteArrayInputStream(entry)
        while (true) {
            val start = entry.size - s.available()
            val tag = try { s.varint() } catch (x: EOFException) { break }
            val wire = (tag and 7).toInt()
            if (tag ushr 3 == 1L && wire == 2) { s.bytes(s.varint().toInt()); continue }
            skipField(s, wire)
            val end = entry.size - s.available()
            rest.write(entry, start, end - start)
        }
        val nb = name.toByteArray(Charsets.UTF_8)
        val outB = ByteArrayOutputStream(rest.size() + nb.size + 8)
        outB.write(0x0A)
        outB.varint(nb.size.toLong())
        outB.write(nb)
        rest.writeTo(outB)
        return outB.toByteArray()
    }

    /** sing-box geosite.db: version byte, count, then (code, index, length) per entry. */
    private fun readSingBoxDb(path: String): List<String> = rootStream(path) { input ->
        if (input.read() != 0) return@rootStream emptyList<String>()
        val n = input.varint().toInt().coerceAtMost(100_000)
        val out = ArrayList<String>(n)
        repeat(n) {
            val len = input.varint().toInt()
            out.add(String(input.bytes(len), Charsets.UTF_8))
            input.varint()
            input.varint()
        }
        out.distinct().sorted()
    }

    fun sourceOf(id: String?): GeoSource = GeoSources.byId(id)
}

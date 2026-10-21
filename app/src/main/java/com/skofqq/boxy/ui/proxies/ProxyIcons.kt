package com.skofqq.boxy.ui.proxies

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Proxy group icons from the config's `icon:` URLs (SVG, PNG, JPEG, WebP). Downloaded once into the app's
 * cache folder, so later visits cost no network; kept in memory while the app runs.
 */
object ProxyIcons {
    private const val SIZE = 128
    private val memory = LruCache<String, ImageBitmap>(64)

    suspend fun load(context: Context, url: String): ImageBitmap? = memory.get(url) ?: withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "proxy-icons").apply { mkdirs() }
        val file = File(dir, sha1(url))
        val bytes = if (file.isFile) file.readBytes() else download(url)?.also { runCatching { file.writeBytes(it) } }
        bytes?.let { decode(it) }?.asImageBitmap()?.also { memory.put(url, it) }
    }

    private fun download(url: String): ByteArray? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Boxy")
        try {
            if (c.responseCode !in 200..299) null else c.inputStream.use { it.readBytes() }.takeIf { it.size < 2_000_000 }
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private fun decode(bytes: ByteArray): Bitmap? = runCatching {
        val head = String(bytes, 0, minOf(bytes.size, 512)).trimStart()
        if (head.startsWith("<") && head.contains("<svg", ignoreCase = true)) {
            val svg = SVG.getFromString(String(bytes))
            svg.documentWidth = SIZE.toFloat()
            svg.documentHeight = SIZE.toFloat()
            Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also { svg.renderToCanvas(Canvas(it)) }
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }.getOrNull()

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

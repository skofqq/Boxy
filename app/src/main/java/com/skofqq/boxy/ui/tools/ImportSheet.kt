package com.skofqq.boxy.ui.tools

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.skofqq.boxy.R
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.CheckResult
import com.skofqq.boxy.root.ConfigCheck
import com.skofqq.boxy.root.RootFiles
import com.skofqq.boxy.ui.components.BoxySheet
import com.skofqq.boxy.ui.components.BoxyTextField
import com.skofqq.boxy.ui.components.ConfigErrorDialog
import com.skofqq.boxy.ui.components.SheetButtons
import com.skofqq.boxy.ui.components.SheetGroup
import com.skofqq.boxy.ui.components.SwitchRow
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/** A subscription to import: from a clash:// / sing-box:// link, a shared text, a QR code or typed in. */
data class ImportRequest(val url: String, val name: String? = null, val core: String? = null)

/** Pending import, set by MainActivity (deep links, shares) and the QR scanner; shown by the main screen. */
object ImportBus {
    var request by mutableStateOf<ImportRequest?>(null)
}

object ImportLinks {
    /** clash://install-config?url=…&name=…, clashmeta://…, sing-box://import-remote-profile?url=…#name, or a plain http(s) URL. */
    fun parse(text: String?): ImportRequest? {
        val raw = text?.trim() ?: return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        when (uri?.scheme?.lowercase()) {
            "clash", "clashmeta", "clashx" -> {
                val url = uri.getQueryParameter("url") ?: return null
                return ImportRequest(url, uri.getQueryParameter("name"), "clash")
            }
            "sing-box", "singbox" -> {
                val url = uri.getQueryParameter("url") ?: return null
                return ImportRequest(url, uri.fragment?.let { decode(it) } ?: uri.getQueryParameter("name"), "sing-box")
            }
            // A Hysteria 2 server link (pasted or from a QR code) becomes a hysteria profile.
            "hysteria2", "hy2" -> return ImportRequest(raw.lineSequence().first().trim(), null, "hysteria")
        }
        // Shared text or QR content: the first http(s) link in it.
        val link = Regex("https?://\\S+").find(raw)?.value ?: return null
        return ImportRequest(link.trimEnd('.', ',', ')', ']', '"', '\''))
    }

    fun fromIntent(intent: Intent?): ImportRequest? = when (intent?.action) {
        Intent.ACTION_VIEW -> parse(intent.dataString)
        Intent.ACTION_SEND -> parse(intent.getStringExtra(Intent.EXTRA_TEXT))
        else -> null
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /** Reads a QR code from a picture (screenshot of a subscription page, saved QR image). */
    fun decodeQr(context: Context, uri: Uri): String? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        var bmp = ImageDecoder.decodeBitmap(source) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val max = maxOf(info.size.width, info.size.height)
            if (max > 1600) d.setTargetSampleSize((max + 1599) / 1600)
        }
        if (bmp.config != Bitmap.Config.ARGB_8888) bmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bmp.width, bmp.height, px)))
        MultiFormatReader().decode(
            bitmap,
            mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)),
        ).text
    }.getOrNull()
}

/** Launchers for scanning a QR code with the camera or from a picture; the result goes to [ImportBus]. */
class QrLaunchers(val camera: () -> Unit, val picture: () -> Unit)

@Composable
fun rememberQrLaunchers(): QrLaunchers {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notFound = stringResource(R.string.import_qr_not_found)
    val handle = { text: String? ->
        val r = ImportLinks.parse(text)
        if (r != null) ImportBus.request = r else Toast.makeText(context, notFound, Toast.LENGTH_SHORT).show()
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) handle(result.contents)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { handle(withContext(Dispatchers.Default) { ImportLinks.decodeQr(context, uri) }) }
    }
    val prompt = stringResource(R.string.import_qr_prompt)
    return remember(scanner, picker) {
        QrLaunchers(
            camera = {
                scanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt(prompt)
                        .setBeepEnabled(false)
                        .setOrientationLocked(false),
                )
            },
            picture = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        )
    }
}

/** Download the subscription as a config file (checked by the core) or add it to the module's subscription list. */
@Composable
fun ImportSheet(request: ImportRequest, onDismiss: () -> Unit) {
    if (request.core == "hysteria" && com.skofqq.boxy.configs.HysteriaConfig.parseLink(request.url) != null) {
        return HysteriaImportSheet(request.url, onDismiss)
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var core by remember { mutableStateOf(request.core) }
    LaunchedEffect(Unit) { if (core == null) core = BoxModule.readSetting("bin_name") ?: "clash" }
    val c = core ?: "clash"
    val ext = if (c == "sing-box" || c == "xray" || c == "v2fly") "json" else "yaml"
    var url by remember { mutableStateOf(request.url) }
    var name by remember(c) { mutableStateOf(fileName(request, ext)) }
    var activate by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var checkError by remember { mutableStateOf<Pair<String, String>?>(null) }
    val validUrl = url.startsWith("http://") || url.startsWith("https://")
    val dir = "${BoxModule.BOX_DIR}/${coreConfig(c).first}"

    suspend fun makeActive(file: String) {
        if (BoxModule.writeSetting(coreConfig(c).second, file)) {
            Toast.makeText(context, context.getString(R.string.config_selected, file), Toast.LENGTH_SHORT).show()
        }
    }

    BoxySheet(stringResource(R.string.import_title), stringResource(R.string.import_subtitle, c), onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            BoxyTextField(url, { url = it.trim() }, stringResource(R.string.config_url_example), Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            BoxyTextField(name, { name = it }, stringResource(R.string.config_placeholder_name), Modifier.fillMaxWidth())
            if (!validUrl && url.isNotEmpty()) {
                Text(stringResource(R.string.config_url_invalid), Modifier.padding(top = 6.dp), color = Tints.red.fg, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(12.dp))
        SheetGroup {
            SwitchRow(BoxyIcons.Check, stringResource(R.string.import_activate), stringResource(R.string.import_activate_sub), activate, showDivider = false) { activate = it }
        }
        if (busy) {
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.height(22.dp).width(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.import_downloading), color = Boxy.colors.text2)
            }
        }
        SheetButtons(
            stringResource(R.string.import_download),
            {
                if (!validUrl || busy) return@SheetButtons
                val file = sanitize(name).ifBlank { "subscription.$ext" }
                busy = true
                scope.launch {
                    val text = download(url, c)
                    val saved = text != null && RootFiles.write("$dir/$file", text)
                    if (!saved) {
                        busy = false
                        Toast.makeText(context, R.string.config_download_failed, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    Toast.makeText(context, context.getString(R.string.config_download_ok, file), Toast.LENGTH_SHORT).show()
                    if (!activate) {
                        busy = false
                        onDismiss()
                        return@launch
                    }
                    val r = ConfigCheck.check(c, "$dir/$file")
                    busy = false
                    if (r is CheckResult.Failed) {
                        checkError = file to r.output
                    } else {
                        makeActive(file)
                        onDismiss()
                    }
                }
            },
            if (c == "clash") stringResource(R.string.import_add_to_subs) else null,
            {
                if (!validUrl || busy) return@SheetButtons
                scope.launch {
                    val ok = addToSubscriptions(url, sanitize(name).ifBlank { "subscription.yaml" })
                    Toast.makeText(context, if (ok) R.string.import_added else R.string.op_failed, Toast.LENGTH_SHORT).show()
                    if (ok) onDismiss()
                }
            },
        )
    }

    checkError?.let { (file, output) ->
        ConfigErrorDialog(
            output,
            stringResource(R.string.check_select_anyway),
            onProceed = {
                checkError = null
                scope.launch { makeActive(file); onDismiss() }
            },
            onDismiss = { checkError = null; onDismiss() },
        )
    }
}

private fun fileName(r: ImportRequest, ext: String): String {
    val base = r.name?.takeIf { it.isNotBlank() }
        ?: runCatching { Uri.parse(r.url).host }.getOrNull()?.removePrefix("www.")
        ?: "subscription"
    val clean = sanitize(base)
    return if (clean.substringAfterLast('.', "").lowercase() in setOf("yaml", "yml", "json")) clean else "$clean.$ext"
}

private fun sanitize(s: String) = s.trim().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")

private suspend fun download(url: String, core: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        // Many panels return a full config only for a known client User-Agent.
        c.setRequestProperty("User-Agent", if (core == "sing-box") "sing-box" else "clash.meta")
        try {
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null
        } finally {
            c.disconnect()
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

/** Appends the URL and file name to subscription_url_clash / name_provide_clash_config in settings.ini. */
private suspend fun addToSubscriptions(url: String, name: String): Boolean {
    val raw = BoxModule.readSettingsRaw(listOf("subscription_url_clash", "name_provide_clash_config"))
    val urls = BoxModule.parseArray(raw["subscription_url_clash"]).filter { it.isNotBlank() }
    val names = BoxModule.parseArray(raw["name_provide_clash_config"]).filter { it.isNotBlank() }
    if (url in urls) return true
    return BoxModule.writeSettingRaw("subscription_url_clash", BoxModule.toArray(urls + url)) &&
        BoxModule.writeSettingRaw("name_provide_clash_config", BoxModule.toArray(names + name))
}

package com.skofqq.boxy.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.R
import com.skofqq.boxy.data.Prefs
import com.skofqq.boxy.net.Mirrors
import com.skofqq.boxy.net.Updates
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.ui.components.BoxySheet
import com.skofqq.boxy.ui.components.SheetButtons
import com.skofqq.boxy.ui.components.SheetGroup
import com.skofqq.boxy.ui.theme.Boxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** What changed in one updated part: "Boxy 0.8.2" or "Box for Root v1.10.10", with its notes (Markdown). */
data class ChangeEntry(
    val title: String,
    val notes: String,
    /** The version this entry shows, recorded as seen only when the sheet is closed (see [WhatsNew.markSeen]). */
    val appCode: Long? = null,
    val moduleVersion: String? = null,
)

/**
 * "What's new" after Boxy or the module was updated. It runs once when the app opens: the installed versions
 * are compared with the ones last shown, and only on a change are the notes fetched (one request each).
 */
object WhatsNew {
    private const val MODULE_CHANGELOG = "https://raw.githubusercontent.com/${Updates.REPO}/main/module/CHANGELOG.md"

    suspend fun pending(context: Context, prefs: Prefs): List<ChangeEntry> {
        val out = ArrayList<ChangeEntry>()
        val russian = isCyrillicLocale(context)
        appEntry(context, prefs, russian)?.let { out += it }
        moduleEntry(prefs, russian)?.let { out += it }
        return out
    }

    private suspend fun appEntry(context: Context, prefs: Prefs, russian: Boolean): ChangeEntry? {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull() ?: return null
        val code = info.longVersionCode
        val name = info.versionName.orEmpty()
        val seen = prefs.seenAppVersion
        // Nothing recorded yet: a fresh install shows nothing, an update from a build without this feature does.
        val updated = if (seen == 0L) info.lastUpdateTime > info.firstInstallTime else code > seen
        if (!updated || !prefs.showChangelog) {
            prefs.seenAppVersion = code
            return null
        }
        val body = get("https://api.github.com/repos/${Updates.REPO}/releases/tags/v$name")
            ?.let { runCatching { JSONObject(it).optString("body") }.getOrNull() }
            ?: return null // Offline: try again next time the app opens.
        val notes = languageSection(body, russian).trim()
        if (notes.isEmpty()) prefs.seenAppVersion = code
        return notes.takeIf { it.isNotEmpty() }?.let { ChangeEntry("Boxy $name", it, appCode = code) }
    }

    private suspend fun moduleEntry(prefs: Prefs, russian: Boolean): ChangeEntry? {
        val current = runCatching { BoxModule.state().moduleVersion }.getOrNull()?.trim() ?: return null
        val seen = prefs.seenModuleVersion
        if (seen.isEmpty() || seen == current || !Updates.isNewer(current.removePrefix("v"), seen.removePrefix("v")) || !prefs.showChangelog) {
            prefs.seenModuleVersion = current
            return null
        }
        val text = get(Mirrors.wrap(MODULE_CHANGELOG)) ?: get(MODULE_CHANGELOG) ?: return null
        val notes = moduleSections(text, seen, current, russian).trim()
        if (notes.isEmpty()) prefs.seenModuleVersion = current
        return notes.takeIf { it.isNotEmpty() }?.let { ChangeEntry("Box for Root $current", it, moduleVersion = current) }
    }

    /**
     * Records the shown versions once the sheet is closed by the user. Marking them when the notes were fetched lost
     * them when the window went away by itself (the system restarts Boxy right after an in-app update).
     */
    fun markSeen(prefs: Prefs, entries: List<ChangeEntry>) {
        entries.forEach { e ->
            e.appCode?.let { prefs.seenAppVersion = it }
            e.moduleVersion?.let { prefs.seenModuleVersion = it }
        }
    }

    /** Release notes hold "## English" and "## Русский": the one for the app's language, else all of it. */
    internal fun languageSection(body: String, russian: Boolean): String {
        val lines = body.replace("\r", "").lines()
        val heads = lines.withIndex().filter { it.value.startsWith("## ") }
        val pick = heads.firstOrNull { h ->
            val t = h.value.lowercase()
            if (russian) "русск" in t else "english" in t
        } ?: return body
        val end = heads.firstOrNull { it.index > pick.index }?.index ?: lines.size
        return lines.subList(pick.index + 1, end).joinToString("\n")
    }

    /**
     * The module's CHANGELOG sections newer than [seen] up to [current]. Each entry is written in English and then
     * in Russian; paragraphs in the other language are left out.
     */
    internal fun moduleSections(text: String, seen: String, current: String, russian: Boolean): String {
        val out = StringBuilder()
        var take = false
        for (block in text.replace("\r", "").split(Regex("\n(?=## )"))) {
            val head = block.lineSequence().first()
            if (!head.startsWith("## ")) continue
            val v = head.removePrefix("## ").trim().removePrefix("v")
            take = !Updates.isNewer(v, current.removePrefix("v")) && Updates.isNewer(v, seen.removePrefix("v"))
            if (!take) continue
            out.append("### ").append(head.removePrefix("## ").trim()).append("\n\n")
            block.substringAfter('\n').split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { para ->
                val cyr = para.count { it in 'Ѐ'..'ӿ' } > para.count { it in 'a'..'z' || it in 'A'..'Z' } / 3
                if (cyr == russian) out.append(para).append("\n\n")
            }
        }
        return out.toString()
    }

    private fun isCyrillicLocale(context: Context): Boolean =
        context.resources.configuration.locales[0].language in setOf("ru", "uk", "be", "kk")

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "Boxy")
            try {
                if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }
}

@Composable
fun WhatsNewSheet(entries: List<ChangeEntry>, onDismiss: () -> Unit) {
    BoxySheet(stringResource(R.string.whats_new_title), stringResource(R.string.whats_new_sub), onDismiss) {
        entries.forEach { e ->
            SheetGroup(Modifier.padding(bottom = 10.dp)) {
                Text(
                    e.title,
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = Boxy.colors.text,
                )
                MarkdownText(e.notes, Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp))
            }
        }
        SheetButtons(stringResource(R.string.action_done), onDismiss)
    }
}

/** Just enough Markdown for release notes: headings, bullet lists, **bold** and `code`. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        text.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(6.dp))
                line.startsWith("#") -> Text(
                    inline(line.trimStart('#').trim()),
                    Modifier.padding(top = 4.dp, bottom = 2.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Boxy.colors.text,
                )
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> Row(Modifier.padding(vertical = 2.dp)) {
                    Text("•", style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
                    Spacer(Modifier.width(8.dp))
                    Text(inline(line.trimStart().substring(2)), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
                }
                else -> Text(inline(line.trim()), Modifier.padding(vertical = 1.dp), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
            }
        }
    }
}

private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) && s.indexOf("**", i + 2) > 0 -> {
                val end = s.indexOf("**", i + 2)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.substring(i + 2, end)) }
                i = end + 2
            }
            s[i] == '`' && s.indexOf('`', i + 1) > 0 -> {
                val end = s.indexOf('`', i + 1)
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(s.substring(i + 1, end)) }
                i = end + 1
            }
            else -> {
                append(s[i])
                i++
            }
        }
    }
}

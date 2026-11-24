package com.skofqq.boxy.ui.tools

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.R
import com.skofqq.boxy.net.CoreUpdate
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.RootTask
import com.skofqq.boxy.ui.components.DialogButton
import com.skofqq.boxy.ui.components.GlassAlertDialog
import com.skofqq.boxy.ui.settings.MarkdownText
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import kotlinx.coroutines.launch

private enum class Phase { OFFER, RUNNING, DONE, FAILED, KEPT_OLD }

/**
 * Offer of a newer release of the active core when the app opens: installed and new version, the release's
 * changelog, and "Update", which runs box.tool upkernel right here (the module restarts a running core itself).
 * [onLater] closes the offer for this version; a later release is offered again.
 */
@Composable
fun CoreUpdateDialog(update: CoreUpdate, onLater: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(Phase.OFFER) }
    val log = remember { mutableStateListOf<String>() }
    // Download progress from curl's progress bar ("#####   42.0%"), -1 until it shows one.
    var percent by remember { mutableStateOf(-1f) }
    var expanded by remember { mutableStateOf(false) }

    fun runUpdate() {
        phase = Phase.RUNNING
        log.clear()
        scope.launch {
            val cmd = BoxModule.toolCommand("upkernel", update.binName, update.xclash)
            percent = -1f
            val ok = RootTask.run(cmd) { line ->
                PERCENT.findAll(line).lastOrNull()?.groupValues?.get(1)?.toFloatOrNull()?.let { percent = (it / 100f).coerceIn(0f, 1f) }
                // Only the module's own log lines ("[Info]: ...") are shown; curl's bar would be noise.
                if ("]:" in line) log.add(line.trim())
            }
            phase = when {
                log.any { "KERNEL_CHECK_FAILED" in it } -> Phase.KEPT_OLD
                ok && log.none { "[Error]" in it } -> Phase.DONE
                else -> Phase.FAILED
            }
        }
    }

    GlassAlertDialog(
        // While the core downloads, a tap outside must not lose the progress.
        onDismissRequest = { if (phase != Phase.RUNNING) (if (phase == Phase.OFFER) onLater else onClose)() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Boxy.colors.accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(BoxyIcons.SystemUpdate, null, Modifier.size(26.dp), tint = Boxy.colors.accent)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(stringResource(R.string.core_update_title), style = MaterialTheme.typography.headlineSmall, color = Boxy.colors.text)
                    Text(
                        listOf(update.title, update.date).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Boxy.colors.text2,
                    )
                }
            }
        },
        text = {
            Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                VersionsRow(update.installed, update.latest)
                when (phase) {
                    Phase.OFFER -> Changelog(update, expanded) { expanded = !expanded }
                    Phase.RUNNING -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (percent >= 0f) {
                            androidx.compose.material3.LinearWavyProgressIndicator(progress = { percent }, modifier = Modifier.fillMaxWidth(), color = Boxy.colors.accent)
                        } else {
                            androidx.compose.material3.LinearWavyProgressIndicator(Modifier.fillMaxWidth(), color = Boxy.colors.accent)
                        }
                        Text(
                            log.lastOrNull()?.substringAfter("]:")?.trim() ?: stringResource(R.string.core_update_running),
                            style = MaterialTheme.typography.bodySmall,
                            color = Boxy.colors.text2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Phase.DONE -> Result(stringResource(R.string.core_update_done, update.title, update.latest), Tints.green.fg, Tints.green.bg)
                    Phase.KEPT_OLD -> Result(stringResource(R.string.update_core_kept_old), Tints.amber.fg, Tints.amber.bg)
                    Phase.FAILED -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Result(stringResource(R.string.core_update_failed), Tints.red.fg, Tints.red.bg)
                        log.lastOrNull { "[Error]" in it }?.let {
                            Text(it.substringAfter("]:").trim(), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (phase) {
                    Phase.OFFER -> {
                        DialogButton(stringResource(R.string.core_update_action), Boxy.colors.accent, Color.White) { runUpdate() }
                        DialogButton(stringResource(R.string.core_update_later), Boxy.colors.surface2, Boxy.colors.text, onLater)
                    }
                    Phase.RUNNING -> Unit
                    Phase.FAILED -> {
                        DialogButton(stringResource(R.string.action_retry), Boxy.colors.accent, Color.White) { runUpdate() }
                        DialogButton(stringResource(R.string.core_update_later), Boxy.colors.surface2, Boxy.colors.text, onClose)
                    }
                    Phase.DONE, Phase.KEPT_OLD -> DialogButton(stringResource(R.string.action_done), Boxy.colors.accent, Color.White, onClose)
                }
                if (phase == Phase.OFFER && update.pageUrl != null) {
                    Text(
                        stringResource(R.string.core_update_github),
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.pageUrl))) } }
                            .padding(vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = Boxy.colors.accent,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        },
        containerColor = Boxy.colors.card,
    )
}

/** Installed → new version, as two tonal pills. */
@Composable
private fun VersionsRow(installed: String, latest: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        VersionPill(stringResource(R.string.core_update_installed), installed, Boxy.colors.surface2, Boxy.colors.text, Modifier.weight(1f))
        Icon(BoxyIcons.ChevronRight, null, Modifier.padding(horizontal = 8.dp).size(20.dp), tint = Boxy.colors.text2)
        VersionPill(stringResource(R.string.core_update_new), latest, Tints.green.bg, Tints.green.fg, Modifier.weight(1f))
    }
}

@Composable
private fun VersionPill(label: String, version: String, bg: Color, fg: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(bg).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = 0.75f), maxLines = 1)
        Text(version, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
    }
}

/** The release notes, cut to a few lines until expanded; long notes scroll inside their box. */
@Composable
private fun Changelog(update: CoreUpdate, expanded: Boolean, onToggle: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.core_update_changelog), style = MaterialTheme.typography.titleSmall, color = Boxy.colors.text)
        if (update.notes.isBlank()) {
            Text(stringResource(R.string.core_update_no_notes), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
            return@Column
        }
        // Markdown links as their text: the URLs only make the lines longer here.
        val notes = remember(update.notes) { update.notes.replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1") }
        val lines = notes.lines()
        val long = lines.size > COLLAPSED_LINES
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Boxy.colors.surface2)
                .heightIn(max = if (expanded) 360.dp else 220.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            MarkdownText(if (expanded || !long) notes.take(MAX_NOTES) else lines.take(COLLAPSED_LINES).joinToString("\n"))
        }
        if (long) {
            Text(
                stringResource(if (expanded) R.string.core_update_collapse else R.string.core_update_show_all),
                Modifier.clip(CircleShape).clickable(onClick = onToggle).padding(horizontal = 4.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                color = Boxy.colors.accent,
            )
        }
    }
}

@Composable
private fun Result(text: String, fg: Color, bg: Color) {
    Text(
        text,
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(bg).padding(14.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = fg,
    )
}

private val PERCENT = Regex("""(\d{1,3}(?:\.\d)?)%""")
private const val COLLAPSED_LINES = 10
private const val MAX_NOTES = 8000

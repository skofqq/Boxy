package com.skofqq.boxy.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.ui.components.InputDialog
import com.skofqq.boxy.ui.components.HeaderAction
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import com.skofqq.boxy.BoxyApp
import com.skofqq.boxy.R
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.data.Backup
import com.skofqq.boxy.data.BackupScope
import com.skofqq.boxy.data.DEFAULT_LATENCY_TARGETS
import com.skofqq.boxy.data.LatencyTarget
import com.skofqq.boxy.data.Prefs
import com.skofqq.boxy.data.ThemeMode
import com.skofqq.boxy.net.AppRelease
import com.skofqq.boxy.net.ModuleUpdate
import com.skofqq.boxy.net.Updates
import com.skofqq.boxy.ui.components.Badge
import com.skofqq.boxy.ui.components.BoxySheet
import com.skofqq.boxy.ui.components.BoxyTextField
import com.skofqq.boxy.ui.components.ConfirmDialog
import com.skofqq.boxy.ui.components.InfoRow
import com.skofqq.boxy.ui.components.SectionCard
import com.skofqq.boxy.ui.components.SettingsRow
import com.skofqq.boxy.ui.components.SheetButtons
import com.skofqq.boxy.ui.components.SheetGroup
import com.skofqq.boxy.ui.components.SubPageHeader
import com.skofqq.boxy.ui.components.SwitchRow
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SystemBarsSheet(prefs: Prefs, onDismiss: () -> Unit) {
    BoxySheet(stringResource(R.string.settings_system_bars), stringResource(R.string.settings_bars_opaque_sub), onDismiss) {
        SheetGroup {
            SwitchRow(BoxyIcons.Web, stringResource(R.string.settings_bars_opaque_status), null, prefs.opaqueStatusBar) { prefs.updateOpaqueStatusBar(it) }
            SwitchRow(BoxyIcons.Web, stringResource(R.string.settings_bars_opaque_nav), null, prefs.opaqueNavBar, showDivider = false) { prefs.updateOpaqueNavBar(it) }
        }
        SheetButtons(stringResource(R.string.action_done), onDismiss)
    }
}

/** Blur, liquid glass, UI scale and true black. */
@Composable
fun AppearanceScreen(contentPadding: PaddingValues, prefs: Prefs, onBack: () -> Unit) {
    val blurSupported = Build.VERSION.SDK_INT >= 31
    PinnedLazyPage(contentPadding, header = {
SubPageHeader(stringResource(R.string.settings_appearance_more), stringResource(R.string.settings_appearance_more_sub), onBack)
}, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionCard(stringResource(R.string.settings_glass), stringResource(R.string.settings_glass_sub)) {
                SwitchRow(
                    BoxyIcons.BlurOn,
                    stringResource(R.string.settings_blur_effects),
                    stringResource(if (blurSupported) R.string.settings_blur_effects_sub else R.string.settings_blur_unsupported),
                    prefs.blurEffects && blurSupported,
                    enabled = blurSupported,
                ) { prefs.updateBlurEffects(it) }
                SwitchRow(
                    BoxyIcons.Glass,
                    stringResource(R.string.settings_glass_elements),
                    stringResource(R.string.settings_glass_elements_sub),
                    prefs.glassElements && prefs.blurEffects && blurSupported,
                    enabled = blurSupported && prefs.blurEffects,
                ) { prefs.updateGlassElements(it) }
                SwitchRow(
                    BoxyIcons.BottomSheet,
                    stringResource(R.string.settings_sheet_blur),
                    stringResource(R.string.settings_sheet_blur_sub),
                    prefs.sheetBlur && blurSupported,
                    enabled = blurSupported && prefs.blurEffects,
                ) { prefs.updateSheetBlur(it) }
                SwitchRow(
                    BoxyIcons.Opacity,
                    stringResource(R.string.settings_glass_translucent),
                    stringResource(R.string.settings_glass_translucent_sub),
                    prefs.glassTranslucent,
                    enabled = blurSupported && prefs.blurEffects,
                    showDivider = false,
                ) { prefs.updateGlassTranslucent(it) }
                SliderRow(
                    stringResource(R.string.settings_blur_strength),
                    stringResource(R.string.settings_blur_strength_sub),
                    prefs.blurStrength,
                    enabled = blurSupported && prefs.blurEffects,
                    steps = 9,
                    percent = true,
                ) { prefs.updateBlurStrength(Math.round(it * 10) / 10f) }
                SliderRow(
                    stringResource(R.string.settings_lens_strength),
                    stringResource(R.string.settings_lens_strength_sub),
                    prefs.lensStrength,
                    enabled = Build.VERSION.SDK_INT >= 33 && prefs.blurEffects,
                    steps = 9,
                    percent = true,
                ) { prefs.updateLensStrength(Math.round(it * 10) / 10f) }
            }
        }
        item {
            SectionCard(stringResource(R.string.settings_display), null) {
                SliderRow(
                    stringResource(R.string.settings_ui_scale, prefs.uiScale),
                    stringResource(R.string.settings_ui_scale_sub),
                    (prefs.uiScale - 80) / 40f,
                    steps = 7,
                ) { prefs.updateUiScale(80 + Math.round(it * 40 / 5f) * 5) }
                SwitchRow(
                    BoxyIcons.Moon,
                    stringResource(R.string.settings_true_black),
                    stringResource(R.string.settings_true_black_sub),
                    checked = prefs.trueBlack,
                    enabled = prefs.themeMode != ThemeMode.LIGHT,
                    showDivider = false,
                ) { prefs.updateTrueBlack(it) }
            }
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    subtitle: String,
    value: Float,
    enabled: Boolean = true,
    steps: Int = 0,
    percent: Boolean = false,
    onChange: (Float) -> Unit,
) {
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
        Text(if (percent) "$title · ${Math.round(local * 100)}%" else title, style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text.copy(alpha = if (enabled) 1f else 0.45f))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2.copy(alpha = if (enabled) 1f else 0.45f))
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            enabled = enabled,
            steps = steps,
            colors = SliderDefaults.colors(thumbColor = Boxy.colors.accent, activeTrackColor = Boxy.colors.accent),
        )
    }
}

/** Services offered for the latency test (small, fast answers where possible). */
private val LATENCY_PRESETS = listOf(
    LatencyTarget("Google", "https://google.com"),
    LatencyTarget("Cloudflare", "https://cloudflare.com"),
    LatencyTarget("YouTube", "https://www.youtube.com/generate_204"),
    LatencyTarget("Telegram", "https://web.telegram.org"),
    LatencyTarget("GitHub", "https://github.com"),
    LatencyTarget("Apple", "https://www.apple.com/library/test/success.html"),
    LatencyTarget("Microsoft", "http://www.msftconnecttest.com/connecttest.txt"),
    LatencyTarget("Instagram", "https://www.instagram.com"),
    LatencyTarget("Discord", "https://discord.com"),
    LatencyTarget("Yandex", "https://ya.ru"),
    LatencyTarget("Baidu", "https://baidu.com"),
)

/** Most targets the home Latency card shows. */
private const val MAX_LATENCY_TARGETS = 3

/**
 * Latency test targets as a grid of provider cards: a tap makes a card active (highlighted, with its place
 * in the order) or inactive again; up to [MAX_LATENCY_TARGETS] at once. Saved right away. A custom address
 * can be added as a card of its own.
 */
@Composable
fun LatencyTargetsScreen(contentPadding: PaddingValues, prefs: Prefs, onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = Boxy.colors
    var active by remember { mutableStateOf(prefs.latencyTargets) }
    // Custom addresses (not among the presets) stay as cards even when switched off during this visit.
    var customs by remember { mutableStateOf(prefs.latencyTargets.filter { t -> LATENCY_PRESETS.none { it.url == t.url } }) }
    var addDialog by remember { mutableStateOf(false) }
    val limit = stringResource(R.string.settings_latency_limit, MAX_LATENCY_TARGETS)

    // Exactly three: a card can be switched off to pick another, but only a full set of three is saved.
    fun toggle(t: LatencyTarget) {
        val on = active.any { it.url == t.url }
        val next = when {
            on -> active.filter { it.url != t.url }
            active.size >= MAX_LATENCY_TARGETS -> {
                Toast.makeText(context, limit, Toast.LENGTH_SHORT).show()
                return
            }
            else -> active + t
        }
        active = next
        if (next.size == MAX_LATENCY_TARGETS) prefs.updateLatencyTargets(next)
    }

    PinnedLazyPage(contentPadding, header = {
        SubPageHeader(
            stringResource(R.string.settings_latency_targets),
            if (active.size < MAX_LATENCY_TARGETS) stringResource(R.string.settings_latency_pick_more, MAX_LATENCY_TARGETS - active.size)
            else stringResource(R.string.settings_latency_selected, active.size, MAX_LATENCY_TARGETS),
            onBack,
        ) {
            HeaderAction(BoxyIcons.Add, stringResource(R.string.settings_latency_add)) { addDialog = true }
        }
    }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val cards = LATENCY_PRESETS + customs
        cards.chunked(2).forEach { pair ->
            item(key = pair.joinToString("|") { it.url }) {
                Row(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    pair.forEach { t ->
                        val place = active.indexOfFirst { it.url == t.url }
                        LatencyCard(t, place, Modifier.weight(1f).fillMaxHeight()) { toggle(t) }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        item {
            Text(
                stringResource(R.string.settings_latency_hint, MAX_LATENCY_TARGETS),
                Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.text2,
            )
        }
    }

    if (addDialog) {
        val urlInvalid = stringResource(R.string.panel_error_url_invalid)
        InputDialog(
            stringResource(R.string.settings_latency_add),
            listOf(stringResource(R.string.settings_latency_name) to "", stringResource(R.string.settings_latency_url) to "https://"),
            confirm = stringResource(R.string.action_add),
            validate = { v -> if (!(v[1].startsWith("http://") || v[1].startsWith("https://")) || v[1].length < 10) urlInvalid else null },
            onConfirm = { v ->
                val t = LatencyTarget(v[0].ifBlank { v[1].substringAfter("://").substringBefore('/') }, v[1])
                if (customs.none { it.url == t.url } && LATENCY_PRESETS.none { it.url == t.url }) customs = customs + t
                if (active.none { it.url == t.url } && active.size < MAX_LATENCY_TARGETS) {
                    active = active + t
                    if (active.size == MAX_LATENCY_TARGETS) prefs.updateLatencyTargets(active)
                }
                addDialog = false
            },
            onDismiss = { addDialog = false },
        )
    }
}

/** Provider card: name and host; highlighted with its number in the order when active. */
@Composable
private fun LatencyCard(t: LatencyTarget, place: Int, modifier: Modifier, onClick: () -> Unit) {
    val colors = Boxy.colors
    val on = place >= 0
    Column(
        modifier.clip(RoundedCornerShape(20.dp))
            .background(if (on) colors.accent.copy(alpha = 0.16f) else colors.card)
            .border(1.5.dp, if (on) colors.accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                t.name,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                color = if (on) colors.accent else colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (on) {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(50)).background(colors.accent), contentAlignment = Alignment.Center) {
                    Text((place + 1).toString(), style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            t.url.substringAfter("://").substringBefore('/'),
            style = MaterialTheme.typography.bodySmall,
            color = colors.text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Export / import module files and app preferences as a zip. */
@Composable
fun BackupScreen(contentPadding: PaddingValues, prefs: Prefs, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var restoreTab by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var detected by remember { mutableStateOf<BackupScope?>(null) }
    var detectFailed by remember { mutableStateOf(false) }
    var scopeChoice by remember { mutableStateOf(BackupScope.BOTH) }
    var exportScope by remember { mutableStateOf(BackupScope.BOTH) }
    var confirm by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val ok = Backup.export(context, uri, prefs, exportScope)
                busy = false
                Toast.makeText(context, if (ok) R.string.backup_export_ok else R.string.backup_export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedUri = uri
            scope.launch {
                val d = Backup.detect(context, uri)
                detected = d
                detectFailed = d == null
                if (d != null) scopeChoice = d
            }
        }
    }

    PinnedLazyPage(contentPadding, header = {
SubPageHeader(stringResource(R.string.settings_backup), stringResource(R.string.settings_backup_sub), onBack)
}, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(50)).background(Boxy.colors.card).padding(4.dp)) {
                listOf(false to R.string.backup_tab_backup, true to R.string.backup_tab_restore).forEach { (r, label) ->
                    val active = restoreTab == r
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (active) Boxy.colors.accent.copy(alpha = 0.15f) else Boxy.colors.card)
                            .clickable { restoreTab = r }.padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(label), color = if (active) Boxy.colors.accent else Boxy.colors.text2, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (!restoreTab) {
            item {
                SectionCard(stringResource(R.string.backup_export_scope), stringResource(R.string.backup_export_desc)) {
                    ScopeTiles(listOf(BackupScope.MODULES, BackupScope.APPS, BackupScope.BOTH), exportScope) { exportScope = it }
                    Box(Modifier.padding(horizontal = 18.dp)) {
                        SheetButtons(stringResource(R.string.backup_export), {
                            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                            exportLauncher.launch("boxy-backup-$stamp.zip")
                        })
                    }
                }
            }
        } else {
            item {
                SectionCard(stringResource(R.string.backup_file_title), null) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(16.dp)).background(Boxy.colors.surface2).clickable {
                        pickLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    }.padding(16.dp)) {
                        val pickedName = remember(pickedUri) {
                            pickedUri?.let { uri ->
                                runCatching {
                                    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                                        if (c.moveToFirst()) c.getString(0) else null
                                    }
                                }.getOrNull() ?: uri.lastPathSegment
                            }
                        }
                        Text(pickedName ?: stringResource(R.string.backup_file_pick), color = Boxy.colors.text)
                        when {
                            detectFailed -> Text(stringResource(R.string.backup_detect_failed), color = Tints.red.fg, style = MaterialTheme.typography.bodySmall)
                            detected != null -> Text(stringResource(R.string.backup_detected, scopeName(detected!!)), color = Boxy.colors.text2, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (detected != null) {
                item {
                    SectionCard(stringResource(R.string.backup_scope_title), null) {
                        val options = when (detected) {
                            BackupScope.BOTH -> listOf(BackupScope.BOTH, BackupScope.MODULES, BackupScope.APPS)
                            else -> listOf(detected!!)
                        }
                        ScopeTiles(options, scopeChoice, restore = true) { scopeChoice = it }
                        Box(Modifier.padding(horizontal = 18.dp)) {
                            SheetButtons(stringResource(R.string.backup_restore), { confirm = true })
                        }
                    }
                }
            }
        }
        if (busy) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
    }

    if (confirm) {
        ConfirmDialog(
            stringResource(R.string.backup_restore),
            stringResource(R.string.backup_restore_confirm, scopeName(scopeChoice)),
            stringResource(R.string.backup_continue),
            onConfirm = {
                confirm = false
                val uri = pickedUri ?: return@ConfirmDialog
                busy = true
                scope.launch {
                    val ok = Backup.restore(context, uri, scopeChoice, prefs)
                    busy = false
                    Toast.makeText(context, if (ok) R.string.backup_restore_ok else R.string.backup_failed, Toast.LENGTH_SHORT).show()
                    if (ok && scopeChoice != BackupScope.MODULES) {
                        (context.applicationContext as BoxyApp).reloadPrefs()
                        (context as? Activity)?.recreate()
                    }
                }
            },
            onDismiss = { confirm = false },
        )
    }
}

/** Choice tiles as in BFR's backup screen: title, what it covers, a check on the selected one. */
@Composable
private fun ScopeTiles(options: List<BackupScope>, selected: BackupScope, restore: Boolean = false, onSelect: (BackupScope) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { s ->
            val on = s == selected
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(if (on) Boxy.colors.accent.copy(alpha = 0.14f) else Boxy.colors.surface2)
                    .clickable { onSelect(s) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(if (restore) scopeLabel(s) else scopeName(s), style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text)
                    Text(
                        stringResource(
                            when (s) {
                                BackupScope.MODULES -> R.string.backup_scope_modules_desc
                                BackupScope.APPS -> R.string.backup_scope_apps_desc
                                BackupScope.BOTH -> R.string.backup_scope_both_desc
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Boxy.colors.text2,
                    )
                }
                if (on) Icon(BoxyIcons.Check, null, tint = Boxy.colors.accent)
            }
        }
    }
}

@Composable
private fun scopeName(s: BackupScope): String = stringResource(
    when (s) {
        BackupScope.BOTH -> R.string.backup_scope_both_name
        BackupScope.MODULES -> R.string.backup_scope_modules
        BackupScope.APPS -> R.string.backup_scope_apps
    },
)

@Composable
private fun scopeLabel(s: BackupScope): String = stringResource(
    when (s) {
        BackupScope.BOTH -> R.string.backup_scope_both
        BackupScope.MODULES -> R.string.backup_scope_modules_only
        BackupScope.APPS -> R.string.backup_scope_apps_only
    },
)

private data class License(val name: String, val artifact: String, val license: String, val url: String)

private const val APACHE = "Apache License 2.0"

private val LICENSES = listOf(
    License("Jetpack Compose (UI, Foundation, Material 3)", "androidx.compose:compose-bom", APACHE, "https://developer.android.com/jetpack/androidx/releases/compose"),
    License("AndroidX Activity Compose", "androidx.activity:activity-compose", APACHE, "https://developer.android.com/jetpack/androidx/releases/activity"),
    License("AndroidX Lifecycle", "androidx.lifecycle:lifecycle-runtime-compose", APACHE, "https://developer.android.com/jetpack/androidx/releases/lifecycle"),
    License("AndroidX Core", "androidx.core:core", APACHE, "https://developer.android.com/jetpack/androidx/releases/core"),
    License("Kotlin Standard Library", "org.jetbrains.kotlin:kotlin-stdlib", APACHE, "https://github.com/JetBrains/kotlin"),
    License("kotlinx.coroutines", "org.jetbrains.kotlinx:kotlinx-coroutines-android", APACHE, "https://github.com/Kotlin/kotlinx.coroutines"),
    License("libsu", "com.github.topjohnwu.libsu:core", APACHE, "https://github.com/topjohnwu/libsu"),
    License("Sora Editor", "io.github.rosemoe:editor", "LGPL-2.1", "https://github.com/Rosemoe/sora-editor"),
    License("ZXing Android Embedded", "com.journeyapps:zxing-android-embedded", APACHE, "https://github.com/journeyapps/zxing-android-embedded"),
    License("ZXing Core", "com.google.zxing:core", APACHE, "https://github.com/zxing/zxing"),
    License("Material Icons", "Google", APACHE, "https://github.com/google/material-design-icons"),
    License("Eva Icons", "Akveo", "MIT License", "https://github.com/akveo/eva-icons"),
    License("Box for Root", "taamarin/box_for_magisk", "GPL-3.0", "https://github.com/taamarin/box_for_magisk"),
    License("BFR", "boxproxy", "Design reference", "https://github.com/boxproxy"),
)

@Composable
fun LicensesScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    PinnedLazyPage(contentPadding, header = {
SubPageHeader(stringResource(R.string.settings_licenses), stringResource(R.string.settings_licenses_sub), onBack)
}, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        LICENSES.forEach { l ->
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp)).background(Boxy.colors.card).padding(vertical = 14.dp)) {
                    Text(l.name, Modifier.padding(horizontal = 18.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Boxy.colors.text)
                    Text(l.artifact, Modifier.padding(horizontal = 18.dp), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
                    Spacer(Modifier.height(6.dp))
                    SettingsRow(BoxyIcons.Description, l.license, l.url.removePrefix("https://"), showDivider = false) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l.url))) }
                    }
                }
            }
        }
    }
}

/** Latest app builds from GitHub releases. */
@Composable
fun AppUpdateSheet(currentVersion: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var releases by remember { mutableStateOf<List<AppRelease>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(-1f) }
    var installLog by remember { mutableStateOf("") }
    var installFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        val r = Updates.appReleases()
        if (r == null) failed = true else releases = r
    }
    BoxySheet(stringResource(R.string.app_name), stringResource(R.string.about_current_version, currentVersion), onDismiss) {
        val list = releases
        when {
            failed -> Text(stringResource(R.string.about_check_failed), color = Tints.red.fg)
            list == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp))
                Text(stringResource(R.string.about_checking), color = Boxy.colors.text2)
            }
            list.isEmpty() -> Text(stringResource(R.string.about_no_releases), color = Boxy.colors.text2)
            else -> {
                val stable = list.firstOrNull { !it.prerelease }
                val pre = list.firstOrNull { it.prerelease }
                val newest = listOfNotNull(stable, pre).maxWithOrNull { a, b -> if (Updates.isNewer(a.version, b.version)) 1 else -1 }
                val hasUpdate = newest != null && Updates.isNewer(newest.version, currentVersion)
                Text(
                    stringResource(if (hasUpdate) R.string.about_update_available else R.string.about_latest),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (hasUpdate) Tints.green.fg else Boxy.colors.text,
                )
                Spacer(Modifier.height(10.dp))
                listOfNotNull(stable, pre).forEach { r ->
                    SheetGroup(Modifier.padding(bottom = 10.dp)) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(r.version, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text)
                            Badge(stringResource(if (r.prerelease) R.string.about_prerelease else R.string.about_stable), if (r.prerelease) Tints.amber else Tints.green)
                        }
                        InfoRow(stringResource(R.string.about_date), r.date)
                        r.commit?.let { InfoRow(stringResource(R.string.about_commit), it) }
                        if (r.notes.isNotBlank()) {
                            Text(
                                r.notes,
                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp).clickable { expanded = !expanded },
                                style = MaterialTheme.typography.bodySmall,
                                color = Boxy.colors.text2,
                                maxLines = if (expanded) Int.MAX_VALUE else 6,
                            )
                        }
                        if (r.prerelease) {
                            Text(stringResource(R.string.about_prerelease_warning), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = Tints.amber.fg)
                        }
                        if (installing == r.version) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                if (progress >= 0f) {
                                    androidx.compose.material3.LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth(), color = Boxy.colors.accent)
                                } else {
                                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth(), color = Boxy.colors.accent)
                                }
                                Text(installLog, style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2, maxLines = 2)
                            }
                        }
                        if (installFailed && installing == null) {
                            Text(
                                stringResource(R.string.about_install_failed) + "\n" + installLog,
                                Modifier.padding(horizontal = 16.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = Tints.red.fg,
                            )
                        }
                        Row(Modifier.padding(horizontal = 16.dp)) {
                            val canInstall = r.apkUrl != null && Updates.isNewer(r.version, currentVersion)
                            SheetButtons(
                                stringResource(if (canInstall) R.string.about_install_update else R.string.about_view_browser),
                                {
                                    if (canInstall && installing == null) {
                                        installing = r.version
                                        installFailed = false
                                        progress = -1f
                                        scope.launch {
                                            val ok = Updates.installApp(context, r.apkUrl!!, { progress = it }, { installLog = it })
                                            if (!ok) {
                                                installFailed = true
                                                installing = null
                                            } else {
                                                installLog = context.getString(R.string.about_installing)
                                            }
                                        }
                                    } else if (!canInstall) {
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.pageUrl))) }
                                    }
                                },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

/** Module version and the update offered by its updateJson. */
@Composable
fun ModuleUpdateSheet(version: String?, installed: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var result by remember { mutableStateOf<Pair<ModuleUpdate?, Long>?>(null) }
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        result = Updates.moduleUpdate()
        checked = true
    }
    BoxySheet(stringResource(R.string.about_module), stringResource(R.string.about_current_version, version ?: "—"), onDismiss) {
        when {
            !installed -> Text(stringResource(R.string.settings_module_not_installed), color = Tints.red.fg)
            !checked -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp))
                Text(stringResource(R.string.about_checking), color = Boxy.colors.text2)
            }
            result == null -> Text(stringResource(R.string.about_check_failed), color = Tints.red.fg)
            result?.first == null -> Text(stringResource(R.string.about_latest), style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text)
            else -> {
                val u = result!!.first!!
                SheetGroup {
                    InfoRow(stringResource(R.string.about_update_available), u.version, Tints.green.fg)
                    InfoRow("versionCode", u.versionCode.toString())
                }
                u.changelogUrl?.let { url ->
                    SheetButtons(stringResource(R.string.about_view_browser), { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } })
                }
                u.zipUrl?.let { url ->
                    SheetButtons(stringResource(R.string.about_download_module), { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } })
                }
            }
        }
    }
}

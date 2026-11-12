package com.skofqq.boxy.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skofqq.boxy.R
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.RootTask
import com.skofqq.boxy.root.CoreTools
import com.skofqq.boxy.ui.components.addTaskLine
import com.skofqq.boxy.ui.components.BoxySheet
import com.skofqq.boxy.ui.components.SectionCard
import com.skofqq.boxy.ui.components.SheetButtons
import com.skofqq.boxy.ui.components.SubPageHeader
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import kotlinx.coroutines.launch

/** A core the module can download: settings bin_name plus, for clash, the xclash_option flavour. */
private data class CoreTarget(val title: String, val subtitle: Int, val binName: String, val xclash: String?)

private val CORE_TARGETS = listOf(
    CoreTarget("Mihomo", R.string.update_core_mihomo, "clash", "mihomo"),
    CoreTarget("Mihomo Smart", R.string.update_core_mihomo_smart, "clash", "smart"),
    CoreTarget("Sing-box", R.string.update_core_singbox, "sing-box", null),
    CoreTarget("Xray", R.string.update_core_xray, "xray", null),
    CoreTarget("V2Ray", R.string.update_core_v2ray, "v2fly", null),
    CoreTarget("Hysteria", R.string.update_core_hysteria, "hysteria", null),
)

@Composable
fun UpdateScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var coreSheet by remember { mutableStateOf(false) }
    var dashboardSheet by remember { mutableStateOf(false) }
    var geoSheet by remember { mutableStateOf(false) }
    var geoLabel by remember { mutableStateOf("MetaCubeX") }
    var core by remember { mutableStateOf<String?>(null) }
    var smartSupported by remember { mutableStateOf(false) }
    var taskTitle by remember { mutableStateOf<String?>(null) }
    val taskLines = remember { mutableStateListOf<String>() }
    var taskDone by remember { mutableStateOf(false) }
    var taskOk by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        smartSupported = BoxModule.exec("grep -q 'smart' ${BoxModule.SCRIPTS}/box.tool").first
        CoreTools.geoSources().let { (p, e) -> geoLabel = geoSourcesLabel(p, e) }
        core = BoxModule.unquote(BoxModule.readSetting("bin_name"))
    }

    val keptOld = stringResource(R.string.update_core_kept_old)
    fun launchTask(title: String, cmd: String) {
        taskTitle = title
        taskLines.clear()
        taskDone = false
        scope.launch {
            val ok = RootTask.run(cmd) { line -> taskLines.addTaskLine(line) }
            // box.tool upkernel tested the current config with the new core and kept the old one.
            val i = taskLines.indexOfFirst { "KERNEL_CHECK_FAILED" in it }
            if (i >= 0) {
                val reason = taskLines.getOrNull(i + 1)?.substringAfter("]:")?.trim().orEmpty()
                taskLines.add("⚠ $keptOld")
                if (reason.isNotEmpty()) taskLines.add(reason)
            }
            taskOk = ok
            taskDone = true
        }
    }

    val subsTitle = stringResource(R.string.update_target_subscription)
    val webuiTitle = stringResource(R.string.update_target_webui)
    val geoTitle = stringResource(R.string.update_target_geo)

    PinnedLazyPage(contentPadding, header = {
SubPageHeader(stringResource(R.string.tools_update), stringResource(R.string.tools_update_sub), onBack)
}, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionCard(null) {
                com.skofqq.boxy.ui.components.NoticeCard(stringResource(R.string.update_tip_title), stringResource(R.string.update_tip_body))
                Spacer(Modifier.height(6.dp))
                UpdateRow(BoxyIcons.Storage, stringResource(R.string.update_target_core), stringResource(R.string.update_core_sub)) { coreSheet = true }
                // Hysteria has no subscriptions in the module (its profiles come from links).
                if (core != "hysteria") UpdateRow(BoxyIcons.Link, subsTitle, stringResource(R.string.update_subscription_sub)) {
                    launchTask(subsTitle, "${BoxModule.SCRIPTS}/box.tool subs")
                }
                UpdateRow(BoxyIcons.Language, webuiTitle, stringResource(R.string.update_webui_sub)) { dashboardSheet = true }
                UpdateRow(BoxyIcons.Shield, geoTitle, stringResource(R.string.update_geo_sub, geoLabel)) { geoSheet = true }
            }
        }
    }

    if (coreSheet) {
        BoxySheet(stringResource(R.string.update_core_sheet_title), stringResource(R.string.update_core_sub), { coreSheet = false }) {
            CORE_TARGETS.filter { it.xclash != "smart" || smartSupported }.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp)).background(Boxy.colors.card)
                        .clickable {
                            coreSheet = false
                            scope.launch { launchTask(t.title, BoxModule.toolCommand("upkernel", t.binName, t.xclash)) }
                        }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text)
                        Text(stringResource(t.subtitle), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
                    }
                    Icon(BoxyIcons.SystemUpdate, null, tint = Boxy.colors.text)
                }
            }
        }
    }

    if (dashboardSheet) com.skofqq.boxy.ui.panel.DashboardSheet(onDismiss = { dashboardSheet = false })

    if (geoSheet) {
        GeoSourcesSheet(
            onChanged = { p, e -> geoLabel = geoSourcesLabel(p, e) },
            onUpdate = {
                geoSheet = false
                scope.launch { launchTask(geoTitle, BoxModule.toolCommand("geox", force = true)) }
            },
            onDismiss = { geoSheet = false },
        )
    }

    taskTitle?.let { title ->
        // Subscriptions and geo files are not "updated: <version>": they say what happened to them.
        val texts = when (title) {
            subsTitle -> com.skofqq.boxy.ui.components.TaskTexts(
                stringResource(R.string.subs_task_working), stringResource(R.string.subs_task_ok), stringResource(R.string.subs_task_failed),
            )
            geoTitle -> com.skofqq.boxy.ui.components.TaskTexts(
                stringResource(R.string.geo_task_working),
                // The module skips cores that need no geo files, and files that did not change.
                stringResource(if (taskLines.any { "skipping" in it || "is up to date" in it || "is unchanged" in it } && taskLines.none { " updated" in it }) R.string.geo_task_nothing else R.string.geo_task_ok),
                stringResource(R.string.geo_task_failed),
            )
            else -> null
        }
        com.skofqq.boxy.ui.components.TaskDialog(title, taskLines, taskDone, taskOk, texts) { if (taskDone) taskTitle = null }
    }
}

@Composable
private fun UpdateRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Boxy.colors.surface2), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = Boxy.colors.text)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2, maxLines = 2)
        }
        Icon(BoxyIcons.SystemUpdate, null, Modifier.size(24.dp), tint = Boxy.colors.text)
    }
}

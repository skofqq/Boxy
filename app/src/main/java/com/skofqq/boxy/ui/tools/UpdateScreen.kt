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
    var smartSupported by remember { mutableStateOf(false) }
    var taskTitle by remember { mutableStateOf<String?>(null) }
    val taskLines = remember { mutableStateListOf<String>() }
    var taskDone by remember { mutableStateOf(false) }
    var taskOk by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        smartSupported = BoxModule.exec("grep -q 'smart' ${BoxModule.SCRIPTS}/box.tool").first
    }

    fun launchTask(title: String, cmd: String) {
        taskTitle = title
        taskLines.clear()
        taskDone = false
        scope.launch {
            val ok = RootTask.run(cmd) { line -> taskLines.addTaskLine(line) }
            taskOk = ok
            taskDone = true
        }
    }

    val subsTitle = stringResource(R.string.update_target_subscription)
    val webuiTitle = stringResource(R.string.update_target_webui)

    PinnedLazyPage(contentPadding, header = {
SubPageHeader(stringResource(R.string.tools_update), stringResource(R.string.tools_update_sub), onBack)
}, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionCard(null) {
                com.skofqq.boxy.ui.components.NoticeCard(stringResource(R.string.update_tip_title), stringResource(R.string.update_tip_body))
                Spacer(Modifier.height(6.dp))
                UpdateRow(BoxyIcons.Storage, stringResource(R.string.update_target_core), stringResource(R.string.update_core_sub)) { coreSheet = true }
                UpdateRow(BoxyIcons.Link, subsTitle, stringResource(R.string.update_subscription_sub)) {
                    launchTask(subsTitle, "${BoxModule.SCRIPTS}/box.tool subs")
                }
                UpdateRow(BoxyIcons.Language, webuiTitle, stringResource(R.string.update_webui_sub)) { dashboardSheet = true }
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
                            launchTask(t.title, coreUpdateScript(t))
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

    taskTitle?.let { title ->
        com.skofqq.boxy.ui.components.TaskDialog(title, taskLines, taskDone, taskOk) { if (taskDone) taskTitle = null }
    }
}

/**
 * box.tool upkernel downloads the core named in settings.ini, so the target is set
 * for the duration of the update and the previous values are put back afterwards.
 */
private fun coreUpdateScript(t: CoreTarget): String {
    val s = BoxModule.SETTINGS
    val setX = if (t.xclash != null) "sed -i 's/^xclash_option=.*/xclash_option=\"${t.xclash}\"/' $s;" else ""
    return """
        ob=${'$'}(grep -m1 '^bin_name=' $s); ox=${'$'}(grep -m1 '^xclash_option=' $s)
        sed -i 's/^bin_name=.*/bin_name="${t.binName}"/' $s; $setX
        ${BoxModule.SCRIPTS}/box.tool upkernel; rc=${'$'}?
        [ -n "${'$'}ob" ] && sed -i "s/^bin_name=.*/${'$'}ob/" $s
        [ -n "${'$'}ox" ] && sed -i "s/^xclash_option=.*/${'$'}ox/" $s
        (exit ${'$'}rc)
    """.trimIndent()
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

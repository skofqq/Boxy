package com.skofqq.boxy.ui.tools

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.R
import com.skofqq.boxy.configs.XrayMigrate
import com.skofqq.boxy.configs.XraySubscription
import com.skofqq.boxy.root.RootTask
import com.skofqq.boxy.ui.components.ConfirmDialog
import com.skofqq.boxy.ui.components.InputDialog
import com.skofqq.boxy.ui.components.SectionCard
import com.skofqq.boxy.ui.components.SettingsRow
import com.skofqq.boxy.ui.components.SwitchRow
import com.skofqq.boxy.ui.components.TaskDialog
import com.skofqq.boxy.ui.components.addTaskLine
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import kotlinx.coroutines.launch

/**
 * Xray / V2Ray: the subscription of share links (outbounds "sub-…" in a file of their own, a balancer "sub" over
 * them) and, for V2Ray, moving the config to Xray.
 */
@Composable
fun XraySubscriptionSection(core: String, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var url by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<XraySubscription.Status?>(null) }
    var editUrl by remember { mutableStateOf(false) }
    var confirmMove by remember { mutableStateOf(false) }
    var taskTitle by remember { mutableStateOf<String?>(null) }
    val taskLines = remember { mutableStateListOf<String>() }
    var taskDone by remember { mutableStateOf(false) }
    var taskOk by remember { mutableStateOf(false) }
    LaunchedEffect(core, reload) {
        url = XraySubscription.url()
        status = XraySubscription.status(core)
    }
    val updateTitle = stringResource(R.string.xsub_update)
    val appliedText = stringResource(R.string.xsub_applied)
    val moveTitle = stringResource(R.string.xray_move_title)

    SectionCard(stringResource(R.string.xsub_title), stringResource(R.string.xsub_sub)) {
        SettingsRow(BoxyIcons.Link, stringResource(R.string.xsub_url), url.ifBlank { stringResource(R.string.xsub_url_none) }) { editUrl = true }
        if (url.isNotBlank()) {
            SettingsRow(
                BoxyIcons.Refresh,
                updateTitle,
                status?.let { stringResource(R.string.xsub_servers, it.servers) },
            ) {
                taskTitle = updateTitle
                taskLines.clear()
                taskDone = false
                scope.launch {
                    val downloaded = RootTask.run(XraySubscription.downloadScript(core)) { taskLines.addTaskLine(it) }
                    taskOk = downloaded && XraySubscription.apply(core).fold(
                        onSuccess = { (n, skipped) -> taskLines.add("✓ " + String.format(appliedText, n, skipped)); true },
                        onFailure = { taskLines.add("✗ " + it.message.orEmpty()); false },
                    )
                    taskDone = true
                    reload++
                    onChanged()
                }
            }
        }
        val st = status
        if (st != null && st.servers > 0) {
            SwitchRow(
                BoxyIcons.Tune,
                stringResource(R.string.xsub_default),
                stringResource(if (core == "xray") R.string.xsub_default_sub else R.string.xsub_default_sub_v2),
                st.byDefault,
                showDivider = core == "v2fly",
            ) { on ->
                scope.launch {
                    if (!XraySubscription.ensureBalancer(core, on)) Toast.makeText(context, R.string.xsub_default_failed, Toast.LENGTH_LONG).show()
                    reload++
                    onChanged()
                }
            }
        }
        if (core == "v2fly") {
            SettingsRow(BoxyIcons.ArrowCircle, moveTitle, stringResource(R.string.xray_move_sub), showDivider = false) { confirmMove = true }
        }
    }

    if (editUrl) {
        InputDialog(
            stringResource(R.string.xsub_url),
            listOf(stringResource(R.string.xsub_url_hint) to url),
            onConfirm = { v ->
                editUrl = false
                scope.launch { XraySubscription.setUrl(v[0]); reload++ }
            },
            onDismiss = { editUrl = false },
        )
    }
    if (confirmMove) {
        ConfirmDialog(
            moveTitle,
            stringResource(R.string.xray_move_confirm),
            stringResource(R.string.xray_move_go),
            onConfirm = {
                confirmMove = false
                taskTitle = moveTitle
                taskLines.clear()
                taskDone = false
                scope.launch {
                    val r = XrayMigrate.run { taskLines.add(it) }
                    taskOk = r
                    taskDone = true
                    onChanged()
                }
            },
            onDismiss = { confirmMove = false },
        )
    }
    val texts = if (taskTitle == moveTitle) {
        com.skofqq.boxy.ui.components.TaskTexts(stringResource(R.string.xray_move_working), stringResource(R.string.xray_move_ok), stringResource(R.string.xray_move_failed))
    } else {
        com.skofqq.boxy.ui.components.TaskTexts(stringResource(R.string.subs_task_working), stringResource(R.string.subs_task_ok), stringResource(R.string.subs_task_failed))
    }
    taskTitle?.let { t -> TaskDialog(t, taskLines, taskDone, taskOk, texts) { if (taskDone) taskTitle = null } }
}

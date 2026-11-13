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
    var extras by remember { mutableStateOf<List<XraySubscription.Extra>>(emptyList()) }
    var addExtra by remember { mutableStateOf(false) }
    var removeExtra by remember { mutableStateOf<XraySubscription.Extra?>(null) }
    var confirmMove by remember { mutableStateOf(false) }
    var taskTitle by remember { mutableStateOf<String?>(null) }
    val taskLines = remember { mutableStateListOf<String>() }
    var taskDone by remember { mutableStateOf(false) }
    var taskOk by remember { mutableStateOf(false) }
    LaunchedEffect(core, reload) {
        url = XraySubscription.url()
        status = XraySubscription.status(core)
        extras = if (core == "xray") XraySubscription.extras() else emptyList()
    }
    val updateTitle = stringResource(R.string.xsub_update)
    val appliedText = stringResource(R.string.xsub_applied)
    val moveTitle = stringResource(R.string.xray_move_title)

    SectionCard(stringResource(R.string.xsub_title), stringResource(R.string.xsub_sub)) {
        SettingsRow(BoxyIcons.Link, stringResource(R.string.xsub_url), url.ifBlank { stringResource(R.string.xsub_url_none) }) { editUrl = true }
        // More lists with a prefix of their own (white lists "wl" and the like): only Xray.
        if (core == "xray") {
            extras.forEach { e ->
                SettingsRow(BoxyIcons.Link, stringResource(R.string.xsub_extra_item, e.prefix), XraySubscription.maskUrl(e.url)) { removeExtra = e }
            }
            SettingsRow(BoxyIcons.Add, stringResource(R.string.xsub_extra_add), stringResource(R.string.xsub_extra_add_sub)) { addExtra = true }
        }
        if (url.isNotBlank() || extras.isNotEmpty()) {
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
                        onSuccess = { (n, skipped) ->
                            taskLines.add("✓ " + String.format(appliedText, n, skipped.size))
                            skipped.forEach { taskLines.add("– " + skipText(context, it)) }
                            true
                        },
                        onFailure = { taskLines.add("✗ " + it.message.orEmpty()); false },
                    )
                    taskDone = true
                    reload++
                    onChanged()
                }
            }
        }
        val st = status
        // Links of the last update that are not servers, by name and reason: a server missing in the panel is explained here.
        if (st != null && st.skipped.isNotEmpty()) {
            SettingsRow(
                BoxyIcons.Info,
                stringResource(R.string.xsub_skipped, st.skipped.size),
                st.skipped.joinToString("\n") { skipText(context, it) },
            ) {}
        }
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
    if (addExtra) {
        InputDialog(
            stringResource(R.string.xsub_extra_add),
            listOf(stringResource(R.string.xsub_extra_prefix) to "wl", stringResource(R.string.xsub_url_hint) to ""),
            message = stringResource(R.string.xsub_extra_add_msg),
            validate = { v ->
                when {
                    XraySubscription.cleanPrefix(v[0]).isEmpty() || XraySubscription.cleanPrefix(v[0]) == "sub" -> context.getString(R.string.xsub_extra_bad_prefix)
                    !v[1].trim().contains("://") -> context.getString(R.string.xsub_extra_bad_url)
                    else -> null
                }
            },
            onConfirm = { v ->
                addExtra = false
                scope.launch {
                    XraySubscription.setExtras(extras + XraySubscription.Extra(XraySubscription.cleanPrefix(v[0]), v[1].trim()))
                    reload++
                }
            },
            onDismiss = { addExtra = false },
        )
    }
    removeExtra?.let { e ->
        ConfirmDialog(
            stringResource(R.string.xsub_extra_item, e.prefix),
            stringResource(R.string.xsub_extra_remove_msg, XraySubscription.maskUrl(e.url)),
            stringResource(R.string.action_delete),
            onConfirm = {
                removeExtra = null
                scope.launch { XraySubscription.setExtras(extras - e); reload++ }
            },
            onDismiss = { removeExtra = null },
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

/** "Estonia (hysteria2): the link could not be read" */
internal fun skipText(context: android.content.Context, k: com.skofqq.boxy.configs.ShareLinks.Skipped): String {
    val why = context.getString(
        when (k.reason) {
            com.skofqq.boxy.configs.ShareLinks.SkipReason.SCHEME -> R.string.xsub_skip_scheme
            com.skofqq.boxy.configs.ShareLinks.SkipReason.CORE -> R.string.xsub_skip_core
            com.skofqq.boxy.configs.ShareLinks.SkipReason.PORT -> R.string.xsub_skip_port
            com.skofqq.boxy.configs.ShareLinks.SkipReason.BROKEN -> R.string.xsub_skip_broken
            com.skofqq.boxy.configs.ShareLinks.SkipReason.INSECURE -> R.string.xsub_skip_insecure
        },
    )
    return context.getString(R.string.xsub_skip_line, k.name, k.scheme, why)
}

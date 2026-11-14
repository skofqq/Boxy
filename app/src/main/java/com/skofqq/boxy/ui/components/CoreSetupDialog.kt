package com.skofqq.boxy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.skofqq.boxy.R
import com.skofqq.boxy.configs.CoreSubscription
import com.skofqq.boxy.net.SubClient
import com.skofqq.boxy.root.CoreChoice
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.tools.ImportBus
import com.skofqq.boxy.ui.tools.ImportRequest
import com.skofqq.boxy.ui.tools.ROUTE_EDIT
import com.skofqq.boxy.ui.tools.ToolsNav
import kotlinx.coroutines.launch

/**
 * The target core of a switch has no config of the user's yet (the module's placeholder or example). Offers to
 * load one from the subscription the user already has (in that core's format), to import another, to open the
 * file, or to switch anyway. [onSwitch] switches; [onDismiss] closes.
 */
@Composable
fun CoreSetupDialog(c: CoreChoice, path: String, onSwitch: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf<List<String>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<CoreSubscription.Outcome?>(null) }
    LaunchedEffect(Unit) { sources = runCatching { CoreSubscription.sources() }.getOrDefault(emptyList()) }

    val o = outcome
    if (o != null) {
        val problem = if (o.ok) "" else {
            when {
                o.limit == SubClient.Limit.MAX_DEVICES -> stringResource(R.string.sub_limit_devices)
                o.limit == SubClient.Limit.HWID_REQUIRED -> stringResource(R.string.sub_limit_hwid)
                o.error == "download" -> stringResource(R.string.core_setup_err_download)
                o.error == "empty" -> stringResource(R.string.core_setup_err_empty)
                o.error == "format" -> stringResource(R.string.core_setup_err_format, c.title)
                else -> stringResource(R.string.core_setup_err_check) + "\n\n" + (o.error ?: "")
            }
        }
        ImportResultDialog(
            outcome = o,
            problem = problem,
            action = stringResource(if (o.ok) R.string.core_setup_switch else R.string.action_done),
            onAction = { if (o.ok) onSwitch() else outcome = null },
            onDismiss = { if (o.ok) onDismiss() else outcome = null },
        )
        return
    }

    GlassAlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(stringResource(R.string.core_switch_problem_title, c.title)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.core_switch_placeholder, path), style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text2)
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.core_setup_loading, c.title))
                    }
                }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val src = sources.orEmpty()
                if (src.isNotEmpty()) {
                    DialogButton(stringResource(R.string.core_setup_from_sub), Boxy.colors.accent, androidx.compose.ui.graphics.Color.White) {
                        if (loading) return@DialogButton
                        loading = true
                        scope.launch {
                            // The active core's subscription first; another one when it gives nothing for this core.
                            var last: CoreSubscription.Outcome? = null
                            for (url in src) {
                                last = CoreSubscription.load(context.applicationContext, c.binName, url)
                                if (last.ok || last.limit != SubClient.Limit.NONE) break
                            }
                            loading = false
                            outcome = last
                        }
                    }
                }
                DialogButton(stringResource(R.string.import_title), Boxy.colors.surface2, Boxy.colors.text) {
                    onDismiss()
                    ImportBus.request = ImportRequest("", core = c.binName)
                }
                DialogButton(stringResource(R.string.config_edit), Boxy.colors.surface2, Boxy.colors.text) {
                    onDismiss()
                    ToolsNav.request = ROUTE_EDIT + path
                    ToolsNav.showTools = true
                }
                DialogButton(stringResource(R.string.core_switch_anyway), Boxy.colors.surface2, com.skofqq.boxy.ui.theme.Tints.red.fg, onSwitch)
                DialogButton(stringResource(R.string.action_cancel), Boxy.colors.surface2, Boxy.colors.text, onDismiss)
            }
        },
    )
}

package com.skofqq.boxy.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.R
import com.skofqq.boxy.configs.XrayInsecureFix
import com.skofqq.boxy.ui.theme.BoxyIcons

/**
 * Runs [XrayInsecureFix] for [config] (the active Xray config when null) and says what it did. [onDone] gets
 * true when the config now passes the core's check.
 */
@Composable
fun XrayFixDialog(config: String?, onDone: (Boolean) -> Unit) {
    var outcome by remember { mutableStateOf<XrayInsecureFix.Outcome?>(null) }
    LaunchedEffect(config) { outcome = XrayInsecureFix.fix(config) }
    val o = outcome
    when {
        o == null -> GlassAlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(R.string.xray_fix_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.xray_fix_running))
                }
            },
        )
        o.failed != null -> ProblemDialog(
            BoxyIcons.SyncProblem,
            stringResource(R.string.xray_fix_failed),
            stringResource(R.string.xray_fix_failed_sub),
            o.failed,
            null,
            {},
        ) { onDone(false) }
        else -> {
            val lines = buildList {
                if (o.pinned.isNotEmpty()) add(stringResource(R.string.xray_fix_pinned, o.pinned.joinToString(", ")))
                if (o.unreachable.isNotEmpty()) add(stringResource(R.string.xray_fix_unreachable, o.unreachable.joinToString(", ")))
                add(stringResource(R.string.xray_fix_backup))
            }
            ConfirmDialog(
                title = stringResource(R.string.xray_fix_done),
                message = lines.joinToString("\n\n"),
                confirm = stringResource(R.string.action_done),
                onConfirm = { onDone(true) },
                onDismiss = { onDone(true) },
            )
        }
    }
}

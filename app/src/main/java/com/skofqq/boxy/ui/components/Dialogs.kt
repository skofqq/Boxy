package com.skofqq.boxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.R
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.Tints

/** One or more text fields with OK / Cancel. [validate] returns an error text or null. */
@Composable
fun InputDialog(
    title: String,
    fields: List<Pair<String, String>>,
    confirm: String = stringResource(R.string.action_apply),
    message: String? = null,
    validate: (List<String>) -> String? = { null },
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var values by remember { mutableStateOf(fields.map { it.second }) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text2)
                fields.forEachIndexed { i, (hint, _) ->
                    BoxyTextField(values[i], { v -> values = values.toMutableList().also { it[i] = v }; error = null }, hint, Modifier.fillMaxWidth())
                }
                if (error != null) Text(error!!, color = Tints.red.fg, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = withTap {
                val e = validate(values)
                if (e != null) error = e else onConfirm(values.map { it.trim() })
            }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = withTap(onDismiss)) { Text(stringResource(R.string.action_cancel)) } },
        containerColor = Boxy.colors.card,
    )
}

/** Result of a failed core config test: the error lines in monospace, with a way to go ahead anyway. */
@Composable
fun ConfigErrorDialog(
    output: String,
    proceed: String,
    onProceed: () -> Unit,
    onDismiss: () -> Unit,
    onEdit: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.check_failed_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.check_failed_body), style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text2)
                Text(
                    output,
                    Modifier.fillMaxWidth().heightIn(max = 260.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(Boxy.colors.surface2)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = Tints.red.fg,
                )
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onEdit != null) DialogButton(stringResource(R.string.config_edit), Boxy.colors.accent, androidx.compose.ui.graphics.Color.White, onEdit)
                DialogButton(proceed, Tints.red.bg, Tints.red.fg, onProceed)
                DialogButton(stringResource(R.string.action_cancel), Boxy.colors.surface2, Boxy.colors.text, onDismiss)
            }
        },
        containerColor = Boxy.colors.card,
    )
}

@Composable
private fun DialogButton(text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxWidth().height(48.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(bg)
            .clickable(onClick = onClick),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Text(text, color = fg, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = withTap(onConfirm)) { Text(confirm, color = if (danger) Tints.red.fg else Boxy.colors.accent) }
        },
        dismissButton = { TextButton(onClick = withTap(onDismiss)) { Text(stringResource(R.string.action_cancel)) } },
        containerColor = Boxy.colors.card,
    )
}

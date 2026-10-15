package com.skofqq.boxy.ui.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.skofqq.boxy.R
import com.skofqq.boxy.automation.Automation
import com.skofqq.boxy.automation.Schedule
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.ui.components.SectionCard
import com.skofqq.boxy.ui.components.SettingsRow
import com.skofqq.boxy.ui.components.SubPageHeader
import com.skofqq.boxy.ui.components.SwitchRow
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import com.skofqq.boxy.ui.components.withTap

/** Schedule (start / stop by time) and intents for Tasker, MacroDroid and adb. */
@Composable
fun AutomationScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    var schedule by remember { mutableStateOf(Automation.schedule(context)) }
    var intents by remember { mutableStateOf(Automation.intentsAllowed(context)) }
    var exact by remember { mutableStateOf(Automation.canExact(context)) }
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = start time, false = stop time
    LifecycleResumeEffect(Unit) {
        exact = Automation.canExact(context)
        onPauseOrDispose {}
    }
    fun save(s: Schedule) {
        schedule = s
        Automation.setSchedule(context, s)
    }
    val copied = stringResource(R.string.auto_copied)
    fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Boxy", text))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
    }

    PinnedLazyPage(contentPadding, header = {
        SubPageHeader(stringResource(R.string.auto_title), stringResource(R.string.auto_subtitle), onBack)
    }, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionCard(stringResource(R.string.auto_schedule), stringResource(R.string.auto_schedule_sub)) {
                SwitchRow(BoxyIcons.Schedule, stringResource(R.string.auto_schedule_enable), nextText(schedule), schedule.enabled) {
                    save(schedule.copy(enabled = it))
                }
                SettingsRow(BoxyIcons.ArrowUp, stringResource(R.string.auto_start_at), timeText(schedule.startAt)) { picking = true }
                SettingsRow(BoxyIcons.ArrowDown, stringResource(R.string.auto_stop_at), timeText(schedule.stopAt), showDivider = false) { picking = false }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DayOfWeek.entries.forEach { day ->
                        val bit = 1 shl (day.value - 1)
                        val on = schedule.days and bit != 0
                        Text(
                            day.getDisplayName(TextStyle.SHORT_STANDALONE, appLocale).replaceFirstChar { it.titlecase() },
                            Modifier.clip(RoundedCornerShape(50))
                                .background(if (on) Boxy.colors.accent.copy(alpha = 0.15f) else Boxy.colors.surface2)
                                .clickable { save(schedule.copy(days = schedule.days xor bit)) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            color = if (on) Boxy.colors.accent else Boxy.colors.text2,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
                if (schedule.enabled && !exact && Build.VERSION.SDK_INT >= 31) {
                    SettingsRow(BoxyIcons.Info, stringResource(R.string.auto_exact), stringResource(R.string.auto_exact_sub), showDivider = false) {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName)))
                        }
                    }
                }
            }
        }
        item {
            SectionCard(stringResource(R.string.auto_intents), stringResource(R.string.auto_intents_sub)) {
                SwitchRow(BoxyIcons.Code, stringResource(R.string.auto_intents_enable), stringResource(R.string.auto_intents_enable_sub), intents, showDivider = false) {
                    intents = it
                    Automation.setIntentsAllowed(context, it)
                }
                Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CodeLine(stringResource(R.string.auto_package), context.packageName) { copy(context.packageName) }
                    listOf(
                        Automation.ACTION_START to R.string.action_start,
                        Automation.ACTION_STOP to R.string.action_stop,
                        Automation.ACTION_RESTART to R.string.action_restart,
                        Automation.ACTION_TOGGLE to R.string.auto_toggle,
                        Automation.ACTION_SET_CONFIG to R.string.auto_set_config,
                    ).forEach { (action, label) ->
                        CodeLine(stringResource(label), action) { copy(action) }
                    }
                    val adb = "am broadcast -a ${Automation.ACTION_SET_CONFIG} -p ${context.packageName} --es name config.yaml"
                    CodeLine(stringResource(R.string.auto_example), adb) { copy(adb) }
                    Text(stringResource(R.string.auto_intents_hint), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
                }
            }
        }
    }

    picking?.let { start ->
        TimeDialog(if (start) schedule.startAt else schedule.stopAt, onDismiss = { picking = null }) { minutes ->
            picking = null
            save(if (start) schedule.copy(startAt = minutes) else schedule.copy(stopAt = minutes))
        }
    }
}

@Composable
private fun CodeLine(label: String, code: String, onCopy: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Boxy.colors.surface2).clickable(onClick = onCopy).padding(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Boxy.colors.text2)
        Text(code, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Boxy.colors.text)
    }
}

@Composable
private fun timeText(minutes: Int): String =
    if (minutes < 0) stringResource(R.string.common_off) else "%02d:%02d".format(minutes / 60, minutes % 60)

@Composable
private fun nextText(s: Schedule): String {
    if (!s.enabled) return stringResource(R.string.auto_schedule_off)
    val start = Automation.next(s.startAt, s.days)
    val stop = Automation.next(s.stopAt, s.days)
    val (at, isStart) = listOfNotNull(start?.let { it to true }, stop?.let { it to false }).minByOrNull { it.first }
        ?: return stringResource(R.string.auto_schedule_off)
    val f = DateTimeFormatter.ofPattern("EEE HH:mm", appLocale)
    return stringResource(if (isStart) R.string.auto_next_start else R.string.auto_next_stop, at.format(f))
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(minutes: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val m = if (minutes < 0) 8 * 60 else minutes
    val state = rememberTimePickerState(m / 60, m % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = withTap { onPick(state.hour * 60 + state.minute) }) { Text(stringResource(R.string.action_apply)) } },
        dismissButton = { TextButton(onClick = withTap(onDismiss)) { Text(stringResource(R.string.action_cancel)) } },
        text = {
            TimePicker(
                state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = Boxy.colors.surface2,
                    selectorColor = Boxy.colors.accent,
                    timeSelectorSelectedContainerColor = Boxy.colors.accent.copy(alpha = 0.18f),
                    timeSelectorContainerColor = Boxy.colors.surface2,
                ),
            )
        },
        containerColor = Boxy.colors.card,
    )
}

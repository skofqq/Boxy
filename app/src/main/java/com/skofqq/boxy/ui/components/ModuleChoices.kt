package com.skofqq.boxy.ui.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.skofqq.boxy.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.skofqq.boxy.root.CORE_CHOICES
import com.skofqq.boxy.root.CoreChoice
import com.skofqq.boxy.root.CorePrecheck
import com.skofqq.boxy.root.CoreTools
import kotlinx.coroutines.launch
import com.skofqq.boxy.root.ModuleSetup
import com.skofqq.boxy.root.NETWORK_MODES

/** Which of the module's main settings a sheet edits. */
enum class ModuleSetting { CORE, MODE, IPV6 }

/**
 * Core, network mode and IPv6 pickers shared by the home page and Settings → Module.
 * [onApply] gets the settings.ini keys to write; the caller restarts the service when it runs.
 */
@Composable
fun ModuleSettingSheet(
    setting: ModuleSetting,
    setup: ModuleSetup?,
    onApply: (List<Pair<String, String>>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val current = stringResource(R.string.sheet_current)
    val missing = stringResource(R.string.module_core_missing)
    val missingToast = stringResource(R.string.module_core_missing_toast)
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<Pair<CoreChoice, CorePrecheck>?>(null) }
    fun apply(c: CoreChoice) {
        onApply(listOfNotNull("bin_name" to c.binName, c.xclash?.let { "xclash_option" to it }))
        onDismiss()
    }
    when (setting) {
        ModuleSetting.CORE -> BoxySheet(stringResource(R.string.sheet_core_title), stringResource(R.string.sheet_core_subtitle), onDismiss) {
            val currentLegacy = setup?.core?.takeIf { it.legacy }
            if (currentLegacy != null) {
                NoticeCard(
                    stringResource(R.string.core_legacy_title, currentLegacy.title),
                    stringResource(if (currentLegacy.binName == "v2fly") R.string.core_legacy_v2fly else R.string.core_legacy_premium),
                )
            }
            // Retired cores are listed only while they are the active one.
            CORE_CHOICES.filter { !it.legacy || it.id == setup?.core?.id }.forEach { c ->
                val selected = setup?.core?.id == c.id
                val installed = setup == null || c.id in setup.installed
                val sub = when {
                    selected -> current
                    !installed -> missing
                    checking == c.id -> stringResource(R.string.core_switch_checking)
                    else -> null
                }
                OptionRow(c.title, sub, selected) {
                    when {
                        selected -> onDismiss()
                        !installed -> Toast.makeText(context, missingToast, Toast.LENGTH_LONG).show()
                        checking != null -> Unit
                        else -> {
                            // The core, its config and the core's own check of it, before the switch is written.
                            checking = c.id
                            scope.launch {
                                val r = runCatching { CoreTools.precheck(c) }.getOrDefault(CorePrecheck.Ok)
                                checking = null
                                if (r == CorePrecheck.Ok) apply(c) else problem = c to r
                            }
                        }
                    }
                }
            }
        }

        ModuleSetting.MODE -> BoxySheet(stringResource(R.string.sheet_mode_title), stringResource(R.string.sheet_mode_subtitle), onDismiss) {
            NETWORK_MODES.forEach { mode ->
                val selected = setup?.mode == mode
                OptionRow(modeTitle(mode), modeDescription(mode), selected) {
                    if (!selected) onApply(listOf("network_mode" to mode))
                    onDismiss()
                }
            }
        }

        ModuleSetting.IPV6 -> BoxySheet(stringResource(R.string.sheet_ipv6_title), stringResource(R.string.sheet_ipv6_subtitle), onDismiss) {
            listOf(true, false).forEach { on ->
                val selected = setup?.ipv6 == on
                OptionRow(stringResource(if (on) R.string.common_on else R.string.common_off), null, selected) {
                    if (!selected) onApply(listOf("ipv6" to on.toString()))
                    onDismiss()
                }
            }
        }
    }
    problem?.let { (c, p) ->
        CoreProblemDialog(c, p, onSwitch = { problem = null; apply(c) }, onDismiss = { problem = null })
    }
}

@Composable
private fun CoreProblemDialog(c: CoreChoice, p: CorePrecheck, onSwitch: () -> Unit, onDismiss: () -> Unit) {
    val message = when (p) {
        CorePrecheck.NoKernel -> stringResource(R.string.module_core_missing_toast)
        is CorePrecheck.NoConfig -> stringResource(R.string.core_switch_no_config, p.path)
        is CorePrecheck.BadConfig -> stringResource(R.string.core_switch_bad_config, p.path) + "\n\n" + p.output
        CorePrecheck.Ok -> ""
    }
    ConfirmDialog(
        title = stringResource(R.string.core_switch_problem_title, c.title),
        message = message,
        confirm = stringResource(R.string.core_switch_anyway),
        danger = true,
        onConfirm = onSwitch,
        onDismiss = onDismiss,
    )
}

fun modeTitle(mode: String): String = when (mode) {
    "tproxy" -> "TProxy"
    "tun" -> "TUN"
    else -> mode.replaceFirstChar { it.uppercase() }
}

fun modeDescription(mode: String): String = when (mode) {
    "redirect" -> "TCP + UDP (direct)"
    "tproxy" -> "TCP + UDP"
    "mixed" -> "redirect (TCP) + tun (UDP)"
    "enhance" -> "redirect (TCP) + tproxy (UDP)"
    else -> "TCP + UDP (auto-route)"
}

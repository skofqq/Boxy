package com.skofqq.boxy.ui.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.skofqq.boxy.R
import com.skofqq.boxy.root.CORE_CHOICES
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
    when (setting) {
        ModuleSetting.CORE -> BoxySheet(stringResource(R.string.sheet_core_title), stringResource(R.string.sheet_core_subtitle), onDismiss) {
            CORE_CHOICES.forEach { c ->
                val selected = setup?.core?.id == c.id
                val installed = setup == null || c.id in setup.installed
                OptionRow(c.title, if (selected) current else if (!installed) missing else null, selected) {
                    when {
                        selected -> onDismiss()
                        !installed -> Toast.makeText(context, missingToast, Toast.LENGTH_LONG).show()
                        else -> {
                            onApply(listOfNotNull("bin_name" to c.binName, c.xclash?.let { "xclash_option" to it }))
                            onDismiss()
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

package com.skofqq.boxy.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.R
import com.skofqq.boxy.configs.XrayMigrate
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.CORE_CHOICES
import com.skofqq.boxy.root.CorePrecheck
import com.skofqq.boxy.root.CoreTools
import com.skofqq.boxy.root.RootTask
import com.skofqq.boxy.ui.components.NoticeCard
import com.skofqq.boxy.ui.components.SectionCard
import com.skofqq.boxy.ui.components.SheetButtons
import com.skofqq.boxy.ui.components.TaskDialog
import com.skofqq.boxy.ui.components.TaskTexts
import com.skofqq.boxy.ui.components.addTaskLine
import kotlinx.coroutines.launch

/**
 * The device still runs a retired core (V2Ray, Clash Premium): a card with the way out. Nothing is switched until
 * the user taps it: V2Ray moves its config to Xray (checked first), Premium goes to Mihomo (downloaded when missing,
 * the config checked with it).
 */
@Composable
fun LegacyCoreCard(core: String?, xclash: String?, onChanged: () -> Unit) {
    val legacy = when {
        core == "v2fly" -> "v2fly"
        core == "clash" && xclash == "premium" -> "premium"
        else -> null
    } ?: return
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf<String?>(null) }
    val lines = remember { mutableStateListOf<String>() }
    var done by remember { mutableStateOf(false) }
    var ok by remember { mutableStateOf(false) }
    val actionTitle = stringResource(if (legacy == "v2fly") R.string.xray_move_title else R.string.core_legacy_to_mihomo)

    SectionCard(null) {
        Column(Modifier.padding(bottom = 12.dp)) {
            NoticeCard(
                stringResource(R.string.core_legacy_title, if (legacy == "v2fly") "V2Ray" else "Clash Premium"),
                stringResource(if (legacy == "v2fly") R.string.core_legacy_v2fly else R.string.core_legacy_premium),
            )
            Column(Modifier.padding(horizontal = 18.dp)) {
                SheetButtons(actionTitle, {
                    title = actionTitle
                    lines.clear()
                    done = false
                    scope.launch {
                        ok = if (legacy == "v2fly") XrayMigrate.run { lines.add(it) } else toMihomo { lines.addTaskLine(it) }
                        done = true
                    }
                })
            }
        }
    }

    title?.let { t ->
        val texts = if (legacy == "v2fly") {
            TaskTexts(stringResource(R.string.xray_move_working), stringResource(R.string.xray_move_ok), stringResource(R.string.xray_move_failed))
        } else {
            TaskTexts(stringResource(R.string.core_legacy_mihomo_working), stringResource(R.string.core_legacy_mihomo_ok), stringResource(R.string.core_legacy_mihomo_failed))
        }
        TaskDialog(t, lines, done, ok, texts) {
            if (done) {
                title = null
                if (ok) onChanged()
            }
        }
    }
}

/** Mihomo instead of Clash Premium: downloads it when missing, checks the clash config with it, then switches. */
private suspend fun toMihomo(log: (String) -> Unit): Boolean {
    val mihomo = CORE_CHOICES.first { it.id == "mihomo" }
    if (!BoxModule.exec("[ -f ${BoxModule.BOX_DIR}/bin/${mihomo.binary} ]").first) {
        if (!RootTask.run(BoxModule.toolCommand("upkernel", "clash", "mihomo")) { log(it) }) return false
    }
    return when (val r = CoreTools.precheck(mihomo)) {
        CorePrecheck.Ok -> {
            val ok = BoxModule.writeSetting("xclash_option", "mihomo")
            if (ok && BoxModule.state().running) BoxModule.restart()
            log("✓ xclash_option = mihomo")
            ok
        }
        is CorePrecheck.BadConfig -> { log(r.output); false }
        is CorePrecheck.NoConfig -> { log(r.path); false }
        CorePrecheck.NoKernel -> false
    }
}

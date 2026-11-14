package com.skofqq.boxy.ui.tools

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
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
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.ui.components.BoxyTextField
import com.skofqq.boxy.ui.components.SectionCard
import com.skofqq.boxy.ui.components.StringListEditor
import com.skofqq.boxy.ui.components.SubPageHeader
import com.skofqq.boxy.ui.components.SwitchRow
import com.skofqq.boxy.ui.theme.BoxyIcons
import kotlinx.coroutines.launch

private val SUB_KEYS = listOf(
    "update_subscription", "run_crontab", "interva_update", "renew", "subscription_url_clash",
    "name_provide_clash_config", "clash_provide_path", "subscription_url_singbox",
)

private data class SubState(
    val update: Boolean,
    val crontab: Boolean,
    val interval: String,
    val renew: Boolean,
    val clashUrls: List<String>,
    val clashNames: List<String>,
    val providePath: String,
    val singboxUrl: String,
)

/** Subscription settings of the module (settings.ini), used by box.tool subs and crontab. */
@Composable
fun SubscriptionScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<SubState?>(null) }
    var state by remember { mutableStateOf<SubState?>(null) }

    LaunchedEffect(Unit) {
        val raw = BoxModule.readSettingsRaw(SUB_KEYS)
        fun bool(k: String) = BoxModule.unquote(raw[k]) == "true"
        val s = SubState(
            update = bool("update_subscription"),
            crontab = bool("run_crontab"),
            interval = BoxModule.unquote(raw["interva_update"]).orEmpty(),
            renew = bool("renew"),
            clashUrls = BoxModule.parseArray(raw["subscription_url_clash"]),
            clashNames = BoxModule.parseArray(raw["name_provide_clash_config"]),
            providePath = BoxModule.unquote(raw["clash_provide_path"]).orEmpty(),
            singboxUrl = BoxModule.unquote(raw["subscription_url_singbox"]).orEmpty(),
        )
        original = s
        state = s
    }

    fun save() {
        val s = state ?: return
        scope.launch {
            var ok = BoxModule.writeSetting("update_subscription", s.update.toString())
            ok = ok && BoxModule.writeSetting("run_crontab", s.crontab.toString())
            ok = ok && BoxModule.writeSetting("interva_update", s.interval)
            ok = ok && BoxModule.writeSetting("renew", s.renew.toString())
            ok = ok && BoxModule.writeSettingRaw("subscription_url_clash", BoxModule.toArray(s.clashUrls.filter { it.isNotBlank() }))
            ok = ok && BoxModule.writeSettingRaw("name_provide_clash_config", BoxModule.toArray(s.clashNames.filter { it.isNotBlank() }))
            // clash_provide_path usually references ${box_dir}; keep it unquoted-safe by writing as a quoted string.
            ok = ok && BoxModule.writeSetting("clash_provide_path", s.providePath)
            ok = ok && BoxModule.writeSetting("subscription_url_singbox", s.singboxUrl)
            if (ok) {
                original = s
                // Apply the crontab change right away, like the module does on boot.
                BoxModule.exec("${BoxModule.SCRIPTS}/box.service ${if (s.crontab) "cron" else "kcron"}")
            }
            com.skofqq.boxy.ui.components.BoxyToast.makeText(context, if (ok) R.string.saved else R.string.sub_save_failed, com.skofqq.boxy.ui.components.BoxyToast.LENGTH_SHORT).show()
        }
    }

    Box(Modifier.fillMaxSize()) {
        PinnedLazyPage(contentPadding, header = {
SubPageHeader(stringResource(R.string.tools_subscription), stringResource(R.string.tools_subscription_sub), onBack)
}, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val s = state
            if (s == null) {
                item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                return@PinnedLazyPage
            }
            item {
                SectionCard(stringResource(R.string.sub_general), stringResource(R.string.sub_general_sub)) {
                    SwitchRow(BoxyIcons.Refresh, stringResource(R.string.sub_update_title), stringResource(R.string.sub_update_sub), s.update) { state = s.copy(update = it) }
                    SwitchRow(BoxyIcons.Schedule, stringResource(R.string.sub_crontab_title), stringResource(R.string.sub_crontab_sub), s.crontab) { state = s.copy(crontab = it) }
                    SwitchRow(BoxyIcons.File, stringResource(R.string.sub_renew_title), stringResource(R.string.sub_renew_sub), s.renew, showDivider = false) { state = s.copy(renew = it) }
                }
            }
            item {
                SectionCard(stringResource(R.string.sub_interval_title), stringResource(R.string.sub_interval_sub)) {
                    BoxyTextField(s.interval, { state = s.copy(interval = it) }, stringResource(R.string.sub_interval_placeholder), Modifier.fillMaxWidth().padding(horizontal = 18.dp))
                }
            }
            item {
                SectionCard(stringResource(R.string.sub_clash_urls_title), stringResource(R.string.sub_clash_urls_sub)) {
                    StringListEditor(s.clashUrls.ifEmpty { listOf("") }, { state = s.copy(clashUrls = it) }, stringResource(R.string.sub_url_placeholder), icon = BoxyIcons.Link)
                }
            }
            item {
                SectionCard(stringResource(R.string.sub_clash_configs_title), stringResource(R.string.sub_clash_configs_sub)) {
                    StringListEditor(s.clashNames.ifEmpty { listOf("") }, { state = s.copy(clashNames = it) }, stringResource(R.string.sub_filename_placeholder), icon = BoxyIcons.Description)
                }
            }
            item {
                SectionCard(stringResource(R.string.sub_provide_path_title), stringResource(R.string.sub_provide_path_sub)) {
                    BoxyTextField(s.providePath, { state = s.copy(providePath = it) }, stringResource(R.string.sub_path_placeholder), Modifier.fillMaxWidth().padding(horizontal = 18.dp))
                }
            }
            item {
                SectionCard(stringResource(R.string.sub_singbox_title), stringResource(R.string.sub_singbox_sub)) {
                    BoxyTextField(s.singboxUrl, { state = s.copy(singboxUrl = it) }, stringResource(R.string.sub_url_placeholder), Modifier.fillMaxWidth().padding(horizontal = 18.dp))
                }
            }
        }

        AnimatedVisibility(
            visible = state != null && state != original,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(contentPadding).padding(bottom = 12.dp),
        ) {
            SaveButton { save() }
        }
    }
}

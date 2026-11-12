package com.skofqq.boxy.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.R
import com.skofqq.boxy.configs.GeoSource
import com.skofqq.boxy.configs.GeoSources
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.CoreTools
import com.skofqq.boxy.ui.components.BoxySheet
import com.skofqq.boxy.ui.components.NoticeCard
import com.skofqq.boxy.ui.components.SheetButtons
import com.skofqq.boxy.ui.theme.Boxy
import kotlinx.coroutines.launch

/**
 * The geo sources: any number enabled at once, one of them primary (fills the core's own GeoSite / GeoIP files,
 * plain category names); the others are kept as their own geosite / geoip pair and named with their prefix.
 */
@Composable
fun GeoSourcesSheet(onChanged: (GeoSource, List<GeoSource>) -> Unit, onUpdate: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var primary by remember { mutableStateOf<GeoSource?>(null) }
    var extras by remember { mutableStateOf<List<GeoSource>>(emptyList()) }
    var supported by remember { mutableStateOf(true) }
    var core by remember { mutableStateOf<String?>(null) }
    var geodata by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        val (p, e) = CoreTools.geoSources()
        primary = p
        extras = e
        supported = BoxModule.exec("grep -q geo_extra ${BoxModule.SCRIPTS}/box.tool").first
        core = BoxModule.unquote(BoxModule.readSetting("bin_name"))
        if (core == "clash") geodata = CoreTools.mihomoGeodataMode()
    }

    fun save(p: GeoSource, e: List<GeoSource>) {
        primary = p
        extras = e
        onChanged(p, e)
        scope.launch { CoreTools.setGeoSources(p.id, e.map { it.id }) }
    }

    BoxySheet(stringResource(R.string.geo_source_title), stringResource(R.string.geo_source_sub), onDismiss) {
        val p = primary ?: return@BoxySheet
        GeoSources.ALL.forEach { s ->
            val isPrimary = s.id == p.id
            val enabled = isPrimary || extras.any { it.id == s.id }
            Column(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp))
                    .background(if (isPrimary) Boxy.colors.accent.copy(alpha = 0.14f) else Boxy.colors.card)
                    .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.title, style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (isPrimary) stringResource(R.string.geo_source_primary) else "${s.prefix}-",
                                Modifier.clip(RoundedCornerShape(50)).background(Boxy.colors.surface2).padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isPrimary) Boxy.colors.accent else Boxy.colors.text2,
                            )
                        }
                        Text(stringResource(s.description), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
                    }
                    Switch(
                        checked = enabled,
                        enabled = !isPrimary,
                        onCheckedChange = { on -> save(p, if (on) extras + s else extras.filter { it.id != s.id }) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Boxy.colors.accent),
                    )
                }
                if (enabled && !isPrimary) {
                    Text(
                        stringResource(R.string.geo_source_make_primary),
                        Modifier.padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).clickable {
                            save(s, (extras.filter { it.id != s.id } + p))
                        }.padding(vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = Boxy.colors.accent,
                    )
                }
            }
        }
        if (!supported) {
            Spacer(Modifier.height(8.dp))
            NoticeCard(stringResource(R.string.geo_source_old_module_title), stringResource(R.string.geo_source_old_module))
        }
        // mihomo takes GeoIP from Country.mmdb unless geodata-mode is on: the extra GeoIP lists would be unused.
        if (core == "clash" && geodata == false && extras.any { it.hasGeoip }) {
            Spacer(Modifier.height(8.dp))
            NoticeCard(stringResource(R.string.geo_source_mmdb_title), stringResource(R.string.geo_source_mmdb))
            Text(
                stringResource(R.string.geo_source_mmdb_enable),
                Modifier.padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).clickable {
                    scope.launch { if (CoreTools.enableMihomoGeodataMode()) geodata = true }
                }.padding(8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = Boxy.colors.accent,
            )
        }
        if (core == "hysteria") {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.geo_source_hysteria), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
        }
        SheetButtons(stringResource(R.string.geo_update_now), onUpdate)
    }
}

/** "MetaCubeX + runetfreedom, itdoginfo" for the update row. */
fun geoSourcesLabel(primary: GeoSource, extras: List<GeoSource>): String =
    primary.title + if (extras.isEmpty()) "" else " + " + extras.joinToString(", ") { it.title }

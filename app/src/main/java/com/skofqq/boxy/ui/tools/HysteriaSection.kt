package com.skofqq.boxy.ui.tools

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.R
import com.skofqq.boxy.configs.HysteriaConfig
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.CoreTools
import com.skofqq.boxy.root.RootTask
import com.skofqq.boxy.ui.components.BoxySheet
import com.skofqq.boxy.ui.components.BoxyTextField
import com.skofqq.boxy.ui.components.ConfirmDialog
import com.skofqq.boxy.ui.components.SectionCard
import com.skofqq.boxy.ui.components.SettingsRow
import com.skofqq.boxy.ui.components.SheetButtons
import com.skofqq.boxy.ui.components.SwitchRow
import com.skofqq.boxy.ui.components.TaskDialog
import com.skofqq.boxy.ui.components.addTaskLine
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import kotlinx.coroutines.launch

/** Hysteria profiles: one YAML per server, the module's tproxy / redirect blocks kept from the active one. */
object HysteriaProfiles {
    private const val DIR = "${BoxModule.BOX_DIR}/hysteria"

    fun fileName(name: String?): String =
        (name?.trim()?.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")?.trim('_')?.take(40)?.ifBlank { null } ?: "profile") + ".yaml"

    /** Writes a profile from [c], with the active config as the template for everything but the server part. */
    /** The first error the profile [c] would have, as text; null when it is fine. */
    suspend fun problem(context: android.content.Context, c: HysteriaConfig.Client): String? {
        val template = BoxModule.readFile(CoreTools.activeConfig("hysteria")).orEmpty()
        val mode = BoxModule.unquote(BoxModule.readSetting("network_mode"))
        return HysteriaConfig.check(HysteriaConfig.write(template, c), mode).firstOrNull { it.error }?.let { context.getString(it.message, it.arg) }
    }

    suspend fun create(file: String, c0: HysteriaConfig.Client, keepBandwidth: Boolean = false): Boolean {
        val template = BoxModule.readFile(CoreTools.activeConfig("hysteria")).orEmpty()
        // A link has no bandwidth: the active profile's measured line speed still applies.
        val c = if (keepBandwidth && c0.up.isBlank() && c0.down.isBlank()) HysteriaConfig.read(template).let { t -> c0.copy(up = t.up, down = t.down) } else c0
        val ports = BoxModule.readSettingsRaw(listOf("tproxy_port", "redir_port"))
        val text = HysteriaConfig.write(template, c, BoxModule.unquote(ports["tproxy_port"]) ?: "9898", BoxModule.unquote(ports["redir_port"]) ?: "9797")
        return BoxModule.writeFile("$DIR/$file", text)
    }

    /** Makes [file] the active profile and restarts hysteria when it runs (quick switch). */
    suspend fun activate(file: String): Boolean {
        if (!BoxModule.writeSetting("name_hysteria_config", file)) return false
        val st = BoxModule.state()
        if (st.running && BoxModule.unquote(st.core) == "hysteria") BoxModule.restart()
        return true
    }
}

@Composable
fun HysteriaSection(activeName: String?, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf<String?>(null) }
    var client by remember { mutableStateOf<HysteriaConfig.Client?>(null) }
    var addLink by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf<Pair<String?, String?>?>(null) }
    var taskTitle by remember { mutableStateOf<String?>(null) }
    val taskLines = remember { mutableStateListOf<String>() }
    var taskDone by remember { mutableStateOf(false) }
    var taskOk by remember { mutableStateOf(false) }
    // Re-read when another profile becomes active in the list above.
    LaunchedEffect(reload, activeName) {
        val path = CoreTools.activeConfig("hysteria")
        active = path
        client = BoxModule.readFile(path)?.let { HysteriaConfig.read(it) }
    }
    val speedTitle = stringResource(R.string.hy_speed)

    SectionCard(stringResource(R.string.hy_title), stringResource(R.string.hy_sub)) {
        SettingsRow(BoxyIcons.QrCode, stringResource(R.string.hy_add_link), stringResource(R.string.hy_add_link_sub)) { addLink = true }
        SettingsRow(BoxyIcons.Edit, stringResource(R.string.hy_form), client?.server?.ifBlank { null } ?: active?.substringAfterLast('/')) { form = true }
        SettingsRow(BoxyIcons.Speed, speedTitle, client?.let { c -> if (c.up.isNotBlank() || c.down.isNotBlank()) "↑ ${c.up}  ↓ ${c.down}" else null } ?: stringResource(R.string.hy_speed_sub), showDivider = false) {
            val file = active ?: return@SettingsRow
            taskTitle = speedTitle
            taskLines.clear()
            taskDone = false
            scope.launch {
                taskOk = RootTask.run(speedScript(file)) { taskLines.addTaskLine(it) }
                val (up, down) = HysteriaConfig.parseSpeed(taskLines)
                if (up != null || down != null) speed = up to down
                taskDone = true
            }
        }
    }

    if (addLink) HysteriaImportSheet("", onDismiss = { addLink = false }, onDone = { reload++; onChanged() })

    if (form) {
        val path = active
        val c0 = client
        if (path != null && c0 != null) {
            HysteriaFormSheet(c0, onSave = { c ->
                scope.launch {
                    // Checked before it is written: a bad value would stop Hysteria at the restart.
                    HysteriaProfiles.problem(context, c)?.let { err ->
                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    val ok = HysteriaProfiles.create(path.substringAfterLast('/'), c)
                    if (ok) {
                        val st = BoxModule.state()
                        if (st.running && BoxModule.unquote(st.core) == "hysteria") BoxModule.restart()
                    }
                    Toast.makeText(context, if (ok) R.string.editor_saved else R.string.op_failed, Toast.LENGTH_SHORT).show()
                    form = false
                    reload++
                    onChanged()
                }
            }, onDismiss = { form = false })
        } else {
            form = false
        }
    }

    taskTitle?.let { t -> TaskDialog(t, taskLines, taskDone, taskOk) { if (taskDone) taskTitle = null } }

    // H2: the measured speed into bandwidth (up / down), which Hysteria's congestion control uses.
    speed?.let { (up, down) ->
        if (taskTitle == null) {
            ConfirmDialog(
                stringResource(R.string.hy_speed_apply_title),
                stringResource(R.string.hy_speed_apply, up ?: "—", down ?: "—"),
                stringResource(R.string.action_apply),
                onConfirm = {
                    speed = null
                    val c = client ?: return@ConfirmDialog
                    val path = active ?: return@ConfirmDialog
                    scope.launch {
                        val ok = HysteriaProfiles.create(path.substringAfterLast('/'), c.copy(up = up ?: c.up, down = down ?: c.down))
                        Toast.makeText(context, if (ok) R.string.editor_saved else R.string.op_failed, Toast.LENGTH_SHORT).show()
                        reload++
                    }
                },
                onDismiss = { speed = null },
            )
        }
    }
}

/** hysteria speedtest with the profile, run as the core's user so it does not go through the running proxy. */
private fun speedScript(file: String): String {
    val b = BoxModule.BOX_DIR
    return """
        (
        BB=; for x in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do [ -x ${'$'}x ] && BB=${'$'}x && break; done
        [ -x $b/bin/hysteria ] || { echo "hysteria is not installed"; exit 1; }
        ug=${'$'}(grep -m1 '^box_user_group=' ${BoxModule.SETTINGS} | cut -d= -f2 | tr -d '"')
        SSL_CERT_DIR=/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts ${'$'}BB timeout 60 ${'$'}BB setuidgid ${'$'}{ug:-root:net_admin} $b/bin/hysteria speedtest -c '$b/hysteria/$file' --duration 5s --disable-update-check -l info
        rc=${'$'}?; exit ${'$'}rc
        )
    """.trimIndent()
}

/** H4: the server part of the profile as a form; the YAML editor stays available for everything else. */
@Composable
fun HysteriaFormSheet(initial: HysteriaConfig.Client, onSave: (HysteriaConfig.Client) -> Unit, onDismiss: () -> Unit) {
    var c by remember { mutableStateOf(initial) }
    BoxySheet(stringResource(R.string.hy_form), stringResource(R.string.hy_form_sub), onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Field(stringResource(R.string.hy_f_server), c.server, "example.com:443,20000-30000") { c = c.copy(server = it) }
            Field(stringResource(R.string.hy_f_auth), c.auth, "password") { c = c.copy(auth = it) }
            Field(stringResource(R.string.hy_f_obfs), c.obfsPassword, "salamander password") { c = c.copy(obfsPassword = it) }
            Field("SNI", c.sni, "bing.com") { c = c.copy(sni = it) }
            Field("pinSHA256", c.pinSha256, "AB:CD:…") { c = c.copy(pinSha256 = it) }
            Field(stringResource(R.string.hy_f_hop), c.hopInterval, "30s") { c = c.copy(hopInterval = it) }
            Field(stringResource(R.string.hy_f_up), c.up, "50 mbps") { c = c.copy(up = it) }
            Field(stringResource(R.string.hy_f_down), c.down, "200 mbps") { c = c.copy(down = it) }
        }
        SwitchRow(BoxyIcons.Shield, stringResource(R.string.hy_f_insecure), stringResource(R.string.hy_f_insecure_sub), c.insecure, showDivider = false) { c = c.copy(insecure = it) }
        SheetButtons(stringResource(R.string.action_save), { if (c.server.isNotBlank()) onSave(c) }, stringResource(R.string.action_cancel), onDismiss)
    }
}

@Composable
private fun Field(label: String, value: String, hint: String, onChange: (String) -> Unit) {
    Text(label, Modifier.padding(top = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = Boxy.colors.text2)
    BoxyTextField(value, onChange, hint, Modifier.fillMaxWidth())
}

/** A hysteria2:// link (typed, pasted or from a QR code) as a new profile, made active right away if wanted. */
@Composable
fun HysteriaImportSheet(initial: String, onDismiss: () -> Unit, onDone: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
        var link by remember { mutableStateOf(initial) }
        var name by remember { mutableStateOf(HysteriaConfig.parseLink(initial)?.first.orEmpty()) }
        var activate by remember { mutableStateOf(true) }
        var autoName by remember { mutableStateOf(true) }
        val parsed = remember(link) { HysteriaConfig.parseLink(link) }
        BoxySheet(stringResource(R.string.hy_add_link), stringResource(R.string.hy_add_link_sheet_sub), onDismiss) {
            // The name follows the link's #name until the user types one.
            BoxyTextField(link, { link = it.trim(); if (autoName) name = HysteriaConfig.parseLink(it)?.first.orEmpty() }, "hysteria2://…", Modifier.fillMaxWidth(), singleLine = false)
            Spacer(Modifier.height(8.dp))
            BoxyTextField(name, { name = it; autoName = false }, stringResource(R.string.config_placeholder_name), Modifier.fillMaxWidth())
            if (link.isNotBlank() && parsed == null) {
                Text(stringResource(R.string.hy_link_invalid), Modifier.padding(top = 6.dp), color = com.skofqq.boxy.ui.theme.Tints.red.fg, style = MaterialTheme.typography.bodySmall)
            }
            parsed?.second?.let { c ->
                Text(c.server + if (c.obfsPassword.isNotBlank()) " · salamander" else "", Modifier.padding(top = 6.dp), color = Boxy.colors.text2, style = MaterialTheme.typography.bodySmall)
            }
            SwitchRow(BoxyIcons.Check, stringResource(R.string.import_activate), stringResource(R.string.hy_activate_sub), activate, showDivider = false) { activate = it }
            SheetButtons(stringResource(R.string.hy_add), {
                val c = parsed?.second ?: return@SheetButtons
                val file = HysteriaProfiles.fileName(name.ifBlank { parsed.first })
                scope.launch {
                    val ok = HysteriaProfiles.create(file, c, keepBandwidth = true) && (!activate || HysteriaProfiles.activate(file))
                    Toast.makeText(context, if (ok) context.getString(R.string.config_download_ok, file) else context.getString(R.string.op_failed), Toast.LENGTH_SHORT).show()
                    if (ok) {
                        onDone()
                        onDismiss()
                    }
                }
            })
        }
    }

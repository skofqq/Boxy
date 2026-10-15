package com.skofqq.boxy.ui.tools

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.ui.components.SectionCard
import com.skofqq.boxy.ui.components.SettingsRow
import com.skofqq.boxy.ui.components.SubPageHeader
import com.skofqq.boxy.ui.components.SwitchRow
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import kotlinx.coroutines.launch
import com.skofqq.boxy.ui.components.addTaskLine

/** DNS of the module: the core's DNS hijack and the optional DNSCrypt (dnscrypt-proxy). */
@Composable
fun DnsScreen(contentPadding: PaddingValues, onBack: () -> Unit, onEdit: (String) -> Unit, onDnsServers: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dnsHijack by remember { mutableStateOf<Boolean?>(null) }
    var dnscrypt by remember { mutableStateOf<com.skofqq.boxy.root.DnsCryptState?>(null) }
    // dnscrypt-proxy download: output lines while the pop-up is open, then whether it worked.
    var dnscryptTask by remember { mutableStateOf(false) }
    val dnscryptLines = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    var dnscryptDone by remember { mutableStateOf(false) }
    var dnscryptOk by remember { mutableStateOf(false) }
    var dnscryptReload by remember { mutableStateOf(0) }
    var dnsCheck by remember { mutableStateOf(false) }
    var core by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { dnsHijack = BoxModule.dnsHijack() }
    LaunchedEffect(dnscryptReload) {
        dnscrypt = BoxModule.dnscrypt()
        core = BoxModule.readSetting("bin_name")
        loaded = true
    }

    PinnedLazyPage(contentPadding, header = {
        SubPageHeader(stringResource(R.string.tools_dns), stringResource(R.string.tools_dns_sub), onBack)
    }, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!loaded) {
            item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            return@PinnedLazyPage
        }
            dnsHijack?.let { hijack ->
                item {
                    SectionCard(null) {
                        SwitchRow(
                            BoxyIcons.Router,
                            stringResource(R.string.net_dns_hijack),
                            stringResource(if (hijack) R.string.net_dns_hijack_on else R.string.net_dns_hijack_off),
                            hijack,
                            showDivider = false,
                        ) { on ->
                            scope.launch {
                                if (BoxModule.setDnsHijack(on)) {
                                    dnsHijack = on
                                    Toast.makeText(context, R.string.saved, Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, R.string.net_save_failed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
            }
            val dc = dnscrypt
            if (dc?.enabled != null) {
                item {
                    SectionCard(stringResource(R.string.dnscrypt_title), stringResource(R.string.dnscrypt_sub)) {
                        com.skofqq.boxy.ui.components.NoticeCard(
                            stringResource(R.string.update_tip_title),
                            stringResource(if (core == "clash" || core == "sing-box") R.string.dnscrypt_hint_clash else R.string.dnscrypt_hint_other, "127.0.0.1:${dc.port}"),
                        )
                        Spacer(Modifier.height(6.dp))
                        val installed = dc.version != null
                        SwitchRow(
                            BoxyIcons.Shield,
                            stringResource(R.string.dnscrypt_enable),
                            when {
                                !installed -> stringResource(R.string.dnscrypt_state_missing)
                                dc.running -> stringResource(R.string.dnscrypt_state_on, "127.0.0.1:${dc.port}")
                                dc.enabled -> stringResource(R.string.dnscrypt_state_pending)
                                else -> stringResource(R.string.dnscrypt_state_off)
                            },
                            dc.enabled,
                            enabled = installed || dc.enabled,
                        ) { on ->
                            scope.launch {
                                if (BoxModule.setDnscrypt(on)) {
                                    // The service reads the setting on start: restart it when it runs.
                                    if (BoxModule.state().running) {
                                        Toast.makeText(context, R.string.dnscrypt_restarting, Toast.LENGTH_SHORT).show()
                                        com.skofqq.boxy.service.BoxControl.restart(context.applicationContext)
                                    }
                                    dnscryptReload++
                                } else {
                                    Toast.makeText(context, R.string.net_save_failed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        SettingsRow(
                            BoxyIcons.Download,
                            stringResource(if (installed) R.string.dnscrypt_update else R.string.dnscrypt_download),
                            dc.version?.let { stringResource(R.string.dnscrypt_installed, it) } ?: "github.com/DNSCrypt/dnscrypt-proxy",
                        ) {
                            if (dnscryptTask) return@SettingsRow
                            dnscryptLines.clear()
                            dnscryptDone = false
                            dnscryptTask = true
                            scope.launch {
                                dnscryptOk = com.skofqq.boxy.root.RootTask.run("${BoxModule.SCRIPTS}/box.tool updnscrypt") { line ->
                                    dnscryptLines.addTaskLine(line)
                                }
                                dnscryptDone = true
                                dnscryptReload++
                            }
                        }
                        SettingsRow(
                            BoxyIcons.Dns,
                            stringResource(R.string.dnscrypt_servers),
                            stringResource(R.string.dnscrypt_servers_row_sub),
                        ) { onDnsServers() }
                        SettingsRow(
                            BoxyIcons.Signal,
                            stringResource(R.string.dnscheck_title),
                            stringResource(if (dc.running) R.string.dnscheck_row_sub else R.string.dnscheck_not_running),
                        ) {
                            if (dc.running) {
                                dnsCheck = true
                            } else {
                                Toast.makeText(context, R.string.dnscheck_not_running, Toast.LENGTH_SHORT).show()
                            }
                        }
                        SettingsRow(
                            BoxyIcons.Edit,
                            stringResource(R.string.dnscrypt_config),
                            stringResource(R.string.dnscrypt_config_sub),
                            showDivider = false,
                        ) { onEdit("${BoxModule.BOX_DIR}/dnscrypt/dnscrypt-proxy.toml") }
                    }
                }
            }
    }

    if (dnscryptTask) {
        com.skofqq.boxy.ui.components.TaskDialog("dnscrypt-proxy", dnscryptLines, dnscryptDone, dnscryptOk) { dnscryptTask = false }
    }
    if (dnsCheck) {
        dnscrypt?.let { DnsCheckSheet(it.port) { dnsCheck = false } }
    }
}

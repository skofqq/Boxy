package com.skofqq.boxy.ui.tools

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.R
import com.skofqq.boxy.configs.ProxyAuth
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.ui.components.BoxyTextField
import com.skofqq.boxy.ui.components.NoticeCard
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.ui.components.SectionCard
import com.skofqq.boxy.ui.components.SettingsRow
import com.skofqq.boxy.ui.components.SheetButtons
import com.skofqq.boxy.ui.components.SmallIconButton
import com.skofqq.boxy.ui.components.StringListEditor
import com.skofqq.boxy.ui.components.SubPageHeader
import com.skofqq.boxy.ui.components.SwitchRow
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import kotlinx.coroutines.launch

/** Logins on the proxy ports of the active core (see [ProxyAuth]). */
@Composable
fun ProxyAuthScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var core by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf<ProxyAuth.State?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var enabled by remember { mutableStateOf(false) }
    val users = remember { mutableStateListOf<ProxyAuth.User>() }
    var skip by remember { mutableStateOf(listOf<String>()) }
    // Which rows show their password.
    val shown = remember { mutableStateListOf<Int>() }

    LaunchedEffect(reload) {
        loaded = false
        val c = BoxModule.unquote(BoxModule.readSetting("bin_name"))
        core = c
        val s = c?.takeIf { it in ProxyAuth.CORES }?.let { ProxyAuth.load(it) }
        state = s
        enabled = s?.enabled == true
        users.clear()
        users.addAll(s?.users.orEmpty())
        skip = s?.skip.orEmpty()
        shown.clear()
        loaded = true
    }

    fun finish(r: ProxyAuth.Result, running: Boolean) {
        when (r) {
            ProxyAuth.Result.Ok -> {
                Toast.makeText(context, if (running) R.string.proxy_auth_saved else R.string.proxy_auth_saved_stopped, Toast.LENGTH_SHORT).show()
                reload++
            }
            is ProxyAuth.Result.Failed -> error = r.output
        }
    }

    PinnedLazyPage(contentPadding, header = {
        SubPageHeader(stringResource(R.string.proxy_auth_title), stringResource(R.string.proxy_auth_sub), onBack)
    }, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!loaded) {
            item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            return@PinnedLazyPage
        }
        val c = core
        val s = state
        if (c == null || c !in ProxyAuth.CORES) {
            item { NoticeCard(stringResource(R.string.proxy_auth_title), stringResource(R.string.proxy_auth_unsupported), Modifier.padding(horizontal = 16.dp)) }
            return@PinnedLazyPage
        }
        if (s == null) {
            item { NoticeCard(stringResource(R.string.proxy_auth_title), stringResource(R.string.proxy_auth_read_failed), Modifier.padding(horizontal = 16.dp)) }
            return@PinnedLazyPage
        }

        item {
            SectionCard(stringResource(R.string.proxy_auth_ports), stringResource(R.string.proxy_auth_ports_sub, c)) {
                if (s.ports.isEmpty()) {
                    Text(
                        stringResource(R.string.proxy_auth_no_ports),
                        Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Boxy.colors.text2,
                    )
                    val (type, address) = ProxyAuth.portToAdd(c)
                    SettingsRow(BoxyIcons.Add, stringResource(R.string.proxy_auth_add_port), stringResource(R.string.proxy_auth_add_port_sub, type, address), showDivider = false) {
                        if (busy) return@SettingsRow
                        busy = true
                        scope.launch {
                            val running = BoxModule.state().running
                            finish(ProxyAuth.addPort(c, if (enabled) users.toList() else emptyList()), running)
                            busy = false
                        }
                    }
                } else {
                    s.ports.forEachIndexed { i, p ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${p.type.uppercase()} · ${p.address}", style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text)
                                Text(
                                    if (p.ownUsers) stringResource(R.string.proxy_auth_own_users, p.key) else p.key,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Boxy.colors.text2,
                                )
                            }
                        }
                        if (i < s.ports.lastIndex) Spacer(Modifier.height(2.dp))
                    }
                    if (s.untagged > 0) {
                        Text(
                            stringResource(R.string.proxy_auth_untagged, s.untagged),
                            Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = Boxy.colors.text2,
                        )
                    }
                }
            }
        }

        if (s.ports.isNotEmpty()) {
            item {
                SectionCard(null) {
                    SwitchRow(
                        BoxyIcons.Shield,
                        stringResource(R.string.proxy_auth_enable),
                        stringResource(R.string.proxy_auth_enable_sub),
                        enabled,
                        showDivider = false,
                    ) { on ->
                        enabled = on
                        if (on && users.isEmpty()) {
                            users.add(ProxyAuth.User("", randomPassword()))
                            shown.add(0)
                        }
                    }
                }
            }
            if (enabled) {
                item {
                    SectionCard(
                        stringResource(R.string.proxy_auth_users),
                        stringResource(if (c == "hysteria") R.string.proxy_auth_hysteria_one else R.string.proxy_auth_users_sub),
                    ) {
                        users.forEachIndexed { i, u ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    BoxyTextField(
                                        u.name,
                                        { v -> users[i] = u.copy(name = v.trim()) },
                                        stringResource(R.string.proxy_auth_user),
                                        Modifier.weight(1f),
                                        leading = { androidx.compose.material3.Icon(BoxyIcons.Person, null, tint = Boxy.colors.text2) },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    SmallIconButton(BoxyIcons.Delete, stringResource(R.string.action_delete)) {
                                        users.removeAt(i)
                                        shown.clear()
                                        if (users.isEmpty()) enabled = false
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    BoxyTextField(
                                        u.password,
                                        { v -> users[i] = u.copy(password = v) },
                                        stringResource(R.string.proxy_auth_password),
                                        Modifier.weight(1f),
                                        keyboardType = KeyboardType.Password,
                                        visualTransformation = if (i in shown) androidx.compose.ui.text.input.VisualTransformation.None
                                        else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                        leading = { androidx.compose.material3.Icon(BoxyIcons.Lock, null, tint = Boxy.colors.text2) },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    SmallIconButton(
                                        if (i in shown) BoxyIcons.VisibilityOff else BoxyIcons.Visibility,
                                        stringResource(if (i in shown) R.string.proxy_auth_hide else R.string.proxy_auth_show),
                                    ) { if (i in shown) shown.remove(i) else shown.add(i) }
                                }
                            }
                        }
                        if (c != "hysteria" || users.isEmpty()) {
                            SettingsRow(BoxyIcons.Add, stringResource(R.string.proxy_auth_add_user), null, showDivider = false) {
                                users.add(ProxyAuth.User("", randomPassword()))
                                shown.add(users.lastIndex)
                            }
                        }
                    }
                }
                if (c == "clash") {
                    item {
                        SectionCard(stringResource(R.string.proxy_auth_skip), stringResource(R.string.proxy_auth_skip_sub)) {
                            StringListEditor(skip, { skip = it }, "127.0.0.1/8")
                        }
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    if (c != "hysteria") NoticeCard(stringResource(R.string.update_tip_title), stringResource(R.string.proxy_auth_kept))
                    SheetButtons(stringResource(R.string.proxy_auth_save), {
                        if (busy) return@SheetButtons
                        val list = if (enabled) users.toList() else emptyList()
                        if (enabled && (list.isEmpty() || !ProxyAuth.valid(list))) {
                            Toast.makeText(context, R.string.proxy_auth_invalid, Toast.LENGTH_LONG).show()
                            return@SheetButtons
                        }
                        busy = true
                        scope.launch {
                            val running = BoxModule.state().running
                            finish(ProxyAuth.save(c, list, if (enabled) skip else emptyList()), running)
                            busy = false
                        }
                    })
                }
            }
        }
    }

    if (busy) {
        androidx.compose.ui.window.Dialog(onDismissRequest = {}) { CircularProgressIndicator() }
    }
    error?.let {
        com.skofqq.boxy.ui.components.ProblemDialog(
            BoxyIcons.Shield,
            stringResource(R.string.proxy_auth_failed),
            stringResource(R.string.proxy_auth_failed_sub),
            it,
            null,
            {},
        ) { error = null }
    }
}

/** 16 letters and digits for a new user, shown so it can be copied into the app that connects. */
private fun randomPassword(): String {
    val chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val rnd = java.security.SecureRandom()
    return (1..16).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
}

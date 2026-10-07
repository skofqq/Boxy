package com.skofqq.boxy.ui.home

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.skofqq.boxy.R
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.data.HomeSection
import com.skofqq.boxy.data.MetricCard
import com.skofqq.boxy.data.Prefs
import com.skofqq.boxy.root.Environment
import com.skofqq.boxy.ui.components.HeaderAction
import com.skofqq.boxy.ui.components.PageHeader
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints

enum class HomeSheet { NONE, DETAILS, GEO, SPEED, SUBSCRIPTION, SYSTEM, LAYOUT }

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    prefs: Prefs,
    onOpenPanel: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSubStore: () -> Unit,
    onOpenDns: () -> Unit = {},
) {
    val vm: HomeViewModel = viewModel { HomeViewModel(prefs) }
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var sheet by remember { mutableStateOf(HomeSheet.NONE) }
    var envKey by remember { mutableStateOf(0) }

    LaunchedEffect(owner, envKey) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.poll() }
    }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.message = null
        }
    }

    val state = vm.state
    val running = state?.running == true
    var moduleSheet by remember { mutableStateOf<com.skofqq.boxy.ui.components.ModuleSetting?>(null) }
    fun editable(target: com.skofqq.boxy.ui.components.ModuleSetting) {
        if (vm.busy != Busy.NONE) return
        vm.loadSetup()
        moduleSheet = target
    }

    PinnedLazyPage(contentPadding, header = {
PageHeader(stringResource(R.string.home_title), stringResource(R.string.home_subtitle)) {
                HeaderAction(BoxyIcons.Edit, stringResource(R.string.home_edit)) { sheet = HomeSheet.LAYOUT }
            }
}, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (val env = vm.env) {
            null -> item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            Environment.READY -> {
                HomeSection.entries.filter { it !in prefs.hiddenSections }.forEach { section ->
                    when (section) {
                        HomeSection.HERO -> item(key = "hero") {
                            HeroCard(
                                state = state,
                                busy = vm.busy,
                                onStatusClick = { vm.loadDetails(); sheet = HomeSheet.DETAILS },
                                onCore = { editable(com.skofqq.boxy.ui.components.ModuleSetting.CORE) },
                                onMode = { editable(com.skofqq.boxy.ui.components.ModuleSetting.MODE) },
                                onIpv6 = { editable(com.skofqq.boxy.ui.components.ModuleSetting.IPV6) },
                                dnscrypt = vm.dnscrypt,
                                onDnscrypt = onOpenDns,
                                onStart = vm::start,
                                onStop = vm::stop,
                                onRestart = vm::restart,
                            )
                        }
                        HomeSection.QUICK -> item(key = "quick") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                val mod = Modifier.weight(1f).fillMaxHeight()
                                QuickCard(stringResource(R.string.quick_panel), stringResource(R.string.quick_panel_sub), Tints.blue, mod, onOpenPanel)
                                QuickCard(stringResource(R.string.quick_logs), stringResource(R.string.quick_logs_sub), Tints.teal, mod, onOpenLogs)
                                if (vm.subStore) {
                                    QuickCard(stringResource(R.string.quick_subs), stringResource(R.string.quick_subs_sub), Tints.purple, mod, onOpenSubStore)
                                }
                            }
                        }
                        HomeSection.LATENCY -> item(key = "latency") {
                            val results = vm.latency.ifEmpty { prefs.latencyTargets.map { LatencyResult(it.name, null) } }
                            LatencyCard(results, vm.latencyBadge, vm::testLatency)
                        }
                        HomeSection.GRID -> {
                            val cards = prefs.metricOrder.filter { it !in prefs.hiddenMetrics }
                            cards.chunked(2).forEachIndexed { i, pair ->
                                item(key = "grid$i") {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(IntrinsicSize.Min),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        pair.forEach { card ->
                                            val mod = Modifier.weight(1f).fillMaxHeight()
                                            when (card) {
                                                MetricCard.IP -> IpCard(vm.lan, vm.wan, prefs.ipWan, mod, onToggle = { prefs.updateIpWan(!prefs.ipWan); vm.refreshIp() }) { vm.loadGeoDetails(); sheet = HomeSheet.GEO }
                                                MetricCard.SPEED -> SpeedCard(vm.speed, mod) { sheet = HomeSheet.SPEED }
                                                MetricCard.SUBSCRIPTION -> SubscriptionCard(vm.subscriptions, mod) { vm.refreshSubscription(); sheet = HomeSheet.SUBSCRIPTION }
                                                MetricCard.SYSTEM -> SystemCard(vm.system, running, mod) { vm.loadSystemEnvironment(); vm.loadDetails(); sheet = HomeSheet.SYSTEM }
                                            }
                                        }
                                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            else -> item { EnvironmentCard(env) { vm.checkEnvironment(); envKey++ } }
        }
    }

    HomeSheets(sheet, vm, prefs) { sheet = HomeSheet.NONE }
    moduleSheet?.let { target ->
        com.skofqq.boxy.ui.components.ModuleSettingSheet(target, vm.setup, onApply = vm::applySettings) { moduleSheet = null }
    }
}

@Composable
private fun EnvironmentCard(env: Environment, onRetry: () -> Unit) {
    val (title, body) = when (env) {
        Environment.NO_ROOT -> R.string.env_root_title to R.string.env_root_body
        Environment.NO_MODULE -> R.string.env_module_title to R.string.env_module_body
        else -> R.string.env_scripts_title to R.string.env_scripts_body
    }
    HomeCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge, color = Boxy.colors.text)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text2)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

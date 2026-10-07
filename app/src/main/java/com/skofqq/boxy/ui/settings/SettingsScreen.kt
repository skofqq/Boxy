package com.skofqq.boxy.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.skofqq.boxy.R
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.data.AppLanguage
import com.skofqq.boxy.data.NavExtra
import com.skofqq.boxy.data.Prefs
import com.skofqq.boxy.data.SubscriptionSource
import com.skofqq.boxy.data.ThemeMode
import com.skofqq.boxy.net.ModuleUpdate
import com.skofqq.boxy.net.Updates
import com.skofqq.boxy.root.BoxModule
import kotlinx.coroutines.launch
import com.skofqq.boxy.service.BoxStatusService
import com.skofqq.boxy.ui.components.Choice
import com.skofqq.boxy.ui.components.ChoiceDialog
import com.skofqq.boxy.ui.components.InputDialog
import com.skofqq.boxy.ui.components.PageHeader
import com.skofqq.boxy.ui.components.SectionCard
import com.skofqq.boxy.ui.components.SettingsRow
import com.skofqq.boxy.ui.components.SwitchRow
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons

const val GITHUB_URL = "https://github.com/skofqq/Boxy"

private const val ROUTE_MAIN = "main"
private const val ROUTE_APPEARANCE = "appearance"
private const val ROUTE_LATENCY = "latency"
private const val ROUTE_BACKUP = "backup"
private const val ROUTE_LICENSES = "licenses"
private const val ROUTE_NOTIFICATIONS = "notifications"

/** Settings tab with its own page stack (appearance extras, latency targets, backup, licenses). */
@Composable
fun SettingsScreen(contentPadding: PaddingValues, prefs: Prefs, active: Boolean) {
    var stack by rememberSaveable { mutableStateOf(listOf<String>()) }
    val push: (String) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { stack = stack.dropLast(1) }
    BackHandler(enabled = active && stack.isNotEmpty()) { pop() }

    AnimatedContent(
        targetState = (stack.lastOrNull() ?: ROUTE_MAIN) to stack.size,
        transitionSpec = {
            val forward = targetState.second >= initialState.second
            (slideInHorizontally(tween(260)) { if (forward) it else -it / 3 }) togetherWith
                (slideOutHorizontally(tween(260)) { if (forward) -it / 3 else it })
        },
        label = "settings",
    ) { (route, _) ->
        Box(Modifier.fillMaxSize().background(Boxy.colors.page)) {
            when (route) {
                ROUTE_APPEARANCE -> AppearanceScreen(contentPadding, prefs, pop)
                ROUTE_LATENCY -> LatencyTargetsScreen(contentPadding, prefs, pop)
                ROUTE_BACKUP -> BackupScreen(contentPadding, prefs, pop)
                ROUTE_LICENSES -> LicensesScreen(contentPadding, pop)
                ROUTE_NOTIFICATIONS -> NotificationsScreen(contentPadding, prefs, pop)
                else -> SettingsMain(contentPadding, prefs, push)
            }
        }
    }
}

@Composable
private fun SettingsMain(contentPadding: PaddingValues, prefs: Prefs, push: (String) -> Unit) {
    val context = LocalContext.current
    var themeDialog by remember { mutableStateOf(false) }
    var languageDialog by remember { mutableStateOf(false) }
    var barsDialog by remember { mutableStateOf(false) }
    var sourceDialog by remember { mutableStateOf(false) }
    var chainsDialog by remember { mutableStateOf(false) }
    var infoSheet by remember { mutableStateOf(false) }
    var autostart by remember { mutableStateOf<Boolean?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) { autostart = BoxModule.autostartEnabled() }
    var moduleSheet by remember { mutableStateOf(false) }
    var mirrorSheet by remember { mutableStateOf(false) }
    var moduleVersion by remember { mutableStateOf<String?>(null) }
    var moduleInstalled by remember { mutableStateOf(true) }
    var moduleUpdate by remember { mutableStateOf<ModuleUpdate?>(null) }
    var appUpdate by remember { mutableStateOf<String?>(null) }
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }

    var moduleSetup by remember { mutableStateOf<com.skofqq.boxy.root.ModuleSetup?>(null) }
    var settingSheet by remember { mutableStateOf<com.skofqq.boxy.ui.components.ModuleSetting?>(null) }
    var applying by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { moduleSetup = runCatching { BoxModule.moduleSetup() }.getOrNull() }
    val restarting = stringResource(R.string.module_applying)
    val applyFailed = stringResource(R.string.module_apply_failed)
    fun applyModule(settings: List<Pair<String, String>>) {
        if (applying) return
        applying = true
        scope.launch {
            if (BoxModule.state().running) Toast.makeText(context, restarting, Toast.LENGTH_SHORT).show()
            val ok = com.skofqq.boxy.service.BoxControl.applySettings(context.applicationContext, settings)
            moduleSetup = runCatching { BoxModule.moduleSetup() }.getOrNull()
            applying = false
            if (!ok) Toast.makeText(context, applyFailed, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        moduleVersion = runCatching { BoxModule.state().moduleVersion }.getOrNull()
        moduleInstalled = moduleVersion != null
        moduleUpdate = runCatching { Updates.moduleUpdate()?.first }.getOrNull()
        appUpdate = runCatching {
            Updates.appReleases()?.firstOrNull { !it.prerelease }?.version?.takeIf { Updates.isNewer(it, versionName) }
        }.getOrNull()
    }

    val themeChoices = buildList {
        add(Choice(ThemeMode.LIGHT, stringResource(R.string.theme_light), stringResource(R.string.theme_light_desc)))
        add(Choice(ThemeMode.DARK, stringResource(R.string.theme_dark), stringResource(R.string.theme_dark_desc)))
        add(Choice(ThemeMode.SYSTEM, stringResource(R.string.theme_system), stringResource(R.string.theme_system_desc)))
        if (Build.VERSION.SDK_INT >= 31) {
            add(Choice(ThemeMode.MATERIAL, stringResource(R.string.theme_material), stringResource(R.string.theme_material_desc)))
        }
    }
    val systemLanguage = stringResource(R.string.lang_system)
    val languageChoices = AppLanguage.entries.map { Choice(it, it.nativeName ?: systemLanguage, leading = it.flag) }
    val sourceChoices = listOf(
        Choice(SubscriptionSource.URL, stringResource(R.string.settings_url_mode), stringResource(R.string.settings_url_mode_desc)),
        Choice(SubscriptionSource.PROVIDERS, stringResource(R.string.settings_providers_mode), stringResource(R.string.settings_providers_mode_desc)),
        Choice(SubscriptionSource.CORE_API, stringResource(R.string.settings_core_api_mode), stringResource(R.string.settings_core_api_mode_desc)),
    )
    val barState = { opaque: Boolean -> context.getString(if (opaque) R.string.settings_bars_opaque else R.string.settings_bars_transparent) }

    PinnedLazyPage(contentPadding, header = {
PageHeader(stringResource(R.string.settings_title), stringResource(R.string.settings_subtitle))
}, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionCard(stringResource(R.string.settings_module), stringResource(R.string.settings_module_sub)) {
                val m = moduleSetup
                val dash = stringResource(R.string.common_dash)
                SettingsRow(BoxyIcons.Core, stringResource(R.string.sheet_core_title), if (applying) restarting else m?.core?.title ?: dash) {
                    if (!applying) settingSheet = com.skofqq.boxy.ui.components.ModuleSetting.CORE
                }
                SettingsRow(
                    BoxyIcons.NetworkMode,
                    stringResource(R.string.sheet_mode_title),
                    m?.mode?.let { com.skofqq.boxy.ui.components.modeTitle(it) + " · " + com.skofqq.boxy.ui.components.modeDescription(it) } ?: dash,
                ) {
                    if (!applying) settingSheet = com.skofqq.boxy.ui.components.ModuleSetting.MODE
                }
                SettingsRow(
                    BoxyIcons.IpAddress,
                    stringResource(R.string.sheet_ipv6_title),
                    m?.ipv6?.let { stringResource(if (it) R.string.common_on else R.string.common_off) } ?: dash,
                    showDivider = false,
                ) {
                    if (!applying) settingSheet = com.skofqq.boxy.ui.components.ModuleSetting.IPV6
                }
            }
        }
        item {
            SectionCard(stringResource(R.string.settings_appearance), stringResource(R.string.settings_appearance_sub)) {
                SettingsRow(BoxyIcons.Palette, stringResource(R.string.settings_theme), themeChoices.firstOrNull { it.value == prefs.themeMode }?.title) { themeDialog = true }
                SettingsRow(BoxyIcons.Translate, stringResource(R.string.settings_language), languageChoices.first { it.value == prefs.language }.title) { languageDialog = true }
                SettingsRow(
                    BoxyIcons.Web,
                    stringResource(R.string.settings_system_bars),
                    stringResource(R.string.settings_bars_summary, barState(prefs.opaqueStatusBar), barState(prefs.opaqueNavBar)),
                ) { barsDialog = true }
                SettingsRow(BoxyIcons.Tune, stringResource(R.string.settings_appearance_more), stringResource(R.string.settings_appearance_more_sub), showDivider = false) {
                    push(ROUTE_APPEARANCE)
                }
            }
        }
        item {
            SectionCard(stringResource(R.string.settings_navigation), stringResource(R.string.settings_navigation_sub)) {
                SwitchRow(BoxyIcons.Apps, stringResource(R.string.tab_apps), stringResource(R.string.nav_apps_desc), checked = prefs.navExtra == NavExtra.APPS) {
                    prefs.setNavPage(NavExtra.APPS, it)
                }
                SwitchRow(
                    BoxyIcons.Description,
                    stringResource(R.string.tab_logs),
                    stringResource(R.string.nav_logs_desc),
                    checked = prefs.navExtra == NavExtra.LOGS,
                    showDivider = false,
                ) { prefs.setNavPage(NavExtra.LOGS, it) }
            }
        }
        item {
            SectionCard(stringResource(R.string.settings_net_speed), stringResource(R.string.settings_net_speed_sub)) {
                SwitchRow(BoxyIcons.Speed, stringResource(R.string.settings_use_clash_api), stringResource(R.string.settings_use_clash_api_sub), prefs.useClashApi) {
                    prefs.updateUseClashApi(it)
                }
                SettingsRow(
                    BoxyIcons.FilterList,
                    stringResource(R.string.settings_filter_chains),
                    prefs.filterChains.ifBlank { stringResource(R.string.settings_filter_chains_sub) },
                    showDivider = false,
                ) { chainsDialog = true }
            }
        }
        item {
            SectionCard(stringResource(R.string.settings_subscription), stringResource(R.string.settings_subscription_sub)) {
                SettingsRow(
                    BoxyIcons.Subscriptions,
                    stringResource(R.string.settings_data_source),
                    sourceChoices.first { it.value == prefs.subscriptionSource }.title,
                ) { sourceDialog = true }
                SettingsRow(BoxyIcons.Router, stringResource(R.string.settings_latency_targets), stringResource(R.string.settings_latency_targets_sub), showDivider = false) {
                    push(ROUTE_LATENCY)
                }
            }
        }
        item {
            SectionCard(stringResource(R.string.settings_misc), stringResource(R.string.settings_misc_sub)) {
                SwitchRow(BoxyIcons.Schedule, stringResource(R.string.settings_autostart), stringResource(R.string.settings_autostart_sub), autostart == true, enabled = autostart != null) { on ->
                    scope.launch { if (BoxModule.setAutostart(on)) autostart = on }
                }
                SwitchRow(BoxyIcons.Dashboard, stringResource(R.string.settings_open_panel), stringResource(R.string.settings_open_panel_sub), prefs.openPanelOnLaunch) {
                    prefs.updateOpenPanelOnLaunch(it)
                }
                SettingsRow(BoxyIcons.Notifications, stringResource(R.string.settings_notifications), stringResource(R.string.settings_notifications_sub)) {
                    push(ROUTE_NOTIFICATIONS)
                }
                SettingsRow(
                    BoxyIcons.Link,
                    stringResource(R.string.settings_mirror),
                    prefs.githubMirror.ifBlank { stringResource(R.string.mirror_direct) }.removePrefix("https://"),
                ) { mirrorSheet = true }
                SettingsRow(BoxyIcons.Backup, stringResource(R.string.settings_backup), stringResource(R.string.settings_backup_sub), showDivider = false) {
                    push(ROUTE_BACKUP)
                }
            }
        }
        item {
            SectionCard(stringResource(R.string.settings_about), stringResource(R.string.settings_about_sub)) {
                SettingsRow(
                    BoxyIcons.Info,
                    stringResource(R.string.settings_version),
                    if (appUpdate != null) stringResource(R.string.settings_update_available_value, versionName, appUpdate!!) else versionName,
                ) { infoSheet = true }
                SettingsRow(
                    BoxyIcons.Tune,
                    stringResource(R.string.settings_module_version),
                    when {
                        !moduleInstalled -> stringResource(R.string.settings_module_not_installed)
                        moduleUpdate != null -> stringResource(R.string.settings_update_available_value, moduleVersion ?: "?", moduleUpdate!!.version)
                        else -> moduleVersion ?: stringResource(R.string.common_dash)
                    },
                ) { moduleSheet = true }
                SettingsRow(BoxyIcons.Code, stringResource(R.string.settings_licenses), stringResource(R.string.settings_licenses_sub), showDivider = false) {
                    push(ROUTE_LICENSES)
                }
            }
        }
    }

    settingSheet?.let { target ->
        com.skofqq.boxy.ui.components.ModuleSettingSheet(target, moduleSetup, onApply = ::applyModule) { settingSheet = null }
    }
    if (themeDialog) {
        ChoiceDialog(stringResource(R.string.settings_theme), themeChoices, prefs.themeMode, onSelect = {
            prefs.updateTheme(it)
            themeDialog = false
        }, onDismiss = { themeDialog = false })
    }
    if (languageDialog) {
        ChoiceDialog(stringResource(R.string.settings_language), languageChoices, prefs.language, onSelect = {
            languageDialog = false
            if (it != prefs.language) {
                prefs.updateLanguage(it)
                (context as? Activity)?.recreate()
            }
        }, onDismiss = { languageDialog = false })
    }
    if (sourceDialog) {
        ChoiceDialog(stringResource(R.string.settings_data_source), sourceChoices, prefs.subscriptionSource, onSelect = {
            prefs.updateSubscriptionSource(it)
            sourceDialog = false
        }, onDismiss = { sourceDialog = false })
    }
    if (barsDialog) SystemBarsSheet(prefs) { barsDialog = false }
    if (chainsDialog) {
        InputDialog(
            stringResource(R.string.settings_filter_chains),
            listOf("DIRECT,REJECT" to prefs.filterChains),
            message = stringResource(R.string.settings_filter_chains_sub),
            onConfirm = { v -> prefs.updateFilterChains(v[0]); chainsDialog = false },
            onDismiss = { chainsDialog = false },
        )
    }
    if (infoSheet) AppInfoSheet(versionName, moduleVersion) { infoSheet = false }
    if (mirrorSheet) MirrorSheet(prefs) { mirrorSheet = false }
    if (moduleSheet) ModuleUpdateSheet(moduleVersion, moduleInstalled) { moduleSheet = false }
}

package com.skofqq.boxy.ui.apps

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.skofqq.boxy.R
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.ui.components.BoxySheet
import com.skofqq.boxy.ui.components.HeaderAction
import com.skofqq.boxy.ui.components.SheetButtons
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import com.skofqq.boxy.ui.components.withTap
import com.skofqq.boxy.ui.components.withTap1
import com.skofqq.boxy.ui.components.GlassMenu
import com.skofqq.boxy.ui.components.GlassMenuItem

@Composable
fun AppsScreen(contentPadding: PaddingValues, header: @Composable () -> Unit = {}) {
    val vm: AppsViewModel = viewModel()
    val context = LocalContext.current
    var searching by remember { mutableStateOf(false) }
    var filterSheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var smartDialog by remember { mutableStateOf(false) }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.message = null
        }
    }

    val visible = vm.visible
    val selectedApps = visible.filter { it.key in vm.selected }
    val others = visible.filter { it.key !in vm.selected }
    val modeName = stringResource(if (vm.mode == ProxyMode.WHITELIST) R.string.apps_whitelist else R.string.apps_blacklist)
    val summary = if (vm.coreRouting) {
        stringResource(R.string.apps_summary_core, visible.size)
    } else {
        stringResource(R.string.apps_summary, visible.size, modeName, vm.selected.size)
    }

    Box(Modifier.fillMaxSize()) {
        PinnedLazyPage(contentPadding, header = {
header()
Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.apps_title), style = MaterialTheme.typography.headlineLarge, color = Boxy.colors.text)
                        Spacer(Modifier.height(6.dp))
                        Text(summary, style = MaterialTheme.typography.bodyLarge, color = Boxy.colors.text2, maxLines = 2)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HeaderAction(BoxyIcons.Search, stringResource(R.string.action_search)) {
                            searching = !searching
                            if (!searching) vm.query = ""
                        }
                        HeaderAction(BoxyIcons.FilterList, stringResource(R.string.apps_sort_filter)) { filterSheet = true }
                        Box {
                            HeaderAction(BoxyIcons.MoreVert, stringResource(R.string.action_more)) { menu = true }
                            GlassMenu(menu, { menu = false }) {
                                GlassMenuItem(stringResource(R.string.action_refresh), { menu = false; vm.refresh() }, BoxyIcons.Refresh)
                                GlassMenuItem(stringResource(R.string.apps_select_all), { menu = false; vm.selectAll() }, BoxyIcons.SelectAll)
                                GlassMenuItem(stringResource(R.string.apps_invert), { menu = false; vm.invert() }, BoxyIcons.Invert)
                                GlassMenuItem(stringResource(R.string.apps_smart_select), { menu = false; smartDialog = true }, BoxyIcons.AutoAwesome)
                            }
                        }
                    }
                }
ModeSwitch(vm.mode, enabled = !vm.coreRouting, onChange = vm::changeMode)
}) {
            if (searching) {
                item { SearchField(vm.query) { vm.query = it } }
            }
            if (vm.loading) {
                item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            } else if (visible.isEmpty()) {
                item {
                    Text(
                        stringResource(if (vm.query.isBlank()) R.string.apps_empty else R.string.apps_no_results),
                        Modifier.fillMaxWidth().padding(40.dp),
                        color = Boxy.colors.text2,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                if (selectedApps.isNotEmpty()) {
                    item { GroupTitle(stringResource(R.string.apps_selected_count, selectedApps.size)) }
                    items(selectedApps, key = { "s" + it.key }) { app -> AppRow(vm, app, true) }
                }
                item { GroupTitle(stringResource(R.string.apps_others, others.size)) }
                items(others, key = { "o" + it.key }) { app -> AppRow(vm, app, false) }
            }
        }

        AnimatedVisibility(
            visible = vm.dirty,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(contentPadding).padding(bottom = 12.dp),
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Boxy.colors.accent).clickable(onClick = vm::save)
                    .padding(horizontal = 26.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(BoxyIcons.Save, null, Modifier.size(20.dp), tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_save), color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (filterSheet) FilterSheet(vm) { filterSheet = false }

    if (smartDialog) {
        AlertDialog(
            onDismissRequest = { smartDialog = false },
            title = { Text(stringResource(R.string.apps_smart_select)) },
            text = { Text(stringResource(R.string.apps_smart_body)) },
            confirmButton = {
                TextButton(onClick = withTap { smartDialog = false; vm.smartSelect(replace = false) }) { Text(stringResource(R.string.apps_smart_merge)) }
            },
            dismissButton = {
                TextButton(onClick = withTap { smartDialog = false; vm.smartSelect(replace = true) }) { Text(stringResource(R.string.apps_smart_replace)) }
            },
            containerColor = Boxy.colors.card,
        )
    }

    if (vm.showRestartTip) {
        AlertDialog(
            onDismissRequest = { vm.showRestartTip = false },
            title = { Text(stringResource(R.string.apps_restart_title)) },
            text = { Text(stringResource(if (vm.dirty) R.string.apps_restart_body_pending else R.string.apps_restart_body)) },
            confirmButton = {
                TextButton(onClick = withTap { vm.showRestartTip = false; vm.restartService() }) { Text(stringResource(R.string.action_restart)) }
            },
            dismissButton = { TextButton(onClick = withTap { vm.showRestartTip = false }) { Text(stringResource(R.string.action_later)) } },
            containerColor = Boxy.colors.card,
        )
    }
}


@Composable
private fun ModeSwitch(mode: ProxyMode, enabled: Boolean, onChange: (ProxyMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(50)).background(Boxy.colors.card).padding(4.dp),
    ) {
        listOf(ProxyMode.BLACKLIST to R.string.apps_blacklist, ProxyMode.WHITELIST to R.string.apps_whitelist).forEach { (m, label) ->
            val active = m == mode
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(50))
                    .background(if (active) Boxy.colors.accent.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable(enabled = enabled) { onChange(m) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(label),
                    color = (if (active) Boxy.colors.accent else Boxy.colors.text2).copy(alpha = if (enabled) 1f else 0.5f),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(50)).background(Boxy.colors.card)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(BoxyIcons.Search, null, Modifier.size(20.dp), tint = Boxy.colors.text2)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(stringResource(R.string.apps_search_hint), color = Boxy.colors.text2)
            BasicTextField(
                value,
                onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Boxy.colors.text),
                cursorBrush = SolidColor(Boxy.colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(BoxyIcons.Close, null, Modifier.size(20.dp).clickable { onChange("") }, tint = Boxy.colors.text2)
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleMedium,
        color = Boxy.colors.text2,
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AppRow(vm: AppsViewModel, app: AppEntry, checked: Boolean) {
    val icon = remember(app.key) { vm.icon(app) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (checked) Boxy.colors.accent.copy(alpha = 0.08f) else Boxy.colors.card)
            .clickable { vm.toggle(app) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(icon, null, Modifier.size(40.dp))
        } else {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Boxy.colors.surface2), contentAlignment = Alignment.Center) {
                Text(app.label.take(1).uppercase(), color = Boxy.colors.text2, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (app.userId != 0 || app.system || !app.network) {
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (app.userId != 0) {
                        val label = when {
                            vm.isWork(app.userId) -> stringResource(R.string.apps_work_profile)
                            app.userId == 999 -> stringResource(R.string.apps_clone)
                            else -> stringResource(R.string.apps_user_space_n, vm.userName(app.userId))
                        }
                        Tag(label, Tints.purple.fg)
                    }
                    if (app.system) Tag(stringResource(R.string.apps_filter_system), Tints.amber.fg)
                    if (!app.network) Tag(stringResource(R.string.apps_no_network), Tints.gray.fg)
                }
            }
        }
        Checkbox(checked, withTap1<Boolean> { vm.toggle(app) }, colors = CheckboxDefaults.colors(checkedColor = Boxy.colors.accent))
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
    )
}

@Composable
private fun FilterSheet(vm: AppsViewModel, onDismiss: () -> Unit) {
    BoxySheet(stringResource(R.string.apps_sort_filter), null, onDismiss) {
        ChipGroup(
            stringResource(R.string.apps_sort_by),
            listOf(
                AppSort.NAME_ASC to R.string.apps_sort_name_asc,
                AppSort.NAME_DESC to R.string.apps_sort_name_desc,
                AppSort.INSTALL_ASC to R.string.apps_sort_install_asc,
                AppSort.INSTALL_DESC to R.string.apps_sort_install_desc,
            ),
            vm.sort,
        ) { vm.sort = it }
        ChipGroup(
            stringResource(R.string.apps_app_type),
            listOf(AppTypeFilter.ALL to R.string.apps_filter_all, AppTypeFilter.USER to R.string.apps_filter_user, AppTypeFilter.SYSTEM to R.string.apps_filter_system),
            vm.typeFilter,
        ) { vm.typeFilter = it }
        ChipGroup(
            stringResource(R.string.apps_network_permission),
            listOf(NetworkFilter.ALL to R.string.apps_filter_all, NetworkFilter.ONLY to R.string.apps_filter_only_network, NetworkFilter.EXCLUDE to R.string.apps_filter_exclude_network),
            vm.networkFilter,
        ) { vm.networkFilter = it }
        ChipGroup(
            stringResource(R.string.apps_user_space),
            listOf(
                UserFilter.ALL to R.string.apps_filter_all_users,
                UserFilter.MAIN to R.string.apps_filter_main_only,
                UserFilter.OTHER to R.string.apps_filter_other_users,
                UserFilter.WORK_CLONE to R.string.apps_filter_work_clone,
            ),
            vm.userFilter,
        ) { vm.userFilter = it }
        SheetButtons(stringResource(R.string.action_done), onDismiss)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipGroup(title: String, options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text2, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Text(
                stringResource(label),
                Modifier.clip(RoundedCornerShape(50))
                    .background(if (active) Boxy.colors.accent.copy(alpha = 0.15f) else Boxy.colors.card)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                color = if (active) Boxy.colors.accent else Boxy.colors.text,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

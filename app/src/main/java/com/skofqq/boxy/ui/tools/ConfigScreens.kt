package com.skofqq.boxy.ui.tools

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.R
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.CheckResult
import com.skofqq.boxy.root.ConfigCheck
import com.skofqq.boxy.ui.components.ConfigErrorDialog
import com.skofqq.boxy.root.RootFile
import com.skofqq.boxy.root.RootFiles
import com.skofqq.boxy.ui.components.Badge
import com.skofqq.boxy.ui.components.BoxyTextField
import com.skofqq.boxy.ui.components.PageSearchField
import com.skofqq.boxy.ui.components.ConfirmDialog
import com.skofqq.boxy.ui.components.HeaderAction
import com.skofqq.boxy.ui.components.InputDialog
import com.skofqq.boxy.ui.components.SectionCard
import com.skofqq.boxy.ui.components.SubPageHeader
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import com.skofqq.boxy.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import com.skofqq.boxy.ui.components.GlassMenu
import com.skofqq.boxy.ui.components.GlassMenuItem

/** Config folder and settings key of each core. */
fun coreConfig(core: String): Pair<String, String> = when (core) {
    "sing-box" -> "sing-box" to "name_sing_config"
    "xray" -> "xray" to "name_xray_config"
    "v2fly" -> "v2fly" to "name_v2fly_config"
    "hysteria" -> "hysteria" to "name_hysteria_config"
    else -> "clash" to "name_clash_config"
}

private val CONFIG_EXTENSIONS = setOf("yaml", "yml", "json")

/** Choose the active config of the current core. */
@Composable
fun ConfigListScreen(contentPadding: PaddingValues, onBack: () -> Unit, onEdit: (String) -> Unit, onManage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var core by remember { mutableStateOf<String?>(null) }
    var active by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<RootFile>?>(null) }
    var folders by remember { mutableStateOf<List<RootFile>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    var createDialog by remember { mutableStateOf(false) }
    var downloadDialog by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf<String?>(null) }
    var checkError by remember { mutableStateOf<Pair<RootFile, String>?>(null) }

    fun select(f: RootFile) {
        scope.launch {
            val c = core ?: return@launch
            if (BoxModule.writeSetting(coreConfig(c).second, f.name)) {
                active = f.name
                Toast.makeText(context, context.getString(R.string.config_selected, f.name), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Same test box.service runs before start, so a broken file is caught before it is made active.
    fun checkAndSelect(f: RootFile) {
        val c = core ?: return
        checking = f.name
        scope.launch {
            val r = ConfigCheck.check(coreConfig(c).first, f.path)
            checking = null
            if (r is CheckResult.Failed) checkError = f to r.output else select(f)
        }
    }

    LaunchedEffect(reload) {
        val c = BoxModule.readSetting("bin_name") ?: "clash"
        core = c
        val (dir, key) = coreConfig(c)
        active = BoxModule.readSetting(key)
        val all = RootFiles.list("${BoxModule.BOX_DIR}/$dir")
        folders = all.filter { it.isDir && !it.name.startsWith(".") }
        files = all.filter { !it.isDir && !it.name.startsWith(".") && it.extension in CONFIG_EXTENSIONS }
    }
    val dir = "${BoxModule.BOX_DIR}/${coreConfig(core ?: "clash").first}"

    PinnedLazyPage(contentPadding, header = {
SubPageHeader(
                stringResource(R.string.tools_config),
                files?.let { stringResource(R.string.config_files_active_summary, it.size, active ?: "—") },
                onBack,
            ) {
                HeaderAction(BoxyIcons.Download, stringResource(R.string.config_download)) { downloadDialog = true }
                HeaderAction(BoxyIcons.Folder, stringResource(R.string.tools_row_manage)) { onManage(dir) }
            }
}, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            // One list like BFR: the core's folders first, then its config files.
            SectionCard(null) {
                val list = files
                when {
                    list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    list.isEmpty() && folders.isEmpty() -> Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                        Text(stringResource(R.string.config_no_files), color = Boxy.colors.text2)
                        Text(
                            stringResource(R.string.config_create_file),
                            Modifier.padding(top = 8.dp).clip(RoundedCornerShape(50)).clickable { createDialog = true }.padding(vertical = 6.dp),
                            color = Boxy.colors.accent,
                        )
                    }
                    else -> {
                        val rows = folders.size + list.size
                        var index = 0
                        folders.forEach { f ->
                            index++
                            ConfigRow(
                                icon = BoxyIcons.Folder,
                                iconTint = Boxy.colors.text,
                                iconBg = Boxy.colors.surface2,
                                title = f.name,
                                subtitle = stringResource(R.string.config_folder),
                                badge = null,
                                divider = index < rows,
                                onClick = { onManage(f.path) },
                                onLongClick = { onManage(f.path) },
                            )
                        }
                        list.forEach { f ->
                            index++
                            val isActive = f.name == active
                            ConfigRow(
                                icon = BoxyIcons.File,
                                iconTint = Tints.green.fg,
                                iconBg = Tints.green.bg,
                                title = f.name,
                                subtitle = when {
                                    checking == f.name -> stringResource(R.string.check_running)
                                    isActive -> stringResource(R.string.config_active)
                                    else -> stringResource(R.string.config_tap_select)
                                },
                                badge = if (isActive) stringResource(R.string.config_badge_active) else null,
                                divider = index < rows,
                                onClick = {
                                    if (isActive) {
                                        onEdit(f.path)
                                    } else if (checking == null) {
                                        checkAndSelect(f)
                                    }
                                },
                                onLongClick = { onEdit(f.path) },
                            )
                        }
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.config_hint),
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Boxy.colors.text2,
            )
        }
    }

    if (createDialog) {
        val invalid = stringResource(R.string.config_name_invalid)
        InputDialog(
            stringResource(R.string.config_create_file),
            listOf(stringResource(R.string.config_placeholder_name) to ""),
            validate = { if (it[0].isBlank() || it[0].contains('/')) invalid else null },
            onConfirm = { v ->
                createDialog = false
                scope.launch {
                    RootFiles.createFile("$dir/${v[0]}")
                    reload++
                }
            },
            onDismiss = { createDialog = false },
        )
    }
    if (downloadDialog) {
        DownloadDialog(dir, onDone = { downloadDialog = false; reload++ }, onDismiss = { downloadDialog = false })
    }
    checkError?.let { (f, output) ->
        ConfigErrorDialog(
            output,
            stringResource(R.string.check_select_anyway),
            onProceed = { checkError = null; select(f) },
            onDismiss = { checkError = null },
            onEdit = { checkError = null; onEdit(f.path) },
        )
    }
}

/** Row of the config list: coloured icon tile, title, subtitle, optional ACTIVE badge and a chevron. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ConfigRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    iconBg: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    badge: String?,
    divider: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(iconBg), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(22.dp), tint = iconTint)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2, maxLines = 1)
            }
            if (badge != null) {
                Text(
                    badge,
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Boxy.colors.accent).padding(horizontal = 12.dp, vertical = 5.dp),
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.width(6.dp))
            }
            Icon(BoxyIcons.ChevronRight, null, Modifier.size(20.dp), tint = Boxy.colors.text2.copy(alpha = 0.75f))
        }
        if (divider) androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 76.dp, end = 18.dp), color = Boxy.colors.outline)
    }
}

/** Browse /data/adb/box: folders, files, search, create, rename, delete, download. */
@Composable
fun FileManagerScreen(contentPadding: PaddingValues, dir: String, onBack: () -> Unit, onOpenDir: (String) -> Unit, onEdit: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<RootFile>?>(null) }
    var reload by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<RootFile>?>(null) }
    var createMenu by remember { mutableStateOf(false) }
    var createKind by remember { mutableStateOf<Boolean?>(null) } // true = folder, false = file
    var downloadDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RootFile?>(null) }
    var deleteTarget by remember { mutableStateOf<RootFile?>(null) }

    LaunchedEffect(dir, reload) { files = RootFiles.list(dir) }
    LaunchedEffect(query) {
        results = if (query.length >= 2) RootFiles.search(dir, query) else null
    }

    val shown = results ?: files?.filter { query.isBlank() || it.name.contains(query, true) }
    PinnedLazyPage(contentPadding, header = {
SubPageHeader(
                if (dir == BoxModule.BOX_DIR) stringResource(R.string.config_files_folders) else dir.substringAfterLast('/'),
                dir,
                onBack,
            ) {
                HeaderAction(BoxyIcons.Search, stringResource(R.string.action_search)) {
                    searching = !searching
                    if (!searching) query = ""
                }
                Box {
                    HeaderAction(BoxyIcons.Add, stringResource(R.string.config_menu_create)) { createMenu = true }
                    GlassMenu(createMenu, { createMenu = false }) {
                        GlassMenuItem(stringResource(R.string.config_new_file), { createMenu = false; createKind = false }, BoxyIcons.File)
                        GlassMenuItem(stringResource(R.string.config_new_folder), { createMenu = false; createKind = true }, BoxyIcons.Folder)
                        GlassMenuItem(stringResource(R.string.config_download), { createMenu = false; downloadDialog = true }, BoxyIcons.Download)
                        GlassMenuItem(stringResource(R.string.action_refresh), { createMenu = false; reload++ }, BoxyIcons.Refresh)
                    }
                }
            }
}, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (searching) {
            item {
                PageSearchField(query, { query = it }, stringResource(R.string.config_search_hint), Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
        }
        item {
            SectionCard(null) {
                val list = shown
                if (dir != BoxModule.BOX_DIR && results == null) {
                    FileRow(BoxyIcons.Folder, "..", stringResource(R.string.config_parent_dir), null, onClick = onBack)
                }
                when {
                    list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    list.isEmpty() -> Text(
                        stringResource(if (query.isBlank()) R.string.config_dir_empty else R.string.config_no_match),
                        Modifier.padding(18.dp),
                        color = Boxy.colors.text2,
                    )
                    else -> list.forEach { f ->
                        val sub = if (f.isDir) Format.dateTime(f.modified) else "${Format.bytes(context, f.size)} · ${Format.dateTime(f.modified)}"
                        FileRow(
                            if (f.isDir) BoxyIcons.Folder else BoxyIcons.File,
                            f.name,
                            if (results != null) f.path.removePrefix("$dir/") else sub,
                            menu = { close ->
                                if (!f.isDir) GlassMenuItem(stringResource(R.string.config_edit), { close(); onEdit(f.path) }, BoxyIcons.Edit)
                                GlassMenuItem(stringResource(R.string.config_rename), { close(); renameTarget = f }, BoxyIcons.Rename)
                                GlassMenuItem(stringResource(R.string.action_delete), { close(); deleteTarget = f }, BoxyIcons.Delete, destructive = true)
                            },
                            onClick = { if (f.isDir) onOpenDir(f.path) else onEdit(f.path) },
                        )
                    }
                }
            }
        }
    }

    createKind?.let { folder ->
        val invalid = stringResource(R.string.config_name_invalid)
        InputDialog(
            stringResource(if (folder) R.string.config_create_folder else R.string.config_create_file),
            listOf(stringResource(R.string.config_name) to ""),
            message = stringResource(if (folder) R.string.config_create_folder_helper else R.string.config_create_file_helper),
            validate = { if (it[0].isBlank() || it[0].contains('/')) invalid else null },
            onConfirm = { v ->
                createKind = null
                scope.launch {
                    val ok = if (folder) RootFiles.createDir("$dir/${v[0]}") else RootFiles.createFile("$dir/${v[0]}")
                    if (!ok) Toast.makeText(context, R.string.op_failed, Toast.LENGTH_SHORT).show()
                    reload++
                }
            },
            onDismiss = { createKind = null },
        )
    }
    renameTarget?.let { f ->
        val invalid = stringResource(R.string.config_name_invalid)
        InputDialog(
            stringResource(R.string.config_rename),
            listOf(stringResource(R.string.config_new_name) to f.name),
            validate = { if (it[0].isBlank() || it[0].contains('/')) invalid else null },
            onConfirm = { v ->
                renameTarget = null
                scope.launch {
                    if (!RootFiles.rename(f.path, v[0])) Toast.makeText(context, R.string.op_failed, Toast.LENGTH_SHORT).show()
                    reload++
                }
            },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { f ->
        ConfirmDialog(
            stringResource(R.string.config_delete_title, f.name),
            stringResource(if (f.isDir) R.string.config_delete_folder_warning else R.string.config_delete_file_warning),
            stringResource(R.string.action_delete),
            danger = true,
            onConfirm = {
                deleteTarget = null
                scope.launch {
                    RootFiles.delete(f.path)
                    reload++
                }
            },
            onDismiss = { deleteTarget = null },
        )
    }
    if (downloadDialog) {
        DownloadDialog(dir, onDone = { downloadDialog = false; reload++ }, onDismiss = { downloadDialog = false })
    }
}

@Composable
private fun FileRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    subtitle: String?,
    menu: (@Composable (close: () -> Unit) -> Unit)? = null,
    onClick: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Boxy.colors.surface2), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = if (icon == BoxyIcons.Folder) Tints.amber.fg else Boxy.colors.text2)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (menu != null) {
            Box {
                Icon(BoxyIcons.MoreVert, stringResource(R.string.action_more), Modifier.size(40.dp).clip(RoundedCornerShape(50)).clickable { open = true }.padding(9.dp), tint = Boxy.colors.text2)
                GlassMenu(open, { open = false }) { menu { open = false } }
            }
        }
    }
}

/** Downloads a file by URL (in the app) and stores it in [dir] with root. */
@Composable
fun DownloadDialog(dir: String, onDone: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val invalidUrl = stringResource(R.string.config_url_invalid)
    InputDialog(
        stringResource(R.string.config_download),
        listOf(stringResource(R.string.config_url_example) to "", stringResource(R.string.config_placeholder_name) to ""),
        confirm = stringResource(R.string.config_menu_download),
        message = stringResource(R.string.config_download_hint),
        validate = { v -> if (!v[0].startsWith("http://") && !v[0].startsWith("https://")) invalidUrl else null },
        onConfirm = { v ->
            val url = v[0]
            val name = v[1].ifBlank { url.substringAfterLast('/').substringBefore('?').ifBlank { "config.yaml" } }
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        val c = URL(url).openConnection() as HttpURLConnection
                        c.connectTimeout = 15000
                        c.readTimeout = 30000
                        c.setRequestProperty("User-Agent", "clash.meta")
                        try {
                            if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null
                        } finally {
                            c.disconnect()
                        }
                    }.getOrNull()
                }
                val ok = text != null && RootFiles.write("$dir/$name", text)
                Toast.makeText(context, if (ok) context.getString(R.string.config_download_ok, name) else context.getString(R.string.config_download_failed), Toast.LENGTH_SHORT).show()
                onDone()
            }
        },
        onDismiss = onDismiss,
    )
}

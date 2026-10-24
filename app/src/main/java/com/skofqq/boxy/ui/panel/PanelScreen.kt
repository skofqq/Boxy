package com.skofqq.boxy.ui.panel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.skofqq.boxy.ui.components.backdropSource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.skofqq.boxy.R
import com.skofqq.boxy.net.Net
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.ui.components.BoxySheet
import com.skofqq.boxy.ui.components.ConfirmDialog
import com.skofqq.boxy.ui.components.HeaderAction
import com.skofqq.boxy.ui.components.InputDialog
import com.skofqq.boxy.ui.components.SheetButtons
import com.skofqq.boxy.ui.components.SubPageHeader
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import org.json.JSONArray
import org.json.JSONObject
import com.skofqq.boxy.ui.components.GlassMenu
import com.skofqq.boxy.ui.components.GlassMenuItem

enum class PanelKind { CORE, SUBSTORE }

data class WebPanel(val id: String, val name: String, val url: String)

private const val LOCAL_ID = "local"

/** Boxy's own proxy page, offered in the panel list next to the web dashboards. */
private const val BOXY_ID = "boxy"

/** User panels are kept per kind in shared preferences (same keys BFR used). */
private class PanelStore(context: Context, kind: PanelKind) {
    private val sp = context.getSharedPreferences("boxy", Context.MODE_PRIVATE)
    private val listKey = if (kind == PanelKind.CORE) "panel_list_v1" else "substore_panel_list_v1"
    private val selectedKey = if (kind == PanelKind.CORE) "panel_selected_id_v1" else "substore_selected_id_v1"

    fun custom(): List<WebPanel> = runCatching {
        val arr = JSONArray(sp.getString(listKey, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> WebPanel(o.getString("id"), o.getString("name"), o.getString("url")) } }
    }.getOrDefault(emptyList())

    fun saveCustom(list: List<WebPanel>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url)) }
        sp.edit().putString(listKey, arr.toString()).apply()
    }

    var selected: String
        get() = sp.getString(selectedKey, LOCAL_ID) ?: LOCAL_ID
        set(value) = sp.edit().putString(selectedKey, value).apply()
}

/** Local URL of the core dashboard (external-controller + /ui/) or of the SubStore module. */
private suspend fun localUrl(kind: PanelKind): String? = when (kind) {
    PanelKind.CORE -> Net.clashApi(BoxModule.readSetting("bin_name"))?.let { "${it.base}/ui/" }
    PanelKind.SUBSTORE -> {
        val cfg = "/data/adb/sub_store/scripts/sub_store.config"
        val (_, out) = BoxModule.exec("grep -E '^sub_store_(frontend|backend)_port=' $cfg")
        val kv = BoxModule.parseKv(out)
        val front = kv["sub_store_frontend_port"]
        val back = kv["sub_store_backend_port"]
        when {
            front.isNullOrBlank() -> null
            back.isNullOrBlank() -> "http://127.0.0.1:$front/"
            else -> "http://127.0.0.1:$front/?api=http://127.0.0.1:$back"
        }
    }
}

/** Web UI of the running core (metacubexd / zashboard / yacd) or SubStore, plus user panels. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PanelScreen(contentPadding: PaddingValues, header: @Composable () -> Unit, kind: PanelKind = PanelKind.CORE, onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember(kind) { PanelStore(context, kind) }
    var local by remember { mutableStateOf<String?>(null) }
    var localChecked by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(store.custom()) }
    var selectedId by remember { mutableStateOf(store.selected) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var listSheet by remember { mutableStateOf(false) }
    var addDialog by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }
    var dashboardSheet by remember { mutableStateOf(false) }
    val localName = stringResource(if (kind == PanelKind.CORE) R.string.panel_local else R.string.substore_local)

    LaunchedEffect(kind) {
        local = localUrl(kind)
        localChecked = true
    }
    val boxyPanel = if (kind == PanelKind.CORE) WebPanel(BOXY_ID, stringResource(R.string.panel_boxy), stringResource(R.string.panel_boxy_sub)) else null
    val panels = listOfNotNull(local?.let { WebPanel(LOCAL_ID, localName, it) }, boxyPanel) + custom
    val current = panels.firstOrNull { it.id == selectedId } ?: panels.firstOrNull()
    val native = current?.id == BOXY_ID
    var nativeReload by remember { mutableStateOf(0) }

    BackHandler {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else onClose()
    }

    val backdrop = com.skofqq.boxy.ui.components.rememberBackdrop()
    androidx.compose.runtime.CompositionLocalProvider(com.skofqq.boxy.ui.components.LocalBackdrop provides backdrop) {
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            header()
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.padding(top = 8.dp, end = 12.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            if (kind == PanelKind.CORE) {
                HeaderAction(BoxyIcons.Palette, stringResource(R.string.dashboard_title)) { dashboardSheet = true }
            }
            HeaderAction(BoxyIcons.Dashboard, stringResource(R.string.panel_sheet_title)) { listSheet = true }
            HeaderAction(BoxyIcons.Refresh, stringResource(R.string.action_refresh_page)) { if (native) nativeReload++ else webView?.reload() }
            if (!native) Box {
                HeaderAction(BoxyIcons.MoreVert, stringResource(R.string.action_more)) { menu = true }
                GlassMenu(menu, { menu = false }) {
                    GlassMenuItem(stringResource(R.string.panel_open_browser), {
                        menu = false
                        current?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.url))) } }
                    }, BoxyIcons.Launch)
                    GlassMenuItem(stringResource(R.string.web_clear_cache), { menu = false; clearDialog = true }, BoxyIcons.Delete, destructive = true)
                }
            }
            }
        }
        Box(
            Modifier.fillMaxSize().backdropSource(backdrop).then(
                if (native) Modifier else Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp).clip(RoundedCornerShape(20.dp)).background(Boxy.colors.card),
            ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                native -> com.skofqq.boxy.ui.proxies.CorePanel(nativeReload)
                current == null && !localChecked -> CircularProgressIndicator()
                current == null -> Text(
                    stringResource(if (kind == PanelKind.CORE) R.string.panel_unavailable else R.string.substore_unavailable),
                    Modifier.fillMaxWidth().padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Boxy.colors.text2,
                    textAlign = TextAlign.Center,
                )
                else -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            // AndroidView gives the WebView WRAP_CONTENT, and a wrap-content WebView reports a 0 px
                            // viewport height to CSS (vh / dvh / % heights), so full-height dashboards render empty.
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            webViewClient = WebViewClient()
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            loadUrl(current.url)
                            tag = current.url
                            webView = this
                        }
                    },
                    update = { w ->
                        if (w.tag != current.url) {
                            w.tag = current.url
                            w.loadUrl(current.url)
                        }
                    },
                    onRelease = { if (webView === it) webView = null; it.destroy() },
                )
            }
        }
    }

    }

    if (listSheet) {
        BoxySheet(stringResource(R.string.panel_sheet_title), null, { listSheet = false }) {
            panels.forEach { p ->
                val sel = p.id == current?.id
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp))
                        .background(if (sel) Boxy.colors.accent.copy(alpha = 0.14f) else Boxy.colors.card)
                        .clickable {
                            selectedId = p.id
                            store.selected = p.id
                            listSheet = false
                        }
                        .padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text)
                        Text(p.url, style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (sel) Icon(BoxyIcons.Check, null, tint = Boxy.colors.accent)
                    if (p.id != LOCAL_ID && p.id != BOXY_ID) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            BoxyIcons.Delete,
                            stringResource(R.string.action_delete),
                            Modifier.size(40.dp).clip(RoundedCornerShape(50)).clickable {
                                custom = custom.filter { it.id != p.id }
                                store.saveCustom(custom)
                            }.padding(9.dp),
                            tint = Tints.red.fg,
                        )
                    }
                }
            }
            SheetButtons(stringResource(R.string.panel_sheet_add_title), { addDialog = true })
        }
    }
    if (addDialog) {
        val nameRequired = stringResource(R.string.panel_error_name_required)
        val urlInvalid = stringResource(R.string.panel_error_url_invalid)
        InputDialog(
            stringResource(R.string.panel_sheet_add_title),
            listOf(stringResource(R.string.panel_field_name) to "", stringResource(R.string.panel_field_url) to "http://"),
            confirm = stringResource(R.string.action_add),
            validate = { v ->
                when {
                    v[0].isBlank() -> nameRequired
                    !(v[1].startsWith("http://") || v[1].startsWith("https://")) || v[1].length < 10 -> urlInvalid
                    else -> null
                }
            },
            onConfirm = { v ->
                val p = WebPanel(System.currentTimeMillis().toString(), v[0], v[1])
                custom = custom + p
                store.saveCustom(custom)
                selectedId = p.id
                store.selected = p.id
                addDialog = false
                listSheet = false
            },
            onDismiss = { addDialog = false },
        )
    }
    if (dashboardSheet) {
        DashboardSheet(onDismiss = { dashboardSheet = false }, onInstalled = {
            webView?.clearCache(true)
            webView?.reload()
        })
    }
    if (clearDialog) {
        ConfirmDialog(
            stringResource(R.string.web_clear_cache),
            stringResource(R.string.web_clear_cache_message),
            stringResource(R.string.web_clear_confirm),
            danger = true,
            onConfirm = {
                clearDialog = false
                webView?.clearCache(true)
                WebStorage.getInstance().deleteAllData()
                CookieManager.getInstance().removeAllCookies(null)
                webView?.reload()
                Toast.makeText(context, R.string.web_cache_cleared, Toast.LENGTH_SHORT).show()
            },
            onDismiss = { clearDialog = false },
        )
    }
}

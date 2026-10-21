package com.skofqq.boxy.configs

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.skofqq.boxy.BoxyApp
import com.skofqq.boxy.R
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.CheckResult
import com.skofqq.boxy.root.ConfigCheck
import com.skofqq.boxy.root.RootFiles
import com.skofqq.boxy.service.BoxControl
import com.skofqq.boxy.ui.components.Choice
import com.skofqq.boxy.ui.components.ChoiceDialog
import com.skofqq.boxy.ui.components.ConfigErrorDialog
import com.skofqq.boxy.ui.components.GlassAlertDialog
import com.skofqq.boxy.ui.components.GlassStyle
import com.skofqq.boxy.ui.components.LocalGlass
import com.skofqq.boxy.ui.theme.BoxyTheme
import com.skofqq.boxy.ui.tools.coreConfig
import com.skofqq.boxy.util.withAppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Configs of the current core and the active one. */
data class ConfigList(val core: String, val active: String?, val files: List<String>)

/**
 * Switching the active config from outside the app: the Quick Settings tile, the home screen widget and the
 * chooser window they open. Nothing runs in the background; the tile and widget are redrawn when a config
 * or the service changes.
 */
object ConfigSwitch {
    private val EXTENSIONS = setOf("yaml", "yml", "json")

    suspend fun list(): ConfigList {
        val core = BoxModule.readSetting("bin_name") ?: "clash"
        val (dir, key) = coreConfig(core)
        val files = RootFiles.list("${BoxModule.BOX_DIR}/$dir")
            .filter { !it.isDir && !it.name.startsWith(".") && it.extension in EXTENSIONS }
            .map { it.name }
            .sortedBy { it.lowercase() }
        return ConfigList(core, BoxModule.readSetting(key), files)
    }

    fun label(name: String?) = name?.substringBeforeLast('.')

    fun chooserIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        7,
        Intent(context, ConfigChooserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Redraws the config widget and asks the tile to refresh (called whenever BoxControl changes something). */
    fun refreshViews(context: Context) {
        val app = context.applicationContext
        runCatching { TileService.requestListeningState(app, ComponentName(app, ConfigTileService::class.java)) }
        val ids = AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, ConfigWidget::class.java))
        if (ids.isEmpty()) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            ConfigWidget.render(app, ids, runCatching { list() }.getOrNull())
        }
    }
}

/** Quick Settings tile: the active config as its subtitle; a tap opens the chooser. */
class ConfigTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withAppLocale())
    }

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            val list = runCatching { ConfigSwitch.list() }.getOrNull()
            val running = runCatching { BoxModule.state().running }.getOrDefault(false)
            val tile = qsTile ?: return@launch
            tile.label = getString(R.string.config_switch_title)
            tile.subtitle = ConfigSwitch.label(list?.active) ?: getString(R.string.common_dash)
            tile.state = if (list == null) Tile.STATE_UNAVAILABLE else if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(ConfigSwitch.chooserIntent(this))
        } else {
            startActivityAndCollapse(Intent(this, ConfigChooserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Home screen widget (2×1): the active config; a tap opens the chooser. Redrawn only on changes. */
class ConfigWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                render(context, ids, runCatching { ConfigSwitch.list() }.getOrNull())
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun render(context: Context, ids: IntArray, list: ConfigList?) {
            val res = context.withAppLocale()
            val v = RemoteViews(context.packageName, R.layout.widget_config)
            v.setTextViewText(R.id.widget_config_name, ConfigSwitch.label(list?.active) ?: res.getString(R.string.common_dash))
            v.setTextViewText(R.id.widget_config_detail, res.getString(R.string.config_switch_detail, list?.core ?: "—"))
            v.setOnClickPendingIntent(R.id.widget_root, ConfigSwitch.chooserIntent(context))
            AppWidgetManager.getInstance(context).updateAppWidget(ids, v)
        }
    }
}

/** Transparent window with the config list over whatever is on screen (opened by the tile and the widget). */
class ConfigChooserActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withAppLocale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = BoxyApp.instance.prefs
        val glass = GlassStyle(
            enabled = prefs.blurEffects && Build.VERSION.SDK_INT >= 31,
            translucent = prefs.glassTranslucent,
            blur = prefs.blurStrength,
            lens = prefs.lensStrength,
            sheetBlur = prefs.sheetBlur,
            elements = prefs.glassElements,
        )
        setContent {
            BoxyTheme(prefs.themeMode, prefs.trueBlack) {
                CompositionLocalProvider(LocalGlass provides glass, com.skofqq.boxy.ui.components.LocalOutsideApp provides true) { Chooser { finish() } }
            }
        }
    }
}

@Composable
private fun Chooser(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<ConfigList?>(null) }
    var applying by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(Unit) { list = runCatching { ConfigSwitch.list() }.getOrNull() ?: ConfigList("clash", null, emptyList()) }

    fun apply(name: String) {
        applying = name
        scope.launch {
            val ok = BoxControl.setConfig(context.applicationContext, name)
            Toast.makeText(context, if (ok) context.getString(R.string.config_selected, name) else context.getString(R.string.op_failed), Toast.LENGTH_SHORT).show()
            onDone()
        }
    }

    fun check(name: String) {
        val l = list ?: return
        applying = name
        scope.launch {
            val r = ConfigCheck.check(coreConfig(l.core).first, "${BoxModule.BOX_DIR}/${coreConfig(l.core).first}/$name")
            if (r is CheckResult.Failed) {
                applying = null
                failed = name to r.output
            } else {
                apply(name)
            }
        }
    }

    val l = list ?: return
    val name = applying
    val error = failed
    when {
        error != null -> ConfigErrorDialog(
            error.second,
            stringResource(R.string.check_select_anyway),
            onProceed = { failed = null; apply(error.first) },
            onDismiss = onDone,
        )
        name != null -> GlassAlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(ConfigSwitch.label(name) ?: name) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.config_switch_applying))
                }
            },
        )
        else -> ChoiceDialog(
            stringResource(R.string.config_switch_title),
            l.files.map { Choice(it, ConfigSwitch.label(it) ?: it, if (it == l.active) stringResource(R.string.config_switch_current) else it) },
            l.active ?: "",
            onSelect = { if (it == l.active) onDone() else check(it) },
            onDismiss = onDone,
        )
    }
}

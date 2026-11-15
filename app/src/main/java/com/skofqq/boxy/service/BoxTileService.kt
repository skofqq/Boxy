package com.skofqq.boxy.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.skofqq.boxy.R
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.util.withAppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile that starts / stops the Box service. */
class BoxTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var busy = false

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase.withAppLocale())
    }

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (busy) return
        val tile = qsTile ?: return
        val running = tile.state == Tile.STATE_ACTIVE
        busy = true
        tile.state = Tile.STATE_UNAVAILABLE
        if (android.os.Build.VERSION.SDK_INT >= 29) tile.subtitle = getString(if (running) R.string.status_stopping else R.string.status_starting)
        tile.updateTile()
        scope.launch {
            if (running) BoxControl.stop(applicationContext) else BoxControl.start(applicationContext)
            busy = false
            refresh()
        }
    }

    private fun refresh() {
        scope.launch {
            val s = runCatching { BoxModule.state() }.getOrNull()
            val tile = qsTile ?: return@launch
            tile.label = getString(R.string.qs_tile_label)
            tile.state = when {
                s == null -> Tile.STATE_UNAVAILABLE
                s.running -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) tile.subtitle = when {
                s == null -> null
                s.running -> s.core
                else -> getString(R.string.status_stopped)
            }
            tile.updateTile()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

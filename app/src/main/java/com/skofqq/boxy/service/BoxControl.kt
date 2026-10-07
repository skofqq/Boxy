package com.skofqq.boxy.service

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import com.skofqq.boxy.data.TrafficStats
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.widget.BoxWidget

/**
 * Start / stop / restart from outside the UI (widget, automation intents, schedule).
 * Traffic is sampled before the core goes away, and the widget and tile are refreshed afterwards.
 */
object BoxControl {
    suspend fun start(context: Context): Boolean = act(context) { BoxModule.start() }

    suspend fun stop(context: Context): Boolean = act(context) {
        TrafficStats.sample(context)
        BoxModule.stop()
    }

    suspend fun restart(context: Context): Boolean = act(context) {
        TrafficStats.sample(context)
        BoxModule.restart()
    }

    suspend fun toggle(context: Context): Boolean =
        if (runCatching { BoxModule.state().running }.getOrDefault(false)) stop(context) else start(context)

    /** Makes [name] the active config of the current core; restarts the core when it runs. */
    suspend fun setConfig(context: Context, name: String): Boolean {
        val core = BoxModule.readSetting("bin_name") ?: "clash"
        val key = com.skofqq.boxy.ui.tools.coreConfig(core).second
        if (!BoxModule.writeSetting(key, name)) return false
        return if (BoxModule.state().running) restart(context) else true.also { changed(context) }
    }

    /**
     * Writes settings.ini keys (core, network mode, IPv6). A running service is stopped first, so iptables
     * are cleaned up with the old mode, and started again with the new settings.
     */
    suspend fun applySettings(context: Context, settings: List<Pair<String, String>>): Boolean {
        val running = runCatching { BoxModule.state().running }.getOrDefault(false)
        if (running) {
            TrafficStats.sample(context)
            runCatching { BoxModule.stop() }
        }
        val ok = settings.all { (k, v) -> BoxModule.writeSetting(k, v) }
        val started = if (running) runCatching { BoxModule.start() }.getOrDefault(false) else true
        changed(context)
        return ok && started
    }

    private suspend fun act(context: Context, block: suspend () -> Boolean): Boolean {
        val ok = runCatching { block() }.getOrDefault(false)
        changed(context)
        return ok
    }

    /** Widget and Quick Settings tile show the new state. */
    fun changed(context: Context) {
        BoxWidget.refresh(context)
        runCatching { TileService.requestListeningState(context, ComponentName(context, BoxTileService::class.java)) }
    }
}

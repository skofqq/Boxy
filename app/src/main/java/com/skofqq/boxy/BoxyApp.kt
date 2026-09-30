package com.skofqq.boxy

import android.app.Application
import com.skofqq.boxy.data.Prefs
import com.topjohnwu.superuser.Shell

class BoxyApp : Application() {
    lateinit var prefs: Prefs
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        com.skofqq.boxy.data.TrafficStats.schedule(this)
        com.skofqq.boxy.automation.Automation.reschedule(this)
        com.skofqq.boxy.net.Mirrors.current = prefs.githubMirror
        com.skofqq.boxy.service.BoxStatusService.createChannel(this)
        com.skofqq.boxy.notify.ModuleEventReceiver.createChannel(this)
    }

    /** Re-reads preferences after a restore. */
    fun reloadPrefs() {
        prefs = Prefs(this)
    }

    companion object {
        lateinit var instance: BoxyApp
            private set

        /** An activity of the app is on screen (set by MainActivity). */
        @Volatile
        var foreground = false

        init {
            Shell.enableVerboseLogging = BuildConfigCompat.DEBUG
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER)
                    .setTimeout(10),
            )
        }
    }
}

private object BuildConfigCompat {
    const val DEBUG = false
}

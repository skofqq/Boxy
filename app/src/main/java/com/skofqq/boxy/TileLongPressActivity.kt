package com.skofqq.boxy

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Target of a long press on the Quick Settings tile. Brings Boxy to the front (an already open window gets
 * onNewIntent), straight on the panel when Settings → "Tile long press opens the panel" is on.
 */
class TileLongPressActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val panel = (application as BoxyApp).prefs.tileLongPressPanel
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN_PANEL, panel),
        )
        finish()
    }

    companion object {
        const val EXTRA_OPEN_PANEL = "com.skofqq.boxy.extra.OPEN_PANEL"
    }
}

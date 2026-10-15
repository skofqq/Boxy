package com.skofqq.boxy.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView

/** Vibration on taps (Settings → Haptic feedback); mirrors the preference so components need no Prefs. */
object Haptics {
    var enabled by mutableStateOf(true)
}

/** [onClick] with a short tap vibration first, when haptic feedback is on. */
@Composable
fun withTap(onClick: () -> Unit): () -> Unit {
    val view = LocalView.current
    return {
        if (Haptics.enabled) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onClick()
    }
}

/** Same as [withTap] for callbacks with one argument (switches, list choices, tabs). */
@Composable
fun <T> withTap1(onClick: (T) -> Unit): (T) -> Unit {
    val view = LocalView.current
    return { value ->
        if (Haptics.enabled) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onClick(value)
    }
}

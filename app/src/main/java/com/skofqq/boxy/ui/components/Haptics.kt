package com.skofqq.boxy.ui.components

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.launch

/** Vibration on taps (Settings → Haptic feedback); mirrors the preference so components need no Prefs. */
object Haptics {
    var enabled by mutableStateOf(true)

    /** Short tick for a tap. Android still drops it when "Touch feedback" is off in the system settings. */
    fun tap(view: View) {
        if (enabled) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** Stronger confirm / reject pattern at the end of a task (Android 11+). */
    fun result(view: View, ok: Boolean) {
        if (!enabled) return
        view.performHapticFeedback(
            when {
                Build.VERSION.SDK_INT >= 30 -> if (ok) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT
                else -> HapticFeedbackConstants.LONG_PRESS
            },
        )
    }

    /** "Touch feedback" in the system sound and vibration settings; without it no app can tick on taps. */
    fun systemEnabled(context: Context): Boolean =
        runCatching { Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0 }.getOrDefault(true)
}

/**
 * Default indication of the app (see BoxyTheme): the usual ripple plus a tap vibration when a press ends in a click
 * (PressInteraction.Release; a press that turns into a scroll is cancelled and stays silent). Every Modifier.clickable
 * with the default indication gets it, so rows, cards, chips and buttons need no code of their own.
 */
class HapticIndication(private val ripple: IndicationNodeFactory) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = HapticNode(interactionSource, ripple.create(interactionSource))

    override fun equals(other: Any?) = other is HapticIndication && other.ripple == ripple

    override fun hashCode() = ripple.hashCode()
}

private class HapticNode(private val source: InteractionSource, ripple: DelegatableNode) : DelegatingNode(), CompositionLocalConsumerModifierNode {
    init {
        delegate(ripple)
    }

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { if (it is PressInteraction.Release) Haptics.tap(currentValueOf(LocalView)) }
        }
    }
}

/**
 * [onClick] with a tap vibration first, for controls the indication does not cover:
 * Material buttons, switches and checkboxes (they draw their own ripple) and clickables without an indication.
 */
@Composable
fun withTap(onClick: () -> Unit): () -> Unit {
    val view = LocalView.current
    return {
        Haptics.tap(view)
        onClick()
    }
}

/** Same as [withTap] for callbacks with one argument (switches, checkboxes, tabs). */
@Composable
fun <T> withTap1(onClick: (T) -> Unit): (T) -> Unit {
    val view = LocalView.current
    return { value ->
        Haptics.tap(view)
        onClick(value)
    }
}

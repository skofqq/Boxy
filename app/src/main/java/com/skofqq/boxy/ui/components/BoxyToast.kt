package com.skofqq.boxy.ui.components

import android.content.Context
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Boxy's own pop-up messages instead of Android's toasts: a Material 3 Expressive pill over the app (a shape with
 * an icon by kind, the text, an optional action), queued, swiped away sideways, announced to accessibility services.
 * The call looks like Toast's: `BoxyToast.makeText(context, text, BoxyToast.LENGTH_SHORT).show()`. When no Boxy
 * screen is shown (a tile, a widget), Android's toast is used.
 */
object BoxyToast {
    const val LENGTH_SHORT = android.widget.Toast.LENGTH_SHORT
    const val LENGTH_LONG = android.widget.Toast.LENGTH_LONG

    enum class Kind { INFO, SUCCESS, ERROR }

    data class Message(val text: String, val kind: Kind, val long: Boolean, val action: String? = null, val onAction: (() -> Unit)? = null, val id: Long = System.nanoTime())

    internal val queue = mutableStateListOf<Message>()

    /** How many hosts are on screen: with none, Android's toast shows the message. */
    internal var hosts by mutableStateOf(0)

    class Pending(private val context: Context, private val m: Message) {
        fun show() {
            if (hosts > 0) {
                queue.removeAll { it.text == m.text }
                queue.add(m)
            } else {
                android.widget.Toast.makeText(context.applicationContext, m.text, if (m.long) LENGTH_LONG else LENGTH_SHORT).show()
            }
        }
    }

    /** Errors and successes are told apart by their words; [kind] makes it explicit. */
    private fun guess(text: String): Kind {
        val t = text.lowercase()
        return when {
            listOf("fail", "error", "не ", "ошиб", "not ", "cannot", "can't", "rejected", "отклон", "недоступ", "нет ").any { it in t } -> Kind.ERROR
            listOf("done", "saved", "applied", "готово", "сохран", "примен", "скопир", "copied", "added", "добав", "imported", "импорт").any { it in t } -> Kind.SUCCESS
            else -> Kind.INFO
        }
    }

    fun makeText(context: Context, text: CharSequence?, duration: Int, kind: Kind? = null): Pending {
        val s = text?.toString().orEmpty()
        return Pending(context, Message(s, kind ?: guess(s), duration == LENGTH_LONG))
    }

    fun makeText(context: Context, resId: Int, duration: Int, kind: Kind? = null): Pending = makeText(context, context.getString(resId), duration, kind)

    /** A message with a button (Undo, Open…). */
    fun withAction(context: Context, text: String, action: String, kind: Kind = Kind.INFO, onAction: () -> Unit) =
        Pending(context, Message(text, kind, true, action, onAction))
}

/** Shows [BoxyToast]'s messages over [content]; put once around a screen's content. */
@Composable
fun BoxyToastHost(content: @Composable BoxScope.() -> Unit) {
    DisposableEffect(Unit) {
        BoxyToast.hosts++
        onDispose { BoxyToast.hosts-- }
    }
    Box {
        content()
        val current = BoxyToast.queue.firstOrNull()
        // A popup window, made when a message comes: it is above bottom sheets and dialogs opened before it.
        if (current != null) androidx.compose.ui.window.Popup(
            alignment = Alignment.BottomCenter,
            properties = androidx.compose.ui.window.PopupProperties(focusable = false, clippingEnabled = false),
        ) {
        Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, bottom = 96.dp), contentAlignment = Alignment.Center) {
            AnimatedVisibility(
                current != null,
                enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = 400f)) { it / 2 } + fadeIn() + scaleIn(initialScale = 0.9f),
                exit = slideOutVertically { it / 2 } + fadeOut() + scaleOut(targetScale = 0.9f),
            ) {
                var shown by remember { mutableStateOf(current) }
                if (current != null) shown = current
                shown?.let { ToastPill(it) }
            }
        }
        }
        if (current != null) {
            LaunchedEffect(current.id) {
                delay(if (current.long || current.action != null) 4000 else 2400)
                BoxyToast.queue.remove(current)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ToastPill(m: BoxyToast.Message) {
    val view: View = LocalView.current
    val tint = when (m.kind) {
        BoxyToast.Kind.SUCCESS -> Tints.green
        BoxyToast.Kind.ERROR -> Tints.red
        BoxyToast.Kind.INFO -> Tints.blue
    }
    val offset = remember(m.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val pop = remember(m.id) { Animatable(0.5f) }
    LaunchedEffect(m.id) {
        pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 420f))
        view.announceForAccessibility(m.text)
    }
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .graphicsLayer { translationX = offset.value; alpha = 1f - (kotlin.math.abs(offset.value) / 900f).coerceIn(0f, 0.8f) }
            .pointerInput(m.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (kotlin.math.abs(offset.value) > 220f) BoxyToast.queue.remove(m)
                            else offset.animateTo(0f, spring())
                        }
                    },
                ) { _, d -> scope.launch { offset.snapTo(offset.value + d) } }
            }
            .clip(shape)
            .background(Boxy.colors.card)
            .border(1.dp, tint.fg.copy(alpha = 0.35f), shape)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = 10.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = when (m.kind) {
            BoxyToast.Kind.SUCCESS -> BoxyIcons.Check
            BoxyToast.Kind.ERROR -> BoxyIcons.Close
            BoxyToast.Kind.INFO -> BoxyIcons.Info
        }
        val cookie = when (m.kind) {
            BoxyToast.Kind.SUCCESS -> MaterialShapes.Cookie9Sided
            BoxyToast.Kind.ERROR -> MaterialShapes.Burst
            BoxyToast.Kind.INFO -> MaterialShapes.Cookie4Sided
        }
        Box(Modifier.size(38.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }.clip(cookie.toShape()).background(tint.bg), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = tint.fg)
        }
        Spacer(Modifier.width(12.dp))
        Text(m.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text, maxLines = 4)
        if (m.action != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                m.action,
                Modifier.clip(RoundedCornerShape(50)).clickable { m.onAction?.invoke(); BoxyToast.queue.remove(m) }.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Boxy.colors.accent,
            )
        }
    }
}

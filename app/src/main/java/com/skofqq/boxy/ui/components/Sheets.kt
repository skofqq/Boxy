package com.skofqq.boxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tint

/** Bottom sheet with the BFR header (title + subtitle) and scrollable content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxySheet(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    skipPartiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = LocalGlass.current
    val blurBehind = glass.enabled && glass.sheetBlur && android.os.Build.VERSION.SDK_INT >= 31
    androidx.compose.runtime.DisposableEffect(Unit) {
        SheetBlurState.open++
        onDispose { SheetBlurState.open-- }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded),
        containerColor = if (blurBehind) Boxy.colors.page.copy(alpha = 0.9f) else Boxy.colors.page,
        scrimColor = if (blurBehind) androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.2f) else androidx.compose.material3.BottomSheetDefaults.ScrimColor,
        contentColor = Boxy.colors.text,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            if (title.isNotEmpty()) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = Boxy.colors.text)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text2)
                }
                Spacer(Modifier.height(16.dp))
            }
            content()
        }
    }
}

/** Card-like group inside a sheet. */
@Composable
fun SheetGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Boxy.colors.card).padding(vertical = 6.dp),
        content = content,
    )
}

/** Group with its own bold title inside the card, as in BFR's system sheet. */
@Composable
fun TitledSheetGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Boxy.colors.card).padding(top = 18.dp, bottom = 10.dp),
    ) {
        Text(
            title,
            Modifier.padding(horizontal = 20.dp).padding(bottom = 6.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = Boxy.colors.text,
        )
        content()
    }
}

/** Icon, label and a one-line value on the right. */
@Composable
fun IconInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = Boxy.colors.text,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(icon, null, Modifier.size(22.dp), tint = Boxy.colors.text)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Boxy.colors.text2, maxLines = 1)
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = valueColor,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Label on the left, value on the right. */
@Composable
fun InfoRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = Boxy.colors.text) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text2)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            Modifier.weight(1.4f),
            style = MaterialTheme.typography.labelLarge,
            color = valueColor,
            textAlign = TextAlign.End,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Selectable option row in a sheet (core, mode, …). */
@Composable
fun OptionRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Boxy.colors.accent.copy(alpha = 0.14f) else Boxy.colors.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
        }
        if (selected) Icon(BoxyIcons.Check, null, tint = Boxy.colors.accent)
    }
}

/** Small rounded badge, like LAN / NET / SUB in BFR. */
@Composable
fun Badge(text: String, tint: Tint, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(50)).background(tint.bg).padding(horizontal = 11.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tint.fg, maxLines = 1)
    }
}

/** Two buttons at the bottom of a sheet. */
@Composable
fun SheetButtons(
    primary: String,
    onPrimary: () -> Unit,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
) {
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (secondary != null) {
            SheetButton(secondary, Boxy.colors.card, Boxy.colors.text, Modifier.weight(1f), onSecondary)
        }
        SheetButton(primary, Boxy.colors.accent, androidx.compose.ui.graphics.Color.White, Modifier.weight(1f), onPrimary)
    }
}

@Composable
private fun SheetButton(text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(50.dp).clip(RoundedCornerShape(18.dp)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontWeight = FontWeight.SemiBold)
    }
}

/** Round icon button used inside rows. */
@Composable
fun SmallIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String?,
    enabled: Boolean = true,
    container: androidx.compose.ui.graphics.Color = Boxy.colors.surface2,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(container).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(20.dp), tint = Boxy.colors.text.copy(alpha = if (enabled) 1f else 0.3f))
    }
}

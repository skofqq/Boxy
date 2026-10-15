package com.skofqq.boxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.focusRequester
import com.skofqq.boxy.R
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons

/** Compact "← Back" pill used on pages opened from another page. */
@Composable
fun BackPill(onClick: () -> Unit) {
    Row(
        Modifier
            .padding(start = 12.dp, top = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(Boxy.colors.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(BoxyIcons.ArrowBack, null, Modifier.size(18.dp), tint = Boxy.colors.text)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.action_back), style = MaterialTheme.typography.labelLarge, color = Boxy.colors.text)
    }
}

/** Back pill, then the page title with actions on the same row. */
@Composable
fun SubPageHeader(title: String, subtitle: String?, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    Column {
        if (onBack != null) BackPill(onBack)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineLarge, color = Boxy.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Boxy.colors.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** Search field on the page background: card-coloured pill with a search icon and a clear button. */
@Composable
fun PageSearchField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Boxy.colors.card).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(BoxyIcons.Search, null, Modifier.size(20.dp), tint = Boxy.colors.text2)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, color = Boxy.colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value,
                onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Boxy.colors.text),
                cursorBrush = SolidColor(Boxy.colors.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (value.isNotEmpty()) {
            Icon(BoxyIcons.Close, null, Modifier.size(20.dp).clickable { onChange("") }, tint = Boxy.colors.text2)
        }
    }
}

/** Single-line text input in the BFR rounded style. */
@Composable
fun BoxyTextField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    container: androidx.compose.ui.graphics.Color = Boxy.colors.surface2,
) {
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(container).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, color = Boxy.colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value,
                onChange,
                singleLine = singleLine,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Boxy.colors.text),
                cursorBrush = SolidColor(Boxy.colors.accent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/**
 * Editable list of strings (SSIDs, URLs, file names) in the BFR style: an icon tile, an outlined field and a
 * delete button per row, dividers between rows and an "Add" row at the end. With [onPick] the tile opens a picker
 * (scan Wi‑Fi / hotspot clients / SIM operators).
 */
@Composable
fun StringListEditor(
    items: List<String>,
    onChange: (List<String>) -> Unit,
    hint: String,
    onPick: ((Int) -> Unit)? = null,
    pickIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector = pickIcon ?: BoxyIcons.Router,
) {
    val colors = Boxy.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        items.forEachIndexed { i, value ->
            if (i > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 56.dp, end = 48.dp), color = colors.outline)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                val pick = onPick?.takeIf { pickIcon != null }
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(colors.surface2)
                        .then(if (pick != null) Modifier.clickable { pick(i) } else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, Modifier.size(22.dp), tint = if (pick != null) colors.accent else colors.text2)
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                ) {
                    if (value.isEmpty()) Text(hint, color = colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicTextField(
                        value,
                        { v -> onChange(items.toMutableList().also { it[i] = v }) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.text),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(50)).clickable { onChange(items.toMutableList().also { it.removeAt(i) }) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(BoxyIcons.Delete, stringResource(R.string.action_delete), Modifier.size(22.dp), tint = colors.text2)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onChange(items + "") }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(colors.surface2), contentAlignment = Alignment.Center) {
                Icon(BoxyIcons.Add, null, Modifier.size(22.dp), tint = colors.text)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.action_add), style = MaterialTheme.typography.titleMedium, color = colors.text)
                Text(stringResource(R.string.list_append_item), style = MaterialTheme.typography.bodyMedium, color = colors.text2)
            }
        }
    }
}

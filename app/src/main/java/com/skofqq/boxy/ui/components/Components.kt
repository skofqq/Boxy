package com.skofqq.boxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.R
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons

/** Large page title with an optional summary and trailing actions on the same row. */
@Composable
fun PageHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = Boxy.colors.text)
            if (!subtitle.isNullOrEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Boxy.colors.text2)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Rounded card with a section title and subtitle, like the BFR settings groups. */
@Composable
fun SectionCard(
    title: String?,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Boxy.colors.card)
            .padding(vertical = 16.dp),
    ) {
        if (title != null) {
            Column(Modifier.padding(horizontal = 18.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = Boxy.colors.text)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text2)
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        content()
    }
}

@Composable
private fun RowIcon(icon: ImageVector, enabled: Boolean = true) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Boxy.colors.surface2),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = Boxy.colors.text.copy(alpha = if (enabled) 1f else 0.4f))
    }
}

@Composable
private fun RowTexts(title: String, subtitle: String?, enabled: Boolean, modifier: Modifier) {
    Column(modifier) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = Boxy.colors.text.copy(alpha = if (enabled) 1f else 0.45f),
        )
        if (!subtitle.isNullOrEmpty()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Boxy.colors.text2.copy(alpha = if (enabled) 1f else 0.45f),
                maxLines = 2,
            )
        }
    }
}

/** Navigation row: icon, title, subtitle and a chevron. */
@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    showDivider: Boolean = true,
    onClick: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = withTap(onClick)).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowIcon(icon)
            Spacer(Modifier.width(14.dp))
            RowTexts(title, subtitle, true, Modifier.weight(1f))
            Icon(BoxyIcons.ChevronRight, null, Modifier.size(20.dp), tint = Boxy.colors.text2.copy(alpha = 0.75f))
        }
        if (showDivider) RowDivider()
    }
}

/** Switch row. */
@Composable
fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    showDivider: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val toggle = withTap1(onCheckedChange)
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { toggle(!checked) }
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowIcon(icon, enabled)
            Spacer(Modifier.width(14.dp))
            RowTexts(title, subtitle, enabled, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = checked,
                onCheckedChange = toggle,
                enabled = enabled,
                colors = SwitchDefaults.colors(checkedTrackColor = Boxy.colors.accent),
            )
        }
        if (showDivider) RowDivider()
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(Modifier.padding(start = 76.dp, end = 18.dp), color = Boxy.colors.outline)
}

/** Round action button used in page headers. */
@Composable
fun HeaderAction(
    icon: ImageVector,
    contentDescription: String?,
    tint: androidx.compose.ui.graphics.Color = Boxy.colors.text,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(Boxy.colors.card).clickable(onClick = withTap(onClick)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(22.dp), tint = tint)
    }
}

data class Choice<T>(val value: T, val title: String, val subtitle: String? = null, val leading: String? = null)

/** Single-choice dialog with the selected option highlighted. */
@Composable
fun <T> ChoiceDialog(
    title: String,
    choices: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val pick = withTap1(onSelect)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                choices.forEach { choice ->
                    val isSelected = choice.value == selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isSelected) Boxy.colors.accent.copy(alpha = 0.14f) else Boxy.colors.surface2)
                            .clickable { pick(choice.value) }
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (choice.leading != null) {
                            Text(choice.leading, fontSize = 24.sp)
                            Spacer(Modifier.width(14.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(choice.title, style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text)
                            if (choice.subtitle != null) {
                                Text(choice.subtitle, style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
                            }
                        }
                        if (isSelected) Icon(BoxyIcons.Check, null, tint = Boxy.colors.accent)
                    }
                }
            }
        },
        containerColor = Boxy.colors.card,
    )
}

/** "Important" note inside a card: info tile, accent title and body on a soft accent background (as in BFR). */
@Composable
fun NoticeCard(title: String, body: String, modifier: Modifier = Modifier) {
    val colors = Boxy.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(20.dp))
            .background(colors.accent.copy(alpha = 0.10f)).padding(horizontal = 14.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(colors.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(BoxyIcons.Info, null, Modifier.size(22.dp), tint = colors.accent)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.accent)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.text)
        }
    }
}

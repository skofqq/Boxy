package com.skofqq.boxy.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.R
import com.skofqq.boxy.data.Prefs
import com.skofqq.boxy.net.Mirrors
import com.skofqq.boxy.ui.components.BoxySheet
import com.skofqq.boxy.ui.components.InputDialog
import com.skofqq.boxy.ui.components.SheetButtons
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import com.skofqq.boxy.util.Format
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** GitHub mirror for downloads (app and module). Each option shows how fast it answers right now. */
@Composable
fun MirrorSheet(prefs: Prefs, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val options = (listOf("") + Mirrors.KNOWN + listOfNotNull(prefs.githubMirror.takeIf { it.isNotBlank() && it !in Mirrors.KNOWN })).distinct()
    val ping = remember { mutableStateMapOf<String, Long?>() }
    var probing by remember { mutableStateOf(true) }
    var customDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        options.map { m -> scope.async { m to Mirrors.probe(m) } }.forEach { val (m, v) = it.await(); ping[m] = v }
        probing = false
    }

    fun choose(m: String) {
        prefs.updateGithubMirror(m)
        Mirrors.current = m
        scope.launch {
            val ok = Mirrors.applyToModule(m)
            com.skofqq.boxy.ui.components.BoxyToast.makeText(context, if (ok) R.string.mirror_applied else R.string.mirror_module_failed, com.skofqq.boxy.ui.components.BoxyToast.LENGTH_SHORT).show()
        }
    }

    BoxySheet(stringResource(R.string.settings_mirror), stringResource(R.string.mirror_sub), onDismiss) {
        options.forEach { m ->
            val selected = m == prefs.githubMirror
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp))
                    .background(if (selected) Boxy.colors.accent.copy(alpha = 0.14f) else Boxy.colors.card)
                    .clickable { choose(m) }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(if (m.isBlank()) stringResource(R.string.mirror_direct) else m.removePrefix("https://"), style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text)
                    val p = ping[m]
                    Text(
                        when {
                            m !in ping -> stringResource(R.string.about_checking)
                            p == null -> stringResource(R.string.latency_timeout)
                            else -> Format.ms(context, p)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            m !in ping -> Boxy.colors.text2
                            p == null -> Tints.red.fg
                            p < 800 -> Tints.green.fg
                            else -> Tints.amber.fg
                        },
                    )
                }
                if (selected) Icon(BoxyIcons.Check, null, tint = Boxy.colors.accent)
            }
        }
        SheetButtons(stringResource(R.string.action_done), onDismiss, stringResource(R.string.mirror_custom), { customDialog = true })
    }

    if (customDialog) {
        val invalid = stringResource(R.string.panel_error_url_invalid)
        InputDialog(
            stringResource(R.string.mirror_custom),
            listOf("https://mirror.example.com" to prefs.githubMirror),
            message = stringResource(R.string.mirror_custom_hint),
            validate = { v -> if (!v[0].startsWith("https://") && !v[0].startsWith("http://")) invalid else null },
            onConfirm = { v -> customDialog = false; choose(v[0].trimEnd('/')) },
            onDismiss = { customDialog = false },
        )
    }
}

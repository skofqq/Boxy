package com.skofqq.boxy.ui.tools

import android.graphics.Typeface
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.skofqq.boxy.R
import com.skofqq.boxy.root.CheckResult
import com.skofqq.boxy.root.ConfigCheck
import com.skofqq.boxy.root.RootFiles
import com.skofqq.boxy.ui.components.ConfigErrorDialog
import com.skofqq.boxy.ui.components.PageSearchField
import com.skofqq.boxy.ui.components.BackPill
import com.skofqq.boxy.ui.components.BoxyTextField
import com.skofqq.boxy.ui.components.ConfirmDialog
import com.skofqq.boxy.ui.components.HeaderAction
import com.skofqq.boxy.ui.components.SmallIconButton
import com.skofqq.boxy.ui.components.SubPageHeader
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.launch

/** Text editor for config files (sora-editor: line numbers, search, large files). */
@Composable
fun EditorScreen(contentPadding: PaddingValues, path: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<CodeEditor?>(null) }
    var modified by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var discardDialog by remember { mutableStateOf(false) }
    var saveAsDialog by remember { mutableStateOf(false) }
    val sp = remember { context.getSharedPreferences("boxy", android.content.Context.MODE_PRIVATE) }
    var wrap by remember { mutableStateOf(sp.getBoolean("editor_wrap", true)) }
    val colors = Boxy.colors

    LaunchedEffect(path) { text = RootFiles.read(path) ?: "" }

    val tryBack = { if (modified) discardDialog = true else onBack() }
    BackHandler { tryBack() }

    var checkError by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun write(content: String, checked: Boolean) {
        scope.launch {
            val ok = RootFiles.write(path, content)
            if (ok) modified = false
            val msg = when {
                !ok -> R.string.op_failed
                checked -> R.string.check_saved_ok
                else -> R.string.editor_saved
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun save() {
        val e = editor ?: return
        if (checking) return
        val content = e.text.toString()
        val core = ConfigCheck.coreFor(path) ?: return write(content, false)
        checking = true
        scope.launch {
            val r = ConfigCheck.checkText(core, path, content)
            checking = false
            if (r is CheckResult.Failed) checkError = r.output else write(content, r == CheckResult.Ok)
        }
    }

    Column(Modifier.fillMaxSize().padding(contentPadding).imePadding()) {
        // Compact bar like BFR: back on the left, search / wrap / save on the right.
        Row(Modifier.fillMaxWidth().padding(start = 0.dp, end = 16.dp, top = 0.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BackPill(tryBack)
            Spacer(Modifier.weight(1f))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeaderAction(BoxyIcons.Search, stringResource(R.string.action_search)) {
                    searching = !searching
                    if (!searching) editor?.searcher?.stopSearch()
                }
                HeaderAction(BoxyIcons.WrapText, stringResource(R.string.editor_wrap), tint = if (wrap) colors.accent else colors.text) {
                    wrap = !wrap
                    sp.edit().putBoolean("editor_wrap", wrap).apply()
                    editor?.isWordwrap = wrap
                }
                HeaderAction(BoxyIcons.SaveAs, stringResource(R.string.editor_save_as)) { if (editor != null) saveAsDialog = true }
                HeaderAction(BoxyIcons.Save, stringResource(R.string.action_save), tint = if (modified) colors.accent else colors.text) { save() }
            }
        }
        Text(
            path.substringAfterLast('/') + (if (modified) " •" else "") + (if (checking) " · " + stringResource(R.string.check_running) else ""),
            Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodySmall,
            color = colors.text2,
            maxLines = 1,
        )
        if (searching) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PageSearchField(
                    query,
                    { q ->
                        query = q
                        val s = editor?.searcher
                        if (q.isEmpty()) s?.stopSearch() else s?.search(q, EditorSearcher.SearchOptions(true, false))
                    },
                    stringResource(R.string.editor_search_hint),
                    Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                SmallIconButton(BoxyIcons.ExpandLess, null, container = Boxy.colors.card) { runCatching { editor?.searcher?.gotoPrevious() } }
                Spacer(Modifier.width(6.dp))
                SmallIconButton(BoxyIcons.ExpandMore, null, container = Boxy.colors.card) { runCatching { editor?.searcher?.gotoNext() } }
            }
        }
        Box(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp)).background(colors.card),
            contentAlignment = Alignment.Center,
        ) {
            val content = text
            if (content == null) {
                CircularProgressIndicator()
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        CodeEditor(ctx).apply {
                            // Proportional font and larger size read better on a phone (as in BFR).
                            typefaceText = Typeface.DEFAULT
                            typefaceLineNumber = Typeface.DEFAULT
                            setTextSize(16f)
                            isWordwrap = wrap
                            setLineSpacing(2f, 1.1f)
                            isHighlightCurrentLine = false
                            setDividerMargin(6f)
                            colorScheme = EditorColorScheme().apply {
                                setColor(EditorColorScheme.WHOLE_BACKGROUND, colors.card.toArgb())
                                setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, colors.card.toArgb())
                                setColor(EditorColorScheme.LINE_NUMBER, colors.text2.toArgb())
                                setColor(EditorColorScheme.LINE_NUMBER_CURRENT, colors.text.toArgb())
                                setColor(EditorColorScheme.LINE_DIVIDER, colors.outline.toArgb())
                                setColor(EditorColorScheme.TEXT_NORMAL, colors.text.toArgb())
                                setColor(EditorColorScheme.CURRENT_LINE, colors.surface2.toArgb())
                                setColor(EditorColorScheme.SELECTION_INSERT, colors.accent.toArgb())
                                setColor(EditorColorScheme.SELECTION_HANDLE, colors.accent.toArgb())
                                setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, colors.accent.copy(alpha = 0.3f).toArgb())
                                setColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND, colors.accent.copy(alpha = 0.25f).toArgb())
                                // Syntax colours as in BFR (see ConfigLanguage): navy keys, purple punctuation,
                                // teal string values, green numbers, plain booleans, grey comments.
                                val dark = colors.isDark
                                setColor(EditorColorScheme.KEYWORD, if (dark) 0xFF9FB4FF.toInt() else 0xFF1A237E.toInt())
                                setColor(EditorColorScheme.OPERATOR, if (dark) 0xFFD59BF6.toInt() else 0xFF9C27B0.toInt())
                                setColor(EditorColorScheme.LITERAL, if (dark) 0xFF4DD0C4.toInt() else 0xFF00897B.toInt())
                                setColor(EditorColorScheme.FUNCTION_NAME, if (dark) 0xFF8BD68F.toInt() else 0xFF2E7D32.toInt())
                                setColor(EditorColorScheme.COMMENT, if (dark) 0xFF8B949E.toInt() else 0xFF8A8F98.toInt())
                                setColor(EditorColorScheme.LINE_DIVIDER, colors.outline.copy(alpha = 0.4f).toArgb())
                            }
                            ConfigLanguage.forPath(path)?.let { setEditorLanguage(it) }
                            setText(content)
                            subscribeEvent(ContentChangeEvent::class.java) { _, _ -> modified = true }
                            editor = this
                        }
                    },
                    onRelease = { it.release() },
                )
            }
        }
    }

    DisposableEffect(Unit) { onDispose { editor = null } }

    if (saveAsDialog) {
        val dir = path.substringBeforeLast('/')
        val name = path.substringAfterLast('/')
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        val exists = stringResource(R.string.editor_save_as_exists)
        val invalid = stringResource(R.string.editor_save_as_invalid)
        com.skofqq.boxy.ui.components.InputDialog(
            title = stringResource(R.string.editor_save_as),
            fields = listOf(stringResource(R.string.editor_save_as_name) to "$base-copy$ext"),
            confirm = stringResource(R.string.action_save),
            message = stringResource(R.string.editor_save_as_hint, dir),
            validate = { v -> v[0].trim().let { if (it.isEmpty() || it.contains('/') || it == name) invalid else null } },
            onConfirm = { v ->
                val target = "$dir/${v[0].trim()}"
                val content = editor?.text?.toString()
                saveAsDialog = false
                if (content != null) {
                    scope.launch {
                        val msg = when {
                            RootFiles.exists(target) -> exists
                            RootFiles.write(target, content) -> context.getString(R.string.editor_saved_as, target.substringAfterLast('/'))
                            else -> context.getString(R.string.op_failed)
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { saveAsDialog = false },
        )
    }

    checkError?.let { output ->
        ConfigErrorDialog(
            output,
            stringResource(R.string.check_save_anyway),
            onProceed = { checkError = null; editor?.let { write(it.text.toString(), false) } },
            onDismiss = { checkError = null },
        )
    }

    if (discardDialog) {
        ConfirmDialog(
            stringResource(R.string.editor_discard_title),
            stringResource(R.string.editor_discard_body),
            stringResource(R.string.editor_discard),
            danger = true,
            onConfirm = { discardDialog = false; onBack() },
            onDismiss = { discardDialog = false },
        )
    }
}


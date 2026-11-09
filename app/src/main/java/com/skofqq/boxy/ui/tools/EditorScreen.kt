package com.skofqq.boxy.ui.tools

import android.content.Context
import android.graphics.Typeface
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.skofqq.boxy.R
import com.skofqq.boxy.root.CheckResult
import com.skofqq.boxy.root.ConfigCheck
import com.skofqq.boxy.root.RootFiles
import com.skofqq.boxy.ui.components.Choice
import com.skofqq.boxy.ui.components.ChoiceDialog
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
import com.skofqq.boxy.ui.theme.Tints
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var mono by remember { mutableStateOf(sp.getBoolean("editor_mono", false)) }
    var replacing by remember { mutableStateOf(false) }
    var replacement by remember { mutableStateOf("") }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var sections by remember { mutableStateOf<List<ConfigAssist.Section>?>(null) }
    var problem by remember { mutableStateOf<ConfigDiagnostics.Problem?>(null) }
    val language = remember(path) { ConfigLanguage.forPath(path) }
    val colors = Boxy.colors
    val errorColor = Tints.red.fg

    LaunchedEffect(path) { text = RootFiles.read(path) ?: "" }

    val tryBack = { if (modified) discardDialog = true else onBack() }
    BackHandler { tryBack() }

    var checkError by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    var liveJob by remember { mutableStateOf<Job?>(null) }

    /** Underlines from [column] to the end of [line] and shows the message on tap. */
    fun underline(e: CodeEditor, line: Int, column: Int, message: String) {
        val text = e.text
        val l = line.coerceIn(0, text.lineCount - 1)
        val c = column.coerceIn(0, text.getColumnCount(l))
        var start = text.getCharIndex(l, c)
        var end = text.getCharIndex(l, text.getColumnCount(l))
        if (start >= end) {
            // Nothing after the mark (end of line or file): underline the character before it.
            if (start > 0) start-- else end = minOf(1, text.length)
        }
        e.diagnostics = DiagnosticsContainer().apply {
            if (start < end) {
                addDiagnostic(DiagnosticRegion(start, end, DiagnosticRegion.SEVERITY_ERROR, 1L, DiagnosticDetail(message, null, emptyList(), null)))
            }
        }
    }

    fun goTo(line: Int, column: Int = 0) {
        val e = editor ?: return
        val l = line.coerceIn(0, e.lineCount - 1)
        e.setSelection(l, column.coerceIn(0, e.text.getColumnCount(l)))
    }

    fun scheduleLiveCheck(e: CodeEditor) {
        val kind = language?.kind ?: return
        liveJob?.cancel()
        liveJob = scope.launch {
            delay(LIVE_CHECK_DELAY_MS)
            val snapshot = e.text.toString()
            val found = withContext(Dispatchers.Default) { ConfigDiagnostics.check(kind, snapshot) }
            problem = found
            if (found == null) e.diagnostics = DiagnosticsContainer() else underline(e, found.line, found.column, found.message)
        }
    }

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

    Column(Modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding).imePadding()) {
        // Compact bar like BFR: back on the left, search / wrap / save on the right.
        Row(Modifier.fillMaxWidth().padding(start = 0.dp, end = 16.dp, top = 0.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BackPill(tryBack)
            Spacer(Modifier.weight(1f))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeaderAction(BoxyIcons.Search, stringResource(R.string.action_search)) {
                    searching = !searching
                    if (!searching) {
                        replacing = false
                        editor?.searcher?.stopSearch()
                    }
                }
                HeaderAction(BoxyIcons.Toc, stringResource(R.string.editor_sections)) {
                    val e = editor
                    val l = language
                    if (e != null && l != null) sections = ConfigAssist.sections(l.kind, e.text.toString())
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
        problem?.let { p ->
            Text(
                stringResource(R.string.editor_error_line, p.line + 1, p.message),
                Modifier.fillMaxWidth().clickable { goTo(p.line, p.column) }.padding(horizontal = 20.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Tints.red.fg,
                maxLines = 2,
            )
        }
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
                Spacer(Modifier.width(6.dp))
                SmallIconButton(BoxyIcons.FindReplace, stringResource(R.string.editor_replace_hint), container = if (replacing) Boxy.colors.accent.copy(alpha = 0.25f) else Boxy.colors.card) { replacing = !replacing }
            }
            if (replacing) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PageSearchField(replacement, { replacement = it }, stringResource(R.string.editor_replace_hint), Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    BarChip(stringResource(R.string.editor_replace_one)) {
                        val s = editor?.searcher
                        if (s != null && s.hasQuery()) runCatching {
                            if (!s.isMatchedPositionSelected) s.gotoNext() else s.replaceCurrentMatch(replacement)
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    BarChip(stringResource(R.string.editor_replace_all)) {
                        val s = editor?.searcher
                        if (s != null && s.hasQuery()) runCatching { s.replaceAll(replacement) }
                    }
                }
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp)).background(colors.card),
            contentAlignment = Alignment.Center,
        ) {
            val content = text
            if (content == null) {
                CircularProgressIndicator()
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WrapEditor(ctx).apply {
                            // Proportional font and larger size read better on a phone (as in BFR).
                            // Monospace is optional: it makes YAML indents line up.
                            typefaceText = if (mono) Typeface.MONOSPACE else Typeface.DEFAULT
                            typefaceLineNumber = Typeface.DEFAULT
                            tabWidth = 2
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
                                setColor(EditorColorScheme.BLOCK_LINE, colors.text2.copy(alpha = 0.35f).toArgb())
                                setColor(EditorColorScheme.BLOCK_LINE_CURRENT, colors.accent.copy(alpha = 0.6f).toArgb())
                                setColor(EditorColorScheme.SIDE_BLOCK_LINE, colors.accent.copy(alpha = 0.6f).toArgb())
                                setColor(EditorColorScheme.PROBLEM_ERROR, errorColor.toArgb())
                                setColor(EditorColorScheme.COMPLETION_WND_BACKGROUND, colors.surface2.toArgb())
                                setColor(EditorColorScheme.COMPLETION_WND_CORNER, colors.outline.toArgb())
                                setColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY, colors.text.toArgb())
                                setColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY, colors.text2.toArgb())
                                setColor(EditorColorScheme.COMPLETION_WND_TEXT_MATCHED, colors.accent.toArgb())
                                setColor(EditorColorScheme.COMPLETION_WND_ITEM_CURRENT, colors.accent.copy(alpha = 0.2f).toArgb())
                            }
                            language?.let { setEditorLanguage(it) }
                            setText(content)
                            subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                                modified = true
                                canUndo = canUndo()
                                canRedo = canRedo()
                                scheduleLiveCheck(this)
                            }
                            editor = this
                            scheduleLiveCheck(this)
                        }
                    },
                    onRelease = { it.release() },
                )
            }
        }
        // Stays visible without the keyboard too: undo and the toggles are useful while reading.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallIconButton(BoxyIcons.Undo, stringResource(R.string.editor_undo), enabled = canUndo, container = colors.card) { editor?.undo() }
            SmallIconButton(BoxyIcons.Redo, stringResource(R.string.editor_redo), enabled = canRedo, container = colors.card) { editor?.redo() }
            BarChip("Tab") { editor?.indentOrCommitTab() }
            BarChip("\u21E4") { editor?.unindentSelection() }
            for ((label, insert, offset) in SYMBOLS) BarChip(label) { editor?.insertText(insert, offset) }
            BarToggle(stringResource(R.string.editor_mono), mono) {
                mono = !mono
                sp.edit().putBoolean("editor_mono", mono).apply()
                editor?.typefaceText = if (mono) Typeface.MONOSPACE else Typeface.DEFAULT
            }
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(if (wrap) colors.accent.copy(alpha = 0.25f) else colors.card).clickable {
                    wrap = !wrap
                    sp.edit().putBoolean("editor_wrap", wrap).apply()
                    editor?.isWordwrap = wrap
                },
                contentAlignment = Alignment.Center,
            ) {
                Icon(BoxyIcons.WrapText, stringResource(R.string.editor_wrap), Modifier.size(20.dp), tint = if (wrap) colors.accent else colors.text)
            }
        }
    }

    sections?.let { list ->
        if (list.isEmpty()) {
            LaunchedEffect(Unit) {
                Toast.makeText(context, R.string.editor_no_sections, Toast.LENGTH_SHORT).show()
                sections = null
            }
        } else {
            ChoiceDialog(
                title = stringResource(R.string.editor_sections),
                choices = list.map { Choice(it.line, it.title) },
                selected = -1,
                onSelect = { line ->
                    sections = null
                    goTo(line)
                },
                onDismiss = { sections = null },
            )
        }
    }

    DisposableEffect(Unit) {
        ToolsNav.editorOpen = true
        onDispose {
            ToolsNav.editorOpen = false
            editor = null
        }
    }

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
        val at = remember(output) { ConfigDiagnostics.lineInCoreOutput(output) }
        ConfigErrorDialog(
            output,
            stringResource(R.string.check_save_anyway),
            onProceed = { checkError = null; editor?.let { write(it.text.toString(), false) } },
            onDismiss = { checkError = null },
            onEdit = at?.let { (line, column) ->
                {
                    checkError = null
                    editor?.let { underline(it, line, column, output) }
                    goTo(line, column)
                }
            },
            editLabel = stringResource(R.string.editor_goto_error),
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

private const val LIVE_CHECK_DELAY_MS = 700L

/** Label, text to insert, and where the cursor lands inside that text. */
private val SYMBOLS = listOf(
    Triple("-", "- ", 2),
    Triple(":", ": ", 2),
    Triple("#", "# ", 2),
    Triple("\"", "\"\"", 1),
    Triple("'", "''", 1),
    Triple("[ ]", "[]", 1),
    Triple("{ }", "{}", 1),
    Triple(",", ",", 1),
    Triple("/", "/", 1),
    Triple("|", "|", 1),
    Triple("&", "&", 1),
    Triple("*", "*", 1),
)

@Composable
private fun BarChip(label: String, background: Color = Boxy.colors.card, tint: Color = Boxy.colors.text, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(50)).background(background).clickable(onClick = onClick).padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = tint, maxLines = 1)
    }
}

@Composable
private fun BarToggle(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Boxy.colors
    BarChip(label, if (on) c.accent.copy(alpha = 0.25f) else c.card, if (on) c.accent else c.text, onClick)
}

/**
 * Sora lets wrapped text still pan sideways by half the width (its max scroll X ignores word wrap), so a
 * slanted vertical swipe shifted the page left or right. With wrap on there is nothing to the side.
 */
private class WrapEditor(context: Context) : CodeEditor(context) {
    override fun getScrollMaxX(): Int = if (isWordwrap) 0 else super.getScrollMaxX()

    override fun setWordwrap(wordwrap: Boolean, antiWordBreaking: Boolean, supportRtlRow: Boolean) {
        super.setWordwrap(wordwrap, antiWordBreaking, supportRtlRow)
        // Back to the left edge; the scroller is not there yet while the base constructor runs.
        val s: io.github.rosemoe.sora.widget.EditorScroller? = scroller
        if (wordwrap && s != null && s.currX != 0) {
            s.forceFinished(true)
            s.startScroll(s.currX, s.currY, -s.currX, 0, 0)
            invalidate()
        }
    }
}

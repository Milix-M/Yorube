package com.hermes.client.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.SnackbarHost
import androidx.compose.material.SnackbarHostState
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.material.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Menu
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.User
import com.composables.icons.lucide.Zap
import com.hermes.client.HermesApp
import com.hermes.client.R

@Composable
fun ChatScreen(
    sessionId: String?,
    onOpenSettings: () -> Unit,
    onNavigateBack: () -> Unit = {},
    onOpenDrawer: (() -> Unit)? = null,
    title: String? = null,
    instanceNonce: Int = 0,
) {
    // onNavigateBack is kept for API compatibility; this screen has no back arrow.
    @Suppress("UNUSED_PARAMETER")
    val unusedBack = onNavigateBack
    val app = LocalContext.current.applicationContext as HermesApp
    val vm: ChatViewModel = viewModel(
        key = "chat-${sessionId.orEmpty()}-$instanceNonce",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(app.container, sessionId) as T
        }
    )
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val clarifyAnswer = remember { mutableStateOf("") }
    // Folded blocks drive the list; hoist so auto-follow can target the last row.
    val messageSnapshot = vm.messages.toList()
    val blocks = remember(messageSnapshot) { buildChatBlocks(messageSnapshot) }

    LaunchedEffect(vm.error) {
        vm.error?.let { snackbarHostState.showSnackbar(it); vm.dismissError() }
    }
    // Follow the stream only while the user stays at the bottom.
    LaunchedEffect(vm.messages.size) {
        if (blocks.isNotEmpty()) {
            val thinking = vm.busy && vm.messages.none { it.kind == MsgKind.ASSISTANT }
            val last = blocks.size - 1 + if (thinking) 1 else 0
            listState.animateScrollToItem(last)
        }
    }

    Scaffold(
        topBar = {
            Column {
                ChatTopBar(
                    title = title?.ifBlank { null } ?: stringResource(R.string.new_chat),
                    onOpenDrawer = onOpenDrawer,
                    onOpenSettings = onOpenSettings,
                )
                if (vm.busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            ChatInputBar(
                input = vm.input,
                busy = vm.busy,
                statusLine = vm.statusLine,
                onInputChange = { vm.input = it },
                onSend = vm::onSend,
                onSteer = vm::onSteer,
                onStop = vm::onStop,
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (vm.messages.isEmpty() && !vm.busy) {
                ChatEmptyState(modifier = Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("chatList"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    blocks.forEach { block ->
                        when (block) {
                            is ChatBlock.Single -> {
                                val msg = block.msg
                                item(key = "msg-${msg.id}") {
                                    MessageRow(
                                        msg = msg,
                                        modifier = Modifier.animateItem(),
                                    )
                                }
                            }
                            is ChatBlock.Activity -> {
                                item(key = "activity-${block.msgs.first().id}") {
                                    ActivityLog(
                                        msgs = block.msgs,
                                        modifier = Modifier.animateItem(),
                                    )
                                }
                            }
                        }
                    }
                    if (vm.busy && vm.messages.none { it.kind == MsgKind.ASSISTANT }) {
                        item(key = "thinking") { ThinkingRow() }
                    }
                }
            }
        }
    }

    vm.approval?.let { req ->
        AlertDialog(
            onDismissRequest = { /* must choose */ },
            title = { Text(stringResource(R.string.approval_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    req.command?.let { cmd ->
                        Surface(
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            SelectionContainer {
                                Text(
                                    cmd,
                                    style = MaterialTheme.typography.body2.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    modifier = Modifier.padding(10.dp),
                                )
                            }
                        }
                    }
                    req.description?.let { Text(it, style = MaterialTheme.typography.body2) }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (choice in req.choices) {
                            Button(
                                onClick = { vm.answerApproval(choice) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(choice)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }

    vm.clarify?.let { prompt ->
        AlertDialog(
            onDismissRequest = { /* must answer */ },
            title = { Text(stringResource(R.string.question_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(prompt.prompt, style = MaterialTheme.typography.body2)
                    TextField(
                        value = clarifyAnswer.value,
                        onValueChange = { clarifyAnswer.value = it },
                        modifier = Modifier.fillMaxWidth().testTag("clarifyInput"),
                        maxLines = 4,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.answerClarify(clarifyAnswer.value)
                        clarifyAnswer.value = ""
                    },
                ) {
                    Text(stringResource(R.string.send))
                }
            },
        )
    }
}

@Composable
private fun ChatTopBar(
    title: String,
    onOpenDrawer: (() -> Unit)?,
    onOpenSettings: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = if (onOpenDrawer != null) {
            {
                IconButton(
                    onClick = onOpenDrawer,
                    modifier = Modifier.testTag("menuButton"),
                ) {
                    Icon(
                        imageVector = Lucide.Menu,
                        contentDescription = stringResource(R.string.menu),
                    )
                }
            }
        } else {
            null
        },
        actions = {
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.testTag("settingsButton"),
            ) {
                Icon(
                    imageVector = Lucide.Settings,
                    contentDescription = stringResource(R.string.settings_desc),
                )
            }
        },
        elevation = 0.dp,
    )
}

@Composable
private fun ChatInputBar(
    input: String,
    busy: Boolean,
    statusLine: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onSteer: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    // The runs API has no binary upload: an attachment becomes a visible
    // [image: name] reference token in the text the user actually sends.
    val attachLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val name = queryDisplayName(context, uri) ?: "image"
            val token = "[${context.getString(R.string.image_label)}: $name]"
            onInputChange(if (input.isBlank()) token else "$input $token")
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (busy || statusLine.isNotBlank()) {
            Text(
                if (busy && statusLine.isBlank()) stringResource(R.string.thinking) else statusLine,
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 6.dp),
                maxLines = 1,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(
                onClick = { attachLauncher.launch("image/*") },
                modifier = Modifier.testTag("attachButton"),
            ) {
                Icon(
                    imageVector = Lucide.Paperclip,
                    contentDescription = stringResource(R.string.attach),
                )
            }
            TextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f).testTag("chatInput"),
                placeholder = {
                    Text(
                        if (busy) stringResource(R.string.steer_hint)
                        else stringResource(R.string.message_hint)
                    )
                },
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { if (busy) onSteer() else onSend() },
                ),
                shape = RoundedCornerShape(20.dp),
                singleLine = false,
            )
            Spacer(Modifier.size(8.dp))
            if (busy) {
                IconButton(
                    onClick = onStop,
                    modifier = Modifier.testTag("stopButton"),
                ) {
                    Icon(
                        imageVector = Lucide.Square,
                        contentDescription = stringResource(R.string.stop),
                    )
                }
            }
            Button(
                onClick = { if (busy) onSteer() else onSend() },
                enabled = input.isNotBlank(),
                modifier = Modifier.size(48.dp).testTag("sendButton"),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(
                    imageVector = Lucide.ArrowUp,
                    contentDescription = stringResource(R.string.send),
                )
            }
        }
    }
}

/** Display name of a picked file for the attachment reference token. */
private fun queryDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
        }
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun ChatEmptyState(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colors
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Lucide.Sparkles,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = colors.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.empty_chat_title),
            style = MaterialTheme.typography.h5,
        )
    }
}

@Composable
private fun ThinkingRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("thinkingIndicator"),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
        Text(
            stringResource(R.string.thinking),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * Messages render as plain rows with a small role marker. There are no chat
 * bubbles anywhere: user, assistant, tool and error rows are all flat text.
 */
@Composable
private fun MessageRow(msg: UiMessage, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
    when (msg.kind) {
        MsgKind.USER -> {
            Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Lucide.User,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = muted,
                    )
                    Text(
                        stringResource(R.string.role_you),
                        style = MaterialTheme.typography.caption,
                        color = muted,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                SelectionContainer {
                    Text(
                        msg.text,
                        style = MaterialTheme.typography.body1,
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    )
                }
            }
        }
        MsgKind.ASSISTANT -> {
            val clipboard = LocalClipboardManager.current
            var copied by remember(msg.id) { mutableStateOf(false) }
            LaunchedEffect(copied) {
                if (copied) {
                    kotlinx.coroutines.delay(1200)
                    copied = false
                }
            }
            Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Lucide.Bot,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = muted,
                    )
                    Text(
                        stringResource(R.string.role_assistant),
                        style = MaterialTheme.typography.caption,
                        color = muted,
                        modifier = Modifier.padding(start = 4.dp).weight(1f),
                    )
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(msg.text))
                        copied = true
                    }) {
                        if (copied) {
                            Icon(
                                imageVector = Lucide.Copy,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                        Text(
                            if (copied) stringResource(R.string.copied) else stringResource(R.string.copy),
                            style = MaterialTheme.typography.caption,
                            modifier = Modifier.padding(start = 2.dp),
                        )
                    }
                }
                if (msg.text.isNotBlank()) {
                    ChatMarkdown(
                        text = msg.text,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        MsgKind.TOOL, MsgKind.STATUS -> {
            // Defensive fallback: tool/status messages are normally grouped into
            // ActivityLog and never reach here. Plain muted line, never a box.
            Text(
                msg.text,
                style = MaterialTheme.typography.caption,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
            )
        }
        MsgKind.ERROR -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
            ) {
                Icon(
                    imageVector = Lucide.TriangleAlert,
                    contentDescription = null,
                    tint = MaterialTheme.colors.error,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    msg.text,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.error,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }
        }
    }
}

/** Consecutive TOOL/STATUS rows are folded into one Activity block. */
internal sealed interface ChatBlock {
    data class Single(val msg: UiMessage) : ChatBlock
    data class Activity(val msgs: List<UiMessage>) : ChatBlock
}

internal fun buildChatBlocks(messages: List<UiMessage>): List<ChatBlock> {
    val out = mutableListOf<ChatBlock>()
    val pending = mutableListOf<UiMessage>()
    fun flush() {
        if (pending.isNotEmpty()) {
            out.add(ChatBlock.Activity(pending.toList()))
            pending.clear()
        }
    }
    for (m in messages) {
        if (m.kind == MsgKind.TOOL || m.kind == MsgKind.STATUS) {
            pending.add(m)
        } else {
            flush()
            out.add(ChatBlock.Single(m))
        }
    }
    flush()
    return out
}

/**
 * Tool/MCP activity folded into a single tappable summary row.
 * Collapsed: one summary line. Expanded: full parsed payloads.
 */
@Composable
private fun ActivityLog(msgs: List<UiMessage>, modifier: Modifier = Modifier) {
    var expanded by remember(msgs.firstOrNull()?.id) { mutableStateOf(false) }
    // Show the most recent payload last, like the previous implementation.
    val lastSummary = msgs.last().text.lineSequence().firstOrNull().orEmpty()
    val collapsedSummary = if (msgs.size == 1) lastSummary else stringResource(
        R.string.activity_summary, msgs.size, lastSummary
    )
    val muted = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClick = { expanded = !expanded },
                    role = Role.Button,
                )
                .testTag("activityToggle")
                .padding(vertical = 6.dp),
        ) {
            Icon(
                imageVector = Lucide.Zap,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = muted,
            )
            Text(
                collapsedSummary,
                style = MaterialTheme.typography.caption,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
            Icon(
                imageVector = if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                contentDescription = stringResource(
                    if (expanded) R.string.collapse else R.string.expand
                ),
                modifier = Modifier.size(16.dp),
                tint = muted,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            SelectionContainer {
                Column(Modifier.padding(start = 20.dp, top = 2.dp, bottom = 4.dp)) {
                    for (m in msgs) {
                        Divider(modifier = Modifier.padding(vertical = 3.dp))
                        // Summary line in bold, then the full parsed payload below.
                        val firstNl = m.text.indexOf("\n")
                        if (firstNl >= 0) {
                            Text(
                                m.text.substring(0, firstNl),
                                style = MaterialTheme.typography.caption.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                            Text(
                                m.text.substring(firstNl + 1),
                                style = MaterialTheme.typography.caption.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = muted,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            )
                        } else {
                            Text(
                                m.text,
                                style = MaterialTheme.typography.caption.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Markdown rendering on established components: fences, bold, code,
// links, headers, bullets, quotes. Plain text degrades gracefully.
// ---------------------------------------------------------------------------

private sealed interface MdBlock {
    data class Text(val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
}

private fun splitMarkdownBlocks(text: String): List<MdBlock> {
    var fences = text.split("```").size - 1
    val closed = if (fences % 2 == 1) "$text\n```" else text
    val out = mutableListOf<MdBlock>()
    var rest = closed
    while (true) {
        val open = rest.indexOf("```")
        if (open < 0) {
            if (rest.isNotBlank()) out.add(MdBlock.Text(rest.trim()))
            break
        }
        if (rest.substring(0, open).isNotBlank()) {
            out.add(MdBlock.Text(rest.substring(0, open).trim()))
        }
        val after = rest.substring(open + 3)
        val close = after.indexOf("```")
        if (close < 0) {
            if (after.isNotBlank()) out.add(MdBlock.Text(after.trim()))
            break
        }
        val body = after.substring(0, close)
        val firstNl = body.indexOf('\n')
        val lang = if (firstNl < 0) "" else body.substring(0, firstNl).trim()
        val code = if (firstNl < 0) body.trim() else body.substring(firstNl + 1).trimEnd()
        out.add(MdBlock.Code(lang, code))
        rest = after.substring(close + 3)
    }
    return out
}

@Composable
private fun ChatMarkdown(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in remember(text) { splitMarkdownBlocks(text) }) {
            when (block) {
                is MdBlock.Code -> ChatCodeBlock(language = block.lang, code = block.code)
                is MdBlock.Text -> ChatRichParagraph(block.text)
            }
        }
    }
}

private val MdLink = Regex("""\[([^\]]+)\]\(([^)\s]+)[^)]*\)""")

@Composable
private fun ChatRichParagraph(text: String) {
    val uriHandler = LocalUriHandler.current
    val colors = MaterialTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (rawLine in text.lines()) {
            val line = rawLine.trimEnd()
            if (line.isBlank()) continue
            val (marker, style, body) = when {
                line.startsWith("### ") -> Triple("### ", MaterialTheme.typography.h6, line.removePrefix("### "))
                line.startsWith("## ") -> Triple("## ", MaterialTheme.typography.h6, line.removePrefix("## "))
                line.startsWith("# ") -> Triple("# ", MaterialTheme.typography.h5, line.removePrefix("# "))
                line.startsWith("> ") -> Triple(
                    "> ",
                    MaterialTheme.typography.body2.copy(fontStyle = FontStyle.Italic),
                    line.removePrefix("> ")
                )
                line.startsWith("- ") || line.startsWith("* ") -> Triple("• ", MaterialTheme.typography.body1, line.drop(2))
                Regex("""^\d+\. """).containsMatchIn(line) ->
                    Triple("• ", MaterialTheme.typography.body1, line.replaceFirst(Regex("""^\d+\. """), ""))
                else -> Triple("", MaterialTheme.typography.body1, line)
            }
            @Suppress("UNUSED_VARIABLE")
            val unusedMarker = marker
            ClickableText(
                text = buildAnnotatedString {
                    append(marker)
                    var cursor = 0
                    val combined = Regex("""(\*\*.+?\*\*)|(`[^`\n]+`)|(\[[^\]]+\]\([^)\s]+[^)]*\))""")
                    for (m in combined.findAll(body)) {
                        val start = m.range.first
                        if (start > cursor) append(body.substring(cursor, start))
                        val token = m.value
                        when {
                            token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(token.removePrefix("**").removeSuffix("**"))
                            }
                            token.startsWith("`") -> withStyle(
                                SpanStyle(
                                    fontFamily = FontFamily.Monospace,
                                    background = colors.onSurface.copy(alpha = 0.10f),
                                )
                            ) { append(token.trim('`')) }
                            else -> {
                                val lm = MdLink.matchEntire(token)
                                if (lm != null) {
                                    pushStringAnnotation("url", lm.groupValues[2])
                                    withStyle(
                                        SpanStyle(
                                            color = colors.primary,
                                            textDecoration = TextDecoration.Underline,
                                        )
                                    ) { append(lm.groupValues[1]) }
                                    pop()
                                } else {
                                    append(token)
                                }
                            }
                        }
                        cursor = m.range.last + 1
                    }
                    if (cursor < body.length) append(body.substring(cursor))
                },
                style = style.copy(color = colors.onSurface),
                onClick = { offset ->
                    lastLinkAt(body, offset - marker.length)?.let { uriHandler.openUri(it) }
                },
            )
        }
    }
}

/** URL tapped inside a rendered paragraph, or null. */
private fun lastLinkAt(body: String, offset: Int): String? {
    if (offset < 0) return null
    var found: String? = null
    for (m in MdLink.findAll(body)) {
        if (offset in m.range) {
            found = m.groupValues[2]
            break
        }
    }
    return found
}

@Composable
private fun ChatCodeBlock(language: String, code: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1200)
            copied = false
        }
    }
    val muted = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
    Surface(
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.06f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    language.ifBlank { "text" },
                    style = MaterialTheme.typography.caption,
                    color = muted,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                }) {
                    Text(
                        if (copied) stringResource(R.string.copied) else stringResource(R.string.copy),
                        style = MaterialTheme.typography.caption,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            SelectionContainer {
                Text(
                    code,
                    style = MaterialTheme.typography.body2.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                )
            }
        }
    }
}

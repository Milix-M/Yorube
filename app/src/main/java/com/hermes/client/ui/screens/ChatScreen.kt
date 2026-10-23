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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composeunstyled.DisclosedContent
import com.composeunstyled.DisclosureButton
import com.composeunstyled.UnstyledDisclosure
import com.hermes.client.HermesApp
import com.hermes.client.R
import com.hermes.client.ui.ds.Hermes
import com.hermes.client.ui.ds.HermesButton
import com.hermes.client.ui.ds.HermesDialog
import com.hermes.client.ui.ds.HermesDivider
import com.hermes.client.ui.ds.HermesIconButton
import com.hermes.client.ui.ds.HermesLinearBar
import com.hermes.client.ui.ds.HermesScaffold
import com.hermes.client.ui.ds.HermesSnackbarHost
import com.hermes.client.ui.ds.HermesTextField
import com.hermes.client.ui.ds.HermesTopBar
import com.hermes.client.ui.ds.HermesTypingDots
import com.hermes.client.ui.ds.rememberHermesSnackbarState

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
    val snackbar = rememberHermesSnackbarState()
    val listState = rememberLazyListState()
    val clarifyAnswer = remember { mutableStateOf("") }
    // Folded blocks drive the list; hoist so auto-follow can target the last row.
    val messageSnapshot = vm.messages.toList()
    val blocks = remember(messageSnapshot) { buildChatBlocks(messageSnapshot) }

    LaunchedEffect(vm.error) {
        vm.error?.let { snackbar.show(it); vm.dismissError() }
    }
    // Follow the stream only while the user stays at the bottom.
    LaunchedEffect(vm.messages.size) {
        if (blocks.isNotEmpty()) {
            val thinking = vm.busy && vm.messages.none { it.kind == MsgKind.ASSISTANT }
            val last = blocks.size - 1 + if (thinking) 1 else 0
            listState.animateScrollToItem(last)
        }
    }

    HermesScaffold(
        topBar = {
            HermesTopBar(
                title = title?.ifBlank { null } ?: stringResource(R.string.new_chat),
                navigation = {
                    if (onOpenDrawer != null) {
                        HermesIconButton(
                            onClick = onOpenDrawer,
                            desc = stringResource(R.string.menu),
                            modifier = Modifier.testTag("menuButton"),
                        ) {
                            Image(imageVector = Icons.Filled.Menu, contentDescription = null)
                        }
                    }
                },
            ) {
                HermesIconButton(
                    onClick = onOpenSettings,
                    desc = stringResource(R.string.settings_desc),
                    modifier = Modifier.testTag("settingsButton"),
                ) {
                    Image(imageVector = Icons.Filled.Settings, contentDescription = null)
                }
            }
        },
        snackbar = { HermesSnackbarHost(snackbar) },
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
    ) {
        Box(Modifier.fillMaxSize()) {
            if (vm.messages.isEmpty() && !vm.busy) {
                ChatEmptyState(modifier = Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("chatList"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    blocks.forEach { block ->
                        when (block) {
                            is ChatBlock.Single -> {
                                val msg = block.msg
                                val index = vm.messages.indexOf(msg)
                                item(key = "msg-${msg.id}") {
                                    MessageRow(
                                        msg = msg,
                                        showCursor = index == vm.messages.size - 1 && vm.busy &&
                                            msg.kind == MsgKind.ASSISTANT,
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
        HermesDialog(
            visible = true,
            onDismiss = { /* must choose */ },
            dismissOnOutside = false,
            title = stringResource(R.string.approval_title),
            confirmLabel = null,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                req.command?.let { cmd ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Hermes.colors.surfaceVariant)
                            .padding(10.dp),
                    ) {
                        SelectionContainer {
                            BasicText(
                                cmd,
                                style = Hermes.type.mono.copy(color = Hermes.colors.onSurface),
                            )
                        }
                    }
                }
                req.description?.let { BasicText(it, style = Hermes.type.body.copy(color = Hermes.colors.onSurface)) }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (choice in req.choices) {
                        HermesButton(
                            choice,
                            onClick = { vm.answerApproval(choice) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    vm.clarify?.let { prompt ->
        HermesDialog(
            visible = true,
            onDismiss = { /* must answer */ },
            dismissOnOutside = false,
            title = stringResource(R.string.question_title),
            confirmLabel = stringResource(R.string.send),
            onConfirm = {
                vm.answerClarify(clarifyAnswer.value)
                clarifyAnswer.value = ""
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(prompt.prompt, style = Hermes.type.body.copy(color = Hermes.colors.onSurface))
                HermesTextField(
                    value = clarifyAnswer.value,
                    onValueChange = { clarifyAnswer.value = it },
                    modifier = Modifier.fillMaxWidth().testTag("clarifyInput"),
                    maxLines = 4,
                    singleLine = false,
                )
            }
        }
    }
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
        if (busy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            ) {
                HermesTypingDots(modifier = Modifier.size(56.dp, 12.dp))
                BasicText(
                    if (statusLine.isNotBlank()) statusLine else stringResource(R.string.thinking),
                    style = Hermes.type.labelSmall.copy(color = Hermes.colors.onSurfaceVariant),
                    modifier = Modifier.padding(start = 8.dp),
                    maxLines = 1,
                )
            }
            HermesLinearBar(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
        } else if (statusLine.isNotBlank()) {
            BasicText(
                statusLine,
                style = Hermes.type.labelSmall.copy(color = Hermes.colors.onSurfaceVariant),
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(Hermes.colors.surfaceVariant.copy(alpha = 0.6f))
                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            HermesIconButton(
                onClick = { attachLauncher.launch("image/*") },
                desc = stringResource(R.string.attach),
                modifier = Modifier.testTag("attachButton"),
            ) {
                Image(imageVector = Icons.Filled.Add, contentDescription = null)
            }
            HermesTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f).testTag("chatInput"),
                placeholder = if (busy) stringResource(R.string.steer_hint)
                else stringResource(R.string.message_hint),
                maxLines = 5,
                singleLine = false,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { if (busy) onSteer() else onSend() },
                ),
            )
            if (busy) {
                HermesIconButton(
                    onClick = onStop,
                    desc = stringResource(R.string.stop),
                    modifier = Modifier.testTag("stopButton"),
                ) {
                    Image(imageVector = Icons.Filled.Stop, contentDescription = null)
                }
                GradientSendButton(
                    enabled = input.isNotBlank(),
                    onClick = onSteer,
                    modifier = Modifier.testTag("sendButton"),
                )
            } else {
                GradientSendButton(
                    enabled = input.isNotBlank(),
                    onClick = onSend,
                    modifier = Modifier.testTag("sendButton"),
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
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Gemini-like hero: gradient app mark + single headline. No subtitle, no suggestion chips.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF4285F4), Color(0xFF9B72CB), Color(0xFFD96570))
                    )
                ),
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.ic_hero_spark),
                contentDescription = null,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        BasicText(
            stringResource(R.string.empty_chat_title),
            style = Hermes.type.headline.copy(
                brush = Brush.horizontalGradient(
                    listOf(Color(0xFF4285F4), Color(0xFF9B72CB), Color(0xFFD96570))
                )
            ),
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
        HermesTypingDots()
        BasicText(
            stringResource(R.string.thinking),
            style = Hermes.type.body.copy(color = Hermes.colors.onSurfaceVariant),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun MessageRow(msg: UiMessage, showCursor: Boolean, modifier: Modifier = Modifier) {
    when (msg.kind) {
        MsgKind.USER -> {
            Box(
                modifier.fillMaxWidth().padding(start = 48.dp, top = 4.dp, bottom = 4.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Hermes.colors.userBubble)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    SelectionContainer {
                        BasicText(
                            msg.text,
                            style = Hermes.type.body.copy(color = Hermes.colors.onUserBubble),
                        )
                    }
                }
            }
        }
        // Assistant replies render as plain text, never in a chat bubble.
        MsgKind.ASSISTANT -> {
            val clipboard = LocalClipboardManager.current
            var copied by remember(msg.id) { mutableStateOf(false) }
            LaunchedEffect(copied) {
                if (copied) {
                    kotlinx.coroutines.delay(1200)
                    copied = false
                }
            }
            Row(modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
                Column(Modifier.weight(1f, fill = false)) {
                    if (msg.text.isNotBlank()) {
                        HermesMarkdown(
                            text = msg.text,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (showCursor) {
                        BasicText(
                            "▍",
                            style = Hermes.type.body.copy(color = Hermes.colors.onSurfaceVariant),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            clipboard.setText(AnnotatedString(msg.text))
                            copied = true
                        }
                        .padding(10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        if (copied) stringResource(R.string.copied) else stringResource(R.string.copy),
                        style = Hermes.type.labelSmall.copy(color = Hermes.colors.onSurfaceVariant),
                    )
                }
            }
        }
        MsgKind.TOOL, MsgKind.STATUS -> {
            // Defensive fallback: tool/status messages are normally grouped into
            // ActivityLog and never reach here. Render as a plain one-line row
            // with no bubble so nothing ever shows a chat box.
            BasicText(
                msg.text,
                style = Hermes.type.labelSmall.copy(color = Hermes.colors.onSurfaceVariant),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
            )
        }
        MsgKind.ERROR -> {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Hermes.colors.errorContainer)
                    .padding(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        "!",
                        style = Hermes.type.label.copy(color = Hermes.colors.onErrorContainer),
                    )
                    BasicText(
                        msg.text,
                        style = Hermes.type.body.copy(color = Hermes.colors.onErrorContainer),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
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
 * Tool/MCP activity folded into a single line with no chat bubble.
 * Collapsed: one tappable summary row. Expanded: full parsed payloads.
 */
@Composable
private fun ActivityLog(msgs: List<UiMessage>, modifier: Modifier = Modifier) {
    var expanded by remember(msgs.firstOrNull()?.id) { mutableStateOf(false) }
    val summary = if (msgs.size == 1) {
        msgs.first().text.lineSequence().firstOrNull().orEmpty()
    } else {
        stringResource(R.string.activity_summary, msgs.size, msgs.last().text.lineSequence().firstOrNull().orEmpty())
    }
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        UnstyledDisclosure(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            DisclosureButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("activityToggle"),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .padding(vertical = 6.dp),
                ) {
                    BasicText(
                        if (expanded) "▾" else "▸",
                        style = Hermes.type.label.copy(color = Hermes.colors.onSurfaceVariant),
                    )
                    BasicText(
                        "⚡",
                        style = Hermes.type.labelSmall.copy(color = Hermes.colors.onSurfaceVariant),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                    BasicText(
                        summary,
                        style = Hermes.type.labelSmall.copy(color = Hermes.colors.onSurfaceVariant),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 6.dp),
                    )
                }
            }
            DisclosedContent(
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                SelectionContainer {
                    Column(Modifier.padding(start = 22.dp, top = 2.dp, bottom = 4.dp)) {
                        for (m in msgs) {
                            HermesDivider(modifier = Modifier.padding(vertical = 3.dp))
                            BasicText(
                                m.text,
                                style = Hermes.type.mono.copy(color = Hermes.colors.onSurface),
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
// Minimal markdown rendering (no Material dependency): fences, bold, code,
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
private fun HermesMarkdown(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in remember(text) { splitMarkdownBlocks(text) }) {
            when (block) {
                is MdBlock.Code -> HermesCodeBlock(language = block.lang, code = block.code)
                is MdBlock.Text -> HermesRichParagraph(block.text)
            }
        }
    }
}

private val MdLink = Regex("""\[([^\]]+)\]\(([^)\s]+)[^)]*\)""")

@Composable
private fun HermesRichParagraph(text: String) {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (rawLine in text.lines()) {
            val line = rawLine.trimEnd()
            if (line.isBlank()) continue
            val (marker, style, body) = when {
                line.startsWith("### ") -> Triple("### ", Hermes.type.title.copy(color = Hermes.colors.onSurface), line.removePrefix("### "))
                line.startsWith("## ") -> Triple("## ", Hermes.type.title.copy(color = Hermes.colors.onSurface), line.removePrefix("## "))
                line.startsWith("# ") -> Triple("# ", Hermes.type.headline.copy(color = Hermes.colors.onSurface), line.removePrefix("# "))
                line.startsWith("> ") -> Triple("> ", Hermes.type.body.copy(color = Hermes.colors.onSurfaceVariant), line.removePrefix("> "))
                line.startsWith("- ") || line.startsWith("* ") -> Triple("• ", Hermes.type.body.copy(color = Hermes.colors.onSurface), line.drop(2))
                Regex("""^\d+\. """).containsMatchIn(line) ->
                    Triple("• ", Hermes.type.body.copy(color = Hermes.colors.onSurface), line.replaceFirst(Regex("""^\d+\. """), ""))
                else -> Triple("", Hermes.type.body.copy(color = Hermes.colors.onSurface), line)
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
                                    background = Hermes.colors.surfaceVariant,
                                )
                            ) { append(token.trim('`')) }
                            else -> {
                                val lm = MdLink.matchEntire(token)
                                if (lm != null) {
                                    pushStringAnnotation("url", lm.groupValues[2])
                                    withStyle(
                                        SpanStyle(
                                            color = Hermes.colors.primary,
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
                style = style,
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
private fun HermesCodeBlock(language: String, code: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1200)
            copied = false
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                language.ifBlank { "text" },
                style = Hermes.type.mono.copy(color = Color(0xFF9E9E9E)),
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                BasicText(
                    if (copied) stringResource(R.string.copied) else stringResource(R.string.copy),
                    style = Hermes.type.labelSmall.copy(color = Color(0xFF9E9E9E)),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        SelectionContainer {
            BasicText(
                code,
                style = Hermes.type.mono.copy(color = Color(0xFFE8E8E8)),
            )
        }
    }
}

/** Gemini-like gradient send button: blue to purple circle. */
@Composable
private fun GradientSendButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val background = if (enabled) {
        Brush.horizontalGradient(listOf(Hermes.colors.primary, Hermes.colors.accent))
    } else {
        Brush.horizontalGradient(
            listOf(Hermes.colors.surfaceVariant, Hermes.colors.surfaceVariant)
        )
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        BasicText(
            "↑",
            style = Hermes.type.title.copy(color = if (enabled) Hermes.colors.onPrimary else Hermes.colors.onSurfaceVariant),
        )
    }
}

package com.hermes.client.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.client.HermesApp
import com.hermes.client.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String?,
    onOpenSettings: () -> Unit,
    onNavigateBack: () -> Unit = {},
    onOpenDrawer: (() -> Unit)? = null,
    title: String? = null,
) {
    val app = LocalContext.current.applicationContext as HermesApp
    val vm: ChatViewModel = viewModel(
        key = "chat-${sessionId.orEmpty()}",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(app.container, sessionId) as T
        }
    )
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val clarifyAnswer = remember { mutableStateOf("") }
    // Folded blocks drive the list; hoist so auto-follow can target the last row.
    val messageSnapshot = vm.messages.toList()
    val blocks = remember(messageSnapshot) { buildChatBlocks(messageSnapshot) }

    LaunchedEffect(vm.error) {
        vm.error?.let { snackbar.showSnackbar(it); vm.dismissError() }
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
            TopAppBar(
                title = {
                    Text(
                        title?.ifBlank { null } ?: stringResource(R.string.new_chat),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (onOpenDrawer != null) {
                        IconButton(
                            onClick = onOpenDrawer,
                            modifier = Modifier.testTag("menuButton"),
                        ) {
                            Icon(
                                Icons.Filled.Menu,
                                contentDescription = stringResource(R.string.menu),
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("settingsButton"),
                    ) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings_desc),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
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
        AlertDialog(
            onDismissRequest = { /* must choose */ },
            confirmButton = {},
            title = { Text(stringResource(R.string.approval_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    req.command?.let { cmd ->
                        Surface(
                            tonalElevation = 2.dp,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SelectionContainer {
                                Text(
                                    cmd,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(10.dp),
                                )
                            }
                        }
                    }
                    req.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (choice in req.choices) {
                            FilledTonalButton(
                                onClick = { vm.answerApproval(choice) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(choice) }
                        }
                    }
                }
            },
        )
    }

    vm.clarify?.let { prompt ->
        AlertDialog(
            onDismissRequest = { /* must answer */ },
            confirmButton = {
                TextButton(onClick = {
                    vm.answerClarify(clarifyAnswer.value)
                    clarifyAnswer.value = ""
                }) { Text(stringResource(R.string.send)) }
            },
            title = { Text(stringResource(R.string.question_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(prompt.prompt)
                    androidx.compose.material3.OutlinedTextField(
                        value = clarifyAnswer.value,
                        onValueChange = { clarifyAnswer.value = it },
                        modifier = Modifier.fillMaxWidth().testTag("clarifyInput"),
                        maxLines = 4,
                    )
                }
            },
        )
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
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    if (statusLine.isNotBlank()) statusLine else stringResource(R.string.thinking),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
        } else if (statusLine.isNotBlank()) {
            Text(
                statusLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 2.dp,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            ) {
                TextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f).testTag("chatInput"),
                    placeholder = {
                        Text(
                            if (busy) stringResource(R.string.steer_hint)
                            else stringResource(R.string.message_hint),
                        )
                    },
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = { if (busy) onSteer() else onSend() },
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                if (busy) {
                    // ChatGPT-like stop + steer-send side by side.
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier.testTag("stopButton"),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.stop))
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
            Icon(
                painter = painterResource(R.drawable.ic_hero_spark),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.empty_chat_title),
            style = MaterialTheme.typography.headlineSmall.copy(
                brush = Brush.horizontalGradient(
                    listOf(Color(0xFF4285F4), Color(0xFF9B72CB), Color(0xFFD96570))
                )
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ThinkingRow() {
    val t = rememberInfiniteTransition(label = "thinking")
    val alpha by t.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thinkingAlpha",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("thinkingIndicator")
            .alpha(alpha),
    ) {
        LoadingIndicator(modifier = Modifier.size(22.dp))
        Text(
            stringResource(R.string.thinking),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun MessageRow(msg: UiMessage, showCursor: Boolean, modifier: Modifier = Modifier) {
    when (msg.kind) {
        MsgKind.USER -> {
            Box(modifier.fillMaxWidth().padding(start = 48.dp, top = 4.dp, bottom = 4.dp), contentAlignment = Alignment.CenterEnd) {
                Surface(
                    color = userBubbleColor(),
                    contentColor = userBubbleTextColor(),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.widthIn(max = 340.dp),
                ) {
                    SelectionContainer {
                        Text(
                            msg.text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
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
            // Same markdown components for every message; the model carries the text.
            val components = remember {
                markdownComponents(
                    codeFence = { ChatGptCodeFence(it) },
                    codeBlock = { ChatGptCodeFence(it) },
                )
            }
            Row(modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
                Surface(
                    color = assistantBubbleColor(),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        if (msg.text.isNotBlank()) {
                            Markdown(
                                content = closeUnclosedFences(msg.text),
                                components = components,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (showCursor) {
                            Text(
                                "▍",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(msg.text))
                        copied = true
                    },
                ) {
                    Icon(
                        if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = stringResource(if (copied) R.string.copied else R.string.copy),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        MsgKind.TOOL, MsgKind.STATUS -> {
            // Defensive fallback: tool/status messages are normally grouped into
            // ActivityLog and never reach here. Render as a plain one-line row
            // with no bubble so nothing ever shows a chat box.
            Text(
                msg.text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
            )
        }
        MsgKind.ERROR -> {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        msg.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
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
 * Collapsed: one tappable summary row. Expanded: plain one-line rows.
 */
@Composable
private fun ActivityLog(msgs: List<UiMessage>, modifier: Modifier = Modifier) {
    var expanded by remember(msgs.firstOrNull()?.id) { mutableStateOf(false) }
    val summary = if (msgs.size == 1) {
        msgs.first().text
    } else {
        stringResource(R.string.activity_summary, msgs.size, msgs.last().text)
    }
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .testTag("activityToggle")
                .padding(vertical = 6.dp),
        ) {
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.collapse else R.string.expand
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Icon(
                Icons.Filled.Bolt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            Column(Modifier.padding(start = 22.dp)) {
                for (m in msgs) {
                    Text(
                        m.text,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** ChatGPT-like neutral user bubble: light gray / dark charcoal. */
@Composable
private fun userBubbleColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFF2F2F2F) else Color(0xFFECECEC)

@Composable
private fun userBubbleTextColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFFEDEDED) else Color(0xFF0D0D0D)

/** LINE-like peer bubble: white / dark gray. Markdown uses theme text colors. */
@Composable
private fun assistantBubbleColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFF242424) else Color.White

/** Gemini-like gradient send button: blue to purple circle. */
@Composable
private fun GradientSendButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val background = if (enabled) {
        Brush.horizontalGradient(listOf(Color(0xFF4285F4), Color(0xFF9B72CB)))
    } else {
        Brush.horizontalGradient(
            listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
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
        Icon(
            Icons.Filled.ArrowUpward,
            contentDescription = stringResource(R.string.send),
            tint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Closes a trailing unclosed ``` fence so a still-streaming code block
 * already renders as a block instead of raw backticks.
 */
private fun closeUnclosedFences(text: String): String {
    val fences = text.split("```").size - 1
    return if (fences % 2 == 1) text + "\n```" else text
}

@Composable
private fun ColumnScope.ChatGptCodeFence(model: MarkdownComponentModel) {
    val lang = model.node.findChildOfType(MarkdownTokenTypes.FENCE_LANG)
        ?.getTextInNode(model.content)?.toString()?.trim().orEmpty()
    val code = model.node.children
        .filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
        .joinToString("") { it.getTextInNode(model.content).toString() }
        .trimEnd()
        .ifEmpty {
            // Indented code block (no fence): fall back to raw node text.
            model.node.getTextInNode(model.content).toString().trim()
        }
    ChatGptCodeBlock(language = lang, code = code)
}

@Composable
private fun ChatGptCodeBlock(language: String, code: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1200)
            copied = false
        }
    }
    Surface(
        color = Color.Black,
        contentColor = Color(0xFFE8E8E8),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
            ) {
                Text(
                    language.ifBlank { "text" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFF9E9E9E),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = stringResource(if (copied) R.string.copied else R.string.copy),
                        tint = Color(0xFF9E9E9E),
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            SelectionContainer {
                Text(
                    code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 10.dp),
                )
            }
        }
    }
}

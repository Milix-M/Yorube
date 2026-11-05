package com.hermes.client.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import com.hermes.client.data.api.SessionInfo
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.ui.theme.GeminiSparkAvatar
import com.hermes.client.ui.theme.geminiHeadlineStyle
import com.hermes.client.ui.theme.geminiHorizontalBrush
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String?,
    onNavigateBack: () -> Unit = {},
    onOpenDrawer: (() -> Unit)? = null,
    title: String? = null,
    instanceNonce: Int = 0,
    recentSessions: List<SessionInfo> = emptyList(),
    onNavigateToFreshChat: () -> Unit = {},
    onSessionCreated: (String) -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as HermesApp
    val vm: ChatViewModel = viewModel(
        key = "chat-${sessionId.orEmpty()}-$instanceNonce",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UnstableApiUsage", "UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(app.container, sessionId) as T
        }
    )
    // The /new slash command leaves through the same navigation as back.
    LaunchedEffect(onNavigateToFreshChat) {
        vm.navigateToFreshChat = onNavigateToFreshChat
    }
    val snackbar = remember { SnackbarHostState() }
    val forkLabel = stringResource(R.string.fork_and_continue)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clarifyAnswer = remember { mutableStateOf("") }
    // Input behavior follows Settings: Enter sends only when opted in.
    val displaySettings by app.container.settingsStore.settings.collectAsState(initial = ClientSettings())
    val messageSnapshot = vm.messages.toList()
    val blocks = remember(messageSnapshot) { buildChatBlocks(messageSnapshot) }

    LaunchedEffect(vm.error) {
        // Foreign sessions ask via dialog/banner instead: the transient
        // snackbar would vanish before the user can choose.
        if (vm.foreignBlocked) return@LaunchedEffect
        vm.error?.let { msg ->
            // A foreign-session block offers fork-and-continue as the action.
            val result = if (vm.forkOffer != null) {
                snackbar.showSnackbar(msg, actionLabel = forkLabel)
            } else {
                snackbar.showSnackbar(msg)
            }
            if (result == SnackbarResult.ActionPerformed) vm.forkAndContinue()
            else vm.dismissError()
        }
    }
    // The jump button shows while the newest user prompt is off-screen.
    // Tapping it lands on that prompt. It stays hidden once the prompt is
    // visible: the auto-follow above already tracks the streaming reply.
    val lastUserIndex = remember(blocks) { lastUserBlockIndex(blocks) }
    val atLatestUser by remember(lastUserIndex) {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index
            lastVisible != null && lastVisible >= lastUserIndex
        }
    }
    // Open at the newest user message without animation (history only);
    // follow the stream while busy. Fresh chats always follow the reply;
    // opened histories only follow while the user stays at the bottom.
    // Manual scrolls never trigger a jump.
    val freshChat = remember(sessionId, instanceNonce) { sessionId.isNullOrBlank() }
    var initialJumpDone by remember(sessionId, instanceNonce) { mutableStateOf(false) }
    LaunchedEffect(vm.messages.size) {
        if (blocks.isEmpty()) return@LaunchedEffect
        if (!initialJumpDone && !vm.busy && !freshChat) {
            listState.scrollToItem(lastUserBlockIndex(blocks))
            initialJumpDone = true
        } else if (vm.busy && (atLatestUser || freshChat)) {
            val thinking = vm.awaitingReply
            val last = blocks.size - 1 + if (thinking) 1 else 0
            listState.animateScrollToItem(last)
        } else if (atLatestUser) {
            listState.scrollToItem(blocks.size - 1)
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    // Header title: route title wins; a fresh chat adopts the server title
    // once known, falling back to the first user prompt.
    val serverTitle = remember(vm.sessionId, recentSessions) {
        recentSessions.find { it.key == vm.sessionId && vm.sessionId.isNotBlank() }
            ?.title?.ifBlank { null }
    }
    val firstUser = remember(messageSnapshot) {
        messageSnapshot.firstOrNull { it.kind == MsgKind.USER }
            ?.text?.trim()?.take(32)?.ifBlank { null }
    }
    val displayTitle = title?.ifBlank { null }
        ?: serverTitle
        ?: firstUser
        ?: stringResource(R.string.new_chat)

    // A fresh chat becomes history as soon as the server assigns a session:
    // refresh the list so the header (and drawer) learn its title.
    LaunchedEffect(vm.sessionId) {
        if (sessionId.isNullOrBlank() && vm.sessionId.isNotBlank()) {
            onSessionCreated(vm.sessionId)
        }
    }

    // A started conversation is history: system back opens a fresh chat.
    // Fresh chats keep the default behavior (back exits).
    BackHandler(enabled = vm.sessionId.isNotBlank() || !sessionId.isNullOrBlank()) {
        onNavigateToFreshChat()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GeminiChatTopBar(
                title = displayTitle,
                scrollBehavior = scrollBehavior,
                onOpenDrawer = onOpenDrawer,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            ChatInputBar(
                input = vm.input,
                busy = vm.busy,
                enabled = !vm.foreignBlocked,
                enterToSend = displaySettings.enterToSend,
                onInputChange = { vm.input = it },
                onSend = vm::onSend,
                onSteer = vm::onSteer,
                onStop = vm::onStop,
                onFork = vm::forkAndContinue,
                showForkBanner = vm.foreignBlocked && !vm.forkDialogOpen,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (vm.messages.isEmpty() && !vm.busy) {
                if (vm.historyLoading) {
                    ChatHistoryLoading(
                        status = vm.statusLine,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    ChatEmptyState(modifier = Modifier.fillMaxSize())
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("chatList"),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
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
                    if (vm.awaitingReply) {
                        item(key = "thinking") { ThinkingRow() }
                    }
                }
            }
            if (!atLatestUser && blocks.isNotEmpty()) {
                SmallFloatingActionButton(
                    // Jump to the newest user prompt.
                    onClick = {
                        scope.launch { listState.animateScrollToItem(lastUserBlockIndex(blocks)) }
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .testTag("scrollToBottom"),
                ) {
                    Icon(
                        Icons.Filled.ArrowDownward,
                        contentDescription = stringResource(R.string.scroll_to_bottom),
                    )
                }
            }
        }
    }

    if (vm.forkDialogOpen) {
        AlertDialog(
            onDismissRequest = vm::dismissForkDialog,
            confirmButton = {
                TextButton(
                    onClick = vm::forkAndContinue,
                    modifier = Modifier.testTag("forkConfirm"),
                ) { Text(stringResource(R.string.fork_and_continue)) }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissForkDialog) { Text(stringResource(R.string.not_now)) }
            },
            icon = { GeminiSparkAvatar(size = 40.dp, iconSize = 22.dp) },
            title = { Text(stringResource(R.string.foreign_title)) },
            text = { Text(stringResource(R.string.foreign_message)) },
        )
    }

    vm.approval?.let { req ->
        AlertDialog(
            onDismissRequest = { /* must choose */ },
            confirmButton = {},
            icon = {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFFF9AB00), Color(0xFFD96570))
                            )
                        ),
                ) {
                    Icon(
                        Icons.Filled.WarningAmber,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
            },
            title = { Text(stringResource(R.string.approval_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.approval_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    req.command?.let { cmd ->
                        Surface(
                            color = Color(0xFF0F0F0F),
                            contentColor = Color(0xFFE8E8E8),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Terminal,
                                        contentDescription = null,
                                        tint = Color(0xFF9E9E9E),
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        "command",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = Color(0xFF9E9E9E),
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                                SelectionContainer {
                                    Text(
                                        cmd,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        lineHeight = 19.sp,
                                        modifier = Modifier.padding(horizontal = 12.dp)
                                            .padding(bottom = 12.dp),
                                    )
                                }
                            }
                        }
                    }
                    req.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            icon = { GeminiSparkAvatar(size = 40.dp, iconSize = 22.dp) },
            title = { Text(stringResource(R.string.question_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(prompt.prompt, style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.OutlinedTextField(
                        value = clarifyAnswer.value,
                        onValueChange = { clarifyAnswer.value = it },
                        placeholder = { Text(stringResource(R.string.clarify_hint)) },
                        modifier = Modifier.fillMaxWidth().testTag("clarifyInput"),
                        maxLines = 4,
                        shape = RoundedCornerShape(16.dp),
                    )
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeminiChatTopBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    onOpenDrawer: (() -> Unit)?,
) {
    TopAppBar(
        title = {
            AnimatedContent(
                targetState = title.ifBlank { stringResource(R.string.new_chat) },
                transitionSpec = {
                    (fadeIn() + slideInVertically { it / 3 })
                        .togetherWith(fadeOut() + slideOutVertically { -it / 3 })
                },
                label = "chatTitle",
            ) { animatedTitle ->
                Text(
                    animatedTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
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
        colors = TopAppBarDefaults.topAppBarColors(
            // Translucent so scrolled chat slides underneath without a hard edge.
            // Text keeps its full-alpha color.
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
            scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
        ),
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun ChatInputBar(
    input: String,
    busy: Boolean,
    enabled: Boolean,
    enterToSend: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onSteer: () -> Unit,
    onStop: () -> Unit,
    onFork: () -> Unit,
    showForkBanner: Boolean,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    // The attach picker needs an ActivityResultRegistryOwner. On some hosts
    // the bottom-bar subcomposition recomposes without one and
    // rememberLauncherForActivityResult throws, killing startup outright —
    // so skip the picker instead of crashing the whole chat. The owner is
    // root-provided and never flips, so this branch is stable per
    // composition.
    val attachLauncher = LocalActivityResultRegistryOwner.current?.let {
        rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri ->
            if (uri != null) {
                val name = queryDisplayName(context, uri) ?: "image"
                val token = "[${context.getString(R.string.image_label)}: $name]"
                onInputChange(if (input.isBlank()) token else "$input $token")
            }
        }
    }
    // Sending clears focus so the keyboard closes.
    val sendAndClose: () -> Unit = { focusManager.clearFocus(); onSend() }
    val steerAndClose: () -> Unit = { focusManager.clearFocus(); onSteer() }
    val submitAndClose: () -> Unit = { if (busy) steerAndClose() else sendAndClose() }
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            // No imePadding(): the manifest uses adjustResize (and the app is
            // not edge-to-edge), so the window itself already moves above the
            // keyboard. Adding imePadding() on top offsets twice and leaves a
            // keyboard-sized white gap.
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (showForkBanner) {
            ForkBanner(
                onFork = onFork,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            ),
            shadowElevation = 2.dp,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(6.dp),
            ) {
                IconButton(
                    onClick = { attachLauncher?.launch("image/*") },
                    enabled = enabled && attachLauncher != null,
                    modifier = Modifier.testTag("attachButton").size(44.dp),
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.attach),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Hardware keyboards: with Enter-to-send, plain Enter submits
                // and Shift+Enter keeps the line break. Software keyboards
                // follow imeAction instead. With the default (Enter = newline)
                // nothing is intercepted.
                val keyModifier = if (enterToSend && enabled) {
                    Modifier.onPreviewKeyEvent { event ->
                        if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                            if (event.isShiftPressed) {
                                false
                            } else {
                                submitAndClose()
                                true
                            }
                        } else {
                            false
                        }
                    }
                } else {
                    Modifier
                }
                TextField(
                    value = input,
                    onValueChange = onInputChange,
                    enabled = enabled,
                    modifier = keyModifier.weight(1f).testTag("chatInput"),
                    placeholder = {
                        Text(
                            when {
                                !enabled -> stringResource(R.string.foreign_input_hint)
                                busy -> stringResource(R.string.steer_hint)
                                else -> stringResource(R.string.message_hint)
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        )
                    },
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = if (enterToSend) ({ submitAndClose() }) else null,
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
                    // While running, the send button itself becomes the stop button.
                    CircleStopButton(
                        onClick = onStop,
                        modifier = Modifier.testTag("stopButton"),
                    )
                } else {
                    GradientSendButton(
                        enabled = enabled && input.isNotBlank(),
                        onClick = sendAndClose,
                        modifier = Modifier.testTag("sendButton"),
                    )
                }
            }
        }
    }
}

/** Persistent read-only notice with a one-tap fork action. */
@Composable
private fun ForkBanner(onFork: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.testTag("forkBanner"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Text(
                stringResource(R.string.foreign_banner),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            TextButton(onClick = onFork) { Text(stringResource(R.string.fork_and_continue)) }
        }
    }
}

@Composable
private fun ChatEmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        AuroraBackground(Modifier.fillMaxSize())
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(76.dp)
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
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.empty_chat_title),
            style = geminiHeadlineStyle(),
        )
        }
    }
}

/**
 * Gemini-style living background for the home screen: slow-drifting color
 * orbs plus a twinkling starfield. Kept faint so text stays readable on
 * light and dark themes.
 */
@Composable
private fun AuroraBackground(modifier: Modifier = Modifier) {
    val drift = rememberInfiniteTransition(label = "aurora")
    val ax by drift.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ax",
    )
    val ay by drift.animateFloat(
        initialValue = 1f,
        targetValue = -1f,
        animationSpec = infiniteRepeatable(
            animation = tween(11000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ay",
    )
    // Loops seamlessly: per-star speeds are whole cycles per loop.
    val t by drift.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000),
            repeatMode = RepeatMode.Restart,
        ),
        label = "twinkle",
    )
    val stars = remember {
        val rnd = kotlin.random.Random(42)
        List(46) {
            Star(
                x = rnd.nextFloat(),
                y = rnd.nextFloat(),
                r = 1f + rnd.nextFloat() * 2.2f,
                phase = rnd.nextFloat(),
                speed = 1 + rnd.nextInt(3),
                tint = if (rnd.nextFloat() < 0.5f) 0xFF8AB4F8 else 0xFFC58AF9,
            )
        }
    }
    Box(modifier = modifier) {
        AuroraOrb(
            color = Color(0xFF4285F4),
            size = 420.dp,
            align = Alignment.TopStart,
            dx = { ax * 90.dp.toPx() },
            dy = { ay * 70.dp.toPx() },
        )
        AuroraOrb(
            color = Color(0xFF9B72CB),
            size = 460.dp,
            align = Alignment.BottomEnd,
            dx = { ax * -110.dp.toPx() },
            dy = { ay * -80.dp.toPx() },
        )
        AuroraOrb(
            color = Color(0xFFD96570),
            size = 300.dp,
            align = Alignment.CenterEnd,
            dx = { ax * 70.dp.toPx() },
            dy = { ay * 110.dp.toPx() },
        )
        Canvas(Modifier.fillMaxSize()) {
            for (s in stars) {
                val twinkle = 0.5f + 0.5f * kotlin.math.sin(
                    (t * s.speed + s.phase) * 2f * kotlin.math.PI.toFloat()
                )
                drawCircle(
                    color = Color(s.tint).copy(alpha = 0.10f + 0.45f * twinkle),
                    radius = s.r.dp.toPx(),
                    center = Offset(s.x * size.width, s.y * size.height),
                )
            }
        }
    }
}

/** One soft drifting color orb. */
@Composable
private fun AuroraOrb(
    color: Color,
    size: androidx.compose.ui.unit.Dp,
    align: Alignment,
    dx: Density.() -> Float,
    dy: Density.() -> Float,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = align,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .offset { IntOffset(dx().roundToInt(), dy().roundToInt()) }
                .background(
                    Brush.radialGradient(
                        listOf(color.copy(alpha = 0.20f), Color.Transparent)
                    ),
                    CircleShape,
                ),
        )
    }
}

/** A twinkling speck in the home starfield. Tint stored as ARGB long. */
private data class Star(
    val x: Float,
    val y: Float,
    val r: Float,
    val phase: Float,
    val speed: Int,
    val tint: Long,
)

@Composable
private fun ChatHistoryLoading(status: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(
            status.ifBlank { stringResource(R.string.thinking) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ThinkingRow() {
    // A shuffled pool: a different opener every run, cycling through
    // several messages while a long thinking phase continues.
    val messages = remember {
        listOf(
            R.string.thinking,
            R.string.thinking_2,
            R.string.thinking_3,
            R.string.thinking_4,
            R.string.thinking_5,
        ).shuffled()
    }
    var index by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2800)
            index = (index + 1) % messages.size
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .testTag("thinkingIndicator"),
    ) {
        Column {
            AnimatedContent(
                targetState = messages[index],
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "thinkingText",
            ) { res ->
                ShimmerText(stringResource(res))
            }
            Spacer(Modifier.height(6.dp))
            TypingDots()
        }
    }
}

@Composable
private fun ShimmerText(text: String) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val alpha by t.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(
            brush = Brush.horizontalGradient(
                listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                    Color(0xFF9B72CB).copy(alpha = alpha),
                )
            ),
            fontWeight = FontWeight.Medium,
        ),
    )
}

/** Gemini-like typing indicator: three dots bounce forward, then back. */
@Composable
private fun TypingDots() {
    // One shared 0->2->0 sweep: dot i peaks when the sweep passes i, so the
    // highlight travels 0,1,2,1,0... (forward then reverse, never jumping).
    val progress by rememberInfiniteTransition(label = "typing").animateFloat(
        initialValue = 0f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "progress",
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until 3) {
            val closeness = (1f - (progress - i).absoluteValue).coerceIn(0f, 1f)
            val scale = 0.5f + 0.5f * closeness
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = 0.4f + 0.6f * scale
                    }
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF4285F4), Color(0xFF9B72CB))
                        )
                    ),
            )
        }
    }
}

@Composable
private fun MessageRow(msg: UiMessage, showCursor: Boolean, modifier: Modifier = Modifier) {
    when (msg.kind) {
        MsgKind.USER -> {
            Box(modifier.fillMaxWidth().padding(start = 56.dp, top = 4.dp, bottom = 4.dp), contentAlignment = Alignment.CenterEnd) {
                Surface(
                    color = userBubbleColor(),
                    contentColor = userBubbleTextColor(),
                    shape = RoundedCornerShape(
                        topStart = 22.dp,
                        topEnd = 22.dp,
                        bottomStart = 22.dp,
                        bottomEnd = 6.dp,
                    ),
                    shadowElevation = 1.dp,
                    modifier = Modifier.widthIn(max = 340.dp),
                ) {
                    SelectionContainer {
                        Text(
                            msg.text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
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
            val components = remember {
                markdownComponents(
                    codeFence = { ChatGptCodeFence(it) },
                    codeBlock = { ChatGptCodeFence(it) },
                )
            }
            Column(modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp)) {
                if (msg.text.isNotBlank()) {
                        SelectionContainer {
                            Markdown(
                                content = closeUnclosedFences(msg.text),
                                components = components,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (showCursor) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "▍",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(6.dp))
                            TypingDots()
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        IconButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(msg.text))
                                copied = true
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                                contentDescription = stringResource(if (copied) R.string.copied else R.string.copy),
                                tint = if (copied) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        AnimatedVisibility(visible = copied) {
                            Text(
                                stringResource(R.string.copied),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
            }
        }
        MsgKind.TOOL, MsgKind.STATUS -> {
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
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(14.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp).padding(top = 2.dp),
                    )
                    Text(
                        msg.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
    }
}

/** Index of the newest user message block; falls back to the last block. */
private fun lastUserBlockIndex(blocks: List<ChatBlock>): Int {
    val i = blocks.indexOfLast { it is ChatBlock.Single && it.msg.kind == MsgKind.USER }
    return if (i >= 0) i else blocks.size - 1
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
 * Gemini-style activity card: tonal rounded card with spark header.
 * Collapsed: one tappable summary row. Expanded: full timeline.
 */
@Composable
private fun ActivityLog(msgs: List<UiMessage>, modifier: Modifier = Modifier) {
    var expanded by remember(msgs.firstOrNull()?.id) { mutableStateOf(false) }
    val summary = if (msgs.size == 1) {
        msgs.first().text
    } else {
        stringResource(R.string.activity_summary, msgs.size, msgs.last().text)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(
            0.5.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { expanded = !expanded }
                    .testTag("activityToggle")
                    .padding(vertical = 6.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(geminiHorizontalBrush()),
                ) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(13.dp),
                    )
                }
                Text(
                    summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.collapse else R.string.expand
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                SelectionContainer {
                    Column(Modifier.padding(start = 4.dp, top = 2.dp, bottom = 6.dp)) {
                        for ((index, m) in msgs.withIndex()) {
                            // IntrinsicSize.Min fixes the row height to the text,
                            // so the weight(1f) connector below spans the full
                            // row even for multi-line details.
                            Row(
                                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 4.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                    )
                                    if (index != msgs.size - 1) {
                                        Box(
                                            modifier = Modifier
                                                .width(2.dp)
                                                .weight(1f)
                                                .padding(top = 3.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                                ),
                                        )
                                    }
                                }
                                Text(
                                    m.text,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Display name of a picked file for the attachment reference token. */
private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
        }
    } catch (_: Exception) {
        null
    }
}

/** Gemini-style user bubble: soft blue-gray pill. */
@Composable
private fun userBubbleColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFF2F3033) else Color(0xFFE9EEF6)

@Composable
private fun userBubbleTextColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFFEDEDED) else Color(0xFF1F1F1F)

/** Stop button: same round slot and gradient as the send button. */
@Composable
private fun CircleStopButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color(0xFF4285F4), Color(0xFF9B72CB))))
            .clickable(onClick = onClick),
    ) {
        Icon(
            Icons.Filled.Stop,
            contentDescription = stringResource(R.string.stop),
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Gemini-like gradient send button: blue to violet circle with soft shadow. */
@Composable
private fun GradientSendButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val background = if (enabled) {
        Brush.linearGradient(listOf(Color(0xFF4285F4), Color(0xFF9B72CB)))
    } else {
        Brush.linearGradient(
            listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
        )
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(
            Icons.Filled.ArrowUpward,
            contentDescription = stringResource(R.string.send),
            tint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
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
        color = Color(0xFF0F0F0F),
        contentColor = Color(0xFFE8E8E8),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(
            0.5.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
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
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = stringResource(if (copied) R.string.copied else R.string.copy),
                        tint = if (copied) Color(0xFFA8C7FA) else Color(0xFF9E9E9E),
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
                        .padding(horizontal = 14.dp)
                        .padding(top = 8.dp, bottom = 12.dp),
                )
            }
        }
    }
}

package com.hermes.client.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.client.HermesApp
import com.hermes.client.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String?,
    onOpenSettings: () -> Unit,
    onNavigateBack: () -> Unit,
    onOpenDrawer: (() -> Unit)? = null,
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
    val scope = rememberCoroutineScope()
    val clarifyAnswer = remember { mutableStateOf("") }

    LaunchedEffect(vm.error) {
        vm.error?.let { snackbar.showSnackbar(it); vm.dismissError() }
    }
    // ChatGPT-like: follow the stream only while the user stays at the bottom.
    val canScrollForward by remember { derivedStateOf { listState.canScrollForward } }
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty() && !listState.canScrollForward) {
            listState.animateScrollToItem(vm.messages.size - 1)
        } else if (vm.messages.isNotEmpty() && listState.firstVisibleItemIndex == 0 && vm.messages.size <= 2) {
            listState.animateScrollToItem(vm.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (vm.sessionId.isBlank()) stringResource(R.string.new_chat)
                        else vm.sessionId.take(24),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    Row {
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
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("backButton"),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
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
                ChatEmptyState(
                    onSuggest = { text ->
                        vm.input = text
                        vm.onSend()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(vm.messages, key = { _, m -> m.id }) { index, msg ->
                        val isLast = index == vm.messages.size - 1
                        MessageRow(msg = msg, showCursor = isLast && vm.busy && msg.kind == MsgKind.ASSISTANT)
                    }
                    if (vm.busy && vm.messages.none { it.kind == MsgKind.ASSISTANT }) {
                        item(key = "thinking") { ThinkingRow() }
                    }
                }
            }
            if (canScrollForward && vm.messages.isNotEmpty()) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(vm.messages.size - 1) } },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .testTag("scrollBottom"),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.scroll_to_bottom),
                    )
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
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
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
                    FilledIconButton(
                        onClick = onSteer,
                        modifier = Modifier.testTag("sendButton"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.send),
                        )
                    }
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = input.isNotBlank(),
                        modifier = Modifier.testTag("sendButton"),
                    ) {
                        Icon(
                            Icons.Filled.ArrowUpward,
                            contentDescription = stringResource(R.string.send),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatEmptyState(onSuggest: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.empty_chat_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_chat_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        val suggestions = listOf(
            stringResource(R.string.suggest_1),
            stringResource(R.string.suggest_2),
            stringResource(R.string.suggest_3),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in suggestions) {
                AssistChip(
                    onClick = { onSuggest(s) },
                    label = { Text(s) },
                )
            }
        }
    }
}

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
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(
            stringResource(R.string.thinking),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun MessageRow(msg: UiMessage, showCursor: Boolean) {
    when (msg.kind) {
        MsgKind.USER -> {
            Box(Modifier.fillMaxWidth().padding(start = 48.dp, top = 4.dp, bottom = 4.dp), contentAlignment = Alignment.CenterEnd) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
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
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(
                        msg.text + if (showCursor) "▍" else "",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(end = 4.dp),
                    )
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
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) {
                Text(
                    msg.text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        MsgKind.ERROR -> {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
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

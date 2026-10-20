package com.hermes.client.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.client.HermesApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String?,
    onOpenSettings: () -> Unit,
    onNavigateBack: () -> Unit,
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

    LaunchedEffect(vm.error) {
        vm.error?.let { snackbar.showSnackbar(it); vm.dismissError() }
    }
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.sessionId.isBlank()) "New chat" else vm.sessionId.take(20)) },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) { Text("‹") }
                },
                actions = {
                    if (vm.busy) {
                        TextButton(onClick = vm::onStop) { Text("Stop") }
                    }
                    TextButton(onClick = onOpenSettings) { Text("⚙") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                if (vm.statusLine.isNotBlank()) {
                    Text(vm.statusLine, style = MaterialTheme.typography.labelSmall)
                }
                Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = vm.input,
                        onValueChange = { vm.input = it },
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        placeholder = { Text(if (vm.busy) "Steer the run…" else "Message…") },
                        maxLines = 4,
                    )
                    if (vm.busy) {
                        Button(onClick = vm::onSteer) { Text("→") }
                    } else {
                        Button(onClick = vm::onSend) { Text("↑") }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp),
        ) {
            items(vm.messages, key = { it.id }) { msg ->
                MessageCard(msg)
            }
        }
    }

    vm.approval?.let { req ->
        AlertDialog(
            onDismissRequest = { /* must choose */ },
            confirmButton = {},
            title = { Text("Approval needed") },
            text = {
                Column {
                    req.command?.let { Text(it) }
                    req.description?.let { Text(it) }
                    Row {
                        for (choice in req.choices) {
                            OutlinedButton(
                                onClick = { vm.answerApproval(choice) },
                                modifier = Modifier.padding(end = 8.dp),
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
                }) { Text("Send") }
            },
            title = { Text("Question") },
            text = {
                Column {
                    Text(prompt.prompt)
                    OutlinedTextField(
                        value = clarifyAnswer.value,
                        onValueChange = { clarifyAnswer.value = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )
    }
}

@Composable
private fun MessageCard(msg: UiMessage) {
    val container = when (msg.kind) {
        MsgKind.USER -> MaterialTheme.colorScheme.primaryContainer
        MsgKind.ERROR -> MaterialTheme.colorScheme.errorContainer
        MsgKind.TOOL, MsgKind.STATUS -> MaterialTheme.colorScheme.surfaceVariant
        MsgKind.ASSISTANT -> MaterialTheme.colorScheme.surface
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(
            msg.text,
            style = if (msg.kind == MsgKind.TOOL || msg.kind == MsgKind.STATUS) {
                MaterialTheme.typography.labelMedium
            } else {
                MaterialTheme.typography.bodyMedium
            },
            modifier = Modifier.padding(10.dp),
        )
    }
}

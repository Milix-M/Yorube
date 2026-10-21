package com.hermes.client.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.hermes.client.HermesApp
import com.hermes.client.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConversationListScreen(
    onOpenChat: (String?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as HermesApp
    val vm: ConversationListViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ConversationListViewModel(app.container) as T
        }
    )
    val snackbar = remember { SnackbarHostState() }
    val pendingDelete = remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(vm.error) {
        vm.error?.let { snackbar.showSnackbar(it) ; vm.dismissError() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hermes") },
                actions = {
                    IconButton(onClick = onOpenSettings) { Text("⚙") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { onOpenChat(null) }) { Text("+") }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                vm.loading && vm.sessions.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                vm.sessions.isEmpty() ->
                    Text(
                        stringResource(R.string.empty_conversations),
                        Modifier.align(Alignment.Center).padding(24.dp),
                    )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(vm.sessions, key = { it.key.ifBlank { it.displayTitle } }) { s ->
                        ListItem(
                            headlineContent = { Text(s.displayTitle) },
                            supportingContent = { Text(s.key.take(24)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onOpenChat(s.key) },
                                    onLongClick = { pendingDelete.value = s.key },
                                )
                                .padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }

    pendingDelete.value?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete.value = null },
            confirmButton = {
                TextButton(onClick = { vm.delete(id); pendingDelete.value = null }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete.value = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(id.take(40)) },
        )
    }
}

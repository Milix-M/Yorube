package com.hermes.client.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.client.HermesApp
import com.hermes.client.R
import com.hermes.client.data.api.SessionInfo

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConversationListScreen(
    onOpenChat: (String?, String?) -> Unit,
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
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.refresh() }
    // Auto-update the list whenever returning to this screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(vm.error) {
        vm.error?.let { snackbar.showSnackbar(it); vm.dismissError() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hermes") },
                actions = {
                    IconButton(
                        onClick = vm::refresh,
                        modifier = Modifier.testTag("refreshButton"),
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.refresh),
                        )
                    }
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("settingsButton"),
                    ) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings_desc),
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onOpenChat(null, null) },
                modifier = Modifier.testTag("newChat"),
            ) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_chat)) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SearchField(query = query, onQuery = { query = it })
            Box(Modifier.fillMaxSize().weight(1f)) {
                ConversationListBody(
                    loading = vm.loading,
                    sessions = vm.sessions.filterQuery(query),
                    onOpenChat = onOpenChat,
                    onAskDelete = { pendingDelete = it },
                )
            }
        }
    }

    pendingDelete?.let { id ->
        DeleteDialog(id = id, onDismiss = { pendingDelete = null }, onConfirm = { vm.delete(id); pendingDelete = null })
    }
}

/** ChatGPT-like drawer content, reuses the same list ViewModel. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationDrawerContent(
    selectedSessionId: String?,
    onOpenChat: (String?, String?) -> Unit,
    onOpenSettings: () -> Unit,
    onCloseDrawer: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as HermesApp
    val vm: ConversationListViewModel = viewModel(
        key = "drawer-list",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ConversationListViewModel(app.container) as T
        }
    )
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.refresh() }

    ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
        Column(Modifier.fillMaxHeight().padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Text(
                    "Hermes",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCloseDrawer, modifier = Modifier.testTag("closeDrawer")) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.back))
                }
            }
            FilledTonalButton(
                onClick = { onOpenChat(null, null) },
                modifier = Modifier.fillMaxWidth().testTag("drawerNewChat"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.new_chat), modifier = Modifier.padding(start = 8.dp))
            }
            SearchField(query = query, onQuery = { query = it }, modifier = Modifier.padding(vertical = 8.dp))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                ConversationListBody(
                    loading = vm.loading,
                    sessions = vm.sessions.filterQuery(query),
                    selectedKey = selectedSessionId,
                    compact = true,
                    onOpenChat = onOpenChat,
                    onAskDelete = { pendingDelete = it },
                )
            }
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth().testTag("drawerSettings"),
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.settings), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }

    pendingDelete?.let { id ->
        DeleteDialog(id = id, onDismiss = { pendingDelete = null }, onConfirm = { vm.delete(id); pendingDelete = null })
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text(stringResource(R.string.search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("searchInput"),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationListBody(
    loading: Boolean,
    sessions: List<SessionInfo>,
    onOpenChat: (String?, String?) -> Unit,
    onAskDelete: (String) -> Unit,
    selectedKey: String? = null,
    compact: Boolean = false,
) {
    when {
        loading && sessions.isEmpty() ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        sessions.isEmpty() ->
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    stringResource(R.string.empty_conversations),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        else -> LazyColumn(Modifier.fillMaxSize()) {
            var lastBucket: HistoryBucket? = null
            sessions.forEach { s ->
                val bucket = bucketFor(s.updatedAt)
                if (bucket != lastBucket) {
                    lastBucket = bucket
                    item(key = "header-$bucket") {
                        Text(
                            stringResource(bucket.titleRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp),
                        )
                    }
                }
                item(key = "session-${s.key.ifBlank { s.displayTitle }}") {
                    val selected = selectedKey != null && s.key == selectedKey
                    Surface(
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = if (compact) 0.dp else 4.dp, vertical = 1.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .combinedClickable(
                                onClick = { onOpenChat(s.key, s.displayTitle) },
                                onLongClick = { onAskDelete(s.key) },
                            ),
                    ) {
                        ListItem(
                            headlineContent = {
                                Text(s.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            trailingContent = {
                                IconButton(onClick = { onAskDelete(s.key) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.delete),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeleteDialog(id: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        title = { Text(stringResource(R.string.delete_title)) },
        text = { Text(id.take(40)) },
    )
}

private fun List<SessionInfo>.filterQuery(q: String): List<SessionInfo> {
    if (q.isBlank()) return this
    return filter { it.displayTitle.contains(q, ignoreCase = true) || it.key.contains(q, ignoreCase = true) }
}

/** ChatGPT-like history sections. */
private enum class HistoryBucket(val titleRes: Int) {
    TODAY(R.string.history_today),
    WEEK(R.string.history_week),
    MONTH(R.string.history_month),
    OLDER(R.string.history_older),
}

private fun bucketFor(updatedAt: Double?): HistoryBucket {
    if (updatedAt == null) return HistoryBucket.OLDER
    // Server sends epoch seconds; tolerate millis/micros just in case.
    var epochSec = updatedAt
    while (epochSec > 1e11) epochSec /= 1000.0
    val ageDays = (System.currentTimeMillis() / 1000.0 - epochSec) / 86400.0
    return when {
        ageDays < 1 -> HistoryBucket.TODAY
        ageDays < 7 -> HistoryBucket.WEEK
        ageDays < 30 -> HistoryBucket.MONTH
        else -> HistoryBucket.OLDER
    }
}

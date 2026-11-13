package com.hermes.client.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
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
import com.hermes.client.ui.theme.GeminiSparkAvatar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConversationListScreen(
    onOpenChat: (String?, String?) -> Unit,
) {
    val app = LocalContext.current.applicationContext as HermesApp
    val vm: ConversationListViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UnstableApiUsage", "UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ConversationListViewModel(app.container) as T
        }
    )
    val snackbar = remember { SnackbarHostState() }
    var pendingMenu by remember { mutableStateOf<SessionInfo?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.refresh() }
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
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GeminiSparkAvatar(size = 32.dp, iconSize = 18.dp)
                        Column(modifier = Modifier.padding(start = 10.dp)) {
                            Text(
                                "Hermes Agent",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                            )
                            Text(
                                stringResource(R.string.hermes_subtitle),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
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
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onOpenChat(null, null) },
                modifier = Modifier.testTag("newChat"),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.new_chat))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SearchField(query = query, onQuery = { query = it })
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxSize().weight(1f)) {
                ConversationListBody(
                    loading = vm.loading,
                    sessions = vm.sessions.filterQuery(query),
                    pinnedIds = vm.pinnedIds,
                    onOpenChat = onOpenChat,
                    onShowOptions = { pendingMenu = it },
                )
            }
        }
    }

    pendingMenu?.let { session ->
        SessionOptionsDialog(
            session = session,
            isPinned = vm.isPinned(session.key),
            onTogglePin = { vm.togglePin(session.key); pendingMenu = null },
            onAskDelete = { pendingMenu = null; pendingDelete = session.key },
            onDismiss = { pendingMenu = null },
        )
    }

    pendingDelete?.let { id ->
        DeleteDialog(
            label = vm.sessions.find { it.key == id }?.title?.ifBlank { null }
                ?: stringResource(R.string.new_chat),
            onDismiss = { pendingDelete = null },
            onConfirm = { vm.delete(id); pendingDelete = null },
        )
    }
}

/** Gemini-like drawer: brand header, pill actions, recent history. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationDrawerContent(
    selectedSessionId: String?,
    drawerState: DrawerState,
    onOpenChat: (String?, String?) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onCloseDrawer: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as HermesApp
    val vm: ConversationListViewModel = viewModel(
        key = "drawer-list",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UnstableApiUsage", "UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ConversationListViewModel(app.container) as T
        }
    )
    var pendingMenu by remember { mutableStateOf<SessionInfo?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.refresh() }
    // Refresh every time the drawer opens so new chats show up immediately.
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) vm.refresh()
    }

    ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
        Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp, vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                Text(
                    "Hermes Agent",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCloseDrawer, modifier = Modifier.testTag("closeDrawer")) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.back))
                }
            }
            Spacer(Modifier.height(8.dp))
            DrawerItemRow(
                icon = Icons.Filled.Add,
                label = stringResource(R.string.new_chat),
                testTag = "drawerNewChat",
                highlighted = true,
                onClick = { onOpenChat(null, null) },
            )
            Spacer(Modifier.height(6.dp))
            DrawerItemRow(
                icon = Icons.Filled.Search,
                label = stringResource(R.string.search_chats),
                testTag = "drawerSearch",
                onClick = onOpenSearch,
            )
            Box(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)) {
                ConversationListBody(
                    loading = vm.loading,
                    sessions = vm.sessions,
                    pinnedIds = vm.pinnedIds,
                    selectedKey = selectedSessionId,
                    compact = true,
                    onOpenChat = onOpenChat,
                    onShowOptions = { pendingMenu = it },
                )
            }
            // Whole row reacts, not just the icon.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f))
                    .clickable(onClick = onOpenSettings)
                    .testTag("drawerSettings")
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    stringResource(R.string.settings),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }

    pendingMenu?.let { session ->
        SessionOptionsDialog(
            session = session,
            isPinned = vm.isPinned(session.key),
            onTogglePin = { vm.togglePin(session.key); pendingMenu = null },
            onAskDelete = { pendingMenu = null; pendingDelete = session.key },
            onDismiss = { pendingMenu = null },
        )
    }

    pendingDelete?.let { id ->
        DeleteDialog(
            label = vm.sessions.find { it.key == id }?.title?.ifBlank { null }
                ?: stringResource(R.string.new_chat),
            onDismiss = { pendingDelete = null },
            onConfirm = {
                vm.delete(id)
                pendingDelete = null
                // Deleted the open conversation: leave it for a fresh chat
                // instead of lingering on a session the server no longer serves.
                if (id == selectedSessionId) onOpenChat(null, null)
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerItemRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    testTag: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (highlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                else Color.Transparent
            )
            .combinedClickable(onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** Full chat-search screen: query field plus the filtered conversation history. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConversationSearchScreen(
    onOpenChat: (String?, String?) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as HermesApp
    val vm: ConversationListViewModel = viewModel(
        key = "search-list",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UnstableApiUsage", "UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ConversationListViewModel(app.container) as T
        }
    )
    var pendingMenu by remember { mutableStateOf<SessionInfo?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_chats)) },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("searchBack"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                }
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SearchField(
                query = query,
                onQuery = { query = it },
                modifier = Modifier.padding(vertical = 8.dp),
                fieldTag = "searchScreenInput",
            )
            Box(Modifier.fillMaxSize().weight(1f)) {
                ConversationListBody(
                    loading = vm.loading,
                    sessions = vm.sessions.filterQuery(query),
                    pinnedIds = vm.pinnedIds,
                    onOpenChat = onOpenChat,
                    onShowOptions = { pendingMenu = it },
                )
            }
        }
    }

    pendingMenu?.let { session ->
        SessionOptionsDialog(
            session = session,
            isPinned = vm.isPinned(session.key),
            onTogglePin = { vm.togglePin(session.key); pendingMenu = null },
            onAskDelete = { pendingMenu = null; pendingDelete = session.key },
            onDismiss = { pendingMenu = null },
        )
    }

    pendingDelete?.let { id ->
        DeleteDialog(
            label = vm.sessions.find { it.key == id }?.title?.ifBlank { null }
                ?: stringResource(R.string.new_chat),
            onDismiss = { pendingDelete = null },
            onConfirm = { vm.delete(id); pendingDelete = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
    fieldTag: String = "searchInput",
) {
    TextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text(stringResource(R.string.search_hint)) },
        leadingIcon = {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = if (query.isNotBlank()) {
            {
                IconButton(onClick = { onQuery("") }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.cancel),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        } else null,
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(fieldTag),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationListBody(
    loading: Boolean,
    sessions: List<SessionInfo>,
    pinnedIds: Set<String>,
    onOpenChat: (String?, String?) -> Unit,
    onShowOptions: (SessionInfo) -> Unit,
    selectedKey: String? = null,
    compact: Boolean = false,
) {
    when {
        loading && sessions.isEmpty() ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.thinking),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        sessions.isEmpty() ->
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Brand mark, not a search glyph: this state is "no
                // conversations yet", not "no search results".
                GeminiSparkAvatar(size = 56.dp, iconSize = 28.dp)
                Text(
                    stringResource(R.string.empty_conversations),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        else -> LazyColumn(Modifier.fillMaxSize()) {
            // Flat newest-first list (server order, pinned on top): no
            // section headers, the rows speak for themselves.
            sessions.forEach { s ->
                item(key = "session-${s.key.ifBlank { s.displayTitle }}") {
                    val selected = selectedKey != null && s.key == selectedKey
                    val pinned = pinnedIds.contains(s.key)
                    Surface(
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = if (compact) 0.dp else 8.dp, vertical = 1.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .combinedClickable(
                                onClick = { onOpenChat(s.key, s.title) },
                                onLongClick = { onShowOptions(s) },
                            )
                            .animateItem(),
                    ) {
                        ListItem(
                            headlineContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        s.title?.ifBlank { null } ?: stringResource(R.string.new_chat),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Medium
                                        ),
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (pinned) {
                                        Box(
                                            modifier = Modifier
                                                .padding(start = 6.dp)
                                                .clearAndSetSemantics { },
                                        ) {
                                            Icon(
                                                Icons.Filled.PushPin,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionOptionsDialog(
    session: SessionInfo,
    isPinned: Boolean,
    onTogglePin: () -> Unit,
    onAskDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 8.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                stringResource(R.string.session_options),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Text(
                session.title?.ifBlank { null } ?: stringResource(R.string.new_chat),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp),
            )
            OptionRow(
                icon = Icons.Filled.PushPin,
                label = if (isPinned) stringResource(R.string.unpin)
                else stringResource(R.string.pin),
                testTag = "pinToggle",
                onClick = onTogglePin,
            )
            OptionRow(
                icon = Icons.Filled.Delete,
                label = stringResource(R.string.delete),
                testTag = "deleteOption",
                onClick = onAskDelete,
            )
            OptionRow(
                label = stringResource(R.string.cancel),
                onClick = onDismiss,
            )
        }
    }
}

/** Plain left-aligned option row for the bottom sheet (not button-styled). */
@Composable
private fun OptionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    testTag: String? = null,
) {
    var rowModifier = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 13.dp)
    if (testTag != null) rowModifier = rowModifier.testTag(testTag)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = rowModifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(20.dp),
        ) {
            icon?.let {
                Icon(
                    it,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun DeleteDialog(label: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        title = { Text(stringResource(R.string.delete_title)) },
        text = { Text(label.take(40)) },
    )
}

private fun List<SessionInfo>.filterQuery(q: String): List<SessionInfo> {
    if (q.isBlank()) return this
    return filter { it.displayTitle.contains(q, ignoreCase = true) || it.key.contains(q, ignoreCase = true) }
}

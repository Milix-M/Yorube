package com.hermes.client.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Trash2
import com.composeunstyled.theme.Theme
import com.composables.ui.components.AlertDialog
import com.composables.ui.components.Button
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.IndeterminateProgressIndicator
import com.composables.ui.components.Text
import com.composables.ui.components.Toolbar
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.primaryColor
import com.hermes.client.HermesApp
import com.hermes.client.R
import com.hermes.client.data.api.SessionInfo

@OptIn(ExperimentalFoundationApi::class)
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
    val snackbar = rememberAppSnackbarState()
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
        vm.error?.let { snackbar.show(it); vm.dismissError() }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Toolbar(
                title = {
                    Text(
                        "Hermes",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                trailing = {
                    IconButton(
                        onClick = vm::refresh,
                        style = ButtonStyle.Ghost,
                        modifier = Modifier.testTag("refreshButton"),
                    ) {
                        Icon(
                            imageVector = Lucide.RefreshCw,
                            contentDescription = stringResource(R.string.refresh),
                        )
                    }
                    IconButton(
                        onClick = onOpenSettings,
                        style = ButtonStyle.Ghost,
                        modifier = Modifier.testTag("settingsButton"),
                    ) {
                        Icon(
                            imageVector = Lucide.Settings,
                            contentDescription = stringResource(R.string.settings_desc),
                        )
                    }
                },
            )
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
        Button(
            onClick = { onOpenChat(null, null) },
            style = ButtonStyle.Primary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .testTag("newChat"),
        ) {
            Icon(
                imageVector = Lucide.Plus,
                contentDescription = stringResource(R.string.new_chat),
            )
        }
        AppSnackbarHost(snackbar)
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

/** Drawer content: new chat + search items, then history, then quiet settings. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationDrawerContent(
    selectedSessionId: String?,
    onOpenChat: (String?, String?) -> Unit,
    onOpenSearch: () -> Unit,
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

    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxHeight()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        ) {
            IconButton(
                onClick = onCloseDrawer,
                style = ButtonStyle.Ghost,
                modifier = Modifier.testTag("closeDrawer"),
            ) {
                Icon(
                    imageVector = Lucide.ArrowLeft,
                    contentDescription = stringResource(R.string.back),
                )
            }
        }
        // Primary drawer items: new chat, then chat search (opens the search screen).
        DrawerItemRow(
            icon = Lucide.Plus,
            label = stringResource(R.string.new_chat),
            testTag = "drawerNewChat",
            onClick = { onOpenChat(null, null) },
        )
        DrawerItemRow(
            icon = Lucide.Search,
            label = stringResource(R.string.search_chats),
            testTag = "drawerSearch",
            onClick = onOpenSearch,
        )
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)) {
            ConversationListBody(
                loading = vm.loading,
                sessions = vm.sessions,
                selectedKey = selectedSessionId,
                compact = true,
                onOpenChat = onOpenChat,
                onAskDelete = { pendingDelete = it },
            )
        }
        // Subtle footer: icon-sized settings, no longer a full-width button.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            IconButton(
                onClick = onOpenSettings,
                style = ButtonStyle.Ghost,
                modifier = Modifier.testTag("drawerSettings").size(40.dp),
            ) {
                Icon(
                    imageVector = Lucide.Settings,
                    contentDescription = stringResource(R.string.settings_desc),
                )
            }
            Text(
                stringResource(R.string.settings),
                fontSize = 12.sp,
                color = Theme[colors][mutedColor],
            )
            Spacer(Modifier.weight(1f))
            Text(
                "v" + com.hermes.client.BuildConfig.VERSION_NAME,
                fontSize = 12.sp,
                color = Theme[colors][mutedColor],
            )
        }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerItemRow(
    icon: ImageVector,
    label: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Text(
            label,
            fontSize = 15.sp,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** Full chat-search screen: query field plus the filtered conversation history. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationSearchScreen(
    onOpenChat: (String?, String?) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as HermesApp
    val vm: ConversationListViewModel = viewModel(
        key = "search-list",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ConversationListViewModel(app.container) as T
        }
    )
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxSize()) {
        Toolbar(
            title = {
                Text(
                    stringResource(R.string.search_chats),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            leading = {
                IconButton(
                    onClick = onNavigateBack,
                    style = ButtonStyle.Ghost,
                    modifier = Modifier.testTag("searchBack"),
                ) {
                    Icon(
                        imageVector = Lucide.ArrowLeft,
                        contentDescription = stringResource(R.string.back),
                    )
                }
            },
        )
        SearchField(query = query, onQuery = { query = it }, fieldTag = "searchScreenInput")
        Box(Modifier.fillMaxSize().weight(1f)) {
            ConversationListBody(
                loading = vm.loading,
                sessions = vm.sessions.filterQuery(query),
                onOpenChat = onOpenChat,
                onAskDelete = { pendingDelete = it },
            )
        }
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

@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
    fieldTag: String = "searchInput",
) {
    AppTextField(
        value = query,
        onValueChange = onQuery,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        placeholder = stringResource(R.string.search_hint),
        leading = {
            Icon(
                imageVector = Lucide.Search,
                contentDescription = null,
            )
        },
        trailing = if (query.isNotEmpty()) {
            { AppSearchClear(onClear = { onQuery("") }) }
        } else {
            null
        },
        singleLine = true,
        fieldTag = fieldTag,
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
    val muted = Theme[colors][mutedColor]
    when {
        loading && sessions.isEmpty() ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                IndeterminateProgressIndicator(modifier = Modifier.width(64.dp))
            }
        sessions.isEmpty() ->
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Lucide.Search,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = muted,
                )
                Text(
                    stringResource(R.string.empty_conversations),
                    fontSize = 14.sp,
                    color = muted,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        else -> LazyColumn(Modifier.fillMaxSize()) {
            // Sessions without a server timestamp render with no bucket header
            // instead of a misleading label. Headers re-emit after a gap.
            var lastBucket: HistoryBucket? = null
            var bucketActive = false
            sessions.forEach { s ->
                val bucket = s.updatedAt?.let { bucketFor(it) }
                if (bucket != null && (!bucketActive || bucket != lastBucket)) {
                    lastBucket = bucket
                    bucketActive = true
                    item(key = "header-$bucket") {
                        Text(
                            stringResource(bucket.titleRes),
                            fontSize = 12.sp,
                            color = muted,
                            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp),
                        )
                    }
                } else if (bucket == null) {
                    bucketActive = false
                }
                item(key = "session-${s.key.ifBlank { s.displayTitle }}") {
                    val selected = selectedKey != null && s.key == selectedKey
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = if (compact) 0.dp else 4.dp, vertical = 1.dp)
                            .combinedClickable(
                                onClick = { onOpenChat(s.key, s.title) },
                                onLongClick = { onAskDelete(s.key) },
                            )
                            .animateItem()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    s.title?.ifBlank { null } ?: stringResource(R.string.new_chat),
                                    fontSize = 15.sp,
                                    color = if (selected) Theme[colors][primaryColor]
                                    else Theme[colors][onBackgroundColor],
                                    maxLines = 1,
                                )
                                formatDateTime(s.updatedAt)?.let { date ->
                                    Text(
                                        date,
                                        fontSize = 12.sp,
                                        color = muted,
                                        maxLines = 1,
                                    )
                                }
                            }
                            IconButton(onClick = { onAskDelete(s.key) }) {
                                Icon(
                                    imageVector = Lucide.Trash2,
                                    contentDescription = stringResource(R.string.delete),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeleteDialog(label: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.delete_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                label.take(40),
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = onDismiss,
                    style = ButtonStyle.Ghost,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = onConfirm,
                    style = ButtonStyle.Destructive,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.delete))
                }
            }
        }
    }
}

/** Short date for conversation rows. Null when the server sent no timestamp. */
private fun formatDateTime(updatedAt: Double?): String? {
    if (updatedAt == null) return null
    var epochSec = updatedAt
    while (epochSec > 1e11) epochSec /= 1000.0
    return try {
        java.time.Instant.ofEpochSecond(epochSec.toLong())
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm"))
    } catch (_: Exception) {
        null
    }
}

private fun List<SessionInfo>.filterQuery(q: String): List<SessionInfo> {
    if (q.isBlank()) return this
    return filter { it.displayTitle.contains(q, ignoreCase = true) || it.key.contains(q, ignoreCase = true) }
}

/** History sections. Undated sessions deliberately get no bucket. */
private enum class HistoryBucket(val titleRes: Int) {
    TODAY(R.string.history_today),
    WEEK(R.string.history_week),
    MONTH(R.string.history_month),
    OLDER(R.string.history_older),
}

private fun bucketFor(updatedAt: Double): HistoryBucket {
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

package com.hermes.client.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.AppContainer
import com.hermes.client.data.api.SessionInfo
import com.hermes.client.data.api.isForeignSurface
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

class ConversationListViewModel(
    private val container: AppContainer,
) : ViewModel() {

    var sessions by mutableStateOf<List<SessionInfo>>(emptyList())
        private set
    var pinnedIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var showForeign by mutableStateOf(true)
        private set
    private var refreshJob: Job? = null

    fun refresh() {
        // Initial composition and ON_RESUME can request the same refresh together.
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            loading = true
            error = null
            try {
                val settings = container.settingsStore.current()
                pinnedIds = settings.pinnedSessionIds
                showForeign = settings.showForeignSessions
                val api = container.api(settings)
                val loaded = linkedMapOf<String, SessionInfo>()
                var offset = 0
                do {
                    val page = api.listSessions(limit = 50, offset = offset)
                    val before = loaded.size
                    page.forEach { if (it.key.isNotBlank()) loaded[it.key] = it }
                    sessions = sortPinnedFirst(filterForeign(loaded.values.toList(), settings), settings.pinnedSessionIds)
                    offset += page.size
                    // Also stop when an older server ignores offset, avoiding an endless fetch.
                } while (page.size == 50 && loaded.size > before)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = (e.message ?: e.javaClass.simpleName).take(200)
            } finally {
                loading = false
            }
        }
    }

    fun delete(sessionId: String) {
        viewModelScope.launch {
            try {
                container.api().deleteSession(sessionId)
                sessions = sessions.filterNot { it.key == sessionId }
                if (pinnedIds.contains(sessionId)) {
                    setPinned(sessionId, false)
                }
            } catch (e: Exception) {
                error = (e.message ?: e.javaClass.simpleName).take(200)
            }
        }
    }

    fun togglePin(sessionId: String) {
        viewModelScope.launch {
            setPinned(sessionId, null)
        }
    }

    fun isPinned(sessionId: String): Boolean = pinnedIds.contains(sessionId)

    private suspend fun setPinned(sessionId: String, pinned: Boolean?) {
        val next = container.settingsStore.setPinned(sessionId, pinned)
        pinnedIds = next
        sessions = sortPinnedFirst(sessions, next)
    }

    fun dismissError() {
        error = null
    }
}

private fun sortPinnedFirst(
    loaded: List<SessionInfo>,
    pinned: Set<String>,
): List<SessionInfo> {
    if (pinned.isEmpty()) return loaded
    val (pinnedList, rest) = loaded.partition { pinned.contains(it.key) }
    // Keep server order inside each group.
    val pinnedOrdered = pinnedList.sortedBy { loaded.indexOf(it) }
    return pinnedOrdered + rest
}

/**
 * Drops other-surface sessions when the user hid them: either the server
 * says so (owner/surface metadata), a past run was rejected with
 * permission_denied on that key (recorded in DataStore), or neither but
 * the key was never created by this app. Keys the app itself created or
 * successfully ran on always stay: explicit proof of "made by this app".
 */
internal fun filterForeign(
    loaded: List<SessionInfo>,
    settings: com.hermes.client.data.store.ClientSettings,
): List<SessionInfo> {
    if (settings.showForeignSessions) return loaded
    return loaded.filter { s ->
        s.key in settings.appOwnedSessionIds ||
            (!s.isForeignSurface() && !settings.foreignSessionIds.contains(s.key))
    }
}

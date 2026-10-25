package com.hermes.client.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.AppContainer
import com.hermes.client.data.api.SessionInfo
import kotlinx.coroutines.launch

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

    fun refresh() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                pinnedIds = container.settingsStore.current().pinnedSessionIds
                val loaded = container.api().listSessions()
                sessions = sortPinnedFirst(loaded, pinnedIds)
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
            setPinned(sessionId, !pinnedIds.contains(sessionId))
        }
    }

    fun isPinned(sessionId: String): Boolean = pinnedIds.contains(sessionId)

    private suspend fun setPinned(sessionId: String, pinned: Boolean) {
        val current = container.settingsStore.current()
        val next = if (pinned) current.pinnedSessionIds + sessionId
        else current.pinnedSessionIds - sessionId
        container.settingsStore.save(current.copy(pinnedSessionIds = next))
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

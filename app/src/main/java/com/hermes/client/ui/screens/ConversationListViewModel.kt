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
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun refresh() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                sessions = container.api().listSessions()
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
            } catch (e: Exception) {
                error = (e.message ?: e.javaClass.simpleName).take(200)
            }
        }
    }

    fun dismissError() {
        error = null
    }
}

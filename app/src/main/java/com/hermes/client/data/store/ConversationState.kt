package com.hermes.client.data.store

import android.content.Context
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Read state belongs to a connection, and survives process recreation. */
class ConversationState(context: Context) {
    private val preferences = context.getSharedPreferences("conversation_state", Context.MODE_PRIVATE)
    private val mutableUnread = MutableStateFlow(
        preferences.all.filterKeys { it.startsWith("unread:") }.mapValues { (_, value) ->
            (value as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
        }.mapKeys { (key, _) -> key.removePrefix("unread:") }
    )
    val unread = mutableUnread.asStateFlow()
    private data class Visible(val owner: Any, val profile: String, val session: String)
    private var visible: Visible? = null
    private var foreground = false

    fun setForeground(resumed: Boolean) {
        foreground = resumed
        if (resumed) visible?.let { markRead(it.profile, it.session) }
    }

    fun show(owner: Any, settings: ClientSettings, session: String) {
        visible = Visible(owner, profileKey(settings), session)
        if (foreground) markRead(profileKey(settings), session)
    }

    fun hide(owner: Any) {
        if (visible?.owner === owner) visible = null
    }

    fun adopt(owner: Any?, settings: ClientSettings, session: String) {
        val current = visible ?: return
        if (current.owner === owner && current.profile == profileKey(settings)) {
            visible = current.copy(session = session)
            if (foreground) markRead(current.profile, session)
        }
    }

    fun isVisible(settings: ClientSettings, session: String): Boolean =
        foreground && session.isNotBlank() && visible?.let {
            it.profile == profileKey(settings) && it.session == session
        } == true

    fun recordReply(settings: ClientSettings, session: String, run: String, terminal: Boolean) {
        if (session.isBlank()) return
        val profile = profileKey(settings)
        val eventKey = "$profile:$run"
        val completed = preferences.getStringSet("completed", emptySet()).orEmpty()
        if (terminal && eventKey in completed) return
        if (isVisible(settings, session)) markRead(profile, session)
        else updateUnread(profile, mutableUnread.value[profile].orEmpty() + session)
        if (terminal) preferences.edit().putStringSet("completed",
            (completed.toList().takeLast(511) + eventKey).toSet()).apply()
    }

    fun markRead(settings: ClientSettings, session: String) = markRead(profileKey(settings), session)

    private fun markRead(profile: String, session: String) {
        if (session in mutableUnread.value[profile].orEmpty()) {
            updateUnread(profile, mutableUnread.value[profile].orEmpty() - session)
        }
    }

    private fun updateUnread(profile: String, sessions: Set<String>) {
        if (mutableUnread.value[profile].orEmpty() == sessions) return
        mutableUnread.value = mutableUnread.value + (profile to sessions)
        preferences.edit().putStringSet("unread:$profile", sessions).apply()
    }

    fun rememberTitle(settings: ClientSettings, session: String, title: String) {
        if (session.isBlank() || title.isBlank()) return
        val key = "title:${profileKey(settings)}:${digest(session)}"
        val value = title.trim().take(200)
        if (preferences.getString(key, null) != value) preferences.edit().putString(key, value).apply()
    }

    fun title(settings: ClientSettings, session: String): String? =
        preferences.getString("title:${profileKey(settings)}:${digest(session)}", null)

    companion object {
        fun profileKey(settings: ClientSettings): String =
            digest(settings.baseUrl.trimEnd('/') + "\n" + settings.apiKey)

        private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

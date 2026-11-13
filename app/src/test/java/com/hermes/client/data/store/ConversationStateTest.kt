package com.hermes.client.data.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ConversationStateTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val settings = ClientSettings(baseUrl = "https://hermes.example", apiKey = "private-test-key")

    @Test fun unreadRepliesSurviveRecreationAndAreScopedToConnection() {
        val store = ConversationState(context)
        store.recordReply(settings, "conversation", "run", terminal = true)
        store.recordReply(settings, "", "ignored", terminal = true)
        val other = settings.copy(apiKey = "other-key")
        store.recordReply(other, "other", "run", terminal = false)
        val restored = ConversationState(context)
        assertEquals(setOf("conversation"), restored.unread.value[ConversationState.profileKey(settings)])
        assertEquals(setOf("other"), restored.unread.value[ConversationState.profileKey(other)])
        assertEquals(ConversationState.profileKey(settings), ConversationState.profileKey(settings.copy(baseUrl = settings.baseUrl + "/")))
        assertEquals(ConversationState.profileKey(settings), ConversationState.profileKey(settings.copy(cfClientSecret = "rotated")))
        assertFalse(context.getSharedPreferences("conversation_state", 0).all.keys.any { "private-test-key" in it })
    }

    @Test fun onlyTheForegroundConversationIsRead() {
        val store = ConversationState(context)
        val owner = Any()
        store.show(owner, settings, "s")
        assertFalse(store.isVisible(settings, "s"))
        store.recordReply(settings, "s", "r", false)
        store.setForeground(true)
        assertTrue(store.isVisible(settings, "s"))
        assertFalse(store.isVisible(settings, ""))
        assertFalse(store.isVisible(settings.copy(apiKey = "other"), "s"))
        assertEquals(emptySet<String>(), store.unread.value[ConversationState.profileKey(settings)])
        store.recordReply(settings, "s", "r", true)
        store.recordReply(settings, "other", "r2", true)
        assertEquals(setOf("other"), store.unread.value[ConversationState.profileKey(settings)])
        store.setForeground(false)
        store.recordReply(settings, "s", "r3", false)
        store.hide(owner)
        store.setForeground(true)
        assertFalse(store.isVisible(settings, "s"))
        store.markRead(settings, "s")
        store.markRead(settings, "not-present")
        assertEquals(setOf("other"), store.unread.value[ConversationState.profileKey(settings)])
    }

    @Test fun completedReplayDoesNotMakeAnAlreadyReadConversationUnreadAgain() {
        val store = ConversationState(context)
        store.recordReply(settings, "s", "r", true)
        store.markRead(settings, "s")
        ConversationState(context).recordReply(settings, "s", "r", true)
        assertEquals(emptySet<String>(), ConversationState(context).unread.value[ConversationState.profileKey(settings)])
    }

    @Test fun oldScreensCannotClearOrAdoptTheNewScreen() {
        val store = ConversationState(context)
        val old = Any()
        val current = Any()
        store.adopt(old, settings, "early")
        store.setForeground(true)
        store.show(old, settings, "old")
        store.show(current, settings, "")
        store.hide(old)
        store.adopt(old, settings, "wrong")
        store.adopt(current, settings.copy(apiKey = "different"), "wrong")
        store.adopt(current, settings, "accepted")
        assertTrue(store.isVisible(settings, "accepted"))
        store.hide(current)
        assertFalse(store.isVisible(settings, "accepted"))
    }

    @Test fun notificationTitlesArePersistedAndDoNotCrossProfiles() {
        val store = ConversationState(context)
        store.rememberTitle(settings, "", "ignored")
        store.rememberTitle(settings, "s", " ")
        assertNull(store.title(settings, "s"))
        store.rememberTitle(settings, "s", "  Project notes  ")
        store.rememberTitle(settings, "s", "Project notes")
        assertEquals("Project notes", ConversationState(context).title(settings, "s"))
        assertNull(store.title(settings.copy(baseUrl = "https://other.example"), "s"))
        store.rememberTitle(settings, "s", "x".repeat(400))
        assertEquals(200, store.title(settings, "s")!!.length)
    }
}

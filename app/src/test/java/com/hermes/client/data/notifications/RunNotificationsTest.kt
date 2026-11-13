package com.hermes.client.data.notifications

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.AppContainer
import com.hermes.client.R
import com.hermes.client.data.api.RunEvent
import com.hermes.client.data.api.RunStatusResponse
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.data.store.ConversationState
import com.hermes.client.data.stream.ActiveRun
import com.hermes.client.data.stream.RunStreamUpdate
import com.hermes.client.testutil.runVmTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class RunNotificationsTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val settings = ClientSettings(baseUrl = "https://hermes.example", apiKey = "k")
    private fun reply(run: String, text: String = "A new reply") =
        RunStreamUpdate.Event(run, RunEvent.Terminal("completed", text, null, null))

    @Test fun visibleConversationIsSilentAndReplayedCompletionRemainsSilent() = runVmTest {
        val c = AppContainer(app)
        c.settingsStore.save(settings); c.settingsStore.current()
        val owner = Any()
        c.conversations.setForeground(true)
        c.conversations.show(owner, settings, "s")
        c.runNotifications.onUpdate(ActiveRun("r", "s"), reply("r"))
        assertTrue(manager.activeNotifications.isEmpty())
        assertTrue(c.conversations.unread.value[ConversationState.profileKey(settings)].orEmpty().isEmpty())
        c.conversations.hide(owner); c.conversations.setForeground(false)
        c.runNotifications.onUpdate(ActiveRun("r", "s"), reply("r"))
        assertTrue(manager.activeNotifications.isEmpty())
        assertTrue(c.conversations.unread.value[ConversationState.profileKey(settings)].orEmpty().isEmpty())
    }

    @Test fun anotherConversationUsesItsTitleAndYorubeIconAndBecomesUnread() = runVmTest {
        val c = AppContainer(app)
        c.settingsStore.save(settings); c.settingsStore.current()
        c.conversations.setForeground(true); c.conversations.show(Any(), settings, "open")
        c.conversations.rememberTitle(settings, "other", "Project notes")
        c.runNotifications.onUpdate(ActiveRun("r", "other"), reply("r"))
        val notification = manager.activeNotifications.single().notification
        assertEquals("Project notes", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("A new reply", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(R.drawable.ic_notification, notification.smallIcon.resId)
        assertEquals(setOf("other"), c.conversations.unread.value[ConversationState.profileKey(settings)])
        c.runNotifications.dismissSession(settings, "open")
        assertEquals(1, manager.activeNotifications.size)
        c.runNotifications.dismissSession(settings, "other")
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun backgroundingAnOpenConversationAllowsItsNotification() = runVmTest {
        val c = AppContainer(app)
        c.settingsStore.save(settings); c.settingsStore.current()
        c.conversations.show(Any(), settings, "s")
        c.conversations.setForeground(false)
        c.runNotifications.onUpdate(ActiveRun("r", "s"), reply("r"))
        assertEquals(1, manager.activeNotifications.size)
    }

    @Test fun unreadStateIsIndependentOfNotificationPermissionsAndSettings() = runVmTest {
        val c = AppContainer(app)
        c.settingsStore.save(settings.copy(notificationsEnabled = false)); c.settingsStore.current()
        c.runNotifications.onUpdate(ActiveRun("r", "s"), RunStreamUpdate.Event("r", RunEvent.Delta("partial")))
        c.runNotifications.onUpdate(ActiveRun("r", "s"), reply("r"))
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(setOf("s"), c.conversations.unread.value[ConversationState.profileKey(settings)])
    }

    @Test fun eventsFromThePreviousConnectionDoNotNotifyOrMarkTheNewConnectionUnread() = runVmTest {
        val c = AppContainer(app)
        c.settingsStore.save(settings); c.settingsStore.current()
        c.runNotifications.onUpdate(ActiveRun("r", "s"), reply("r"), settings.copy(apiKey = "previous"))
        assertTrue(manager.activeNotifications.isEmpty())
        assertTrue(c.conversations.unread.value.isEmpty())
        c.runNotifications.onUpdate(ActiveRun("r2", "s"), RunStreamUpdate.PollTerminal("r2",
            RunStatusResponse(status = "completed", output = "polled")), settings)
        assertEquals(1, manager.activeNotifications.size)
    }
}

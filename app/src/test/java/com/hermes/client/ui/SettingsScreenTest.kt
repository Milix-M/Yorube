package com.hermes.client.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.HermesApp
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.ui.screens.SettingsScreen
import com.hermes.client.ui.theme.HermesAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun saveSettings(settings: ClientSettings) {
        val app = ApplicationProvider.getApplicationContext<Application>() as HermesApp
        kotlinx.coroutines.runBlocking {
            app.container.settingsStore.save(settings)
            val deadline = System.currentTimeMillis() + 10_000
            while (app.container.settingsStore.current().baseUrl != settings.baseUrl ||
                app.container.settingsStore.current().fcmToken != settings.fcmToken
            ) {
                if (System.currentTimeMillis() >= deadline) error("timed out saving settings")
                withContext(Dispatchers.IO) { Thread.sleep(100) }
            }
        }
    }

    @Test
    fun saveFlow_showsSaved() {
        compose.setContent {
            HermesAppTheme {
                SettingsScreen(onNavigateBack = {})
            }
        }
        compose.onNodeWithText("Server").assertIsDisplayed()
        compose.onNodeWithTag("baseUrl").performTextInput("https://example.com:8642")
        compose.onNodeWithTag("apiKey").performTextInput("k")
        for (tag in listOf("relayUrl", "fbProject", "fbApp", "fbKey", "fbSender", "cfId", "cfSecret")) {
            compose.onNodeWithTag(tag).performScrollTo()
            compose.onNodeWithTag(tag).performTextInput("v")
        }
        compose.onNodeWithTag("save").performScrollTo()
        compose.onNodeWithTag("save").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Saved ✓").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun cloudflareSection_isShown() {
        compose.setContent {
            HermesAppTheme {
                SettingsScreen(onNavigateBack = {})
            }
        }
        assertTrue(
            compose.onAllNodesWithText("Client ID").fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            compose.onAllNodesWithText("Client Secret").fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            compose.onAllNodesWithText("Firebase manual setup (optional)")
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun secretToggle_flipsVisibility() {
        compose.setContent {
            HermesAppTheme {
                SettingsScreen(onNavigateBack = {})
            }
        }
        compose.onNodeWithTag("apiKey").performScrollTo()
        compose.onNodeWithTag("visibility-apiKey").performClick()
        compose.onNodeWithTag("apiKey").assertIsDisplayed()
    }

    @Test
    fun fcmToken_showsSnippetWhenSet() {
        saveSettings(ClientSettings(baseUrl = "https://example.com", apiKey = "k", fcmToken = "abcdef1234567890"))
        compose.setContent {
            HermesAppTheme {
                SettingsScreen(onNavigateBack = {})
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("abcdef123456", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun displayOptions_persistOnSave() {
        saveSettings(ClientSettings(baseUrl = "https://example.com", apiKey = "k"))
        compose.setContent {
            HermesAppTheme {
                SettingsScreen(onNavigateBack = {})
            }
        }
        // Language is a dropdown: open it, see every choice, pick Japanese.
        compose.onNodeWithTag("langField").performScrollTo()
        compose.onNodeWithTag("langField").performClick()
        compose.onNodeWithTag("lang-ko").assertIsDisplayed()
        compose.onNodeWithTag("lang-ja").performClick()
        compose.onNodeWithTag("showForeign").performScrollTo()
        compose.onNodeWithTag("showForeign").performClick()
        compose.onNodeWithTag("enterToSend").performScrollTo()
        compose.onNodeWithTag("enterToSend").performClick()
        compose.onNodeWithTag("notificationsEnabled").performScrollTo()
        compose.onNodeWithTag("notificationsEnabled").performClick()
        compose.onNodeWithTag("save").performScrollTo()
        compose.onNodeWithTag("save").performClick()
        val app = ApplicationProvider.getApplicationContext<Application>() as HermesApp
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Saved ✓").fetchSemanticsNodes().isNotEmpty()
        }
        kotlinx.coroutines.runBlocking {
            val deadline = System.currentTimeMillis() + 10_000
            while (app.container.settingsStore.current().language != "ja") {
                if (System.currentTimeMillis() >= deadline) error("timed out saving language")
                withContext(Dispatchers.IO) { Thread.sleep(100) }
            }
        }
        kotlinx.coroutines.runBlocking {
            val saved = app.container.settingsStore.current()
            assertTrue(saved.showForeignSessions == false)
            assertTrue(saved.enterToSend == true)
            assertTrue(saved.notificationsEnabled == false)
        }
    }

    @Test
    fun testButton_showsHealthResult() {
        val want = server.url("").toString().removeSuffix("/")
        saveSettings(ClientSettings(baseUrl = want, apiKey = "k"))
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        server.enqueue(
            MockResponse().setBody("""{"features":{"run_stop":true},"endpoints":{}}""")
        )
        compose.setContent {
            HermesAppTheme {
                SettingsScreen(onNavigateBack = {})
            }
        }
        compose.onNodeWithText("Test connection").performScrollTo()
        compose.onNodeWithText("Test connection").performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("health=true", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Close").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("health=true", substring = true).fetchSemanticsNodes().isEmpty()
        }
    }
}

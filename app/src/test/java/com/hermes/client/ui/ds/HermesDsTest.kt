package com.hermes.client.ui.ds

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HermesDsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theme_topBarButtonsAndDialog() {
        compose.setContent {
            HermesDsTheme {
                var dialog by remember { mutableStateOf(false) }
                Column {
                    HermesTopBar(
                        title = "Title",
                        onBack = {},
                        backDesc = "Back",
                        backTestTag = "dsBack",
                    ) {
                        HermesButton("Go", onClick = { dialog = true }, modifier = Modifier.testTag("dsGo"))
                    }
                    HermesTextField(
                        value = "",
                        onValueChange = {},
                        label = "Label",
                        placeholder = "Hint",
                        modifier = Modifier.testTag("dsInput"),
                    )
                    HermesTypingDots(Modifier.testTag("dsDots"))
                    HermesLinearBar(Modifier.testTag("dsBar"))
                    HermesDialog(
                        visible = dialog,
                        onDismiss = { dialog = false },
                        title = "Dlg",
                        confirmLabel = "OK",
                        onConfirm = { dialog = false },
                        dismissLabel = "No",
                    ) {
                        BasicText("Body")
                    }
                }
            }
        }
        compose.onNodeWithText("Title").assertIsDisplayed()
        compose.onNodeWithTag("dsBack").assertIsDisplayed()
        compose.onNodeWithTag("dsInput").performTextInput("abc")
        assertTrue(compose.onAllNodesWithText("Hint").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("dsDots").assertIsDisplayed()
        compose.onNodeWithTag("dsBar").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Body").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("dsGo").performClick()
        compose.onNodeWithText("Body").assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        assertTrue(compose.onAllNodesWithText("Body").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("dsGo").performClick()
        compose.onNodeWithText("No").performClick()
        assertTrue(compose.onAllNodesWithText("Body").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun snackbar_showsAndDismisses() {
        compose.setContent {
            HermesDsTheme {
                val state = rememberHermesSnackbarState()
                HermesButton("Show", onClick = { state.show("hello snack") }, modifier = Modifier.testTag("snackGo"))
                HermesSnackbarHost(state)
            }
        }
        compose.onNodeWithTag("snackGo").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("hello snack").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun drawer_opensAndCloses() {
        compose.setContent {
            HermesDsTheme {
                var open by remember { mutableStateOf(false) }
                HermesButton("Open", onClick = { open = true }, modifier = Modifier.testTag("drawerGo"))
                HermesDrawer(
                    open = open,
                    onClose = { open = false },
                    drawerContent = { BasicText("DrawerBody", modifier = Modifier.testTag("drawerBody")) },
                ) {
                    BasicText("MainBody")
                }
            }
        }
        compose.onNodeWithTag("drawerBody").assertIsNotDisplayed()
        compose.onNodeWithTag("drawerGo").performClick()
        compose.onNodeWithTag("drawerBody").assertIsDisplayed()
    }
}

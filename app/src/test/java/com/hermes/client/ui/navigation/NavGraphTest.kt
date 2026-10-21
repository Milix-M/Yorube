package com.hermes.client.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermes.client.ui.theme.HermesAppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavGraphTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun listToSettings_navigates() {
        compose.setContent {
            HermesAppTheme {
                NavGraph(null)
            }
        }
        compose.onNodeWithText("Hermes").assertIsDisplayed()
        compose.onAllNodesWithText("⚙")[0].performClick()
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun chatRoute_rendersInput() {
        compose.setContent {
            HermesAppTheme {
                NavGraph("s1")
            }
        }
        compose.onNodeWithTag("chatInput").assertExists()
    }
}

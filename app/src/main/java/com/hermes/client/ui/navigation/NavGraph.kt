package com.hermes.client.ui.navigation

import android.net.Uri
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hermes.client.ui.screens.AppDrawer
import com.hermes.client.ui.screens.ChatScreen
import com.hermes.client.ui.screens.ConversationDrawerContent
import com.hermes.client.ui.screens.ConversationSearchScreen
import com.hermes.client.ui.screens.SettingsScreen

object Routes {
    const val CHAT = "chat"
    const val SEARCH = "search"
    const val SETTINGS = "settings"

    fun chat(sessionId: String?, title: String? = null): String {
        val params = listOfNotNull(
            sessionId?.ifBlank { null }?.let { "sessionId=$it" },
            title?.ifBlank { null }?.let { "title=${Uri.encode(it)}" },
        )
        return if (params.isEmpty()) CHAT else "$CHAT?${params.joinToString("&")}"
    }
}

private const val EnterMs = 300
private const val ExitMs = 250
private const val SlidePx = 32

@Composable
fun NavGraph(startSessionId: String?) {
    val navController = rememberNavController()
    // Launch always lands on the home chat (a fresh conversation, or the
    // notification's session when one was tapped). No auto-forwarding.
    NavHost(
        navController = navController,
        startDestination = Routes.chat(startSessionId),
        // Shared-axis-X: short slide with fade. Forward slides in
        // from the right, back pops from the left.
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { SlidePx },
                animationSpec = tween(EnterMs, easing = FastOutSlowInEasing),
            ) + fadeIn(tween(EnterMs, easing = FastOutSlowInEasing))
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { -SlidePx },
                animationSpec = tween(ExitMs, easing = FastOutLinearInEasing),
            ) + fadeOut(tween(ExitMs, easing = FastOutLinearInEasing))
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { -SlidePx },
                animationSpec = tween(EnterMs, easing = FastOutSlowInEasing),
            ) + fadeIn(tween(EnterMs, easing = FastOutSlowInEasing))
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { SlidePx },
                animationSpec = tween(ExitMs, easing = FastOutLinearInEasing),
            ) + fadeOut(tween(ExitMs, easing = FastOutLinearInEasing))
        },
    ) {
        composable(
            route = "${Routes.CHAT}?sessionId={sessionId}&title={title}",
            arguments = listOf(
                navArgument("sessionId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("title") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId")
            val title = entry.arguments?.getString("title")
            // Fresh-home nonce: tapping "new chat" while already home recreates
            // the chat in place (no navigation, no transition), just closing
            // the drawer. From another session, navigate home as usual.
            var homeNonce by rememberSaveable { mutableIntStateOf(0) }
            var drawerOpen by rememberSaveable { mutableStateOf(false) }
            AppDrawer(
                open = drawerOpen,
                onClose = { drawerOpen = false },
                drawerContent = {
                    ConversationDrawerContent(
                        selectedSessionId = sessionId,
                        onOpenChat = { id, chatTitle ->
                            drawerOpen = false
                            if (id == null && sessionId == null) {
                                homeNonce++
                            } else {
                                navController.navigate(Routes.chat(id, chatTitle)) {
                                    popUpTo(Routes.CHAT) { inclusive = true }
                                }
                            }
                        },
                        onOpenSearch = {
                            drawerOpen = false
                            navController.navigate(Routes.SEARCH)
                        },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        onCloseDrawer = { drawerOpen = false },
                    )
                },
            ) {
                ChatScreen(
                    sessionId = sessionId,
                    title = title,
                    instanceNonce = homeNonce,
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    // At the home root there is nothing to pop: stay instead of a blank screen.
                    onNavigateBack = { if (navController.previousBackStackEntry != null) navController.popBackStack() },
                    onOpenDrawer = { drawerOpen = true },
                )
            }
        }
        composable(Routes.SEARCH) {
            ConversationSearchScreen(
                onOpenChat = { id, chatTitle ->
                    navController.navigate(Routes.chat(id, chatTitle)) {
                        popUpTo(Routes.CHAT) { inclusive = true }
                    }
                },
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}

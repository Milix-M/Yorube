package com.hermes.client.ui.navigation

import android.net.Uri
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hermes.client.ui.screens.ChatScreen
import com.hermes.client.ui.screens.ConversationDrawerContent
import com.hermes.client.ui.screens.ConversationSearchScreen
import com.hermes.client.ui.screens.SettingsScreen
import kotlinx.coroutines.launch

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

private const val EnterMs = 350
private const val ExitMs = 200

@Composable
fun NavGraph(startSessionId: String?) {
    val navController = rememberNavController()
    // Home is a fresh chat; past conversations live in the sidebar drawer.
    NavHost(
        navController = navController,
        startDestination = Routes.chat(startSessionId),
        enterTransition = {
            fadeIn(tween(EnterMs, easing = FastOutSlowInEasing)) +
                scaleIn(initialScale = 0.96f, animationSpec = tween(EnterMs, easing = FastOutSlowInEasing))
        },
        exitTransition = { fadeOut(tween(ExitMs, easing = FastOutLinearInEasing)) },
        popEnterTransition = { fadeIn(tween(EnterMs, easing = FastOutSlowInEasing)) },
        popExitTransition = {
            fadeOut(tween(ExitMs, easing = FastOutLinearInEasing)) +
                scaleOut(targetScale = 0.98f, animationSpec = tween(ExitMs, easing = FastOutLinearInEasing))
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
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ConversationDrawerContent(
                        selectedSessionId = sessionId,
                        onOpenChat = { id, chatTitle ->
                            scope.launch { drawerState.close() }
                            navController.navigate(Routes.chat(id, chatTitle)) {
                                popUpTo(Routes.CHAT) { inclusive = true }
                            }
                        },
                        onOpenSearch = {
                            scope.launch { drawerState.close() }
                            navController.navigate(Routes.SEARCH)
                        },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        onCloseDrawer = { scope.launch { drawerState.close() } },
                    )
                },
            ) {
                ChatScreen(
                    sessionId = sessionId,
                    title = title,
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    // At the home root there is nothing to pop: stay instead of a blank screen.
                    onNavigateBack = { if (navController.previousBackStackEntry != null) navController.popBackStack() },
                    onOpenDrawer = { scope.launch { drawerState.open() } },
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

package com.hermes.client.ui.navigation

import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import com.hermes.client.ui.screens.ConversationListScreen
import com.hermes.client.ui.screens.SettingsScreen
import kotlinx.coroutines.launch

object Routes {
    const val LIST = "list"
    const val CHAT = "chat"
    const val SETTINGS = "settings"

    fun chat(sessionId: String?, title: String? = null): String {
        val params = listOfNotNull(
            sessionId?.ifBlank { null }?.let { "sessionId=$it" },
            title?.ifBlank { null }?.let { "title=${Uri.encode(it)}" },
        )
        return if (params.isEmpty()) CHAT else "$CHAT?${params.joinToString("&")}"
    }
}

private const val SlideOffset = 4

@Composable
fun NavGraph(startSessionId: String?) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = if (startSessionId != null) {
            Routes.chat(startSessionId)
        } else {
            Routes.LIST
        },
        enterTransition = { slideInHorizontally(initialOffsetX = { it / SlideOffset }) + fadeIn() },
        exitTransition = { slideOutHorizontally(targetOffsetX = { -it / SlideOffset }) + fadeOut() },
        popEnterTransition = { slideInHorizontally(initialOffsetX = { -it / SlideOffset }) + fadeIn() },
        popExitTransition = { slideOutHorizontally(targetOffsetX = { it / SlideOffset }) + fadeOut() },
    ) {
        composable(Routes.LIST) {
            ConversationListScreen(
                onOpenChat = { sessionId, title -> navController.navigate(Routes.chat(sessionId, title)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
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
                                popUpTo(Routes.LIST) { inclusive = false }
                            }
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
                    onNavigateBack = { navController.popBackStack() },
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                )
            }
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}

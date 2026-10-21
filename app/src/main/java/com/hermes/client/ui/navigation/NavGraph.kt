package com.hermes.client.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hermes.client.ui.screens.ChatScreen
import com.hermes.client.ui.screens.ConversationListScreen
import com.hermes.client.ui.screens.SettingsScreen

object Routes {
    const val LIST = "list"
    const val CHAT = "chat"
    const val SETTINGS = "settings"

    fun chat(sessionId: String?): String =
        if (sessionId.isNullOrBlank()) CHAT else "$CHAT?sessionId=$sessionId"
}

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
    ) {
        composable(Routes.LIST) {
            ConversationListScreen(
                onOpenChat = { sessionId -> navController.navigate(Routes.chat(sessionId)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = "${Routes.CHAT}?sessionId={sessionId}",
            arguments = listOf(
                navArgument("sessionId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            ),
        ) { entry ->
            ChatScreen(
                sessionId = entry.arguments?.getString("sessionId"),
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}

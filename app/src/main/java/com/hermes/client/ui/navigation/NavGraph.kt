package com.hermes.client.ui.navigation

import android.net.Uri
import androidx.compose.animation.core.EmphasizedAccelerate
import androidx.compose.animation.core.EmphasizedDecelerate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hermes.client.HermesApp
import com.hermes.client.ui.screens.ChatScreen
import com.hermes.client.ui.screens.ConversationDrawerContent
import com.hermes.client.ui.screens.ConversationListViewModel
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

private const val EnterMs = 300
private const val ExitMs = 250
private const val SlidePx = 32

@Composable
fun NavGraph(startSessionId: String?) {
    val navController = rememberNavController()
    val app = LocalContext.current.applicationContext as HermesApp
    // Home shows the latest conversation; explicit session ids (e.g. from a
    // notification tap) always win. Sessions load async, so the start
    // destination resolves once the list arrives; key() swaps the NavHost to
    // the resolved start without pushing a back-stack entry.
    val startVm: ConversationListViewModel = viewModel(
        key = "home-start",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ConversationListViewModel(app.container) as T
        }
    )
    LaunchedEffect(Unit) { startVm.refresh() }
    val latest = startVm.sessions.firstOrNull()
    val startDestination = if (startSessionId != null || latest == null) {
        Routes.chat(startSessionId)
    } else {
        Routes.chat(latest.key, latest.title)
    }
    key(startDestination) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
        // Material shared-axis-X: short slide with fade. Forward slides in
        // from the right, back pops from the left.
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { SlidePx },
                animationSpec = tween(EnterMs, easing = EmphasizedDecelerate),
            ) + fadeIn(tween(EnterMs, easing = EmphasizedDecelerate))
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { -SlidePx },
                animationSpec = tween(ExitMs, easing = EmphasizedAccelerate),
            ) + fadeOut(tween(ExitMs, easing = EmphasizedAccelerate))
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { -SlidePx },
                animationSpec = tween(EnterMs, easing = EmphasizedDecelerate),
            ) + fadeIn(tween(EnterMs, easing = EmphasizedDecelerate))
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { SlidePx },
                animationSpec = tween(ExitMs, easing = EmphasizedAccelerate),
            ) + fadeOut(tween(ExitMs, easing = EmphasizedAccelerate))
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
            // Fresh-home nonce: tapping "new chat" while already home recreates
            // the chat in place (no navigation, no transition), just closing
            // the drawer. From another session, navigate home as usual.
            var homeNonce by rememberSaveable { mutableIntStateOf(0) }
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ConversationDrawerContent(
                        selectedSessionId = sessionId,
                        onOpenChat = { id, chatTitle ->
                            scope.launch { drawerState.close() }
                            if (id == null && sessionId == null) {
                                homeNonce++
                            } else {
                                navController.navigate(Routes.chat(id, chatTitle)) {
                                    popUpTo(Routes.CHAT) { inclusive = true }
                                }
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
                    instanceNonce = homeNonce,
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
}

package com.hermes.client.ui.navigation

import android.net.Uri
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.data.store.LocaleHelper
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
            sessionId?.ifBlank { null }?.let { "sessionId=${Uri.encode(it)}" },
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
    // Apply the in-app language to every screen below: stringResource reads
    // from LocalContext, so a wrapped context re-resolves all labels.
    val appSettings by app.container.settingsStore.settings.collectAsState(initial = ClientSettings())
    val baseContext = LocalContext.current
    val localizedContext = remember(baseContext, appSettings.language) {
        LocaleHelper.contextualize(baseContext, appSettings.language)
    }
    CompositionLocalProvider(
        androidx.compose.ui.platform.LocalContext provides localizedContext,
    ) {
    // Home is always a fresh chat; explicit session ids (e.g. from a
    // notification tap) always win. Sessions load async, so an explicit
    // start resolves once the list arrives, then we navigate to it
    // (popUpTo inclusive so home has no back-stack entry). Using navigate()
    // instead of key(startDestination) recreation because NavHost
    // startDestination with query params does not parse args (they arrive
    // null), while navigate() with the same route parses correctly.
    val startVm: ConversationListViewModel = viewModel(
        key = "home-start",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ConversationListViewModel(app.container) as T
        }
    )
    LaunchedEffect(Unit) { startVm.refresh() }
    // Home is always a fresh chat: explicit session ids (e.g. from a
    // notification tap) still win via navigate(), but the latest
    // conversation is never auto-opened on launch. Sessions still load
    // async for the drawer and header titles.
    var frozenStart by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(startSessionId) {
        if (frozenStart == null && startSessionId != null) {
            frozenStart = Routes.chat(startSessionId)
        }
    }
    // Navigate to the resolved start once (popUpTo inclusive so back from
    // home exits, not to an empty fresh chat). Guarded: the effect can
    // re-fire on recomposition and must never push the entry twice (double
    // history load, flicker on notification taps).
    var startNavigated by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(frozenStart) {
        val target = frozenStart
        if (!startNavigated && target != null && target != Routes.CHAT) {
            startNavigated = true
            navController.navigate(target) {
                popUpTo(Routes.CHAT) { inclusive = true }
            }
        }
    }
    NavHost(
        navController = navController,
        startDestination = Routes.CHAT,
            // Draw the surface color behind transitions: without this the
            // transparent NavHost shows the white windowBackground as a flash.
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
        // Material shared-axis-X: short slide with fade. Forward slides in
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
                        drawerState = drawerState,
                        onOpenChat = { id, chatTitle ->
                            scope.launch { drawerState.close() }
                            if (id == null && sessionId == null) {
                                homeNonce++
                            } else if (id != null && id == sessionId) {
                                // Already open: just close the drawer, never
                                // reload the conversation underneath.
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
                        // Close the drawer first so returning from settings
                        // lands on the chat, not an open sidebar.
                        onOpenSettings = {
                            scope.launch { drawerState.close() }
                            navController.navigate(Routes.SETTINGS)
                        },
                        onCloseDrawer = { scope.launch { drawerState.close() } },
                    )
                },
            ) {
                ChatScreen(
                    sessionId = sessionId,
                    title = title,
                    instanceNonce = homeNonce,
                    recentSessions = startVm.sessions,
                    onNavigateToFreshChat = {
                        navController.navigate(Routes.chat(null, null)) {
                            popUpTo(Routes.CHAT) { inclusive = true }
                        }
                    },
                    onSessionCreated = { startVm.refresh() },
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

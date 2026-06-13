package com.aichatvn.agent.ui.navigation

import androidx.compose.animation.*
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aichatvn.agent.R
import com.aichatvn.agent.ui.screens.*

sealed class Screen(val route: String, val titleRes: Int, val icon: ImageVector) {
    object Chat       : Screen("chat",        R.string.tab_chat,        Icons.Default.Chat)
    object Camera     : Screen("camera",      R.string.tab_camera,      Icons.Default.Videocam)
    object Training   : Screen("training",    R.string.tab_training,    Icons.Default.School)
    object Diagnostics: Screen("diagnostics", R.string.tab_diagnostics, Icons.Default.MonitorHeart)
    object Logs       : Screen("logs",       R.string.tab_logs,        Icons.Default.BugReport)
    object Settings   : Screen("settings",   R.string.tab_settings,    Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigator() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val screens = listOf(
        Screen.Chat, Screen.Camera, Screen.Training, Screen.Diagnostics, Screen.Settings
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                screens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = null) },
                        label = { Text(stringResource(screen.titleRes)) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Screen.Chat.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(Screen.Chat.route)        { ChatScreen(navController) }
            composable(Screen.Camera.route)      { CameraScreen(navController) }
            composable(Screen.Training.route)    { TrainingScreen(navController) }
            composable(Screen.Diagnostics.route) { DiagnosticsScreen(navController) }
            composable(Screen.Logs.route)        { LogScreen(navController) }
            composable(Screen.Settings.route)    { SettingsScreen(navController) }
        }
    }
}

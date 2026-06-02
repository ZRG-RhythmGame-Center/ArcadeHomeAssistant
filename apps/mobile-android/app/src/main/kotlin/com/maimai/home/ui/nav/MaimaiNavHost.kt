package com.maimai.home.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.maimai.home.ServiceLocator
import com.maimai.home.ui.audio.AudioScreen
import com.maimai.home.ui.audio.AudioTabUnconnected
import com.maimai.home.ui.connection.ConnectionScreen
import com.maimai.home.ui.files.FilesScreen
import com.maimai.home.ui.files.FilesTabUnconnected

/**
 * Three top-level tabs live side-by-side in a [NavigationBar]. Audio and Files
 * are primary task surfaces; Device owns connection setup and switching.
 */
@Composable
fun MaimaiNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val connectionHandle by ServiceLocator.connectionHandle.collectAsState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppDestination.entries.forEach { destination ->
                    val selected = destination.route == currentRoute
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (selected) destination.filledIcon else destination.outlinedIcon,
                                contentDescription = destination.label,
                            )
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (connectionHandle == null) AppDestination.Device.route else AppDestination.Audio.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(AppDestination.Device.route) {
                ConnectionScreen(
                    onConnected = { address, machineName ->
                        ServiceLocator.setConnectionHandle(
                            com.maimai.home.ConnectionHandle(address, machineName),
                        )
                        navController.navigate(AppDestination.Audio.route) {
                            popUpTo(AppDestination.Device.route) { saveState = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(AppDestination.Audio.route) {
                val handle = connectionHandle
                if (handle == null) {
                    AudioTabUnconnected(onGoToConnection = {
                        navController.navigate(AppDestination.Device.route) {
                            popUpTo(AppDestination.Device.route) { saveState = true }
                            launchSingleTop = true
                        }
                    })
                } else {
                    AudioScreen(
                        address = handle.address,
                        machineName = handle.machineName,
                        onOpenDevice = {
                            navController.navigate(AppDestination.Device.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onOpenFiles = { _, _ ->
                            navController.navigate(AppDestination.Files.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
            composable(AppDestination.Files.route) {
                val handle = connectionHandle
                if (handle == null) {
                    FilesTabUnconnected(onGoToConnection = {
                        navController.navigate(AppDestination.Device.route) {
                            popUpTo(AppDestination.Device.route) { saveState = true }
                            launchSingleTop = true
                        }
                    })
                } else {
                    FilesScreen(
                        address = handle.address,
                        machineName = handle.machineName,
                        onSwitchDevice = {
                            navController.navigate(AppDestination.Device.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        }
    }
}

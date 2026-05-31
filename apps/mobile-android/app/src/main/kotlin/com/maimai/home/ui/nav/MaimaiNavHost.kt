package com.maimai.home.ui.nav

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.maimai.home.ui.audio.AudioScreen
import com.maimai.home.ui.connection.ConnectionScreen
import com.maimai.home.ui.files.FilesScreen

private object Routes {
    const val Connection = "connection"
    const val Audio = "audio/{address}/{machineName}"
    const val Files = "files/{address}/{machineName}"

    fun audio(address: String, machineName: String) = "audio/${Uri.encode(address)}/${Uri.encode(machineName)}"
    fun files(address: String, machineName: String) = "files/${Uri.encode(address)}/${Uri.encode(machineName)}"
}

/**
 * Wave 5 task 36: MaimaiNavHost.
 *  - Dynamic machineName title passed to AudioScreen / FilesScreen.
 *  - BackHandler in AudioScreen and FilesScreen clears connectedStatus on
 *    back-navigation to ConnectionScreen.
 */
@Composable
fun MaimaiNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.Connection) {
        composable(Routes.Connection) {
            ConnectionScreen(
                onConnected = { address, machineName ->
                    navController.navigate(Routes.audio(address, machineName))
                },
            )
        }
        composable(
            route = Routes.Audio,
            arguments = listOf(
                navArgument("address") { type = NavType.StringType },
                navArgument("machineName") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val address = backStackEntry.arguments?.getString("address").orEmpty()
            val machineName = backStackEntry.arguments?.getString("machineName").orEmpty()

            // Task 36: back from AudioScreen pops to ConnectionScreen.
            BackHandler {
                navController.popBackStack(Routes.Connection, inclusive = false)
            }

            AudioScreen(
                address = address,
                machineName = machineName,
                onOpenFiles = { addr, mn -> navController.navigate(Routes.files(addr, mn)) },
            )
        }
        composable(
            route = Routes.Files,
            arguments = listOf(
                navArgument("address") { type = NavType.StringType },
                navArgument("machineName") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val address = backStackEntry.arguments?.getString("address").orEmpty()
            val machineName = backStackEntry.arguments?.getString("machineName").orEmpty()

            // Task 36: back from FilesScreen pops to AudioScreen.
            BackHandler {
                navController.popBackStack()
            }

            FilesScreen(
                address = address,
                machineName = machineName,
            )
        }
    }
}

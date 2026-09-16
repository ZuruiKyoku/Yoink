package com.zuruikyoku.yoink.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zuruikyoku.yoink.ui.main.MainScreen
import com.zuruikyoku.yoink.ui.main.MainViewModel
import com.zuruikyoku.yoink.ui.settings.SettingsScreen

private const val ROUTE_MAIN = "main"
private const val ROUTE_SETTINGS = "settings"

@Composable
fun YoinkNavHost(mainViewModel: MainViewModel) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = ROUTE_MAIN) {
        composable(ROUTE_MAIN) {
            MainScreen(
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                viewModel = mainViewModel
            )
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}

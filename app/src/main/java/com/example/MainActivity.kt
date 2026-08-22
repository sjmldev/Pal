package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.preferences.AppPreferences
import com.example.ui.dashboard.DashboardScreen
import com.example.ui.feedback.FeedbackScreen
import com.example.ui.limits.LimitSetupScreen
import com.example.ui.navigation.Screen
import com.example.ui.permissions.PermissionGuideScreen
import com.example.ui.permissions.PermissionsScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val openLimitSetup = intent?.getBooleanExtra(EXTRA_OPEN_LIMIT_SETUP, false) == true

        setContent {
            MyApplicationTheme {
                ReelsPalApp(openLimitSetupInitial = openLimitSetup)
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_LIMIT_SETUP = "extra_open_limit_setup"
    }
}

@Composable
fun ReelsPalApp(openLimitSetupInitial: Boolean = false) {
    val navController = rememberNavController()
    val preferences = remember { AppPreferences.getInstance(navController.context) }

    val startDestination = if (!preferences.hasCompletedOnboarding) {
        Screen.Permissions.route
    } else if (openLimitSetupInitial) {
        Screen.LimitSetup.route
    } else {
        Screen.Dashboard.route
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // Permissions Setup
        composable(Screen.Permissions.route) {
            PermissionsScreen(
                onAllPermissionsGranted = {
                    navController.navigate(Screen.LimitSetup.route) {
                        popUpTo(Screen.Permissions.route) { inclusive = true }
                    }
                },
                onOpenGuide = {
                    navController.navigate(Screen.PermissionGuide.route)
                }
            )
        }

        // Permission 4-Step Guide
        composable(Screen.PermissionGuide.route) {
            PermissionGuideScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // Daily Limit Setup (Ad-gated)
        composable(Screen.LimitSetup.route) {
            LimitSetupScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onLimitsSaved = {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(Screen.LimitSetup.route) { inclusive = true }
                    }
                }
            )
        }

        // Main Dashboard & Stats
        composable(Screen.Dashboard.route) {
            DashboardScreen(
                onNavigateToLimits = {
                    navController.navigate(Screen.LimitSetup.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToFeedback = {
                    navController.navigate(Screen.Feedback.route)
                }
            )
        }

        // Settings
        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onOpenGuide = {
                    navController.navigate(Screen.PermissionGuide.route)
                },
                onNavigateToFeedback = {
                    navController.navigate(Screen.Feedback.route)
                }
            )
        }

        // Feedback & Support
        composable(Screen.Feedback.route) {
            FeedbackScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}

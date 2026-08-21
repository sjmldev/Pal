package com.example.ui.navigation

sealed class Screen(val route: String) {
    data object Permissions : Screen("permissions")
    data object PermissionGuide : Screen("permission_guide")
    data object LimitSetup : Screen("limit_setup")
    data object Dashboard : Screen("dashboard")
    data object Settings : Screen("settings")
    data object Feedback : Screen("feedback")
}

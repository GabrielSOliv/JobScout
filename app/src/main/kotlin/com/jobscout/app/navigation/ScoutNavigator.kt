package com.jobscout.app.navigation

import androidx.navigation.NavHostController
import androidx.navigation.NavDestination.Companion.hasRoute

/** Only root destinations are saved as tabs; child screens never become a tab's landing page. */
internal class ScoutNavigator(private val nav: NavHostController) {
    private val roots: List<Any> = listOf(Explore, Saved, Profile)
    fun tab(route: Any) {
        if (nav.currentDestination?.hasRoute(route::class) == true) return
        val currentRoot = roots.firstOrNull { runCatching { nav.getBackStackEntry(it) }.isSuccess } ?: Home
        nav.popBackStack(currentRoot, inclusive = false)
        nav.navigate(route) {
            popUpTo<Home> { saveState = true }
            launchSingleTop = true
            // Home remains in the active stack. Restoring its saved child stack can reopen Vagas.
            restoreState = route != Home
        }
        // This also normalizes an older saved stack containing a child destination.
        nav.popBackStack(route, inclusive = false)
    }
    fun push(route: Any) { nav.navigate(route) { launchSingleTop = true } }
    fun back() { if (!nav.popBackStack()) tab(Home) }
}

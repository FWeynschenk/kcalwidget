package nl.flwe.kcalwidget.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import nl.flwe.kcalwidget.ui.MainViewModel
import nl.flwe.kcalwidget.ui.history.HistoryScreen
import nl.flwe.kcalwidget.ui.home.HomeScreen
import nl.flwe.kcalwidget.ui.settings.AboutScreen
import nl.flwe.kcalwidget.ui.settings.BodyScreen
import nl.flwe.kcalwidget.ui.settings.CalculationScreen
import nl.flwe.kcalwidget.ui.settings.CalibrationScreen
import nl.flwe.kcalwidget.ui.settings.GoalScreen
import nl.flwe.kcalwidget.ui.settings.SettingsIndexScreen
import nl.flwe.kcalwidget.ui.settings.SettingsRoutes
import nl.flwe.kcalwidget.ui.settings.SourcesScreen
import nl.flwe.kcalwidget.ui.settings.RemindersScreen
import nl.flwe.kcalwidget.ui.settings.WidgetsScreen
import nl.flwe.kcalwidget.ui.wizard.WizardScreen

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val WIZARD = "wizard"
}

@Composable
fun AppNav(viewModel: MainViewModel) {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Only once the first read has landed: the default state claims no permissions, and
    // launching setup over a working install would be a poor way to say hello.
    LaunchedEffect(state.loading, state.settings.onboardingCompleted) {
        if (!state.loading && !state.settings.onboardingCompleted) {
            nav.navigate(Routes.WIZARD)
        }
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                viewModel,
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenCalibration = { nav.navigate(SettingsRoutes.CALIBRATION) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsIndexScreen(onBack = back, onNavigate = { nav.navigate(it) })
        }
        composable(SettingsRoutes.HISTORY) { HistoryScreen(viewModel, back) }
        composable(SettingsRoutes.GOAL) { GoalScreen(viewModel, back) }
        composable(SettingsRoutes.BODY) { BodyScreen(viewModel, back) }
        composable(SettingsRoutes.SOURCES) { SourcesScreen(viewModel, back) }
        composable(SettingsRoutes.CALCULATION) { CalculationScreen(viewModel, back) }
        composable(SettingsRoutes.CALIBRATION) { CalibrationScreen(viewModel, back) }
        composable(SettingsRoutes.WIDGETS) { WidgetsScreen(viewModel, back) }
        composable(SettingsRoutes.REMINDERS) { RemindersScreen(viewModel, back) }
        composable(Routes.WIZARD) {
            WizardScreen(viewModel) { nav.popBackStack(Routes.HOME, inclusive = false) }
        }
        composable(SettingsRoutes.ABOUT) { AboutScreen(back) }
    }
}

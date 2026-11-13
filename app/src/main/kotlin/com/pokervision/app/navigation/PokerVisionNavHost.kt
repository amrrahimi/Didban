package com.pokervision.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pokervision.app.locale.LanguageMenu
import com.pokervision.app.ui.StatisticsPlaceholderScreen
import com.pokervision.feature.advisor.AdvisorRoute
import com.pokervision.feature.camera.CameraRoute
import com.pokervision.feature.history.HistoryRoute
import com.pokervision.feature.home.HomeRoute
import com.pokervision.feature.imageanalysis.IMAGE_ID_ARG
import com.pokervision.feature.imageanalysis.ImageDetailRoute
import com.pokervision.feature.imageanalysis.ImageListRoute
import com.pokervision.feature.live.LiveRoute
import com.pokervision.feature.live.learn.LearnDeckRoute
import com.pokervision.feature.settings.DetectionSettingsRoute
import com.pokervision.feature.settings.PrivacySettingsRoute
import com.pokervision.feature.settings.VariantSelectionRoute

@Composable
fun PokerVisionNavHost(navController: NavHostController = rememberNavController()) {
    check(IMAGE_ID_ARG == Routes.MEDIA_ID_ARG) { "Route argument mismatch" }
    val back: () -> Unit = { navController.popBackStack() }
    val mediaIdArg = listOf(navArgument(Routes.MEDIA_ID_ARG) { type = NavType.LongType })

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeRoute(
                onOpen = { navController.navigate(Routes.forHomeEntry(it)) { launchSingleTop = true } },
                topBarActions = { LanguageMenu() },
            )
        }
        composable(Routes.CAMERA) { CameraRoute(onBack = back) }

        composable(Routes.IMAGES) {
            ImageListRoute(onBack = back, onOpenImage = { navController.navigate(Routes.imageDetail(it)) })
        }
        composable(Routes.IMAGE_DETAIL, arguments = mediaIdArg) { ImageDetailRoute(onBack = back) }

        composable(Routes.HISTORY) {
            HistoryRoute(
                onBack = back,
                onOpenImage = { navController.navigate(Routes.imageDetail(it)) },
            )
        }
        composable(Routes.ADVISOR) { AdvisorRoute(onBack = back) }
        composable(Routes.LIVE) { LiveRoute(onBack = back) }
        composable(Routes.LEARN) { LearnDeckRoute(onBack = back) }
        composable(Routes.STATISTICS) { StatisticsPlaceholderScreen(onBack = back) }
        composable(Routes.VARIANT) { VariantSelectionRoute(onBack = back) }
        composable(Routes.DETECTION) { DetectionSettingsRoute(onBack = back) }
        composable(Routes.PRIVACY) { PrivacySettingsRoute(onBack = back) }
    }
}

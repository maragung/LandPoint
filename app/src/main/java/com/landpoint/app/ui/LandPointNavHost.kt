package com.landpoint.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.landpoint.app.R
import com.landpoint.app.ui.compass.CompassScreen
import com.landpoint.app.ui.detail.LandDetailScreen
import com.landpoint.app.ui.edit.LandEditScreen
import com.landpoint.app.ui.lands.LandListDetailPane
import com.landpoint.app.ui.lands.LandListScreen
import com.landpoint.app.ui.map.MapScreen
import com.landpoint.app.ui.navigation.Routes
import com.landpoint.app.ui.settings.SettingsScreen
import com.landpoint.app.ui.shape.LandShapeScreen

/**
 * The three places a user comes back to. Everything else — a land's detail, the
 * editor, the compass, the corner picker — is work with an end, so it takes the
 * whole screen and the bar steps out of the way.
 */
private enum class Tab(
    val route: String,
    val icon: ImageVector,
    @StringRes val labelRes: Int
) {
    LANDS(Routes.LIST, Icons.Outlined.Landscape, R.string.nav_lands),
    MAP(Routes.MAP, Icons.Default.Map, R.string.nav_map),
    SETTINGS(Routes.SETTINGS, Icons.Default.Settings, R.string.nav_settings)
}

@Composable
fun LandPointNavHost() {
    val navController = rememberNavController()

    val currentRoute = navController.currentBackStackEntryAsState()
        .value?.destination?.route
    val onTab = Tab.entries.any { it.route == currentRoute }

    // Width decides where the destinations live and whether a record can sit
    // beside the list; see WindowLayout for the two thresholds and why they
    // differ. Read once here so the bar, the rail and the graph cannot disagree.
    val widthDp = windowWidthDp()
    val rail = onTab && WindowLayout.railInsteadOfBottomBar(widthDp)
    val barAtBottom = onTab && !rail

    Scaffold(
        // Zero here on purpose: the bar brings its own navigation-bar inset, and
        // every screen inside already handles its own status bar. Without this,
        // the system insets would be applied twice.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (barAtBottom) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab.route == currentRoute,
                            onClick = { navController.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            // Labelled, not icon-only: three tabs have room for
                            // words, and words do not have to be guessed at.
                            label = { Text(stringResource(tab.labelRes)) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Row(
            // Only the bottom edge is reserved, and only while the bar is
            // showing. A full-screen map should still reach every edge of the
            // glass — and with a rail there is no bottom bar to make room for.
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (barAtBottom) padding.calculateBottomPadding() else 0.dp)
        ) {
            // Sideways there is room to spare and downwards there is not, which
            // is what a phone on its side is. The three destinations move into
            // the margin and give the screen its height back. The rail carries
            // its own inset on the side it sits, the same way the bar did.
            if (rail) {
                // Told to fill the height rather than trusting it to: this Row
                // aligns its children to the top, and a rail the height of its
                // three items would leave the rest of that column bare.
                NavigationRail(modifier = Modifier.fillMaxHeight()) {
                    Tab.entries.forEach { tab ->
                        NavigationRailItem(
                            selected = tab.route == currentRoute,
                            onClick = { navController.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.labelRes)) }
                        )
                    }
                }
            }

            LandPointGraph(
                navController = navController,
                listBesideDetail = WindowLayout.listBesideDetail(widthDp),
                modifier = Modifier.weight(1f)
            )
        }
    }
}
/**
 * Tab switching, the standard way.
 *
 * `saveState`/`restoreState` are what keep the land list's scroll position when
 * a user glances at the map and comes back, and `launchSingleTop` stops the
 * back stack growing by one entry every time a tab is tapped.
 */
private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun LandPointGraph(
    navController: NavHostController,
    listBesideDetail: Boolean,
    modifier: Modifier = Modifier
) {
    val landIdArg = listOf(navArgument(Routes.ARG_LAND_ID) { type = NavType.StringType })

    NavHost(
        navController = navController,
        startDestination = Routes.LIST,
        modifier = modifier
    ) {

        composable(Routes.LIST) {
            // Same list either way; on a wide window it keeps the chosen record
            // beside it instead of handing the screen over to it. A record opened
            // from the map tab still takes the whole window: the map is a screen
            // in its own right, not a second index of the same list.
            if (listBesideDetail) {
                LandListDetailPane(
                    onNewLand = { navController.navigate(Routes.NEW) },
                    onEdit = { id -> navController.navigate(Routes.edit(id)) },
                    onCompass = { id -> navController.navigate(Routes.compass(id)) },
                    onShape = { id -> navController.navigate(Routes.shape(id)) }
                )
            } else {
                LandListScreen(
                    onOpenLand = { id -> navController.navigate(Routes.detail(id)) },
                    onNewLand = { navController.navigate(Routes.NEW) }
                )
            }
        }

        // Map and Settings are tabs now, so neither gets a back arrow.
        composable(Routes.MAP) {
            MapScreen(
                onOpenLand = { id -> navController.navigate(Routes.detail(id)) }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen()
        }

        composable(Routes.NEW) {
            LandEditScreen(
                // Replace the editor with the saved record so Back lands on the list.
                onDone = { id ->
                    navController.navigate(Routes.detail(id)) {
                        popUpTo(Routes.NEW) { inclusive = true }
                    }
                },
                onCancel = { navController.popBackStack() }
            )
        }

        composable(Routes.DETAIL, arguments = landIdArg) {
            LandDetailScreen(
                onBack = { navController.popBackStack() },
                onEdit = { id -> navController.navigate(Routes.edit(id)) },
                onCompass = { id -> navController.navigate(Routes.compass(id)) },
                onShape = { id -> navController.navigate(Routes.shape(id)) }
            )
        }

        composable(Routes.EDIT, arguments = landIdArg) {
            LandEditScreen(
                onDone = { navController.popBackStack() },
                onCancel = { navController.popBackStack() }
            )
        }

        composable(Routes.COMPASS, arguments = landIdArg) {
            CompassScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SHAPE, arguments = landIdArg) {
            LandShapeScreen(
                onBack = { navController.popBackStack() },
                onEdit = { id -> navController.navigate(Routes.edit(id)) }
            )
        }
    }
}

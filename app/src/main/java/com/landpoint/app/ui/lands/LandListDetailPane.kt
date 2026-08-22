package com.landpoint.app.ui.lands

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.landpoint.app.R
import com.landpoint.app.ui.detail.LandDetailScreen
import com.landpoint.app.ui.navigation.Routes

/**
 * The pane's own "nothing chosen" destination.
 *
 * Private, and never handed to the app's own NavController: it exists so the pane
 * has something to show before a choice is made, and so closing a record has
 * somewhere to go back to.
 */
private const val NOTHING_CHOSEN = "pane_nothing_chosen"

/**
 * Share of the width the list keeps.
 *
 * The record is the thing being read — a map, an area, a column of coordinates —
 * so it takes the larger half; the list only has to stay wide enough for a name
 * and a distance on one line.
 */
private const val LIST_SHARE = 0.4f

/**
 * The land list with the chosen record beside it, for a window wide enough.
 *
 * On a phone held upright, opening a record replaces the list, and going back
 * brings it again — which is the right trade when there is one column of room. On
 * a tablet, or a phone on its side, that same navigation throws away most of the
 * screen to show one record, and every glance from one plot to the next becomes
 * open, read, back, open.
 *
 * The pane runs a navigation host of its own rather than holding the chosen id in
 * a piece of state. Not for tidiness: [LandDetailScreen]'s ViewModel reads the
 * land id out of its navigation entry, so a navigation entry is what it has to be
 * given. It pays for itself twice over — the choice survives rotation and process
 * death, and the system back button closes the record before it will leave the
 * tab.
 *
 * Editing, the compass and the full-screen boundary all stay full-screen jobs on
 * the app's own back stack: they are work with an end, and a job with an end wants
 * the whole window whatever size it is.
 */
@Composable
fun LandListDetailPane(
    onNewLand: () -> Unit,
    onEdit: (String) -> Unit,
    onCompass: (String) -> Unit,
    onShape: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val paneNav = rememberNavController()
    val landIdArg = listOf(navArgument(Routes.ARG_LAND_ID) { type = NavType.StringType })

    Row(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(LIST_SHARE)) {
            LandListScreen(
                onOpenLand = { id -> paneNav.showInPane(Routes.detail(id)) },
                onNewLand = onNewLand
            )
        }

        VerticalDivider()

        NavHost(
            navController = paneNav,
            startDestination = NOTHING_CHOSEN,
            modifier = Modifier.weight(1f - LIST_SHARE)
        ) {
            composable(NOTHING_CHOSEN) { NothingChosen() }

            composable(Routes.DETAIL, arguments = landIdArg) {
                LandDetailScreen(
                    // The arrow does not go back anywhere here — the list never
                    // left. It closes the record and leaves the pane empty.
                    onBack = { paneNav.popBackStack() },
                    onEdit = onEdit,
                    onCompass = onCompass,
                    onShape = onShape
                )
            }
        }
    }
}

/**
 * Shows one record, replacing whatever was on show.
 *
 * Popping back to the placeholder first is what keeps the pane's back stack two
 * entries deep at most, so Back always means "close this record" rather than
 * walking backwards through every plot that has been glanced at. It also ends the
 * previous record's ViewModel, which is what stops a screenful of maps quietly
 * accumulating tile threads.
 */
private fun NavHostController.showInPane(route: String) {
    navigate(route) {
        popUpTo(NOTHING_CHOSEN)
        launchSingleTop = true
    }
}

/** The pane before a choice has been made: says what the empty half is for. */
@Composable
private fun NothingChosen() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 40.dp)
            ) {
                Icon(
                    Icons.Outlined.Landscape,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                )
                Text(
                    stringResource(R.string.pane_nothing_chosen_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Text(
                    stringResource(R.string.pane_nothing_chosen_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

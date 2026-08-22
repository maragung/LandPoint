package com.landpoint.app.ui.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.landpoint.app.R
import com.landpoint.app.ui.components.LocationPermissionState
import com.landpoint.app.ui.components.openAppSettings
import com.landpoint.app.ui.components.rememberLocationPermission
import kotlinx.coroutines.launch

private data class Intro(
    val icon: ImageVector,
    @StringRes val title: Int,
    @StringRes val body: Int
)

/**
 * Three things a first-time user cannot work out from the screens themselves:
 * what the app is for, that a boundary can be measured four different ways, and —
 * the one that costs money when it is missed — that there is no copy of these
 * records anywhere else.
 */
private val intros = listOf(
    Intro(Icons.Outlined.Landscape, R.string.onboarding_welcome_title, R.string.onboarding_welcome_body),
    Intro(Icons.Outlined.Straighten, R.string.onboarding_measure_title, R.string.onboarding_measure_body),
    Intro(Icons.Outlined.Lock, R.string.onboarding_private_title, R.string.onboarding_private_body)
)

/**
 * The first run: what LandPoint does, then the location request with its reason
 * given first.
 *
 * The reason comes before the system dialog because that dialog says only
 * "Allow LandPoint to access this device's location?", and someone who has just
 * installed a land-records app has no way to know whether refusing it leaves them
 * with a working app. It does — coordinates can be typed and tapped — and the last
 * page says so, so the choice is a real one rather than a guess.
 *
 * [onFinish] is called once, whether the permission was granted, refused or left
 * alone. Nothing here is a gate.
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val permission = rememberLocationPermission()
    val pageCount = intros.size + 1
    val pager = rememberPagerState(pageCount = { pageCount })
    val scope = rememberCoroutineScope()
    val onLast = pager.currentPage == pageCount - 1

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Skippable from the first page. Someone reinstalling the app after a
            // new phone has read all this before and wants their records back.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                if (!onLast) {
                    TextButton(onClick = onFinish) {
                        Text(stringResource(R.string.onboarding_skip))
                    }
                }
            }

            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f)
            ) { page ->
                if (page < intros.size) {
                    IntroPage(intros[page])
                } else {
                    LocationPage(permission)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Dots(count = pageCount, current = pager.currentPage)
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        if (onLast) onFinish()
                        else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    }
                ) {
                    Text(
                        stringResource(
                            if (onLast) R.string.onboarding_start else R.string.onboarding_next
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun IntroPage(intro: Intro) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            intro.icon,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            stringResource(intro.title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(
            stringResource(intro.body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The ask, with the consequence of refusing stated rather than implied. */
@Composable
private fun LocationPage(permission: LocationPermissionState) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.GpsFixed,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            stringResource(R.string.onboarding_location_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(
            stringResource(R.string.onboarding_location_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        when {
            permission.isGranted -> Text(
                stringResource(R.string.onboarding_location_granted),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )

            // The system will not put the dialog up again, so offering the ask
            // would be a button that does nothing.
            permission.isBlocked -> {
                OutlinedButton(onClick = { openAppSettings(context) }) {
                    Text(stringResource(R.string.permission_open_settings))
                }
                Text(
                    stringResource(R.string.permission_blocked_body),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> {
                Button(onClick = permission.request) {
                    Text(stringResource(R.string.permission_grant))
                }
                Text(
                    stringResource(R.string.onboarding_location_later),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Where in the introduction this is, and how much of it is left. */
@Composable
private fun Dots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { index ->
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        if (index == current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape
                    )
            )
        }
    }
}

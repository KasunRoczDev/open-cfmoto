// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.shell

import android.app.Activity
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.TwoWheeler
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.zanderp.opencfmoto.NavLauncher
import dev.zanderp.opencfmoto.ui.ride.HeroData
import dev.zanderp.opencfmoto.maintenance.MaintenanceSummary
import dev.zanderp.opencfmoto.ui.ride.ParkHere
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.zanderp.opencfmoto.AboutActivity
import dev.zanderp.opencfmoto.ConnectionState
import dev.zanderp.opencfmoto.ControlsActivity
import dev.zanderp.opencfmoto.CrashGuard
import dev.zanderp.opencfmoto.GarageActivity
import dev.zanderp.opencfmoto.HudViewActivity
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.SetupActivity
import dev.zanderp.opencfmoto.TripActivity
import dev.zanderp.opencfmoto.TripsListActivity
import dev.zanderp.opencfmoto.connect.ConnectionController
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.dialog.DialogHost
import dev.zanderp.opencfmoto.ui.log.LogsScreen
import dev.zanderp.opencfmoto.ui.ride.RideScreen
import dev.zanderp.opencfmoto.ui.ride.rideUiState
import dev.zanderp.opencfmoto.ui.tabs.MapTab
import dev.zanderp.opencfmoto.ui.tabs.MeTab
import dev.zanderp.opencfmoto.ui.tabs.TripsTab

private data class Tab(val route: String, val labelRes: Int, val icon: ImageVector)

private val Tabs = listOf(
    Tab("ride", R.string.ui_tab_ride, Icons.Rounded.TwoWheeler),
    Tab("map", R.string.ui_tab_map, Icons.Rounded.Map),
    Tab("trips", R.string.ui_tab_trips, Icons.Rounded.Insights),
    Tab("me", R.string.ui_tab_me, Icons.Rounded.Person),
)

/** @param searchFocusTick bumped by MainActivity when the bike asks to type on the phone.
 *  @param openMaintenanceTick bumped by MainActivity when a maintenance notification is tapped. */
@Composable
fun AppShell(c: ConnectionController, searchFocusTick: Int, openMaintenanceTick: Int = 0) {
    val ctx = LocalContext.current
    val nav = rememberNavController()
    val (phase, detail) = ConnectionState.flow.collectAsStateWithLifecycle().value
    val bike by c.bike.collectAsStateWithLifecycle()
    val dialog by c.dialog.collectAsStateWithLifecycle()
    val ride = remember(phase, detail, bike) { rideUiState(phase, detail, bike.name, bike.hasSaved) }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    // Hero data (model, photo, rides, parked pin) reloads each time the screen becomes visible — a ride just saved or a
    // model just typed in Garage shows when the rider comes back.
    var heroTick by remember { mutableIntStateOf(0) }
    var hero by remember { mutableStateOf(HeroData.EMPTY) }
    var maintenance by remember { mutableStateOf(MaintenanceSummary.EMPTY) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, bike, heroTick) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            hero = withContext(Dispatchers.IO) { HeroData.load(ctx.applicationContext) }
            maintenance = withContext(Dispatchers.IO) { MaintenanceSummary.load(ctx.applicationContext, System.currentTimeMillis()) }
        }
    }

    LaunchedEffect(searchFocusTick) { if (searchFocusTick > 0) nav.navigate("map") { launchSingleTop = true } }
    LaunchedEffect(openMaintenanceTick) { if (openMaintenanceTick > 0) nav.navigate("maintenance") { launchSingleTop = true } }

    Scaffold(
        containerColor = Ocm.colors.bg,
        bottomBar = {
            NavigationBar(containerColor = Ocm.colors.surface) {
                for (t in Tabs) {
                    val selected = route == t.route || (t.route == "me" && (route == "logs" || route == "forest" || route == "maintenance"))
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.labelRes)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Ocm.colors.accent, selectedTextColor = Ocm.colors.accent,
                            indicatorColor = Ocm.colors.accent.copy(alpha = 0.14f),
                            unselectedIconColor = Ocm.colors.text2, unselectedTextColor = Ocm.colors.text2,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "ride", modifier = Modifier.padding(pad)) {
            composable("ride") {
                RideScreen(
                    s = ride,
                    hero = hero,
                    onPrimary = c::onConnectPressed,
                    onDashView = { ctx.startActivity(android.content.Intent(ctx, HudViewActivity::class.java)) },
                    onControls = { ctx.startActivity(android.content.Intent(ctx, ControlsActivity::class.java)) },
                    onParked = {
                        val p = hero.parked
                        if (p != null) NavLauncher.navigateLatLon(ctx, p.lat, p.lon, p.name, LogBus::log)
                        else if (ParkHere.mark(ctx as Activity)) heroTick++
                    },
                    onMirror = c::onMirrorPressed,
                    onScan = c::startAaScan,
                    onRides = { TripsListActivity.start(ctx) },
                )
            }
            composable("map") {
                MapTab(searchFocusTick, openMapEnabled = ride.mapEnabled, onOpenMap = c::onMapPressed, onSearch = c::navigateToTyped, onSearchTextChanged = c::onSearchTextChanged)
            }
            composable("trips") {
                TripsTab(onSavedRides = { TripsListActivity.start(ctx) }, onTripComputer = { TripActivity.start(ctx) })
            }
            composable("me") {
                MeTab(
                    onGarage = { GarageActivity.start(ctx) }, onSettings = { SetupActivity.start(ctx) }, onForest = { nav.navigate("forest") },
                    onMaintenance = { nav.navigate("maintenance") }, maintenance = maintenance,
                    onLogs = { nav.navigate("logs") }, onReport = c::reportProblem, onUpdate = c::checkUpdateManual,
                    onAbout = { AboutActivity.start(ctx) }, onDonate = c::donate,
                )
            }
            composable("forest") { dev.zanderp.opencfmoto.ui.forest.ForestScreen() }
            composable("maintenance") {
                dev.zanderp.opencfmoto.ui.maintenance.MaintenanceScreen(onChanged = { heroTick++ })
            }
            composable("logs") {
                LogsScreen(
                    onShare = c::shareLog,
                    onClear = {
                        LogBus.clear()
                        CrashGuard.clearSession(ctx)
                        LogBus.logSessionBanner()
                    },
                )
            }
        }
    }
    DialogHost(dialog, c)
}

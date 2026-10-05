package com.example.ftnnavigation.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.ftnnavigation.R
import com.example.ftnnavigation.departure.DepartureScheduler
import com.example.ftnnavigation.settings.SettingsScreen
import com.example.ftnnavigation.events.EventEditScreen
import com.example.ftnnavigation.home.HomeScreen
import com.example.ftnnavigation.onboarding.OnboardingScreen
import com.example.ftnnavigation.poc.LiftDialog
import com.example.ftnnavigation.poc.PocRoute
import com.example.ftnnavigation.poc.PocViewModel
import com.example.ftnnavigation.schedule.ScheduleScreen
import com.example.ftnnavigation.schedule.ScheduleSelection
import com.example.ftnnavigation.schedule.ScheduleViewModel
import com.example.ftnnavigation.schedule.rememberNow
import com.example.ftnnavigation.schedule.selectionSummary
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable data object HomeRoute
@Serializable data object MapRoute
@Serializable data object ScheduleRoute
@Serializable data object SettingsRoute

/** Izmena događaja [eventId], ili novi događaj (eventId 0) za dan [date] (ISO). */
@Serializable data class EventEditRoute(val eventId: Long = 0, val date: String? = null)

/** Tabovi donje navigacione trake, redom kojim se prikazuju. */
private enum class TopLevelDestination(
    val route: Any,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
) {
    HOME(HomeRoute, R.string.nav_home, R.drawable.ic_home),
    MAP(MapRoute, R.string.nav_map, R.drawable.ic_map),
    SCHEDULE(ScheduleRoute, R.string.nav_schedule, R.drawable.ic_calendar),
}

/**
 * Prelazak na tab: back stack ostaje [Početna, tab] (nazad uvek vodi na Početnu),
 * a stanje napuštenog taba se čuva i vraća pri povratku.
 */
private fun NavController.navigateToTopLevel(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/** Koren aplikacije: donja navigaciona traka + NavHost sa ekranima. */
@Composable
fun FtnApp() {
    val navController = rememberNavController()
    // Vezani za aktivnost (poziv je van NavHost-a): raspored dele Početna i Raspored,
    // a mapu (graf, odredište) Početna i Mapa.
    val scheduleViewModel: ScheduleViewModel = viewModel()
    val mapViewModel: PocViewModel = viewModel()
    val currentDestination = navController.currentBackStackEntryAsState().value?.destination

    // Prvo pokretanje: izbor rasporeda pre svega ostalog (i pre pitanja za obaveštenja).
    if (scheduleViewModel.needsOnboarding) {
        OnboardingScreen(
            data = scheduleViewModel.data,
            onSave = scheduleViewModel::select,
            onSkip = scheduleViewModel::skipOnboarding,
        )
        return
    }
    DepartureNotificationsEffect(scheduleViewModel.selection)

    Scaffold(
        // Svaki ekran ima svoju gornju traku koja sama rešava status bar,
        // pa ovde ostaje samo prostor za donju traku.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            // Podekrani (Podešavanja, izmena događaja) su bez donje trake - nazad vraća odakle se došlo.
            val isSubscreen = currentDestination?.run { hasRoute(SettingsRoute::class) || hasRoute(EventEditRoute::class) } == true
            if (!isSubscreen) {
                NavigationBar {
                    // Podrazumevano je izabrani natpis u `secondary` (cijan), što na svetloj podlozi slabo čita.
                    val itemColors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                    )
                    TopLevelDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            colors = itemColors,
                            selected = currentDestination?.hierarchy?.any { it.hasRoute(destination.route::class) } == true,
                            onClick = { navController.navigateToTopLevel(destination.route) },
                            icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                            label = { Text(stringResource(destination.label)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            composable<HomeRoute> {
                val now by rememberNow()
                val timetable = scheduleViewModel.selectedTimetable
                val agenda = scheduleViewModel.agenda
                val upcoming = agenda.next(now)
                HomeScreen(
                    scheduleSummary = timetable?.let { selectionSummary(it, scheduleViewModel.selection?.group) },
                    upcoming = upcoming,
                    now = now,
                    nextBuilding = upcoming?.place?.let { mapViewModel.buildingNameOf(it) },
                    departure = upcoming?.let { mapViewModel.departureFor(it, agenda.on(it.date)) },
                    onOpenSchedule = { navController.navigateToTopLevel(ScheduleRoute) },
                    onOpenMap = { navController.navigateToTopLevel(MapRoute) },
                    onShowRoute = {
                        mapViewModel.selectDestination(upcoming?.place)
                        navController.navigateToTopLevel(MapRoute)
                    },
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                )
            }
            composable<MapRoute> { PocRoute(mapViewModel, scheduleViewModel.data, scheduleViewModel.calendar) }
            composable<ScheduleRoute> {
                ScheduleScreen(
                    scheduleViewModel,
                    onAddEvent = { navController.navigate(EventEditRoute(date = it.toString())) },
                    onEditEvent = { navController.navigate(EventEditRoute(eventId = it)) },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    autoRotateMap = mapViewModel.autoRotateMap,
                    onAutoRotateMapChange = mapViewModel::updateAutoRotateMap,
                    floorChange = mapViewModel.floorChange,
                    onFloorChangeChange = mapViewModel::updateFloorChange,
                    onBack = { navController.popBackStack() },
                )
            }
            composable<EventEditRoute> { entry ->
                val route = entry.toRoute<EventEditRoute>()
                val event = scheduleViewModel.event(route.eventId)
                val date = event?.localDate ?: route.date?.let(LocalDate::parse) ?: LocalDate.now()
                EventEditScreen(
                    event = event,
                    initialDate = date,
                    defaultUntil = scheduleViewModel.semesterEnd(date) ?: date.plusMonths(3),
                    places = mapViewModel.placeOptions,
                    onSave = {
                        scheduleViewModel.saveEvent(it)
                        navController.popBackStack()
                    },
                    onDelete = {
                        scheduleViewModel.deleteEvent(it)
                        navController.popBackStack()
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
    // Pitanje za lift iskače na bilo kom ekranu - praćenje radi i kad Mapa nije na ekranu.
    mapViewModel.state.liftPrompt?.let { prompt ->
        LiftDialog(prompt = prompt, onSelect = mapViewModel::selectLiftFloor, onDismiss = mapViewModel::dismissLift)
    }
}

/**
 * Obaveštenje "kreni na čas": dozvola se traži pri prvom pokretanju, a alarm se zakazuje
 * iznova pri svakom pokretanju i promeni izbora rasporeda (force stop briše alarme).
 */
@Composable
private fun DepartureNotificationsEffect(selection: ScheduleSelection?) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(selection) {
        DepartureScheduler.reschedule(context)
    }
}


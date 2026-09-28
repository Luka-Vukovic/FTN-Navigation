package com.example.ftnnavigation.departure

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.ftnnavigation.BuildConfig
import com.example.ftnnavigation.R
import com.example.ftnnavigation.schedule.TIME_FORMAT
import com.example.ftnnavigation.schedule.dayName
import com.example.ftnnavigation.ui.components.FtnTopAppBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Podešavanja obaveštenja o polasku: uključivanje, stanje sistemskih dozvola (obaveštenja,
 * tačni alarmi) sa prečicom do sistemskih podešavanja i sledeće zakazano obaveštenje.
 */
@Composable
fun DepartureSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val alarms = remember { context.getSystemService(AlarmManager::class.java) }
    var enabled by remember { mutableStateOf(DepartureScheduler.isEnabled(context)) }
    var allowed by remember { mutableStateOf(DepartureNotifications.areAllowed(context)) }
    var exact by remember { mutableStateOf(alarms.canScheduleExactAlarms()) }
    // Dozvole se menjaju u sistemskim podešavanjima - stanje se osvežava pri povratku na ekran.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        allowed = DepartureNotifications.areAllowed(context)
        exact = alarms.canScheduleExactAlarms()
        resumes++
        onPauseOrDispose {}
    }
    var upcoming by remember { mutableStateOf<Departure?>(null) }
    var upcomingLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled, resumes) {
        if (enabled) {
            upcoming = DepartureScheduler.upcoming(context)
            upcomingLoaded = true
        }
    }

    Scaffold(
        topBar = {
            FtnTopAppBar(
                title = stringResource(R.string.notifications_title),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.nav_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsCard {
                Row(
                    Modifier.toggleable(
                        value = enabled,
                        role = Role.Switch,
                        onValueChange = {
                            enabled = it
                            scope.launch { DepartureScheduler.setEnabled(context, it) }
                        },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(stringResource(R.string.departure_channel_name), style = MaterialTheme.typography.titleMedium)
                        CardBody(stringResource(R.string.departure_channel_description))
                    }
                    Switch(checked = enabled, onCheckedChange = null)
                }
            }
            if (enabled) {
                if (!allowed) {
                    SettingsCard(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        Text(stringResource(R.string.notifications_blocked), style = MaterialTheme.typography.bodyMedium)
                        FilledTonalButton(onClick = { openNotificationSettings(context) }, modifier = Modifier.align(Alignment.End)) {
                            Text(stringResource(R.string.notifications_open_settings))
                        }
                    }
                }
                SettingsCard {
                    Text(stringResource(R.string.notifications_exact_title), style = MaterialTheme.typography.titleMedium)
                    CardBody(
                        if (exact) {
                            stringResource(R.string.notifications_exact_on)
                        } else {
                            stringResource(R.string.notifications_exact_off, DepartureScheduler.INEXACT_WINDOW_MIN)
                        },
                    )
                    if (!exact) {
                        FilledTonalButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()),
                                )
                            },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(stringResource(R.string.notifications_exact_allow))
                        }
                    }
                }
                SettingsCard {
                    Text(stringResource(R.string.notifications_next_title), style = MaterialTheme.typography.titleMedium)
                    val departure = upcoming
                    when {
                        !upcomingLoaded -> Unit
                        departure == null -> CardBody(stringResource(R.string.home_no_upcoming))
                        else -> UpcomingDeparture(departure, exact)
                    }
                    if (BuildConfig.DEBUG) {
                        OutlinedButton(onClick = { showTestNotification(context, scope) }, modifier = Modifier.align(Alignment.End)) {
                            Text(stringResource(R.string.debug_test_notification))
                        }
                    }
                }
            }
        }
    }
}

/** Kada stiže obaveštenje (bez tačnih alarma: prozor pre notifyAt), za koji čas / događaj i odakle je ruta. */
@Composable
private fun UpcomingDeparture(departure: Departure, exact: Boolean) {
    val item = departure.item
    val notifyAt = departure.notifyAt
    val day = dayLabel(notifyAt.toLocalDate())
    val time = if (exact) {
        stringResource(R.string.notifications_next_at, day, notifyAt.format(TIME_FORMAT))
    } else {
        val from = notifyAt.minusMinutes(DepartureScheduler.INEXACT_WINDOW_MIN)
        stringResource(R.string.notifications_next_window, day, from.format(TIME_FORMAT), notifyAt.format(TIME_FORMAT))
    }
    val routeLine = departure.routeText(LocalResources.current)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(time, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(item.title, style = MaterialTheme.typography.bodyLarge)
        CardBody(listOfNotNull(item.start.format(TIME_FORMAT), item.place).joinToString(" · "))
        if (routeLine != null) CardBody(routeLine)
    }
}

@Composable
private fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> stringResource(R.string.when_today)
        today.plusDays(1) -> stringResource(R.string.when_tomorrow)
        else -> "${dayName(date.dayOfWeek.value)} ${date.format(DATE_FORMAT)}"
    }
}

/** Obaveštenja aplikacije su isključena -> njena podešavanja, inače podešavanja kanala. */
private fun openNotificationSettings(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    val intent = if (manager.areNotificationsEnabled()) {
        DepartureNotifications.createChannel(context)
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_CHANNEL_ID, DepartureNotifications.CHANNEL_ID)
    } else {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    }
    context.startActivity(intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
}

/** Probno obaveštenje za sledeći polazak (debug); Toast kad nije prikazano. */
private fun showTestNotification(context: Context, scope: CoroutineScope) {
    scope.launch {
        val message = when {
            !DepartureNotifications.areAllowed(context) -> R.string.debug_test_no_permission
            !DepartureScheduler.showTest(context) -> R.string.debug_test_no_departure
            else -> null
        }
        if (message != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun SettingsCard(
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun CardBody(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val DATE_FORMAT = DateTimeFormatter.ofPattern("d.M.")

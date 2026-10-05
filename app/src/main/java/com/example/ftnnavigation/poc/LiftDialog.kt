package com.example.ftnnavigation.poc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.ftnnavigation.R
import com.example.ftnnavigation.graph.indoorBuilding

/**
 * Pitanje "na koji sprat?" kad korisnik stoji kod lifta ([LiftPrompt]): dugme po spratu do koga lift ide (od najvišeg,
 * kao birač sprata), sprat odredišta istaknut. Dodir van dijaloga ga ne zatvara - dok se vozi, telefon je možda u džepu,
 * a odgovara se posle izlaska; "Nisam u liftu" (ili nazad) ga sklanja.
 */
@Composable
fun LiftDialog(
    prompt: LiftPrompt,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val building = indoorBuilding(prompt.buildingId)
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.poc_lift_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.poc_lift_text,
                        building?.let { stringResource(it.locationRes(), floorName(prompt.fromFloor)) } ?: floorName(prompt.fromFloor),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Kula ima 10 nivoa - lista se skroluje.
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (floor in prompt.floors) {
                        if (floor == prompt.suggested) {
                            Button(onClick = { onSelect(floor) }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.poc_lift_destination, floorName(floor)))
                            }
                        } else {
                            OutlinedButton(onClick = { onSelect(floor) }, modifier = Modifier.fillMaxWidth()) {
                                Text(floorName(floor))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.poc_lift_dismiss)) }
        },
    )
}

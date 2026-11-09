package com.example.ftnnavigation.poc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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

/**
 * Pitanje "gore ili dole?" posle prolaza na međupodest bez odredišta ([LevelPrompt]; AMF S1 posle trema iz Kule): dugme za viši
 * nivo (naviše, tačka je tamo) i niži (naniže). Kao [LiftDialog]: dodir van dijaloga ga ne zatvara, "Ne znam" (ili nazad) ga
 * sklanja. Teren 09.11.2026 popodne: silazak srednjim krakom se po senzorima ne razlikuje od penjanja bočnim.
 */
@Composable
fun LevelDialog(
    prompt: LevelPrompt,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val upper = floorName(prompt.upper)
    val lower = floorName(prompt.lower)
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.poc_level_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.poc_level_text, upper, lower), style = MaterialTheme.typography.bodyMedium)
                Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { onSelect(prompt.upper) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.poc_level_up, upper.replaceFirstChar { it.uppercase() }))
                    }
                    OutlinedButton(onClick = { onSelect(prompt.lower) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.poc_level_down, lower.replaceFirstChar { it.uppercase() }))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.poc_level_dismiss)) }
        },
    )
}

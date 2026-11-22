package id.tetuko.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.datetime.LocalDate

private const val MILLIS_PER_DAY = 86_400_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val initialMillis = remember(value) {
        runCatching { LocalDate.parse(value).toEpochDays().toLong() * MILLIS_PER_DAY }.getOrNull()
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = supportingText?.let { text -> ({ Text(text) }) },
        readOnly = readOnly,
        isError = isError,
        trailingIcon = {
            IconButton(onClick = { pickerOpen = true }, enabled = enabled) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = "Pilih tanggal")
            }
        },
        singleLine = true,
        enabled = enabled,
        modifier = modifier,
    )

    if (pickerOpen) {
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { pickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onValueChange(LocalDate.fromEpochDays((millis / MILLIS_PER_DAY).toInt()).toString())
                    }
                    pickerOpen = false
                }) { Text("Pilih") }
            },
            dismissButton = { TextButton(onClick = { pickerOpen = false }) { Text("Batal") } },
        ) {
            DatePicker(state = state, showModeToggle = false)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val parts = remember(value) {
        Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(value.trim())?.groupValues
    }
    val hour = parts?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
    val minute = parts?.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 59) ?: 0

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        trailingIcon = {
            IconButton(onClick = { pickerOpen = true }, enabled = enabled) {
                Icon(Icons.Filled.Schedule, contentDescription = "Pilih jam")
            }
        },
        singleLine = true,
        enabled = enabled,
        modifier = modifier,
    )

    if (pickerOpen) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            title = { Text(label) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    onValueChange("%02d:%02d".format(state.hour, state.minute))
                    pickerOpen = false
                }) { Text("Pilih") }
            },
            dismissButton = { TextButton(onClick = { pickerOpen = false }) { Text("Batal") } },
        )
    }
}
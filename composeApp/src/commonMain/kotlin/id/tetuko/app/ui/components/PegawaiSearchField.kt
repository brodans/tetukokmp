package id.tetuko.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.tetuko.app.data.PegawaiSearchResult
import id.tetuko.app.data.PegawaiSearchRepository
import id.tetuko.app.data.RefTable
import id.tetuko.app.data.searchPegawai
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun PegawaiSearchField(
    enabled: Boolean,
    selected: PegawaiSearchResult,
    own: PegawaiSearchResult,
    onSelect: (PegawaiSearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    var table by remember { mutableStateOf<RefTable?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(emptyList<PegawaiSearchResult>()) }
    var query by remember(selected.id, selected.nama, selected.nip) {
        mutableStateOf(selected.nama.ifBlank { selected.nip }.ifBlank { selected.id })
    }

    LaunchedEffect(enabled) {
        if (enabled && table == null && !loading) {
            loading = true
            loadError = null
            try {
                table = withContext(Dispatchers.Default) {
                    PegawaiSearchRepository.loadTable()
                }
            } catch (error: Exception) {
                loadError = error.message ?: "Data pegawai tidak dapat dimuat."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(table, query, enabled) {
        val source = table
        val searchQuery = query.trim()
        if (!enabled || source == null || searchQuery.length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(180)
        results = withContext(Dispatchers.Default) { source.searchPegawai(searchQuery) }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!enabled) {
            Text(
                "Target saat ini: ${selected.nama.ifBlank { selected.nip }.ifBlank { selected.id }}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Pencarian pegawai lain dibatasi oleh izin akun.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            expanded = true
                        },
                        label = { Text("Cari pegawai (nama/NIP)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            Text(
                                when {
                                    loading -> "Memuat database pegawai..."
                                    loadError != null -> loadError!!
                                    else -> "Ketik minimal 2 karakter untuk mencari."
                                },
                            )
                        },
                    )
                    DropdownMenu(
                        expanded = expanded && query.trim().length >= 2,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        when {
                            loading -> DropdownMenuItem(
                                text = { Text("Memuat data pegawai...") },
                                onClick = {},
                                enabled = false,
                            )
                            loadError != null -> DropdownMenuItem(
                                text = { Text(loadError!!) },
                                onClick = {},
                                enabled = false,
                            )
                            results.isEmpty() -> DropdownMenuItem(
                                text = { Text("Pegawai tidak ditemukan.") },
                                onClick = {},
                                enabled = false,
                            )
                            else -> results.forEach { result ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(result.nama, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                listOf(result.nip, result.instansi).filter { it.isNotBlank() }.joinToString(" • "),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    onClick = {
                                        onSelect(result)
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                if (selected.id != own.id) {
                    OutlinedButton(onClick = { onSelect(own) }) { Text("Saya") }
                }
            }
        }
    }
}

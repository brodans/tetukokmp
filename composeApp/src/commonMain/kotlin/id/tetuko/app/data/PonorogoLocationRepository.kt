package id.tetuko.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PonorogoLocation(
    val id: String,
    val kode: String,
    val nama: String,
    val alamat: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Double?,
)

object PonorogoLocationRepository {
    private val mutex = Mutex()
    private var cachedTable: RefTable? = null

    suspend fun loadTable(): RefTable = mutex.withLock {
        cachedTable ?: RefDataLoader.loadTable("files/lokasi.json").also { cachedTable = it }
    }
}

fun RefTable.findLocationFor(pegawai: PegawaiSearchResult): PonorogoLocation? {
    fun matches(predicate: (Int) -> Boolean): PonorogoLocation? {
        for (index in 0 until size) {
            if (!predicate(index)) continue
            val latitude = cell(index, "latitude").toDoubleOrNull() ?: continue
            val longitude = cell(index, "longitude").toDoubleOrNull() ?: continue
            return PonorogoLocation(
                id = cell(index, "id"),
                kode = cell(index, "kode"),
                nama = cell(index, "nama"),
                alamat = cell(index, "alamat"),
                latitude = latitude,
                longitude = longitude,
                radius = cell(index, "radius").toDoubleOrNull(),
            )
        }
        return null
    }

    if (pegawai.idLokasi.isNotBlank()) {
        matches { cell(it, "id") == pegawai.idLokasi }?.let { return it }
    }
    val name = pegawai.namaLokasi.trim().lowercase()
    val unor = pegawai.unor.trim().lowercase()
    val instansi = pegawai.instansi.trim().lowercase()
    if (name.isNotBlank()) matches { cell(it, "nama").trim().lowercase() == name }?.let { return it }
    if (unor.isNotBlank()) matches { cell(it, "nama").trim().lowercase() == unor }?.let { return it }
    if (instansi.isNotBlank()) matches { cell(it, "nama").trim().lowercase() == instansi }?.let { return it }
    if (name.isNotBlank()) {
        matches {
            val locationName = cell(it, "nama").trim().lowercase()
            locationName.contains(name) || name.contains(locationName)
        }?.let { return it }
    }
    if (unor.isNotBlank()) {
        matches {
            val locationName = cell(it, "nama").trim().lowercase()
            locationName.contains(unor) || unor.contains(locationName)
        }?.let { return it }
    }
    if (pegawai.kodeInstansi.isNotBlank()) {
        matches { cell(it, "kode") == pegawai.kodeInstansi }?.let { return it }
    }
    return matches { true }
}

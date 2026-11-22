package id.tetuko.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PegawaiSearchResult(
    val id: String,
    val nip: String,
    val nama: String,
    val instansi: String,
    val kodeUnor: String = "",
    val kodeInstansi: String = "",
    val unor: String = "",
    val namaLokasi: String = "",
    val idLokasi: String = "",
    val deviceId: String = "",
    val badgenumber: String = "",
)

object PegawaiSearchRepository {
    private val mutex = Mutex()
    private var cachedTable: RefTable? = null

    suspend fun loadTable(): RefTable = mutex.withLock {
        cachedTable ?: RefDataLoader.loadTable("files/pegawai.json").also { cachedTable = it }
    }
}

fun RefTable.searchPegawai(query: String, limit: Int = 10): List<PegawaiSearchResult> {
    if (query.trim().length < 2 || limit <= 0) return emptyList()
    return search(query, listOf("nama", "nip", "id", "id_pegawai"), limit).mapNotNull { row ->
        val id = row["id"].orEmpty().ifBlank { row["id_pegawai"].orEmpty() }
        val nama = row["nama"].orEmpty()
        if (id.isBlank() || nama.isBlank()) return@mapNotNull null
        PegawaiSearchResult(
            id = id,
            nip = row["nip"].orEmpty(),
            nama = nama,
            instansi = row["nama_instansi"].orEmpty()
                .ifBlank { row["instansi"].orEmpty() }
                .ifBlank { row["unor"].orEmpty() },
            kodeUnor = row["kode_unor"].orEmpty().ifBlank { row["unor"].orEmpty() },
            kodeInstansi = row["kode_instansi"].orEmpty(),
            unor = row["unor"].orEmpty(),
            namaLokasi = row["nama_lokasi"].orEmpty(),
            idLokasi = row["id_lokasi"].orEmpty(),
            deviceId = row["emei"].orEmpty().ifBlank {
                row["imei"].orEmpty().ifBlank { row["device_id"].orEmpty().ifBlank { row["sim_serial"].orEmpty() } }
            },
            badgenumber = row["badgenumber"].orEmpty(),
        )
    }
}

fun RefTable.findImmediateSupervisor(pegawaiId: String, nip: String): PegawaiSearchResult? {
    val ownIndex = (0 until size).firstOrNull { index ->
        (pegawaiId.isNotBlank() && cell(index, "id") == pegawaiId) ||
            (nip.isNotBlank() && cell(index, "nip") == nip)
    } ?: return null
    val own = rowObject(ownIndex)
    val ownId = own["id"].orEmpty()
    val ownUnor = own["kode_unor"].orEmpty().ifBlank { own["unor"].orEmpty() }
    if (ownUnor.isBlank()) return null

    fun bestStructuralMatch(unor: String): PegawaiSearchResult? {
        var best: PegawaiSearchResult? = null
        var bestClass = Int.MIN_VALUE
        for (index in 0 until size) {
            val id = cell(index, "id")
            if (id.isBlank() || id == ownId || cell(index, "tipe_jabatan") != "STRUKTURAL") continue
            if (cell(index, "kode_unor") != unor && cell(index, "unor") != unor) continue
            val kelas = cell(index, "kelas_jabatan").toIntOrNull() ?: 0
            if (kelas > bestClass) {
                bestClass = kelas
                best = rowObject(index).toSearchResult()
            }
        }
        return best
    }

    bestStructuralMatch(ownUnor)?.let { return it }

    val segments = ownUnor.split('.')
    if (segments.size >= 2) {
        for (index in segments.lastIndex downTo 1) {
            val parent = segments.toMutableList()
            for (child in index until parent.size) parent[child] = "00"
            val parentUnor = parent.joinToString(".")
            if (parentUnor != ownUnor) bestStructuralMatch(parentUnor)?.let { return it }
        }
    }

    var best: PegawaiSearchResult? = null
    var bestClass = Int.MIN_VALUE
    for (index in 0 until size) {
        val id = cell(index, "id")
        if (id.isBlank() || id == ownId || cell(index, "tipe_jabatan") != "STRUKTURAL") continue
        val sameUnit = cell(index, "instansi") == own["instansi"] ||
            cell(index, "nama_instansi") == own["nama_instansi"] ||
            cell(index, "unor") == own["unor"]
        if (!sameUnit) continue
        val kelas = cell(index, "kelas_jabatan").toIntOrNull() ?: 0
        if (kelas > bestClass) {
            bestClass = kelas
            best = rowObject(index).toSearchResult()
        }
    }
    return best
}

private fun Map<String, String>.toSearchResult(): PegawaiSearchResult = PegawaiSearchResult(
    id = this["id"].orEmpty().ifBlank { this["id_pegawai"].orEmpty() },
    nip = this["nip"].orEmpty(),
    nama = this["nama"].orEmpty(),
    instansi = this["nama_instansi"].orEmpty().ifBlank { this["instansi"].orEmpty() },
    kodeUnor = this["kode_unor"].orEmpty().ifBlank { this["unor"].orEmpty() },
    kodeInstansi = this["kode_instansi"].orEmpty(),
    unor = this["unor"].orEmpty(),
    namaLokasi = this["nama_lokasi"].orEmpty(),
    idLokasi = this["id_lokasi"].orEmpty(),
    deviceId = this["emei"].orEmpty().ifBlank {
        this["imei"].orEmpty().ifBlank { this["device_id"].orEmpty().ifBlank { this["sim_serial"].orEmpty() } }
    },
    badgenumber = this["badgenumber"].orEmpty(),
)

package id.tetuko.core.data.rpc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object RpcObjects {
    const val LOGIN = "login"
    const val LOGOUT = "logout"
    const val GET_WORK_CODE = "getworkcode"
    const val GET_LOKASI_ABSEN = "getlokasiabsen"
    const val GET_MASTER_TIPE_IJIN = "getmastertipeijin"
    const val JENIS_IJIN = "jenis_ijin"
    const val TIPE_IJIN = "tipe_ijin"
    const val SYNC_DATA = "syncdata"
    const val CEK_ABSEN = "cekabsen"
    const val ABSEN = "absen"
    const val HISTORY_ABSEN = "history_absen"
    const val ADD_IJIN = "add_ijin"
    const val DELETE_IJIN = "delete_ijin"
    const val LIST_IJIN = "list_ijin"
    const val UPDATE_FOTO = "update_foto"
    const val UPDATE_PROFIL = "update_profil"
}

object UploadContract {
    const val IMPORT_FILE_PATH_QUERY = "/api/upload?path=%2Fimportfile"
    const val IMPORT_FILE_PATH = "/importfile"
    const val FIELD_API_KEY = "api_key"
    const val FIELD_ID = "id"
    const val FIELD_LAST_LATLONG = "last_latlong"
    const val FIELD_TYPE = "type"
    const val FIELD_IMAGE = "image"
    const val TYPE_IJIN = "ijin"
}

object AbsenCheckType {
    const val DATANG = 1
    const val PULANG = 2
    const val SIANG = 3
    const val DINAS_LUAR = 4

    fun label(code: Int?): String = when (code) {
        DATANG -> "Datang"
        PULANG -> "Pulang"
        SIANG -> "Siang"
        DINAS_LUAR -> "Dinas Luar"
        else -> code?.toString().orEmpty()
    }
}

object GateCodes {
    const val INVALID_NIP = 401
    const val DEVICE_BOUND = 402
    const val FORBIDDEN = 403
    const val OBJECT_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INVALID_TOKEN = -32604

    fun isInvalidToken(code: Int?): Boolean = code == INVALID_TOKEN
}

val RETRYABLE_OBJECTS = setOf(
    RpcObjects.GET_WORK_CODE,
    RpcObjects.GET_LOKASI_ABSEN,
    RpcObjects.GET_MASTER_TIPE_IJIN,
    RpcObjects.JENIS_IJIN,
    RpcObjects.TIPE_IJIN,
    RpcObjects.HISTORY_ABSEN,
    RpcObjects.LIST_IJIN,
)

val INJECTED_PARAM_KEYS = listOf("api_key", "last_latlong", "imei")

@Serializable
data class RpcEnvelope(
    val jsonrpc: Int = 2,
    val method: String = "POST",
    val version: Int = 89,
    val `object`: String,
    val param: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class RpcRequestBody(
    val `object`: String,
    val param: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class RpcRawResponse(
    val jsonrpc: Int? = null,
    @SerialName("id_req") val idReq: String? = null,
    val result: JsonElement? = null,
    val total: Int? = null,
    val page: Int? = null,
    val view: Int? = null,
    val error: String? = null,
    val errorCode: Int? = null,
    val message: String? = null,
    val huluStatus: Int? = null,
)

@Serializable
data class ServerContext(
    val apiKey: String = "",
    val lastLatLong: String = "",
    val imei: String = "",
)

@Serializable
data class LoginResult(
    val message: String? = null,
    val api_key: String? = null,
    val pegawai_id: Int? = null,
    val user_id: Int? = null,
    val departemen_id: Int? = null,
    val group_id: Int? = null,
    val group_name: String? = null,
    val nama: String? = null,
    val jabatan: String? = null,
    val departemen: String? = null,
    val logo_departemen: String? = null,
    val upload_wajah: Int? = null,
    val allow_wfh: Int? = null,
    val foto_profile: String? = null,
    val vektor_profile: String? = null,
    val vektor_approved: Int? = null,
    val home: Int? = null,
    val presensi: Int? = null,
    val perizinan: Int? = null,
    val laporan: Int? = null,
    val label_wfh: String? = null,
    val absen_timeout: JsonElement? = null,
    val using_wajah: Int? = null,
)

@Serializable
data class CekAbsenResult(
    val absen: Boolean = false,
    val message: String? = null,
    val kode: String? = null,
)

@Serializable
data class SimpleResult(
    val result: Boolean? = null,
    val code: Int? = null,
    val message: String? = null,
)

@Serializable
data class WorkCodeModel(
    val id: Int = 0,
    val nama: String = "",
    val hari: HariModel? = null,
)

@Serializable
data class HariModel(
    val nama: String = "",
    val jam: JamModel? = null,
)

@Serializable
data class JamModel(
    val jam_masuk_awal: String = "",
    val jam_masuk: String = "",
    val jam_keluar: String = "",
    val jam_keluar_akhir: String = "",
)

@Serializable
data class LokasiModel(
    val tipe: String = "",
    val id: Int = 0,
    val data: String = "",
    val latlong: String = "",
    val lokasi: String = "",
    val radius: Double = 0.0,
    val deskripsi: String = "",
    val prioritas: Int = 0,
)

@Serializable
data class HistoryAbsenModel(
    val nama: String? = null,
    val departemen: String? = null,
    val jarak: String? = null,
    val checktype: String? = null,
    val approval: Boolean? = null,
    val approval_text: String? = null,
    val waktu: String? = null,
)

@Serializable
data class IjinModel(
    val id: Int = 0,
    val approval: Boolean = false,
    val approval_at: String? = null,
    val email: String? = null,
    val nama: String? = null,
    val departemen: String? = null,
    val tipe_ijin: Int? = null,
    val tipe_ijin_text: String? = null,
    val jenis_ijin: Int? = null,
    val jenis_ijin_text: String? = null,
    val master_jenis_ijin: Int? = null,
    val tgl_ijin: String? = null,
    val tgl_ijin_dari: String? = null,
    val tgl_ijin_sampai: String? = null,
    val alasan: String? = null,
    val berkas: String? = null,
    val created_at: String? = null,
    val catatan: String? = null,
    val status: Int? = null,
)

@Serializable
data class MasterTipeIjinModel(
    val Id: Int = 0,
    val Nama: String = "",
    val Tipe: List<Int> = emptyList(),
)

@Serializable
data class JenisIjinModel(
    val Id: Int = 0,
    val Nama: String = "",
    val Kode: String = "",
    val SetengahHari: Boolean = false,
    val TipeId: Int = 0,
    val Potongan: String? = null,
    val IsAktif: Int = 1,
)

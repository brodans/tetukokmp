package id.tetuko.core.data.repository

import id.tetuko.core.data.rpc.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PresensiRepository(private val client: RpcClient) {

    suspend fun login(
        email: String,
        password: String,
        imei: String = "",
        latLong: String = "0,0",
        profileId: String? = null,
    ): RpcRawResponse {
        val param = buildJsonObject {
            put("email", email)
            put("password", password)
            put("latlong", latLong)
            put("imei", imei)
        }
        return client.callRaw(RpcObjects.LOGIN, param, ServerContext(), profileId)
    }

    suspend fun logout(ctx: ServerContext, profileId: String? = null): JsonElement? =
        client.callResultElement(RpcObjects.LOGOUT, JsonObject(emptyMap()), ctx, profileId)

    suspend fun getWorkCode(ctx: ServerContext, profileId: String? = null): JsonElement? =
        client.callResultElement(RpcObjects.GET_WORK_CODE, JsonObject(emptyMap()), ctx, profileId)

    suspend fun getLokasiAbsen(ctx: ServerContext, profileId: String? = null): JsonElement? =
        client.callResultElement(RpcObjects.GET_LOKASI_ABSEN, JsonObject(emptyMap()), ctx, profileId)

    suspend fun getMasterTipeIjin(ctx: ServerContext, profileId: String? = null): JsonElement? =
        client.callResultElement(RpcObjects.GET_MASTER_TIPE_IJIN, JsonObject(emptyMap()), ctx, profileId)

    suspend fun jenisIjin(
        ctx: ServerContext,
        absen: Int = 0,
        masterTipeIjin: Int = 0,
        profileId: String? = null,
    ): JsonElement? = client.callResultElement(
        RpcObjects.JENIS_IJIN,
        buildJsonObject {
            put("absen", absen)
            put("master_tipe_ijin", masterTipeIjin)
        },
        ctx,
        profileId,
    )

    suspend fun tipeIjin(ctx: ServerContext, profileId: String? = null): JsonElement? =
        client.callResultElement(RpcObjects.TIPE_IJIN, JsonObject(emptyMap()), ctx, profileId)

    suspend fun historyAbsen(
        ctx: ServerContext,
        tgl: String,
        page: Int = 1,
        limit: Int = 30,
        profileId: String? = null,
    ): JsonElement? = client.callResultElement(
        RpcObjects.HISTORY_ABSEN,
        buildJsonObject {
            put("tgl", tgl)
            put("page", page)
            put("limit", limit)
        },
        ctx,
        profileId,
    )

    suspend fun cekAbsen(
        ctx: ServerContext,
        checkType: Int,
        workCode: Any,
        isWfh: Boolean = false,
        profileId: String? = null,
    ): JsonElement? = client.callResultElement(
        RpcObjects.CEK_ABSEN,
        buildJsonObject {
            put("checktype", checkType)
            put("work_code", normalizeWorkCode(workCode))
            put("iswfh", if (isWfh) 1 else 0)
        },
        ctx,
        profileId,
    )

    suspend fun absen(
        ctx: ServerContext,
        checkType: Int,
        workCode: Any,
        isWfh: Boolean = false,
        withIjin: Boolean = false,
        keterangan: String = "",
        typeIjin: Int = 0,
        profileId: String? = null,
    ): JsonElement? = client.callResultElement(
        RpcObjects.ABSEN,
        buildJsonObject {
            put("checktype", checkType)
            put("work_code", normalizeWorkCode(workCode))
            put("iswfh", if (isWfh) 1 else 0)
            put("ijin", if (withIjin) 1 else 0)
            put("keterangan", keterangan)
            put("type_ijin", typeIjin)
        },
        ctx,
        profileId,
    )

    suspend fun listIjin(
        ctx: ServerContext,
        page: Int = 1,
        limit: Int = 50,
        profileId: String? = null,
    ): JsonElement? = client.callResultElement(
        RpcObjects.LIST_IJIN,
        buildJsonObject {
            put("page", page)
            put("limit", limit)
        },
        ctx,
        profileId,
    )

    suspend fun addIjin(
        ctx: ServerContext,
        jenisIjin: Int,
        tipeIjin: Int,
        tglIjin: String,
        tglIjinSampai: String,
        alasan: String,
        profileId: String? = null,
    ): JsonElement? = client.callResultElement(
        RpcObjects.ADD_IJIN,
        buildJsonObject {
            put("id", "")
            put("tgl_ijin", tglIjin)
            put("tgl_ijin_sampai", tglIjinSampai)
            put("alasan", alasan)
            put("jenis_ijin", jenisIjin)
            put("tipe_ijin", tipeIjin)
        },
        ctx,
        profileId,
    )

    suspend fun deleteIjin(ctx: ServerContext, id: String, profileId: String? = null): JsonElement? =
        client.callResultElement(RpcObjects.DELETE_IJIN, buildJsonObject { put("id", id) }, ctx, profileId)

    suspend fun syncData(ctx: ServerContext, profileId: String? = null): JsonElement? =
        client.callResultElement(RpcObjects.SYNC_DATA, JsonObject(emptyMap()), ctx, profileId)

    /** Generic convenience passthrough for additional RPC operations. */
    suspend fun call(
        `object`: String,
        param: JsonObject = JsonObject(emptyMap()),
        ctx: ServerContext = ServerContext(),
        profileId: String? = null,
    ): JsonElement? = client.callResultElement(`object`, param, ctx, profileId)

    suspend fun updateProfil(
        ctx: ServerContext,
        param: JsonObject,
        profileId: String? = null,
    ): JsonElement? = client.callResultElement(RpcObjects.UPDATE_PROFIL, param, ctx, profileId)

    suspend fun updateFoto(
        ctx: ServerContext,
        param: JsonObject,
        profileId: String? = null,
    ): JsonElement? = client.callResultElement(RpcObjects.UPDATE_FOTO, param, ctx, profileId)

    suspend fun uploadImportFile(
        ctx: ServerContext,
        izinId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        profileId: String? = null,
    ): JsonElement? = client.uploadImportFile(ctx, izinId, fileName, mimeType, bytes, profileId)

    fun toRows(element: JsonElement?) = client.toRows(element)
    fun resultMessage(element: JsonElement?) = client.resultMessage(element)

    private fun normalizeWorkCode(workCode: Any): Int = when (workCode) {
        is Int -> workCode
        is Long -> workCode.toInt()
        is Number -> workCode.toInt()
        else -> workCode.toString().trim().toIntOrNull() ?: 0
    }
}

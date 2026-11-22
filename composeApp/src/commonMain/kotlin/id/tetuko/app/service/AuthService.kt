package id.tetuko.app.service

import id.tetuko.core.data.rpc.GateCodes
import id.tetuko.core.data.rpc.LoginResult
import id.tetuko.core.data.rpc.RpcException
import id.tetuko.core.data.rpc.RpcObjects
import id.tetuko.core.data.rpc.ServerContext
import id.tetuko.core.domain.model.AttendanceMode
import id.tetuko.core.domain.model.ServerProfile
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class LoginOutcome(
    val ok: Boolean,
    val profileName: String = "Pengguna",
    val nip: String = "",
    val apiKey: String = "",
    val message: String? = null,
    val deviceBound: Boolean = false,
    val raw: LoginResult? = null,
)

object AuthService {
    fun supportsImeiRecovery(profile: ServerProfile): Boolean =
        profile.mode == AttendanceMode.BKD_RPC && profile.id in setOf("jatim", "madiun")

    /**
     * Authenticates against the selected server profile.
     * BKD profiles use JSON-RPC (`login`); Ponorogo profiles use the
     * `/login/do_LoginMobile` endpoint via the local proxy bridge.
     */
    suspend fun loginServer(profile: ServerProfile, username: String, password: String, imei: String = ""): LoginOutcome {
        val u = username.trim()
        if (u.isBlank()) return LoginOutcome(ok = false, message = "Username/NIP wajib diisi.")

        if (profile.mode == AttendanceMode.PONOROGO_ABSENSI) {
            return try {
                val response = AppServices.ponorogoClient.send(
                    "/login/do_LoginMobile",
                    buildJsonObject {
                        put("username", u)
                        put("password", password)
                        put("versi", "2.0.0")
                    },
                    apiBase = profile.ponorogoApiBase,
                )
                val root = response as? JsonObject
                val hasSuccess = root?.containsKey("success") == true
                val success = (root?.get("success") as? JsonPrimitive)?.contentOrNull
                    ?.toBooleanStrictOrNull()
                    ?: (root?.get("success") as? JsonPrimitive)?.contentOrNull == "1"
                val data = (root?.get("data") as? JsonObject) ?: root
                fun str(vararg keys: String): String =
                    keys.firstNotNullOfOrNull { key ->
                        (data?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
                    }.orEmpty()
                val rootMessage = listOf("error", "message", "msg", "pesan", "keterangan", "raw").firstNotNullOfOrNull { key ->
                    (root?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
                }.orEmpty()
                val code = listOf("code", "kode", "status_code", "errorCode", "error_code").firstNotNullOfOrNull { key ->
                    (root?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
                        ?: (data?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
                }.orEmpty()

                if ((hasSuccess && !success) || (!hasSuccess && (rootMessage.isNotBlank() || code.isNotBlank()))) {
                    val raw = listOf(str("message"), str("msg"), str("error"), str("pesan"), rootMessage)
                        .firstOrNull { it.isNotBlank() }
                        .orEmpty()
                    val msg = friendlyPonorogoLoginError(raw, code)
                    return LoginOutcome(
                        ok = false,
                        nip = u,
                        message = msg,
                        deviceBound = Regex("imei|perangkat|device|terikat|terkunci|5002", RegexOption.IGNORE_CASE).containsMatchIn("$raw $code $msg"),
                    )
                }

                val apiKey = str("api_key", "token").ifBlank { "ponorogo-session" }
                val deviceId = str("emai", "imei", "device_id", "deviceId", "sim_serial").ifBlank { imei }
                AppServices.clearServerSession()
                AppServices.setActiveProfile(profile)
                AppServices.ponorogoProfile = data
                AppServices.updateServerContext(apiKey = apiKey, imei = deviceId)
                LoginOutcome(
                    ok = true,
                    profileName = str("nama", "nama_pegawai").ifBlank { u },
                    nip = str("nip").ifBlank { u },
                    apiKey = apiKey,
                )
            } catch (e: Exception) {
                LoginOutcome(ok = false, message = e.message ?: "Gagal menghubungi server Ponorogo.", nip = u)
            }
        }

        return try {
            val param = buildJsonObject {
                put("email", u)
                put("password", password)
                put("latlong", "0,0")
                put("imei", imei)
            }
            val rawResp = AppServices.rpcClient.callRaw(
                `object` = RpcObjects.LOGIN,
                param = param,
                ctx = ServerContext(apiKey = "", lastLatLong = "", imei = imei),
                profileId = profile.id,
            )
            val resultEl = rawResp.result
            val login: LoginResult? = when {
                resultEl == null || resultEl is JsonNull -> null
                resultEl is JsonObject -> AppServices.rpcClient.decodeOrNull<LoginResult>(resultEl)
                else -> null
            }
            val apiKey = login?.api_key?.takeIf { it.isNotBlank() } ?: ""
            if (apiKey.isBlank()) {
                val msg = login?.message ?: rawResp.message ?: "Login gagal: sesi tidak diterima dari server."
                return LoginOutcome(ok = false, message = msg, nip = u)
            }
            AppServices.clearServerSession()
            AppServices.setActiveProfile(profile)
            AppServices.updateServerContext(apiKey = apiKey, imei = imei)
            LoginOutcome(
                ok = true,
                profileName = login?.nama?.takeIf { !it.isNullOrBlank() } ?: u,
                nip = u,
                apiKey = apiKey,
                raw = login,
            )
        } catch (e: RpcException) {
            val bound = e.code == GateCodes.DEVICE_BOUND
            val msg = when (e.code) {
                GateCodes.INVALID_NIP -> "NIP/password tidak valid atau akun tidak ditemukan."
                GateCodes.DEVICE_BOUND -> "Akun terikat ke perangkat lain (IMEI/device)."
                GateCodes.INVALID_PARAMS -> "Parameter login tidak valid."
                else -> e.message ?: "Login gagal."
            }
            LoginOutcome(ok = false, message = msg, nip = u, deviceBound = bound)
        } catch (e: Exception) {
            LoginOutcome(ok = false, message = e.message ?: "Gagal menghubungi server.", nip = u)
        }
    }

    fun logoutLocal() {
        AppServices.clearServerSession()
    }

    private fun friendlyPonorogoLoginError(raw: String, code: String): String {
        val combined = "$raw $code".trim()
        if (combined.isBlank()) return "Login Ponorogo gagal. Periksa NIP dan password."
        val numericOnly = raw.trim().matches(Regex("\\d+"))
        if (numericOnly || raw.trim() in setOf("5002", "5001", "5000")) {
            return when (raw.trim()) {
                "5002" -> "Login Ponorogo ditolak server (kode 5002). Periksa NIP/password, versi aplikasi, atau ikatan perangkat/IMEI."
                else -> "Login Ponorogo ditolak server (kode ${raw.trim()}). Periksa NIP dan password."
            }
        }
        if (code.trim() == "5002" && raw.isBlank()) {
            return "Login Ponorogo ditolak server (kode 5002). Periksa NIP/password, versi aplikasi, atau ikatan perangkat/IMEI."
        }
        if (Regex("\\b5002\\b").containsMatchIn(combined)) {
            return "$raw (kode 5002). Periksa NIP/password, versi aplikasi, atau ikatan perangkat/IMEI."
        }
        return raw
    }
}

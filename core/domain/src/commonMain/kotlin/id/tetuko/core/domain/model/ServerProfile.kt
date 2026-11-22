package id.tetuko.core.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class AttendanceMode {
    BKD_RPC,
    PONOROGO_ABSENSI;

    companion object {
        fun fromString(value: String?): AttendanceMode = when (value?.lowercase()) {
            "ponorogo-absensi", "ponorogo_absensi", "ponorogo" -> PONOROGO_ABSENSI
            else -> BKD_RPC
        }
    }
}

@Serializable
data class ServerProfile(
    val id: String,
    val nama: String,
    val label: String,
    val gatewayUrl: String = "",
    val epBaseUrl: String = "",
    val mode: AttendanceMode = AttendanceMode.BKD_RPC,
    val requiresPhotoPreset: Boolean = false,
    val requiresWorkCode: Boolean = false,
    val ponorogoApiBase: String? = null,
    val ponorogoImageBase: String? = null,
) {
    val isPonorogo: Boolean get() = mode == AttendanceMode.PONOROGO_ABSENSI
    val isBkd: Boolean get() = mode == AttendanceMode.BKD_RPC
    val displayTitle: String get() = label.ifBlank { nama.ifBlank { id } }
}

object DefaultProfiles {
    private val builtInProfiles = listOf(
        ServerProfile(
            id = "jatim",
            nama = "Provinsi Jawa Timur",
            label = "Presensi Jatimprov",
            gatewayUrl = "",
            epBaseUrl = "",
            mode = AttendanceMode.BKD_RPC,
            requiresWorkCode = true,
        ),
        ServerProfile(
            id = "ponorogo",
            nama = "Kabupaten Ponorogo",
            label = "Presensi Ponorogo",
            gatewayUrl = "",
            epBaseUrl = "",
            mode = AttendanceMode.PONOROGO_ABSENSI,
            requiresPhotoPreset = true,
            ponorogoApiBase = null,
            ponorogoImageBase = null,
        ),
        ServerProfile(
            id = "madiun",
            nama = "Kabupaten Madiun",
            label = "Presensi Madiunkab",
            gatewayUrl = "",
            epBaseUrl = "",
            mode = AttendanceMode.BKD_RPC,
            requiresWorkCode = true,
        ),
    )
    @Volatile
    var all: List<ServerProfile> = builtInProfiles
        private set

    val default: ServerProfile get() = all.firstOrNull() ?: builtInProfiles.first()
    fun byId(id: String?): ServerProfile = all.firstOrNull { it.id == id } ?: default

    fun applyEnvironmentOverrides(values: Map<String, String>) {
        all = profilesWithOverrides(values)
    }

    fun profilesWithOverrides(values: Map<String, String>): List<ServerProfile> =
        builtInProfiles.map { profile ->
            val prefix = profile.id.uppercase()
            fun value(key: String, fallback: String): String =
                values["${prefix}_$key"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: fallback
            fun flag(key: String, fallback: Boolean): Boolean = when (value(key, fallback.toString()).lowercase()) {
                "1", "true", "yes", "on" -> true
                "0", "false", "no", "off" -> false
                else -> fallback
            }

            profile.copy(
                nama = value("NAME", profile.nama),
                label = value("LABEL", profile.label),
                gatewayUrl = value("GATEWAY_URL", profile.gatewayUrl),
                epBaseUrl = value("EP_BASE_URL", profile.epBaseUrl),
                mode = AttendanceMode.fromString(value("MODE", profile.mode.name.lowercase().replace('_', '-'))),
                requiresPhotoPreset = flag("REQUIRES_PHOTO", profile.requiresPhotoPreset),
                requiresWorkCode = flag("REQUIRES_WORKCODE", profile.requiresWorkCode),
                ponorogoApiBase = value("API_BASE", profile.ponorogoApiBase.orEmpty()).ifBlank { null },
                ponorogoImageBase = value("IMAGE_BASE", profile.ponorogoImageBase.orEmpty()).ifBlank { null },
            )
        }
}

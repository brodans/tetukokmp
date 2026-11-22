package id.tetuko.core.domain.model

/**
 * Shared, platform-agnostic interpretation of automation submit responses.
 * Platforms perform HTTP/transport and pass normalized response facts here so
 * BKD/Ponorogo decision semantics stay consistent across Desktop and Android.
 */
object AutomationSubmitProtocol {

    fun bkdCekAbsen(
        httpCode: Int,
        hasJson: Boolean,
        absenFlag: Boolean?,
        message: String?,
    ): AutomationManualResult? {
        if (httpCode == 0) return AutomationManualResult("RETRYABLE", "BKD: cekabsen gagal karena jaringan/proxy", "NETWORK_ERROR")
        if (httpCode !in 200..299 || !hasJson) {
            return AutomationManualResult("RETRYABLE", "BKD: cekabsen respons tidak dapat diproses (HTTP $httpCode)", "BAD_RESPONSE", httpStatus = httpCode)
        }
        if (absenFlag == true) {
            val msg = message?.trim().orEmpty().ifBlank { "server menyatakan absen ditolak/sudah tercatat" }
            return AutomationManualResult("SKIPPED", "BKD: $msg", "SERVER_REJECTED_PRECHECK", httpStatus = httpCode)
        }
        return null // proceed to absen
    }

    fun bkdAbsen(
        httpCode: Int,
        hasJson: Boolean,
        successFlag: Boolean?,
        message: String?,
    ): AutomationManualResult {
        if (httpCode == 0) return AutomationManualResult("AMBIGUOUS", "BKD: request dikirim tetapi respons hilang; verifikasi riwayat sebelum retry", "NO_RESPONSE", errorClass = "Network")
        if (httpCode in 500..599) return AutomationManualResult("AMBIGUOUS", "BKD: server respons HTTP $httpCode; verifikasi riwayat sebelum retry", "SERVER_5XX", httpStatus = httpCode)
        if (httpCode !in 200..299 || !hasJson) return AutomationManualResult("FAILED", "BKD ditolak atau respons tidak valid (HTTP $httpCode)", "BAD_RESPONSE", httpStatus = httpCode)
        val msg = message?.trim().orEmpty().ifBlank { "Respons server diterima" }
        return if (successFlag == true) {
            AutomationManualResult("SUCCESS", "BKD berhasil: $msg", "EXECUTION_ACCEPTED", httpStatus = httpCode)
        } else {
            AutomationManualResult("FAILED", "BKD ditolak: $msg", "EXECUTION_REJECTED", httpStatus = httpCode)
        }
    }

    fun ponorogoSubmit(
        httpCode: Int,
        hasJson: Boolean,
        successFlag: Boolean?,
        message: String?,
    ): AutomationManualResult {
        if (httpCode == 0) return AutomationManualResult("AMBIGUOUS", "Ponorogo: request dikirim tetapi respons hilang; verifikasi riwayat sebelum retry", "NO_RESPONSE", errorClass = "Network")
        if (httpCode in 500..599) return AutomationManualResult("AMBIGUOUS", "Ponorogo: server respons HTTP $httpCode; verifikasi riwayat sebelum retry", "SERVER_5XX", httpStatus = httpCode)
        if (httpCode !in 200..299 || !hasJson) return AutomationManualResult("FAILED", "Ponorogo ditolak atau respons tidak valid (HTTP $httpCode)", "BAD_RESPONSE", httpStatus = httpCode)
        val msg = message?.trim().orEmpty().ifBlank { "Respons server diterima" }
        return if (successFlag == true) {
            AutomationManualResult("SUCCESS", "Ponorogo berhasil: $msg", "EXECUTION_ACCEPTED", httpStatus = httpCode)
        } else {
            AutomationManualResult("FAILED", "Ponorogo ditolak: $msg", "EXECUTION_REJECTED", httpStatus = httpCode)
        }
    }
}

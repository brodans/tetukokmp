package id.tetuko.core.data.ponorogo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PonorogoReportContractTest {
    private val client = PonorogoClient { "http://127.0.0.1:14200" }

    @Test
    fun `employee report targets preserve id and dates`() {
        assertEquals(
            "/api/report/absensi/sby/per-pegawai.pdf?idp=pegawai-1&t1=2026-11-01&t2=2026-11-22",
            client.buildReportTarget(mapOf("reportType" to "per-pegawai", "idp" to "pegawai-1", "t1" to "2026-11-01", "t2" to "2026-11-22", "format" to "pdf")),
        )
    }

    @Test
    fun `activity employee report uses Ponorogo endpoint`() {
        assertEquals(
            "/api/report/absensi/prg/per-pegawai-aktivitas.xls?idp=pegawai-1&t1=2026-11-01&t2=2026-11-22",
            client.buildReportTarget(mapOf("reportType" to "per-pegawai-aktivitas", "idp" to "pegawai-1", "t1" to "2026-11-01", "t2" to "2026-11-22", "format" to "xls")),
        )
    }

    @Test
    fun `institution reports preserve unit and status filters`() {
        assertEquals(
            "/api/report/absensi/sby/rekap-instansi.pdf?idu=5.19.00&t1=2026-11-01&t2=2026-11-22&status=pns",
            client.buildReportTarget(mapOf("reportType" to "rekap-instansi", "idu" to "5.19.00", "t1" to "2026-11-01", "t2" to "2026-11-22", "status" to "pns", "format" to "pdf")),
        )
    }

    @Test
    fun `Ponorogo unit activity and TPP report routes remain distinct`() {
        assertEquals(
            "/api/report/absensi/prg/aktivitas-per-instansi2026-new.xls?idu=5.19.00&t1=2026-11-01&t2=2026-11-22&status=pns",
            client.buildReportTarget(mapOf("reportType" to "aktivitas-per-instansi", "idu" to "5.19.00", "t1" to "2026-11-01", "t2" to "2026-11-22", "status" to "pns", "format" to "xls")),
        )
        assertEquals(
            "/api/report/absensi/prg/rekap-tpp-aktivitas2026-new.pdf?idu=5.19.00&t1=2026-11-01&t2=2026-11-22&status=all",
            client.buildReportTarget(mapOf("reportType" to "rekap-tpp-aktivitas", "idu" to "5.19.00", "t1" to "2026-11-01", "t2" to "2026-11-22", "status" to "all", "format" to "pdf")),
        )
    }

    @Test
    fun `unknown report type is rejected`() {
        assertFailsWith<PonorogoException> {
            client.buildReportTarget(mapOf("reportType" to "unknown"))
        }
    }
}

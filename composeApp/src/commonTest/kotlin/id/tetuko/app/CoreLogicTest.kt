package id.tetuko.app

import id.tetuko.app.service.SavedPoint
import id.tetuko.app.service.SavedPointsCodec
import id.tetuko.app.service.AppServices
import id.tetuko.app.service.AuthService
import id.tetuko.app.service.PickedFile
import id.tetuko.core.domain.model.AutomationLogCodec
import id.tetuko.core.domain.model.AutomationLogEntry
import id.tetuko.core.domain.model.PresetCodec
import id.tetuko.core.domain.model.AutomationPreset
import id.tetuko.core.domain.model.AutomationExecutionState
import id.tetuko.app.data.RefTable
import id.tetuko.app.data.searchPegawai
import id.tetuko.app.data.findImmediateSupervisor
import id.tetuko.app.data.findLocationFor
import id.tetuko.app.data.PegawaiSearchResult
import id.tetuko.app.util.ExcelSheet
import id.tetuko.app.util.PdfTable
import id.tetuko.app.util.SpreadsheetXml
import id.tetuko.app.ui.screens.pages.expandDateRange
import id.tetuko.app.ui.screens.pages.buildIzinCsv
import id.tetuko.core.data.web.WebPresensiClient
import id.tetuko.core.data.ponorogo.PonorogoClient
import id.tetuko.core.domain.model.AppPermissions
import id.tetuko.core.domain.model.AppRole
import id.tetuko.core.domain.model.ProfileAccess
import id.tetuko.core.domain.model.DefaultProfiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertContains
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonPrimitive

class PegawaiSearchTest {
    @Test
    fun `Jatim profile enables captcha IMEI recovery`() {
        assertTrue(AuthService.supportsImeiRecovery(DefaultProfiles.byId("jatim")))
    }

    @Test
    fun `Madiun profile enables captcha IMEI recovery independently`() {
        assertTrue(AuthService.supportsImeiRecovery(DefaultProfiles.byId("madiun")))
    }

    @Test
    fun `Ponorogo profile does not use BKD captcha IMEI recovery`() {
        assertFalse(AuthService.supportsImeiRecovery(DefaultProfiles.byId("ponorogo")))
    }

    @Test
    fun `search matches employee name and nip without exposing the full table`() {
        val table = RefTable(
            keys = listOf("id", "nip", "nama", "instansi"),
            rows = buildJsonArray {
                add(buildJsonArray { add(JsonPrimitive("1")); add(JsonPrimitive("111")); add(JsonPrimitive("Sari")); add(JsonPrimitive("Dinas A")) })
                add(buildJsonArray { add(JsonPrimitive("2")); add(JsonPrimitive("222")); add(JsonPrimitive("Budi")); add(JsonPrimitive("Dinas B")) })
            },
        )

        assertEquals("1", table.searchPegawai("sari").single().id)
        assertEquals("Budi", table.searchPegawai("222").single().nama)
        assertTrue(table.searchPegawai("").isEmpty())
    }

    @Test
    fun `review target prefers the highest class structural leader in the same unit`() {
        val keys = listOf("id", "nip", "nama", "kode_unor", "unor", "tipe_jabatan", "kelas_jabatan", "instansi", "nama_instansi")
        val table = RefTable(
            keys = keys,
            rows = buildJsonArray {
                add(buildJsonArray { listOf("u1", "111", "Pegawai", "1.2.3", "", "PELAKSANA", "0", "Dinas A", "Dinas A").forEach { add(JsonPrimitive(it)) } })
                add(buildJsonArray { listOf("m1", "222", "Kabid A", "1.2.3", "", "STRUKTURAL", "4", "Dinas A", "Dinas A").forEach { add(JsonPrimitive(it)) } })
                add(buildJsonArray { listOf("m2", "333", "Kabid B", "1.2.3", "", "STRUKTURAL", "6", "Dinas A", "Dinas A").forEach { add(JsonPrimitive(it)) } })
            },
        )

        assertEquals("m2", table.findImmediateSupervisor("u1", "111")?.id)
    }

    @Test
    fun `attendance target location prefers exact employee location name`() {
        val table = RefTable(
            keys = listOf("id", "kode", "nama", "alamat", "latitude", "longitude", "radius"),
            rows = buildJsonArray {
                add(buildJsonArray { listOf("1", "10", "Kantor A", "Alamat A", "-7.1", "111.1", "50").forEach { add(JsonPrimitive(it)) } })
                add(buildJsonArray { listOf("2", "20", "Kantor B", "Alamat B", "-7.2", "111.2", "75").forEach { add(JsonPrimitive(it)) } })
            },
        )
        val pegawai = PegawaiSearchResult("p1", "123", "Nama", "", namaLokasi = "Kantor B")

        assertEquals("2", table.findLocationFor(pegawai)?.id)
        assertEquals(-7.2, table.findLocationFor(pegawai)?.latitude)
    }
}

class ApiTrafficRedactionTest {
    @Test
    fun `inspector redacts secrets from nested json and form bodies`() {
        AppServices.clearApiTraffic()
        AppServices.recordApiTraffic(
            method = "POST",
            path = "/api/proxy",
            status = 200,
            request = """{"payload":{"password":"secret-pass","api_key":"secret-token","nested":{"passwordHash":"secret-hash"}}}""",
            response = "password=secret-pass&token=secret-token",
        )

        val entry = AppServices.apiTrafficSnapshot().first()
        assertFalse(entry.request.contains("secret-pass"))
        assertFalse(entry.request.contains("secret-token"))
        assertFalse(entry.request.contains("secret-hash"))
        assertContains(entry.request, "[REDACTED]")
        assertFalse(entry.response.contains("secret-pass"))
        assertFalse(entry.response.contains("secret-token"))
    }
}

class PonorogoImageProxyUrlTest {
    @Test
    fun `image path is encoded as a proxy query parameter`() {
        val client = PonorogoClient { "http://127.0.0.1:14200/" }
        val url = client.imageProxyUrl("/uploads/foto presensi.jpg")

        assertTrue(url.startsWith("http://127.0.0.1:14200/api/proxy-image?path="))
        assertTrue(url.contains("%20"))
        assertFalse(url.contains(" "))
    }

    @Test
    fun `absolute image url is downloaded directly`() {
        val client = PonorogoClient { "http://127.0.0.1:14200/" }

        assertEquals("https://cdn.example.test/foto.jpg", client.imageDownloadUrl(" https://cdn.example.test/foto.jpg "))
    }
}

class PlatformFilePickerContractTest {
    @Test
    fun `file picker supports synchronous desktop and asynchronous platform results`() {
        val previousPicker = AppServices.filePicker
        val previousAsyncPicker = AppServices.filePickerAsync
        try {
            val desktopFile = PickedFile("desktop.jpg", "image/jpeg", byteArrayOf(1))
            AppServices.filePicker = { _, _ -> desktopFile }
            AppServices.filePickerAsync = null
            var selected: PickedFile? = null
            assertTrue(AppServices.requestFilePicker("Pilih", listOf("jpg")) { selected = it })
            assertEquals("desktop.jpg", selected?.name)

            val androidFile = PickedFile("android.jpg", "image/jpeg", byteArrayOf(2))
            AppServices.filePicker = null
            AppServices.filePickerAsync = { _, _, onPicked -> onPicked(androidFile) }
            selected = null
            assertTrue(AppServices.requestFilePicker("Pilih", listOf("jpg")) { selected = it })
            assertEquals("android.jpg", selected?.name)

            AppServices.filePickerAsync = null
            assertFalse(AppServices.requestFilePicker("Pilih", listOf("jpg")) { selected = it })
        } finally {
            AppServices.filePicker = previousPicker
            AppServices.filePickerAsync = previousAsyncPicker
        }
    }
}

class AppPermissionParityTest {
    @Test
    fun `explicit per profile empty allowlist denies every restricted menu`() {
        val permissions = AppPermissions(
            role = AppRole.USER,
            profiles = ProfileAccess(jatim = true, madiun = false, ponorogo = true),
            allowedMenuIdsByProfile = mapOf("jatim" to emptySet(), "ponorogo" to setOf("tabBeranda", "tabAbsen")),
        )

        assertTrue(permissions.canAccessMenu("jatim", "tabBeranda"))
        assertTrue(permissions.canAccessMenu("ponorogo", "tabBeranda"))
        assertTrue(permissions.canAccessMenu("ponorogo", "tabAbsen"))
        assertFalse(permissions.canAccessMenu("ponorogo", "tabReport"))
        assertFalse(permissions.canAccessMenu("madiun", "tabBeranda"))
    }

    @Test
    fun `review target search requires the dedicated review permission`() {
        val productivitySearchOnly = AppPermissions.defaultUser().copy(
            allowSearchProduktivitas = true,
            allowSearchPegawai = true,
        )

        assertFalse(productivitySearchOnly.canUseFeature("search_review"))
        assertTrue(productivitySearchOnly.copy(allowSearchReview = true).canUseFeature("search_review"))
    }

    @Test
    fun `default user menu access matches Tauri Ponorogo defaults`() {
        val permissions = AppPermissions.defaultUser()

        listOf("tabBeranda", "tabAbsen", "tabLog", "tabInputAktivitas", "tabAktivitas", "tabReview", "tabIzin")
            .forEach { assertTrue(permissions.canAccessMenu("ponorogo", it), "$it should be accessible") }
        listOf("tabLogPresensiInstansi", "tabReport", "tabAutomasi", "tabDatabase", "tabUnor")
            .forEach { assertFalse(permissions.canAccessMenu("ponorogo", it), "$it should be restricted") }
        assertFalse(permissions.canAccessProfile("jatim"))
        assertFalse(permissions.canAccessProfile("madiun"))
    }

    @Test
    fun `enabled BKD profile defaults keep automation and web menus disabled like Tauri`() {
        val permissions = AppPermissions.defaultUser().copy(
            profiles = AppPermissions.defaultUser().profiles.copy(jatim = true, madiun = true),
        )

        listOf("jatim", "madiun").forEach { profileId ->
            listOf("tabBeranda", "tabPresensi", "tabPerizinan", "tabLaporan", "tabLokasiAbsen")
                .forEach { assertTrue(permissions.canAccessMenu(profileId, it), "$profileId/$it should be accessible") }
            listOf("tabPresetOtomatisasi", "tabWeb")
                .forEach { assertFalse(permissions.canAccessMenu(profileId, it), "$profileId/$it should be restricted") }
        }
    }
}

class WebPresensiParsingTest {
    @Test
    fun `login page detection requires both form fields`() {
        assertTrue(WebPresensiClient.isLoginPage("""<input name="m_user[email]"><input name="m_user[CAPTCHA]">"""))
        assertFalse(WebPresensiClient.isLoginPage("""<input name="m_user[email]">"""))
    }

    @Test
    fun `page counter reads the highest page link`() {
        val html = """<a href="?page=2">2</a><a href="?page=17">17</a><a href="?page=3">3</a>"""
        assertEquals(17, WebPresensiClient.countPages(html))
        assertEquals(1, WebPresensiClient.countPages("no links here"))
    }

    @Test
    fun `entities and tags are decoded without leaking markup`() {
        assertEquals("A & B < C", WebPresensiClient.decodeEntities("A &amp; B &lt; C"))
        assertEquals(
            "Baris satu\nBaris dua",
            WebPresensiClient.stripTags("Baris <b>satu</b><br/>Baris&nbsp;dua"),
        )
    }

    @Test
    fun `attendance rows keep coordinates, approval and ids`() {
        val row = """
            <tr>
              <td>1</td>
              <td class="sf_admin_list_td_pegawai_id">Budi<br>1987654321<br>PNS</td>
              <td class="sf_admin_list_td_latlong"><a href="?latlong=42">-7.865,111.466</a><br>Jl. Diponegoro</td>
              <td class="sf_admin_list_td_departemen">Dinas Pendidikan<br>Jl. Rumah</td>
              <td class="sf_admin_list_td_jarak">12 m</td>
              <td class="sf_admin_list_td_checktype">Masuk</td>
              <td class="sf_admin_list_td_imei">123456789</td>
              <td class="sf_admin_list_td_approval"><i class="fa-check"></i><br>2026-11-18 07:00</td>
              <td class="sf_admin_list_td_created_at">2026-11-18 06:59</td>
              <td class="sf_admin_list_td_iswfh">Tidak</td>
              <td class="sf_admin_list_td_work_code">WFH</td>
              <td><input type="checkbox" name="ids[]" value="99"></td>
            </tr>
        """.trimIndent()
        val rows = WebPresensiClient.parseAttendance(row)
        assertEquals(1, rows.size)
        val item = rows.first()
        assertEquals("99", item.id)
        assertEquals("Budi", item.name)
        assertEquals("1987654321", item.nip)
        assertEquals(-7.865, item.latitude)
        assertEquals(111.466, item.longitude)
        assertEquals("42", item.idMap)
        assertEquals("Jl. Diponegoro", item.attendanceAddress)
        assertEquals("Dinas Pendidikan", item.unit)
        assertEquals(true, item.approved)
        assertEquals("Masuk", item.checkType)
    }

    @Test
    fun `leave rows expose type, reason and attachment`() {
        val row = """
            <tr>
              <td class="sf_admin_list_td_pegawai_id">Sari<br>1999000011</td>
              <td class="sf_admin_list_td_departemen">Dinas Kesehatan</td>
              <td class="sf_admin_list_td_jenis_ijin_id">Cuti Tahunan</td>
              <td class="sf_admin_list_td_pegawai_id_master_tipe_ijin">Cuti</td>
              <td class="sf_admin_list_td_tgl_ijin">2026-11-01</td>
              <td class="sf_admin_list_td_keterangan">Keperluan keluarga</td>
              <td class="sf_admin_list_td_approval_note">Disetujui atasan</td>
              <td class="sf_admin_list_td_attachment"><a href="/uploads/surat.pdf">berkas</a></td>
              <td class="sf_admin_list_td_approval"><i class="fa-times"></i></td>
              <td class="sf_admin_list_td_created_at">2026-10-30</td>
            </tr>
        """.trimIndent()
        val rows = WebPresensiClient.parseLeave(row)
        assertEquals(1, rows.size)
        val item = rows.first()
        assertEquals("Cuti Tahunan", item.type)
        assertEquals("Keperluan keluarga", item.reason)
        assertEquals("/uploads/surat.pdf", item.filePath)
        assertEquals(false, item.approved)
    }
}

class AutomationReliabilitySchemaTest {
    @Test
    fun `execution state distinguishes pending retry ambiguous and terminal outcomes`() {
        assertEquals(AutomationExecutionState.PENDING, AutomationExecutionState.valueOf("PENDING"))
        assertEquals(AutomationExecutionState.AMBIGUOUS, AutomationExecutionState.valueOf("AMBIGUOUS"))
        assertEquals(AutomationExecutionState.RETRYABLE, AutomationExecutionState.valueOf("RETRYABLE"))
        assertEquals(AutomationExecutionState.SUCCESS, AutomationExecutionState.valueOf("SUCCESS"))
    }

    @Test
    fun `legacy preset json remains readable and gains schema version one`() {
        val preset = PresetCodec.decode("""[{"id":"pst-1","nama":"Masuk","tipe":"datang","jamMulai":"07:00","jamSelesai":"09:00","aktif":true}]""").single()

        assertEquals("pst-1", preset.id)
        assertEquals(1, preset.schemaVersion)
        assertTrue(preset.aktif)
    }

    @Test
    fun `structured log round trip preserves correlation and reason metadata`() {
        val log = AutomationLogEntry(
            id = "run-1",
            presetId = "pst-1",
            status = "SUCCESS",
            source = "scheduler",
            dateWib = "2026-11-21",
            correlationId = "run_abc",
            reasonCode = "EXECUTION_ACCEPTED",
            latLong = "-7.8,111.4",
            workcodeId = "101",
        )
        val decoded = AutomationLogCodec.decode(AutomationLogCodec.encode(listOf(log))).single()

        assertEquals("run_abc", decoded.correlationId)
        assertEquals("EXECUTION_ACCEPTED", decoded.reasonCode)
        assertEquals("-7.8,111.4", decoded.latLong)
        assertEquals("2026-11-21", decoded.dateWib)
    }
}

class SavedPointsCodecTest {
    @Test
    fun `round trip preserves all fields`() {
        val points = listOf(
            SavedPoint("a", "Kantor", -7.86, 111.46, 120.0, active = true),
            SavedPoint("b", "Rumah", -7.87, 111.47, 80.0),
        )
        val decoded = SavedPointsCodec.decode(SavedPointsCodec.encode(points))
        assertEquals(points, decoded)
    }

    @Test
    fun `blank or corrupt input degrades to empty list`() {
        assertTrue(SavedPointsCodec.decode(null).isEmpty())
        assertTrue(SavedPointsCodec.decode("").isEmpty())
        val corrupt = runCatching { SavedPointsCodec.decode("{not json") }.getOrDefault(emptyList())
        assertTrue(corrupt.isEmpty())
    }
}

class ExportWriterTest {
    @Test
    fun `spreadsheet keeps text inert and drops invalid xml characters`() {
        val xml = SpreadsheetXml.build(
            listOf(
                ExcelSheet(
                    name = "Laporan",
                    header = listOf("Nama"),
                    rows = listOf(listOf("=HYPERLINK(\"http://evil\")"), listOf("bad\u0000char")),
                ),
            ),
        )
        assertTrue(xml.contains("=HYPERLINK"))
        assertFalse(xml.contains("\u0000"))
        assertTrue(xml.startsWith("<?xml"))
    }

    @Test
    fun `permission history CSV escapes fields and neutralizes formulas`() {
        val csv = buildIzinCsv(
            listOf(
                kotlinx.serialization.json.JsonObject(
                    mapOf(
                        "id" to JsonPrimitive("3"),
                        "nama" to JsonPrimitive("Sari, Dewi"),
                        "alasan" to JsonPrimitive("=HYPERLINK(\"x\")"),
                        "berkas" to JsonPrimitive("/uploads/surat izin.pdf"),
                    ),
                ),
            ),
        )

        assertContains(csv, "ID,Nama,NIP,Jenis,Tipe,Mulai,Selesai,Alasan,Berkas,Diajukan,Status,Catatan")
        assertContains(csv, "\"Sari, Dewi\"")
        assertContains(csv, "'=HYPERLINK")
        assertContains(csv, "surat izin.pdf")
    }

    @Test
    fun `pdf has a catalog, xref and escaped literal strings`() {
        val pdf = PdfTable.build(
            title = "Laporan (uji)",
            subtitle = "2026-11-01 s/d 2026-11-18",
            headers = listOf("Nama", "Waktu"),
            widths = listOf(60, 40),
            rows = listOf(listOf("Budi (A)", "07:00")),
        )
        assertTrue(pdf.startsWith("%PDF-1.4"))
        assertTrue(pdf.contains("/Type /Catalog"))
        assertTrue(pdf.contains("xref"))
        assertTrue(pdf.trimEnd().endsWith("%%EOF"))
        assertTrue(pdf.contains("Laporan \\(uji\\)"))
        assertNotNull(Regex("startxref\\n\\d+").find(pdf))
    }

    @Test
    fun `pdf preserves Latin accents and truncates overlong cells across pages`() {
        val pdf = PdfTable.build(
            title = "Laporan Kehadiran",
            subtitle = "Periode 2026",
            headers = listOf("Nama", "Kode"),
            widths = listOf(90, 10),
            rows = List(35) { listOf("Jos\u00e9 Santoso", "KODE-SANGAT-PANJANG-UNTUK-DIUJI") },
        )

        assertTrue(pdf.contains("Jos\\351"))
        assertTrue(pdf.contains("..."))
        assertTrue(pdf.contains("/Count 2"))
        assertTrue(pdf.contains("/BaseFont /Helvetica-Bold"))
    }
}

class ReportDateRangeTest {
    @Test
    fun `report range matches Tauri 62 day limit`() {
        val dates = expandDateRange("2026-09-01", "2026-11-18")

        assertEquals(62, dates.size)
        assertEquals("2026-11-18", dates.first())
        assertEquals("2026-09-18", dates.last())
    }
}

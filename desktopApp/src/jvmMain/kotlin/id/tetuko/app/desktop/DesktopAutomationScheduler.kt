package id.tetuko.app.desktop

import id.tetuko.app.service.AppServices
import id.tetuko.core.domain.model.AutomationLogCodec
import id.tetuko.core.domain.model.AutomationLogEntry
import id.tetuko.core.domain.model.AutomationManualResult
import id.tetuko.core.domain.model.AutomationPreset
import id.tetuko.core.domain.model.PresetCodec
import id.tetuko.app.service.SavedPointsCodec
import id.tetuko.core.data.rpc.RpcObjects
import id.tetuko.core.domain.model.AttendanceMode
import id.tetuko.core.domain.model.AutomationPlanning
import id.tetuko.core.domain.model.AutomationRunCoordinator
import id.tetuko.core.domain.model.AutomationRunInput
import id.tetuko.core.domain.model.AutomationSubmitProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Desktop automation scheduler/executor (incremental P3).
 *
 * - Evaluates enabled presets (per-profile JSON stores) on a periodic tick.
 * - Picks a deterministic random fire-minute within the preset window per day
 *   (persisted best-effort) and attempts execution once per preset/day.
 * - Feasible execution path: BKD RPC presensi (cekabsen -> absen) when an active
 *   in-memory session/profile matches, required workcode/coordinates are available.
 * - Ponorogo/other cases are skipped with explicit log reasons when requirements
 *   are not satisfiable (e.g. stock-photo/queue engine parity not implemented).
 * - Best-effort file IO/logging; no claims of full parity or guaranteed delivery.
 */
object DesktopAutomationScheduler {
    private val zone: ZoneId = ZoneId.of("Asia/Jakarta")
    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var pausedUntilEpochMillis: Long = 0L

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun start() {
        if (running) return
        running = true
        job = scope.launch {
            while (isActive && running) {
                runCatching { tick() }
                delay(60_000L)
            }
        }
    }

    fun pauseFor(duration: java.time.Duration) {
        pausedUntilEpochMillis = System.currentTimeMillis() + duration.toMillis().coerceAtLeast(0L)
    }

    fun resume() {
        pausedUntilEpochMillis = 0L
    }

    fun isPaused(): Boolean = System.currentTimeMillis() < pausedUntilEpochMillis

    fun executeNow(profileId: String, preset: AutomationPreset): AutomationManualResult {
        val hhmm = timeFmt.format(ZonedDateTime.now(zone))
        val dateKey = dateFmt.format(ZonedDateTime.now(zone))
        val result = tryExecute(profileId, preset, hhmm, dateKey)
        return AutomationManualResult(result.status, result.message)
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
    }

    @Serializable
    private data class DailyPlan(
        val date: String = "",
        val fireMinute: Int = -1,
        val attempted: Boolean = false,
        val resultStatus: String? = null,
        val lastAttemptMinute: Int = -1,
    )

    @Serializable
    private data class DailyState(
        val plans: Map<String, DailyPlan> = emptyMap(), // key = profileId|presetId
    )

    private fun dataDir(): Path = Path.of(System.getProperty("user.home"), ".tetuko", "data")
    private fun statePath(dir: Path): Path = dir.resolve("automation-scheduler-state.json")

    private fun tick() {
        if (!AppServices.isAutomationSchedulerEnabled()) return
        if (isPaused()) return
        val dir = dataDir()
        if (!Files.exists(dir)) return
        val now = ZonedDateTime.now(zone)
        val hhmm = timeFmt.format(now)
        val nowMin = AutomationPlanning.parseMinutes(hhmm) ?: return
        val day = now.dayOfWeek.toPresetDay() // 0=Sun..6=Sat
        val dateKey = dateFmt.format(now)

        val state = readDailyState(statePath(dir)).let { if (it.plans.isNotEmpty() && stateDate(it) != dateKey) DailyState() else it }
        val plans = state.plans.toMutableMap()
        var stateChanged = false

        Files.list(dir).use { stream ->
            stream.iterator().forEachRemaining { path ->
                val name = path.fileName.toString()
                if (!name.startsWith("presets-") || !name.endsWith(".json")) return@forEachRemaining
                val profileId = name.removePrefix("presets-").removeSuffix(".json")
                val presetsRaw = runCatching {
                    if (Files.size(path) <= 2_000_000) Files.readString(path) else null
                }.getOrNull() ?: return@forEachRemaining
                val presets = runCatching { PresetCodec.decode(presetsRaw) }.getOrDefault(emptyList())
                if (presets.isEmpty()) return@forEachRemaining

                val logPath = dir.resolve("automation-log-$profileId.json")
                val logs = readLogs(logPath).toMutableList()
                var logsChanged = false

                presets.filter { it.aktif }.forEach { preset ->
                    if (day !in preset.hariAktif) return@forEach
                    val startMin = AutomationPlanning.parseMinutes(preset.jamMulai) ?: return@forEach
                    val endMin = AutomationPlanning.parseMinutes(preset.jamSelesai) ?: return@forEach
                    if (!AutomationPlanning.inWindow(nowMin, startMin, endMin)) return@forEach

                    val key = "$profileId|${preset.id}"
                    val existingPlan = plans[key]?.takeIf { it.date == dateKey }
                    val fireMin = existingPlan?.fireMinute?.takeIf { it >= 0 } ?: pickFireMinute(startMin, endMin, key, dateKey)
                    var plan = existingPlan ?: DailyPlan(date = dateKey, fireMinute = fireMin)
                    if (existingPlan == null || existingPlan.fireMinute != fireMin || existingPlan.date != dateKey) {
                        plans[key] = plan
                        stateChanged = true
                    }

                    // Wait until planned fire minute (or execute if we're already past within window and not attempted)
                    if (nowMin < fireMin) return@forEach
                    if (plan.attempted) return@forEach
                    val existingRetry = plans[key]?.takeIf { it.resultStatus == "RETRYABLE" }
                    val nextRetryAtMs = if (existingRetry != null && existingRetry.lastAttemptMinute >= 0 && nowMin - existingRetry.lastAttemptMinute < 5) Long.MAX_VALUE / 4 else 0L
                    val runPlan = AutomationRunCoordinator.plan(
                        AutomationRunInput(
                            preset = preset,
                            profileId = profileId,
                            hasActiveSession = AppServices.hasActiveSession,
                            activeProfileId = AppServices.activeProfile?.id,
                            requiresWorkCode = AppServices.activeProfile?.requiresWorkCode == true,
                            requiresPhoto = AppServices.activeProfile?.requiresPhotoPreset == true,
                            availableStockPhotoIds = runCatching {
                                id.tetuko.core.domain.model.StockPhotoCodec.decode(AppServices.stockPhotoReader?.invoke(profileId)).map { it.id }.toSet()
                            }.getOrDefault(emptySet()),
                            nowMinute = nowMin,
                            dateWib = dateKey,
                            nowMs = System.currentTimeMillis(),
                            alreadyTerminalToday = alreadyLoggedToday(logs, preset.id, dateKey),
                            nextRetryAtMs = nextRetryAtMs,
                        )
                    )
                    if (!runPlan.decision.shouldRun) {
                        val status = runPlan.decision.status
                        if (status in setOf("SKIPPED", "FAILED", "SUCCESS")) {
                            plans[key] = plan.copy(attempted = true, resultStatus = status, lastAttemptMinute = nowMin)
                            stateChanged = true
                        }
                        if (status != "RETRYABLE") {
                            val nowMs = System.currentTimeMillis()
                            logs.add(0, AutomationRunCoordinator.logEntry(
                                preset = preset, status = status, message = runPlan.decision.message, source = "scheduler",
                                dateWib = dateKey, nowMs = nowMs, reasonCode = runPlan.decision.reasonCode,
                                latLong = AppServices.serverContext.value.lastLatLong,
                                correlationId = "run_${java.util.UUID.randomUUID()}",
                            ))
                            logsChanged = true
                        }
                        return@forEach
                    }

                    val result = tryExecute(profileId, preset, hhmm, dateKey)
                    val firedAt = System.currentTimeMillis()
                    val correlationId = "run_${java.util.UUID.randomUUID()}"
                    val reasonCode = when (result.status) {
                        "SUCCESS" -> "EXECUTION_ACCEPTED"
                        "FAILED" -> "EXECUTION_FAILED"
                        "SKIPPED" -> "EXECUTION_SKIPPED"
                        "RETRYABLE" -> "EXECUTION_RETRYABLE"
                        else -> "UNKNOWN_RESULT"
                    }
                    val entry = AutomationRunCoordinator.logEntry(
                        preset = preset,
                        status = result.status,
                        message = result.message,
                        source = "scheduler",
                        dateWib = dateKey,
                        nowMs = firedAt,
                        reasonCode = reasonCode,
                        latLong = AppServices.serverContext.value.lastLatLong,
                        correlationId = correlationId,
                    )
                    logs.add(0, entry)
                    logsChanged = true
                    val isRetryable = result.status == "RETRYABLE"
                    plans[key] = plan.copy(attempted = !isRetryable, resultStatus = result.status, lastAttemptMinute = nowMin)
                    stateChanged = true

                    val title = when (result.status) {
                        "SUCCESS" -> "Automasi presensi berhasil"
                        "FAILED" -> "Automasi presensi gagal"
                        "SKIPPED" -> "Automasi dilewati"
                        else -> "Automasi presensi"
                    }
                    runCatching { DesktopNotifications.notify(title = title, message = "${preset.nama}: ${result.shortMessage}") }
                }

                if (logsChanged) {
                    writeLogs(logPath, logs.take(120))
                }
            }
        }

        if (stateChanged) {
            // Keep only today's plans
            val cleaned = plans.filterValues { it.date == dateKey }
            writeDailyState(statePath(dir), DailyState(cleaned))
        }
    }

    private data class ExecResult(val status: String, val message: String, val shortMessage: String)

    private fun tryExecute(profileId: String, preset: AutomationPreset, hhmm: String, dateKey: String): ExecResult {
        val activeProfile = AppServices.activeProfile
        val ctx = AppServices.serverContext.value
        if (activeProfile == null || !profileIdMatches(profileId, activeProfile.id)) {
            return ExecResult(
                "SKIPPED",
                "[$dateKey $hhmm WIB] Eksekusi dilewati: sesi/profil aktif di aplikasi tidak cocok (preset profil: $profileId). Buka/login profil terkait agar automasi dapat berjalan.",
                "sesi/profil aktif tidak cocok",
            )
        }
        if (ctx.apiKey.isBlank()) {
            return ExecResult(
                "SKIPPED",
                "[$dateKey $hhmm WIB] Eksekusi dilewati: sesi server belum aktif/token kosong.",
                "sesi server belum aktif",
            )
        }

        return when (activeProfile.mode) {
            AttendanceMode.BKD_RPC -> executeBkd(activeProfile.id, preset, hhmm, dateKey)
            AttendanceMode.PONOROGO_ABSENSI -> executePonorogo(activeProfile.id, preset, hhmm, dateKey)
        }
    }

    private fun executeBkd(profileId: String, preset: AutomationPreset, hhmm: String, dateKey: String): ExecResult {
        val ctx = AppServices.serverContext.value
        val checkType = when (preset.tipe.lowercase()) {
            "datang", "masuk", "in" -> 1
            "pulang", "out" -> 2
            "siang" -> 3
            "custom" -> 1
            else -> 1
        }
        val wcRaw = preset.workcodeId.trim()
        if (wcRaw.isBlank()) {
            return ExecResult(
                "SKIPPED",
                "[$dateKey $hhmm WIB] BKD dilewati: preset '${preset.nama}' belum memiliki Work Code.",
                "work code belum diatur",
            )
        }
        val point = resolveBkdLatLong(profileId, preset)
            ?: return ExecResult(
                "SKIPPED",
                "[$dateKey $hhmm WIB] BKD dilewati: koordinat/lokasi tidak tersedia. Atur lokasi pada preset atau titik aktif/lokasi tersimpan.",
                "koordinat/lokasi tidak tersedia",
            )

        AppServices.updateServerContext(lastLatLong = "${point.first},${point.second}")
        val wcInt = wcRaw.toIntOrNull()
        val nowWib = ZonedDateTime.now(zone)
        val isFriday = nowWib.dayOfWeek == java.time.DayOfWeek.FRIDAY
        val wfh = 0 // otomasi default non-WFH; WFH hanya relevan skenario khusus dan memerlukan kebijakan eksplisit

        return try {
            kotlinx.coroutines.runBlocking {
                suspend fun callCekAbsen() = AppServices.rpcClient.callRaw(
                    RpcObjects.CEK_ABSEN,
                    buildJsonObject {
                        put("checktype", checkType)
                        if (wcInt != null) put("work_code", wcInt) else put("work_code", wcRaw)
                        put("iswfh", wfh)
                    },
                    AppServices.serverContext.value,
                    profileId,
                )
                val cek = runCatching { callCekAbsen() }.getOrElse { firstErr ->
                    // Retry terbatas & aman: hanya untuk cekabsen (belum submit absen).
                    kotlinx.coroutines.delay(1500)
                    runCatching { callCekAbsen() }.getOrElse { throw firstErr }
                }
                val cekObj = cek.result as? JsonObject
                val cekAbsen = (cekObj?.get("absen") as? JsonPrimitive)?.booleanOrNull
                val cekMsg = (cekObj?.get("message") as? JsonPrimitive)?.contentOrNull
                AutomationSubmitProtocol.bkdCekAbsen(200, cekObj != null || cek.result != null, cekAbsen, cekMsg)?.let { r ->
                    return@runBlocking ExecResult(r.status, "[$dateKey $hhmm WIB] ${r.message}", r.reasonCode)
                }

                val ab = AppServices.rpcClient.callRaw(
                    RpcObjects.ABSEN,
                    buildJsonObject {
                        put("checktype", checkType)
                        if (wcInt != null) put("work_code", wcInt) else put("work_code", wcRaw)
                        put("iswfh", wfh)
                        put("ijin", 0)
                        put("keterangan", "")
                        put("type_ijin", 0)
                        put("mock", 0)
                    },
                    AppServices.serverContext.value,
                    profileId,
                )
                val abObj = ab.result as? JsonObject
                val abFlag = (abObj?.get("absen") as? JsonPrimitive)?.booleanOrNull
                val abMsg = (abObj?.get("message") as? JsonPrimitive)?.contentOrNull
                    ?: (abObj?.get("error") as? JsonPrimitive)?.contentOrNull
                    ?: ab.error
                    ?: ab.message
                val interpreted = when {
                    ab.error != null -> AutomationSubmitProtocol.bkdAbsen(200, true, false, ab.error)
                    else -> AutomationSubmitProtocol.bkdAbsen(200, abObj != null || ab.result != null, abFlag ?: (ab.error == null), abMsg)
                }
                val prefix = "[$dateKey $hhmm WIB]"
                when (interpreted.status) {
                    "SUCCESS" -> ExecResult("SUCCESS", "$prefix BKD berhasil (${preset.tipe}, workcode=$wcRaw, latlong=${point.first},${point.second}). ${abMsg ?: "Respons server diterima."}".trim(), interpreted.reasonCode)
                    "FAILED" -> ExecResult("FAILED", "$prefix ${interpreted.message}", interpreted.reasonCode)
                    "RETRYABLE" -> ExecResult("RETRYABLE", "$prefix ${interpreted.message}", interpreted.reasonCode)
                    "AMBIGUOUS" -> ExecResult("AMBIGUOUS", "$prefix ${interpreted.message}", interpreted.reasonCode)
                    else -> ExecResult(interpreted.status, "$prefix ${interpreted.message}", interpreted.reasonCode)
                }
            }
        } catch (e: Exception) {
            ExecResult(
                "RETRYABLE",
                "[$dateKey $hhmm WIB] BKD gangguan transport: ${e.message ?: e::class.simpleName}",
                e.message ?: "error eksekusi",
            )
        }
    }

    private fun executePonorogo(profileId: String, preset: AutomationPreset, hhmm: String, dateKey: String): ExecResult {
        val ctx = AppServices.serverContext.value
        val pon = AppServices.ponorogoProfile
        if (pon == null) {
            return ExecResult(
                "SKIPPED",
                "[$dateKey $hhmm WIB] Ponorogo dilewati: data profil server (id_pegawai/nip/konteks) belum tersedia di sesi aktif. Login ulang profil Ponorogo di aplikasi.",
                "profil Ponorogo belum tersedia",
            )
        }

        val pegawaiId = ponStr(pon, "id_pegawai").ifBlank { ponStr(pon, "id") }
        val nip = ponStr(pon, "nip")
        if (pegawaiId.isBlank()) {
            return ExecResult(
                "SKIPPED",
                "[$dateKey $hhmm WIB] Ponorogo dilewati: ID pegawai tidak tersedia pada profil aktif.",
                "ID pegawai tidak tersedia",
            )
        }

        val point = resolveBkdLatLong(profileId, preset)
            ?: return ExecResult(
                "SKIPPED",
                "[$dateKey $hhmm WIB] Ponorogo dilewati: koordinat/lokasi tidak tersedia. Atur lokasi pada preset atau titik aktif/lokasi tersimpan.",
                "koordinat/lokasi tidak tersedia",
            )

        val photoSelection = selectPonorogoPhoto(profileId, preset)
        if (!photoSelection.ok) {
            return ExecResult(
                "SKIPPED",
                "[$dateKey $hhmm WIB] Ponorogo dilewati: ${photoSelection.reason}.",
                photoSelection.shortReason,
            )
        }

        AppServices.updateServerContext(lastLatLong = "${point.first},${point.second}")
        val deviceId = ctx.imei.ifBlank { ponStr(pon, "emai") }
        val keterangan = when (preset.tipe.lowercase()) {
            "pulang", "out" -> "Presensi Pulang (otomasi)"
            "datang", "masuk", "in" -> "Presensi Masuk (otomasi)"
            else -> "Presensi Otomatis (${preset.tipe.ifBlank { "preset" }})"
        }
        val payload = buildJsonObject {
            put("id_pegawai", pegawaiId)
            put("nip", nip.ifBlank { ponStr(pon, "nip") })
            put("lattitude", point.first.toString())
            put("longitude", point.second.toString())
            put("latitude", point.first.toString())
            put("imei", deviceId)
            put("sim_serial", deviceId)
            put("bedgenumber", deviceId)
            put("keterangan", keterangan)
            put("work_mode", "WFO")
            put("kode_instansi", ponStr(pon, "kode_instansi"))
            put("kode_unor", ponStr(pon, "kode_unor"))
            put("id_lokasi", ponStr(pon, "id_lokasi"))
            put("versi", "2.0.0")
            put("lampiran", photoSelection.base64 ?: "")
        }

        return try {
            kotlinx.coroutines.runBlocking {
                val res = AppServices.ponorogoClient.send("/login/absen_mobile", payload)
                val obj = res as? JsonObject
                val ok = (obj?.get("success") as? JsonPrimitive)?.booleanOrNull
                    ?: (obj?.get("success") as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
                    ?: false
                val msg = (obj?.get("message") as? JsonPrimitive)?.contentOrNull
                    ?: (obj?.get("keterangan") as? JsonPrimitive)?.contentOrNull
                    ?: (obj?.get("error") as? JsonPrimitive)?.contentOrNull
                    ?: res?.toString()?.take(180).orEmpty()
                val photoNote = if (!photoSelection.photoName.isNullOrBlank()) " foto='${photoSelection.photoName}'" else ""
                val interpreted = AutomationSubmitProtocol.ponorogoSubmit(200, obj != null, ok, msg)
                when (interpreted.status) {
                    "SUCCESS" -> ExecResult(
                        "SUCCESS",
                        "[$dateKey $hhmm WIB] Ponorogo berhasil (${preset.tipe}, latlong=${point.first},${point.second}$photoNote). ${msg.ifBlank { "Respons server diterima." }}".trim(),
                        interpreted.reasonCode,
                    )
                    else -> ExecResult(
                        interpreted.status,
                        "[$dateKey $hhmm WIB] ${interpreted.message}",
                        interpreted.reasonCode,
                    )
                }
            }
        } catch (e: Exception) {
            ExecResult(
                "RETRYABLE",
                "[$dateKey $hhmm WIB] Ponorogo gangguan transport: ${e.message ?: e::class.simpleName}",
                e.message ?: "error eksekusi",
            )
        }
    }

    private data class PhotoPickResult(
        val ok: Boolean,
        val base64: String? = null,
        val photoName: String? = null,
        val reason: String = "",
        val shortReason: String = "",
    )

    private fun selectPonorogoPhoto(profileId: String, preset: AutomationPreset): PhotoPickResult {
        val requiredByProfile = AppServices.activeProfile?.requiresPhotoPreset == true
        val wantsPhoto = preset.stockFotoIds.isNotEmpty() || requiredByProfile
        if (!wantsPhoto) {
            // Best-effort: kirim tanpa lampiran jika tidak diwajibkan oleh preset/profil.
            return PhotoPickResult(ok = true, base64 = "", photoName = null)
        }

        val allPhotos = runCatching {
            val raw = AppServices.stockPhotoReader?.invoke(profileId)
            id.tetuko.core.domain.model.StockPhotoCodec.decode(raw)
        }.getOrDefault(emptyList())
        if (allPhotos.isEmpty()) {
            return PhotoPickResult(
                ok = false,
                reason = "stok foto lokal belum tersedia untuk profil ini (tambahkan stok foto di perangkat/penyimpanan lokal agar otomasi Ponorogo dapat berjalan)",
                shortReason = "stok foto belum tersedia",
            )
        }

        val candidateIds = preset.stockFotoIds.filter { it.isNotBlank() }.distinct()
        val candidatePhotos = if (candidateIds.isNotEmpty()) {
            val byId = allPhotos.associateBy { it.id }
            candidateIds.mapNotNull { byId[it] }
        } else {
            allPhotos
        }
        if (candidatePhotos.isEmpty()) {
            return PhotoPickResult(
                ok = false,
                reason = "preset menunjuk stok foto, tetapi foto terkait tidak ditemukan di penyimpanan lokal profil ini",
                shortReason = "foto preset tidak ditemukan",
            )
        }

        val picked = pickPhotoFromQueue(profileId, preset.id, candidatePhotos.map { it.id }, candidatePhotos.associateBy { it.id })
            ?: return PhotoPickResult(
                ok = false,
                reason = "gagal memilih foto dari antrian lokal",
                shortReason = "gagal memilih foto",
            )

        val normalized = normalizePhotoPayload(picked.dataBase64)
        if (normalized.isBlank()) {
            return PhotoPickResult(
                ok = false,
                reason = "data foto terpilih kosong/tidak valid",
                shortReason = "data foto tidak valid",
            )
        }
        return PhotoPickResult(ok = true, base64 = normalized, photoName = picked.nama.ifBlank { picked.id })
    }

    private fun pickPhotoFromQueue(
        profileId: String,
        presetId: String,
        validIds: List<String>,
        byId: Map<String, id.tetuko.core.domain.model.AutomationStockPhoto>,
    ): id.tetuko.core.domain.model.AutomationStockPhoto? {
        if (validIds.isEmpty()) return null
        val queueRaw = runCatching { AppServices.photoQueueReader?.invoke(profileId) }.getOrNull()
        val queueState = runCatching { id.tetuko.core.domain.model.PhotoQueueCodec.decode(queueRaw) }
            .getOrDefault(id.tetuko.core.domain.model.AutomationPhotoQueueState())
        val validSet = validIds.toSet()
        var queue = (queueState.queues[presetId] ?: emptyList()).filter { it in validSet }
        if (queue.isEmpty()) {
            queue = validIds.shuffled(Random(System.nanoTime()))
        }
        val chosenId = queue.firstOrNull() ?: validIds.firstOrNull() ?: return null
        val remaining = if (queue.size > 1) queue.drop(1) else emptyList()
        val newQueues = queueState.queues.toMutableMap()
        newQueues[presetId] = remaining
        val newState = id.tetuko.core.domain.model.AutomationPhotoQueueState(newQueues)
        runCatching {
            val encoded = id.tetuko.core.domain.model.PhotoQueueCodec.encode(newState)
            AppServices.photoQueueWriter?.invoke(profileId, encoded)
        }
        return byId[chosenId] ?: byId[validIds.first()]
    }

    private fun normalizePhotoPayload(raw: String): String {
        val t = raw.trim()
        if (t.isBlank()) return ""
        val idx = t.indexOf("base64,")
        return if (idx >= 0) t.substring(idx + "base64,".length).trim() else t
    }

    private fun ponStr(obj: JsonObject, key: String): String =
        (obj[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private fun resolveBkdLatLong(profileId: String, preset: AutomationPreset): Pair<Double, Double>? {
        val points = runCatching {
            val raw = AppServices.pointsReader?.invoke(profileId)
            SavedPointsCodec.decode(raw)
        }.getOrDefault(emptyList())
        if (points.isNotEmpty()) {
            val byId = points.associateBy { it.id }
            val candidates = preset.lokasiIds.mapNotNull { byId[it] }
            val chosen = when {
                candidates.isNotEmpty() -> candidates[Random.nextInt(candidates.size)]
                else -> points.firstOrNull { it.active } ?: points.first()
            }
            return chosen.latitude to chosen.longitude
        }
        val ctx = AppServices.serverContext.value.lastLatLong
        if (ctx.contains(",")) {
            val la = ctx.substringBefore(",").trim().toDoubleOrNull()
            val ln = ctx.substringAfter(",").trim().toDoubleOrNull()
            if (la != null && ln != null) return la to ln
        }
        return null
    }

    private fun profileIdMatches(presetProfileId: String, activeProfileId: String): Boolean {
        if (presetProfileId.isBlank() || activeProfileId.isBlank()) return false
        return presetProfileId == activeProfileId ||
            activeProfileId.equals(presetProfileId, ignoreCase = true) ||
            activeProfileId.contains(presetProfileId, ignoreCase = true) ||
            presetProfileId.contains(activeProfileId, ignoreCase = true)
    }

    private fun pickFireMinute(startMin: Int, endMin: Int, key: String, dateKey: String): Int {
        if (startMin == endMin) return startMin
        val seed = (key + "|" + dateKey).hashCode()
        val rnd = Random(seed)
        return if (startMin < endMin) {
            rnd.nextInt(startMin, endMin + 1)
        } else {
            // overnight window: choose in [start, 1439] U [0, end]
            val span1 = 1440 - startMin
            val span2 = endMin + 1
            val pick = rnd.nextInt(span1 + span2)
            if (pick < span1) startMin + pick else pick - span1
        }
    }

    
    private fun DayOfWeek.toPresetDay(): Int = when (this) {
        DayOfWeek.SUNDAY -> 0
        DayOfWeek.MONDAY -> 1
        DayOfWeek.TUESDAY -> 2
        DayOfWeek.WEDNESDAY -> 3
        DayOfWeek.THURSDAY -> 4
        DayOfWeek.FRIDAY -> 5
        DayOfWeek.SATURDAY -> 6
    }

    private fun stateDate(state: DailyState): String? = state.plans.values.firstOrNull()?.date

    private fun alreadyLoggedToday(logs: List<AutomationLogEntry>, presetId: String, dateKey: String): Boolean {
        // If any terminal attempt was logged today for this preset, do not auto-retry in same scheduler pass logic.
        if (logs.isEmpty()) return false
        val dayStart = runCatching {
            java.time.LocalDate.parse(dateKey, dateFmt).atStartOfDay(zone).toInstant().toEpochMilli()
        }.getOrNull() ?: return false
        val dayEnd = dayStart + 24L * 60 * 60 * 1000 - 1
        return logs.any { e ->
            e.presetId == presetId &&
                e.waktu in dayStart..dayEnd &&
                e.source == "scheduler" &&
                e.status in setOf("SUCCESS", "FAILED", "SKIPPED")
        }
    }

    private fun readLogs(path: Path): List<AutomationLogEntry> = runCatching {
        if (!Files.exists(path) || Files.size(path) > 2_000_000) emptyList()
        else AutomationLogCodec.decode(Files.readString(path))
    }.getOrDefault(emptyList())

    private fun writeLogs(path: Path, logs: List<AutomationLogEntry>) {
        runCatching {
            Files.createDirectories(path.parent)
            val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
            Files.writeString(tmp, AutomationLogCodec.encode(logs))
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun readDailyState(path: Path): DailyState = runCatching {
        if (!Files.exists(path) || Files.size(path) > 1_000_000) DailyState()
        else json.decodeFromString<DailyState>(Files.readString(path))
    }.getOrDefault(DailyState())

    private fun writeDailyState(path: Path, state: DailyState) {
        runCatching {
            Files.createDirectories(path.parent)
            val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
            Files.writeString(tmp, json.encodeToString(state))
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

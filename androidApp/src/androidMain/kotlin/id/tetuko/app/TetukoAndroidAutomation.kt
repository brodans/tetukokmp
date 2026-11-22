package id.tetuko.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import id.tetuko.app.service.SavedPointsCodec
import id.tetuko.core.domain.model.AutomationLogCodec
import id.tetuko.core.domain.model.AutomationLogEntry
import id.tetuko.core.domain.model.AutomationPhotoQueueState
import id.tetuko.core.domain.model.AutomationPlanning
import id.tetuko.core.domain.model.AutomationRunCoordinator
import id.tetuko.core.domain.model.AutomationRunInput
import id.tetuko.core.domain.model.AutomationSubmitProtocol
import id.tetuko.core.domain.model.AutomationPreset
import id.tetuko.core.domain.model.PhotoQueueCodec
import id.tetuko.core.domain.model.PresetCodec
import id.tetuko.core.domain.model.StockPhotoCodec
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min

object TetukoAndroidAutomation {
    private const val UNIQUE_WORK = "tetuko-android-automation"
    private val zone: ZoneId = ZoneId.of("Asia/Jakarta")

    fun install(context: Context) {
        val prefs = context.getSharedPreferences("tetuko_native_stores", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("automation_scheduler_enabled", true)
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(UNIQUE_WORK)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<TetukoAutomationWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        scheduleNextExactAlarm(context)
    }

    private fun scheduleNextExactAlarm(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            context.getSharedPreferences("tetuko_native_stores", Context.MODE_PRIVATE).edit()
                .putBoolean("automation_exact_alarm_available", false)
                .apply()
            return
        }
        val prefs = context.getSharedPreferences("tetuko_native_stores", Context.MODE_PRIVATE)
        val profileId = prefs.getString("active_profile_id", null)?.trim().orEmpty()
        val presetsRaw = prefs.getString("$profileId:presets", null)
        val next = runCatching { parseNextPresetStart(presetsRaw) }.getOrNull() ?: return
        val intent = Intent(context, TetukoAutomationReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            18422,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
            prefs.edit().putBoolean("automation_exact_alarm_available", true).apply()
        }.onFailure {
            prefs.edit().putBoolean("automation_exact_alarm_available", false).apply()
        }
    }

    private fun parseNextPresetStart(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val presets = PresetCodec.decode(raw).filter { it.aktif }
        if (presets.isEmpty()) return null
        val now = ZonedDateTime.now(zone)
        var next: ZonedDateTime? = null
        for (preset in presets) {
            val parts = preset.jamMulai.split(':')
            if (parts.size != 2) continue
            val hour = parts[0].toIntOrNull() ?: continue
            val minute = parts[1].toIntOrNull() ?: continue
            var candidate = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
            if (candidate.isBefore(now)) candidate = candidate.plusDays(1)
            if (next == null || candidate.isBefore(next)) next = candidate
        }
        return next?.toInstant()?.toEpochMilli()
    }

    fun runManual(context: Context, profileId: String, preset: AutomationPreset): id.tetuko.core.domain.model.AutomationManualResult {
        return runCatching {
            val prefs = context.getSharedPreferences("tetuko_native_stores", Context.MODE_PRIVATE)
            val apiKey = AndroidAutomationSecureStore.decrypt(context, prefs.getString("encrypted_active_api_key", null))?.trim().orEmpty()
            val imei = prefs.getString("active_imei", null)?.trim().orEmpty()
            val lastLatLong = prefs.getString("active_last_latlong", null)?.trim().orEmpty()
            val activeProfileId = prefs.getString("active_profile_id", null)?.trim().orEmpty()
            if (activeProfileId.isNotBlank() && activeProfileId != profileId) {
                return@runCatching id.tetuko.core.domain.model.AutomationManualResult("SKIPPED", "Profil aktif tidak cocok dengan preset.")
            }
            if (apiKey.isBlank()) return@runCatching id.tetuko.core.domain.model.AutomationManualResult("SKIPPED", "Sesi/API key belum aktif.")
            val point = resolvePointStatic(prefs, profileId, lastLatLong, preset)
                ?: return@runCatching id.tetuko.core.domain.model.AutomationManualResult("SKIPPED", "Koordinat/lokasi preset belum tersedia.")
            val dateKey = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(ZonedDateTime.now(zone))
            val result = if (profileId == "ponorogo") {
                executePonorogoStatic(context, prefs, profileId, preset, point, imei)
            } else {
                executeBkdStatic(prefs, profileId, preset, point, apiKey, imei)
            }
            appendLogStatic(context, prefs, profileId, preset, dateKey, result.status, result.message, result.reasonCode, result.errorClass, result.httpStatus)
            notifyStatic(context, result.status, preset.nama, result.message)
            id.tetuko.core.domain.model.AutomationManualResult(result.status, result.message)
        }.getOrElse { e ->
            id.tetuko.core.domain.model.AutomationManualResult("FAILED", e.message ?: "Gagal menjalankan preset.")
        }
    }

    private data class StaticExecResult(val status: String, val message: String, val reasonCode: String = "UNKNOWN", val errorClass: String = "", val httpStatus: Int = 0)

    private fun resolvePointStatic(prefs: android.content.SharedPreferences, profileId: String, fallback: String, preset: AutomationPreset): Pair<Double, Double>? {
        val points = runCatching { SavedPointsCodec.decode(prefs.getString("$profileId:points", null)) }.getOrDefault(emptyList())
        if (points.isNotEmpty()) {
            val byId = points.associateBy { it.id }
            val candidates = preset.lokasiIds.mapNotNull { byId[it] }
            val chosen = when {
                candidates.isNotEmpty() -> candidates.random()
                else -> points.firstOrNull { it.active } ?: points.first()
            }
            return chosen.latitude to chosen.longitude
        }
        val lat = fallback.substringBefore(',').trim().toDoubleOrNull()
        val lon = fallback.substringAfter(',', "").trim().toDoubleOrNull()
        return if (lat != null && lon != null) lat to lon else null
    }

    private fun postJsonStatic(urlString: String, body: JSONObject): Pair<Int, JSONObject?> {
        var connection: HttpURLConnection? = null
        return runCatching {
            connection = URL(urlString).openConnection() as HttpURLConnection
            connection!!.requestMethod = "POST"
            connection!!.connectTimeout = 20_000
            connection!!.readTimeout = 20_000
            connection!!.doOutput = true
            connection!!.setRequestProperty("Content-Type", "application/json")
            connection!!.setRequestProperty("Accept", "application/json")
            connection!!.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection!!.responseCode
            val stream = if (code in 200..299) connection!!.inputStream else connection!!.errorStream
            val text = stream?.let { BufferedReader(it.reader()).use(BufferedReader::readText) }.orEmpty()
            code to runCatching { JSONObject(text) }.getOrNull()
        }.getOrElse { 0 to null }.also { connection?.disconnect() }
    }

    private fun executeBkdStatic(prefs: android.content.SharedPreferences, profileId: String, preset: AutomationPreset, point: Pair<Double, Double>, apiKey: String, imei: String): StaticExecResult {
        if (preset.workcodeId.isBlank()) return StaticExecResult("SKIPPED", "BKD: work code belum diatur", "NO_WORKCODE")
        val gateway = prefs.getString("active_gateway_url", null)?.trim().orEmpty()
        if (gateway.isBlank()) return StaticExecResult("SKIPPED", "BKD: gateway profil tidak dikenali", "NO_GATEWAY")
        val checkType = when (preset.tipe.lowercase()) { "pulang", "out" -> 2; "siang" -> 3; else -> 1 }
        val param = JSONObject()
            .put("checktype", checkType).put("work_code", preset.workcodeId.toIntOrNull() ?: preset.workcodeId)
            .put("iswfh", 0).put("api_key", apiKey).put("last_latlong", "${point.first},${point.second}").put("imei", imei)
        val (cekCode, cekJson) = postJsonStatic(gateway, JSONObject().put("object", "cekabsen").put("param", param))
        val cekObj = cekJson?.optJSONObject("result")
        AutomationSubmitProtocol.bkdCekAbsen(cekCode, cekJson != null, cekObj?.optBoolean("absen", false), cekObj?.optString("message"))?.let {
            return StaticExecResult(it.status, it.message, it.reasonCode, it.errorClass, it.httpStatus)
        }
        val (absenCode, absenJson) = postJsonStatic(gateway, JSONObject().put("object", "absen").put("param", param.put("ijin", 0).put("keterangan", "").put("type_ijin", 0).put("mock", 0)))
        val absenObj = absenJson?.optJSONObject("result")
        val success = absenObj?.optBoolean("absen", absenJson?.optBoolean("success", false) ?: false) == true
        val message = absenObj?.optString("message").orEmpty().ifBlank { absenJson?.optString("message").orEmpty() }
        val interpreted = AutomationSubmitProtocol.bkdAbsen(absenCode, absenJson != null, success, message)
        return StaticExecResult(interpreted.status, interpreted.message, interpreted.reasonCode, interpreted.errorClass, interpreted.httpStatus)
    }

    private fun executePonorogoStatic(context: Context, prefs: android.content.SharedPreferences, profileId: String, preset: AutomationPreset, point: Pair<Double, Double>, imei: String): StaticExecResult {
        val profileRaw = prefs.getString("active_ponorogo_profile", null).orEmpty()
        if (profileRaw.isBlank()) return StaticExecResult("SKIPPED", "Ponorogo: profil pegawai belum tersedia", "NO_PROFILE")
        val profile = runCatching { JSONObject(profileRaw) }.getOrNull() ?: return StaticExecResult("SKIPPED", "Ponorogo: profil pegawai tidak valid", "BAD_PROFILE")
        val employeeId = profile.optString("id_pegawai").ifBlank { profile.optString("id") }
        if (employeeId.isBlank()) return StaticExecResult("SKIPPED", "Ponorogo: ID pegawai belum tersedia", "NO_EMPLOYEE_ID")
        val stock = StockPhotoCodec.decode(prefs.getString("$profileId:stock_photos", null))
        val candidates = if (preset.stockFotoIds.isNotEmpty()) stock.filter { it.id in preset.stockFotoIds.toSet() } else stock
        if (candidates.isEmpty()) return StaticExecResult("SKIPPED", "Ponorogo: stok foto preset belum tersedia", "NO_STOCK_PHOTO")
        val queueState = PhotoQueueCodec.decode(prefs.getString("$profileId:photo_queues", null))
        val validIds = candidates.map { it.id }
        var queue = (queueState.queues[preset.id] ?: emptyList()).filter { it in validIds.toSet() }
        if (queue.isEmpty()) queue = validIds.shuffled()
        val chosenId = queue.firstOrNull() ?: return StaticExecResult("SKIPPED", "Ponorogo: gagal memilih foto", "NO_PHOTO_PICK")
        val chosen = candidates.firstOrNull { it.id == chosenId } ?: candidates.first()
        val normalized = chosen.dataBase64.substringAfter("base64,", chosen.dataBase64).trim()
        if (normalized.isBlank()) return StaticExecResult("SKIPPED", "Ponorogo: data foto tidak valid", "BAD_PHOTO")
        val payload = JSONObject()
            .put("id_pegawai", employeeId).put("nip", profile.optString("nip"))
            .put("lattitude", point.first).put("longitude", point.second).put("latitude", point.first)
            .put("imei", imei.ifBlank { profile.optString("imei") }).put("sim_serial", imei.ifBlank { profile.optString("imei") })
            .put("keterangan", if (preset.tipe.lowercase() == "pulang") "Presensi Pulang (otomasi)" else "Presensi Masuk (otomasi)")
            .put("work_mode", "WFO").put("kode_instansi", profile.optString("kode_instansi")).put("kode_unor", profile.optString("kode_unor"))
            .put("id_lokasi", profile.optString("id_lokasi")).put("versi", "2.0.0").put("lampiran", normalized)
        val proxyUrl = prefs.getString("android_proxy_url", null)?.trim().orEmpty().ifBlank { "http://127.0.0.1:14200/api/proxy" }
        val proxyPayload = JSONObject().put("endpoint", "/login/absen_mobile").put("payload", payload)
        val apiBase = prefs.getString("active_ponorogo_api_base", null)?.trim().orEmpty().takeIf { it.isNotBlank() }
        if (apiBase != null) proxyPayload.put("api_base", apiBase)
        val (code, json) = postJsonStatic(proxyUrl, proxyPayload)
        val success = json?.optBoolean("success", false) == true
        val message = json?.optString("message").orEmpty().ifBlank { json?.optString("error").orEmpty() }
        val interpreted = AutomationSubmitProtocol.ponorogoSubmit(code, json != null, success, message)
        if (interpreted.status == "SUCCESS" && queue.size > 1) {
            val newQueues = queueState.queues.toMutableMap()
            newQueues[preset.id] = queue.drop(1)
            prefs.edit().putString("$profileId:photo_queues", PhotoQueueCodec.encode(AutomationPhotoQueueState(newQueues))).apply()
        }
        return StaticExecResult(interpreted.status, interpreted.message, interpreted.reasonCode, interpreted.errorClass, interpreted.httpStatus)
    }

    private fun appendLogStatic(context: Context, prefs: android.content.SharedPreferences, profileId: String, preset: AutomationPreset, dateKey: String, status: String, message: String, reasonCode: String, errorClass: String = "", httpStatus: Int = 0) {
        val key = "$profileId:automation_logs"
        val logs = AutomationLogCodec.decode(prefs.getString(key, null)).toMutableList()
        val now = System.currentTimeMillis()
        logs.add(0, AutomationLogEntry(
            id = "alog_android_${now}_${UUID.randomUUID().toString().take(8)}",
            presetId = preset.id, presetNama = preset.nama, waktu = now, status = status, pesan = message,
            source = "manual", dateWib = dateKey, plannedAt = now, firedAt = now, finishedAt = System.currentTimeMillis(),
            latLong = prefs.getString("active_last_latlong", null)?.trim().orEmpty(),
            workcodeId = preset.workcodeId, attempt = 1, reasonCode = reasonCode, errorClass = errorClass, httpStatus = httpStatus,
            correlationId = "run_android_${UUID.randomUUID()}",
        ))
        prefs.edit().putString(key, AutomationLogCodec.encode(logs.take(120))).apply()
    }

    private fun notifyStatic(context: Context, status: String, presetName: String, message: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val importance = if (status in setOf("FAILED", "AMBIGUOUS")) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel("tetuko_automation", "TETUKO Otomasi", importance).apply {
            description = "Status eksekusi automasi presensi TETUKO"
        }
        manager.createNotificationChannel(channel)
        val title = when (status) {
            "SUCCESS" -> "Automasi berhasil"
            "SKIPPED" -> "Automasi dilewati"
            "RETRYABLE" -> "Automasi dijadwalkan ulang"
            "AMBIGUOUS" -> "Perlu verifikasi presensi"
            "PENDING" -> "Automasi berjalan"
            else -> "Automasi gagal"
        }
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val contentIntent = PendingIntent.getActivity(context, 1002, openIntent, flags)
        val notification = NotificationCompat.Builder(context, "tetuko_automation")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("$presetName: ${message.take(180)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$presetName: $message"))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(if (status in setOf("FAILED", "AMBIGUOUS")) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }
}

class TetukoAutomationWorker(
    private val context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    private val prefs = context.getSharedPreferences("tetuko_native_stores", Context.MODE_PRIVATE)
    private val zone = ZoneId.of("Asia/Jakarta")
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    override fun doWork(): Result {
        if (!prefs.getBoolean("automation_scheduler_enabled", true)) return Result.success()
        val profileId = prefs.getString("active_profile_id", null)?.trim().orEmpty()
        val apiKey = AndroidAutomationSecureStore.decrypt(context, prefs.getString("encrypted_active_api_key", null))?.trim().orEmpty()
        val imei = prefs.getString("active_imei", null)?.trim().orEmpty()
        val lastLatLong = prefs.getString("active_last_latlong", null)?.trim().orEmpty()
        if (profileId.isBlank() || apiKey.isBlank()) return Result.success()

        val now = ZonedDateTime.now(zone)
        val dateKey = dateFmt.format(now)
        val minute = now.hour * 60 + now.minute
        val day = when (now.dayOfWeek) {
            java.time.DayOfWeek.MONDAY -> 1
            java.time.DayOfWeek.TUESDAY -> 2
            java.time.DayOfWeek.WEDNESDAY -> 3
            java.time.DayOfWeek.THURSDAY -> 4
            java.time.DayOfWeek.FRIDAY -> 5
            java.time.DayOfWeek.SATURDAY -> 6
            java.time.DayOfWeek.SUNDAY -> 0
        }

        val presets = PresetCodec.decode(prefs.getString("$profileId:presets", null))
        for (preset in presets) {
            if (!preset.aktif || day !in preset.hariAktif) continue
            val start = AutomationPlanning.parseMinutes(preset.jamMulai) ?: continue
            val end = AutomationPlanning.parseMinutes(preset.jamSelesai) ?: continue
            if (!AutomationPlanning.inWindow(minute, start, end)) {
                if (AutomationPlanning.isPastWindow(minute, start, end)) {
                    markMissedOnce(profileId, preset, dateKey)
                }
                continue
            }

            val terminalKey = terminalKey(profileId, preset.id, dateKey)
            val retryKey = retryKey(profileId, preset.id)
            val nextRetryAt = prefs.getLong(retryKey, 0L)
            val stockIds = runCatching { StockPhotoCodec.decode(prefs.getString("$profileId:stock_photos", null)).map { it.id }.toSet() }.getOrDefault(emptySet())
            val plan = AutomationRunCoordinator.plan(
                AutomationRunInput(
                    preset = preset,
                    profileId = profileId,
                    hasActiveSession = apiKey.isNotBlank(),
                    activeProfileId = profileId,
                    requiresWorkCode = preset.workcodeId.isNotBlank() || profileId != "ponorogo",
                    requiresPhoto = profileId == "ponorogo",
                    availableStockPhotoIds = stockIds,
                    nowMinute = minute,
                    dateWib = dateKey,
                    nowMs = System.currentTimeMillis(),
                    alreadyTerminalToday = prefs.getBoolean(terminalKey, false),
                    nextRetryAtMs = nextRetryAt,
                )
            )
            if (!plan.decision.shouldRun) {
                if (plan.decision.reasonCode == "WINDOW_MISSED") {
                    markMissedOnce(profileId, preset, dateKey)
                }
                continue
            }

            val point = resolvePoint(profileId, lastLatLong, preset)
            if (point == null) {
                logAndNotify(profileId, preset, dateKey, "SKIPPED", "Koordinat/lokasi preset belum tersedia", "NO_LOCATION", transient = true)
                continue
            }

            appendLog(profileId, preset, dateKey, "PENDING", "Eksekusi dimulai; menunggu respons server", "EXECUTION_PENDING")
            val result = if (profileId == "ponorogo") {
                executePonorogo(profileId, preset, point, imei)
            } else {
                executeBkd(profileId, preset, point, apiKey, imei)
            }

            when (result.status) {
                "SUCCESS", "FAILED", "SKIPPED" -> {
                    prefs.edit().putBoolean(terminalKey, true).remove(retryKey).apply()
                }
                "RETRYABLE", "AMBIGUOUS" -> {
                    val backoffMs = TimeUnit.MINUTES.toMillis(5)
                    prefs.edit().putLong(retryKey, System.currentTimeMillis() + backoffMs).apply()
                }
            }
            appendLog(profileId, preset, dateKey, result.status, result.message, result.reasonCode, result.errorClass, result.httpStatus)
            notify(result.status, preset.nama, result.message)
        }
        TetukoAndroidAutomation.install(context) // reschedule exact alarm based on latest presets
        return Result.success()
    }

    private data class ExecResult(
        val status: String,
        val message: String,
        val reasonCode: String = "UNKNOWN",
        val errorClass: String = "",
        val httpStatus: Int = 0,
    )

    private fun terminalKey(profileId: String, presetId: String, dateKey: String) =
        "automation_android_terminal_$profileId|$presetId|$dateKey"

    private fun retryKey(profileId: String, presetId: String) =
        "automation_android_retry_$profileId|$presetId"

    private fun markMissedOnce(profileId: String, preset: AutomationPreset, dateKey: String) {
        val missedKey = "automation_android_missed_$profileId|${preset.id}|$dateKey"
        if (prefs.getBoolean(missedKey, false)) return
        prefs.edit().putBoolean(missedKey, true).apply()
        appendLog(profileId, preset, dateKey, "SKIPPED", "Jendela preset terlewati: ${preset.jamMulai}-${preset.jamSelesai} WIB", "WINDOW_MISSED")
        notify("SKIPPED", preset.nama, "Jendela preset terlewati")
    }

    private fun logAndNotify(profileId: String, preset: AutomationPreset, dateKey: String, status: String, message: String, reasonCode: String, transient: Boolean = false) {
        if (transient) {
            val shortKey = "automation_android_skip_${profileId}|${preset.id}|${System.currentTimeMillis() / 900000}"
            if (prefs.getBoolean(shortKey, false)) return
            prefs.edit().putBoolean(shortKey, true).apply()
        }
        appendLog(profileId, preset, dateKey, status, message, reasonCode)
        notify(status, preset.nama, message)
    }

    private fun resolvePoint(profileId: String, fallback: String, preset: AutomationPreset): Pair<Double, Double>? {
        val points = runCatching { SavedPointsCodec.decode(prefs.getString("$profileId:points", null)) }.getOrDefault(emptyList())
        if (points.isNotEmpty()) {
            val byId = points.associateBy { it.id }
            val candidates = preset.lokasiIds.mapNotNull { byId[it] }
            val chosen = when {
                candidates.isNotEmpty() -> candidates.random()
                else -> points.firstOrNull { it.active } ?: points.first()
            }
            return chosen.latitude to chosen.longitude
        }
        val lat = fallback.substringBefore(',').trim().toDoubleOrNull()
        val lon = fallback.substringAfter(',', "").trim().toDoubleOrNull()
        return if (lat != null && lon != null) lat to lon else null
    }

    private fun executeBkd(profileId: String, preset: AutomationPreset, point: Pair<Double, Double>, apiKey: String, imei: String): ExecResult {
        if (preset.workcodeId.isBlank()) return ExecResult("SKIPPED", "BKD: work code belum diatur", "NO_WORKCODE")
        val gateway = prefs.getString("active_gateway_url", null)?.trim().orEmpty()
        if (gateway.isBlank()) return ExecResult("SKIPPED", "BKD: gateway profil tidak dikenali", "NO_GATEWAY")

        val checkType = when (preset.tipe.lowercase()) { "pulang", "out" -> 2; "siang" -> 3; else -> 1 }
        val param = JSONObject()
            .put("checktype", checkType)
            .put("work_code", preset.workcodeId.toIntOrNull() ?: preset.workcodeId)
            .put("iswfh", 0)
            .put("api_key", apiKey)
            .put("last_latlong", "${point.first},${point.second}")
            .put("imei", imei)
        val cek = postJson(gateway, JSONObject().put("object", "cekabsen").put("param", param))
        val cekObj = cek.json?.optJSONObject("result")
        AutomationSubmitProtocol.bkdCekAbsen(cek.code, cek.json != null, cekObj?.optBoolean("absen", false), cekObj?.optString("message"))?.let {
            return ExecResult(it.status, it.message, it.reasonCode, it.errorClass, it.httpStatus)
        }
        val absen = postJson(
            gateway,
            JSONObject().put("object", "absen").put("param", param.put("ijin", 0).put("keterangan", "").put("type_ijin", 0).put("mock", 0)),
        )
        val absenObj = absen.json?.optJSONObject("result")
        val success = absenObj?.optBoolean("absen", absen.json?.optBoolean("success", false) ?: false) == true
        val message = absenObj?.optString("message").orEmpty().ifBlank { absen.json?.optString("message").orEmpty() }
        val interpreted = AutomationSubmitProtocol.bkdAbsen(absen.code, absen.json != null, success, message)
        return ExecResult(interpreted.status, interpreted.message, interpreted.reasonCode, interpreted.errorClass, interpreted.httpStatus)
    }

    private fun executePonorogo(profileId: String, preset: AutomationPreset, point: Pair<Double, Double>, imei: String): ExecResult {
        val profileRaw = prefs.getString("active_ponorogo_profile", null).orEmpty()
        if (profileRaw.isBlank()) return ExecResult("SKIPPED", "Ponorogo: profil pegawai belum tersedia", "NO_PROFILE")
        val profile = runCatching { JSONObject(profileRaw) }.getOrNull()
            ?: return ExecResult("SKIPPED", "Ponorogo: profil pegawai tidak valid", "BAD_PROFILE")
        val employeeId = profile.optString("id_pegawai").ifBlank { profile.optString("id") }
        if (employeeId.isBlank()) return ExecResult("SKIPPED", "Ponorogo: ID pegawai belum tersedia", "NO_EMPLOYEE_ID")

        val stock = StockPhotoCodec.decode(prefs.getString("$profileId:stock_photos", null))
        val candidates = if (preset.stockFotoIds.isNotEmpty()) stock.filter { it.id in preset.stockFotoIds.toSet() } else stock
        if (candidates.isEmpty()) return ExecResult("SKIPPED", "Ponorogo: stok foto preset belum tersedia", "NO_STOCK_PHOTO")
        val queueRaw = prefs.getString("$profileId:photo_queues", null)
        val queueState = PhotoQueueCodec.decode(queueRaw)
        val validIds = candidates.map { it.id }
        var queue = (queueState.queues[preset.id] ?: emptyList()).filter { it in validIds.toSet() }
        if (queue.isEmpty()) queue = validIds.shuffled()
        val chosenId = queue.firstOrNull() ?: return ExecResult("SKIPPED", "Ponorogo: gagal memilih foto", "NO_PHOTO_PICK")
        val chosen = candidates.firstOrNull { it.id == chosenId } ?: candidates.first()
        val normalized = chosen.dataBase64.substringAfter("base64,", chosen.dataBase64).trim()
        if (normalized.isBlank()) return ExecResult("SKIPPED", "Ponorogo: data foto tidak valid", "BAD_PHOTO")

        val payload = JSONObject()
            .put("id_pegawai", employeeId)
            .put("nip", profile.optString("nip"))
            .put("lattitude", point.first)
            .put("longitude", point.second)
            .put("latitude", point.first)
            .put("imei", imei.ifBlank { profile.optString("imei") })
            .put("sim_serial", imei.ifBlank { profile.optString("imei") })
            .put("keterangan", if (preset.tipe.lowercase() == "pulang") "Presensi Pulang (otomasi)" else "Presensi Masuk (otomasi)")
            .put("work_mode", "WFO")
            .put("kode_instansi", profile.optString("kode_instansi"))
            .put("kode_unor", profile.optString("kode_unor"))
            .put("id_lokasi", profile.optString("id_lokasi"))
            .put("versi", "2.0.0")
            .put("lampiran", normalized)

        val proxyUrl = prefs.getString("android_proxy_url", null)?.trim().orEmpty().ifBlank { "http://127.0.0.1:14200/api/proxy" }
        val proxyPayload = JSONObject()
            .put("endpoint", "/login/absen_mobile")
            .put("payload", payload)
        val apiBase = prefs.getString("active_ponorogo_api_base", null)?.trim().orEmpty().takeIf { it.isNotBlank() }
        if (apiBase != null) proxyPayload.put("api_base", apiBase)

        val response = postJson(proxyUrl, proxyPayload)
        val success = response.json?.optBoolean("success", false) == true
        val message = response.json?.optString("message").orEmpty().ifBlank { response.json?.optString("error").orEmpty() }
        val interpreted = AutomationSubmitProtocol.ponorogoSubmit(response.code, response.json != null, success, message)
        if (interpreted.status == "SUCCESS") consumeQueue(profileId, preset.id, queueState, queue)
        return ExecResult(interpreted.status, interpreted.message, interpreted.reasonCode, interpreted.errorClass, interpreted.httpStatus)
    }

    private fun consumeQueue(profileId: String, presetId: String, state: AutomationPhotoQueueState, queue: List<String>) {
        if (queue.size <= 1) return
        val newQueues = state.queues.toMutableMap()
        newQueues[presetId] = queue.drop(1)
        prefs.edit().putString("$profileId:photo_queues", PhotoQueueCodec.encode(AutomationPhotoQueueState(newQueues))).apply()
    }

    private data class HttpExchange(val code: Int, val json: JSONObject?)

    private fun postJson(urlString: String, body: JSONObject): HttpExchange {
        var connection: HttpURLConnection? = null
        return runCatching {
            connection = URL(urlString).openConnection() as HttpURLConnection
            connection!!.requestMethod = "POST"
            connection!!.connectTimeout = 20_000
            connection!!.readTimeout = 20_000
            connection!!.doOutput = true
            connection!!.setRequestProperty("Content-Type", "application/json")
            connection!!.setRequestProperty("Accept", "application/json")
            connection!!.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection!!.responseCode
            val stream = if (code in 200..299) connection!!.inputStream else connection!!.errorStream
            val text = stream?.let { BufferedReader(it.reader()).use(BufferedReader::readText) }.orEmpty()
            HttpExchange(code, runCatching { JSONObject(text) }.getOrNull())
        }.getOrElse { HttpExchange(0, null) }.also { connection?.disconnect() }
    }

    private fun appendLog(
        profileId: String,
        preset: AutomationPreset,
        dateKey: String,
        status: String,
        message: String,
        reasonCode: String,
        errorClass: String = "",
        httpStatus: Int = 0,
    ) {
        val key = "$profileId:automation_logs"
        val logs = AutomationLogCodec.decode(prefs.getString(key, null)).toMutableList()
        val now = System.currentTimeMillis()
        val entry = AutomationLogEntry(
            id = "alog_android_${now}_${UUID.randomUUID().toString().take(8)}",
            presetId = preset.id,
            presetNama = preset.nama,
            waktu = now,
            status = status,
            pesan = message,
            source = "scheduler",
            dateWib = dateKey,
            plannedAt = now,
            firedAt = now,
            finishedAt = System.currentTimeMillis(),
            latLong = prefs.getString("active_last_latlong", null)?.trim().orEmpty(),
            workcodeId = preset.workcodeId,
            attempt = 1,
            reasonCode = reasonCode,
            errorClass = errorClass,
            httpStatus = httpStatus,
            correlationId = "run_android_${UUID.randomUUID()}",
        )
        logs.add(0, entry)
        val capped = logs.take(120)
        prefs.edit().putString(key, AutomationLogCodec.encode(capped)).apply()
    }

    private fun notify(status: String, presetName: String, message: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val importance = if (status in setOf("FAILED", "AMBIGUOUS")) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel("tetuko_automation", "TETUKO Otomasi", importance).apply {
            description = "Status eksekusi automasi presensi TETUKO"
        }
        manager.createNotificationChannel(channel)
        val title = when (status) {
            "SUCCESS" -> "Automasi berhasil"
            "SKIPPED" -> "Automasi dilewati"
            "RETRYABLE" -> "Automasi dijadwalkan ulang"
            "AMBIGUOUS" -> "Perlu verifikasi presensi"
            "PENDING" -> "Automasi berjalan"
            else -> "Automasi gagal"
        }
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val contentIntent = PendingIntent.getActivity(context, 1001, openIntent, flags)
        val notification = NotificationCompat.Builder(context, "tetuko_automation")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("$presetName: ${message.take(180)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$presetName: $message"))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(if (status in setOf("FAILED", "AMBIGUOUS")) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
        }
    }
}

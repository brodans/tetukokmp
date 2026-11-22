package id.tetuko.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import id.tetuko.app.ui.components.ModalScrollColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.tetuko.app.service.AppServices
import id.tetuko.app.service.ImageCropRect
import id.tetuko.core.data.rpc.RpcObjects
import id.tetuko.core.domain.model.AttendanceMode
import id.tetuko.core.domain.model.ServerProfile
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.io.encoding.ExperimentalEncodingApi

private val PASSWORD_POLICY = Regex("""^(?=.*[A-Z])(?=.*[0-9])(?=.*[^A-Za-z0-9]).{8,}$""")

/**
 * Akun & keamanan:
 * - BKD (RPC): ubah password via `update_profil` (password_lama, password).
 * - Ponorogo: ubah password via `/login/Ubah_PasswordMobile` (proxy).
 * - Ponorogo: pembaruan IMEI/Device ID via endpoint `/login/update_imei`.
 * Konfirmasi dan kebijakan password dasar divalidasi sisi klien.
 */
@Composable
fun ChangePasswordDialog(
    profile: ServerProfile,
    allowEditCreds: Boolean,
    onDismiss: () -> Unit,
) {
    var lama by remember { mutableStateOf("") }
    var baru by remember { mutableStateOf("") }
    var konfirmasi by remember { mutableStateOf("") }
    var showLama by remember { mutableStateOf(false) }
    var showBaru by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    // IMEI update (BKD / best-effort)
    var imeiUser by remember(profile.id) { mutableStateOf("") }
    var imeiPass by remember { mutableStateOf("") }
    var imeiValue by remember(profile.id) { mutableStateOf(AppServices.serverContext.value.imei) }
    var imeiBusy by remember { mutableStateOf(false) }
    var imeiMessage by remember { mutableStateOf<String?>(null) }
    var imeiIsError by remember { mutableStateOf(false) }
    var showImeiPass by remember { mutableStateOf(false) }

    // Foto profil (BKD RPC: update_foto)
    var photoFile by remember(profile.id) { mutableStateOf<id.tetuko.app.service.PickedFile?>(null) }
    var photoCropSource by remember(profile.id) { mutableStateOf<id.tetuko.app.service.PickedFile?>(null) }
    var confirmDeletePhotoOpen by remember(profile.id) { mutableStateOf(false) }
    var photoBusy by remember { mutableStateOf(false) }
    var photoMessage by remember { mutableStateOf<String?>(null) }
    var photoIsError by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val isPonorogo = profile.mode == AttendanceMode.PONOROGO_ABSENSI

    fun fail(text: String) { message = text; isError = true }
    fun imeiFail(text: String) { imeiMessage = text; imeiIsError = true }

    fun submit() {
        if (!allowEditCreds) { fail("Perubahan kredensial dikunci administrator."); return }
        if (lama.isBlank() || baru.isBlank() || konfirmasi.isBlank()) { fail("Semua kolom password wajib diisi."); return }
        if (baru != konfirmasi) { fail("Konfirmasi password baru tidak cocok."); return }
        if (!isPonorogo && !PASSWORD_POLICY.matches(baru)) {
            fail("Password baru minimal 8 karakter serta mengandung huruf besar, angka, dan karakter khusus.")
            return
        }
        if (isPonorogo && baru.length < 4) {
            fail("Password baru minimal 4 karakter.")
            return
        }
        busy = true
        scope.launch {
            try {
                if (isPonorogo) {
                    val pon = AppServices.ponorogoProfile
                    val idPegawai = pon?.get("id_pegawai")?.let { (it as? JsonPrimitive)?.contentOrNull }
                        ?: pon?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }
                        ?: pon?.get("nip")?.let { (it as? JsonPrimitive)?.contentOrNull }
                        .orEmpty()
                    if (idPegawai.isBlank()) {
                        fail("ID pegawai tidak tersedia. Silakan login ulang mode Ponorogo.")
                        return@launch
                    }
                    val res = AppServices.ponorogoClient.send(
                        "/login/Ubah_PasswordMobile",
                        buildJsonObject {
                            put("id_pegawai", idPegawai)
                            put("password_sekarang", lama)
                            put("password_baru", baru)
                        },
                    ) as? JsonObject
                    val ok = (res?.get("success") as? JsonPrimitive)?.booleanOrNull
                        ?: (res?.get("success") as? JsonPrimitive)?.contentOrNull?.equals("true", true)
                        ?: false
                    val serverMessage = (res?.get("message") as? JsonPrimitive)?.contentOrNull
                    if (ok) {
                        message = serverMessage?.takeIf { it.isNotBlank() } ?: "Password berhasil diubah."
                        isError = false
                        lama = ""; baru = ""; konfirmasi = ""
                    } else {
                        fail(serverMessage?.takeIf { it.isNotBlank() } ?: "Gagal mengubah password. Periksa password lama dan coba lagi.")
                    }
                } else {
                    val result = AppServices.rpcClient.callResultElement(
                        RpcObjects.UPDATE_PROFIL,
                        buildJsonObject {
                            put("password_lama", lama)
                            put("password", baru)
                        },
                        AppServices.serverContext.value,
                        profile.id,
                    )
                    val ok = when (result) {
                        is JsonPrimitive -> result.booleanOrNull ?: (result.contentOrNull == "true")
                        is JsonObject -> {
                            val inner = result["result"] as? JsonPrimitive
                            (inner?.booleanOrNull ?: (inner?.contentOrNull == "true"))
                        }
                        else -> false
                    }
                    val serverMessage = (result as? JsonObject)?.get("message")?.let { it as? JsonPrimitive }?.contentOrNull
                    if (ok) {
                        message = serverMessage?.takeIf { it.isNotBlank() } ?: "Password berhasil diubah."
                        isError = false
                        lama = ""; baru = ""; konfirmasi = ""
                    } else {
                        fail(serverMessage?.takeIf { it.isNotBlank() } ?: "Password lama tidak sesuai atau password ditolak server.")
                    }
                }
            } catch (e: Exception) {
                fail("Gagal mengubah password: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    fun submitImei() {
        if (!allowEditCreds) { imeiFail("Perubahan kredensial/perangkat dikunci administrator."); return }
        val user = imeiUser.trim()
        val dev = imeiValue.trim()
        if (user.isBlank()) { imeiFail("Username/NIP wajib diisi."); return }
        if (imeiPass.isBlank()) { imeiFail("Password wajib diisi."); return }
        if (dev.isBlank()) { imeiFail("IMEI/Device ID tidak boleh kosong."); return }
        imeiBusy = true
        imeiMessage = null
        scope.launch {
            try {
                // Best-effort: beberapa backend menerima update_imei via proxy endpoint; respons bisa bervariasi.
                val res = AppServices.ponorogoClient.send(
                    "/login/update_imei",
                    buildJsonObject {
                        put("username", user)
                        put("password", imeiPass)
                        put("imei", dev)
                    },
                ) as? JsonObject
                val successFlag = (res?.get("success") as? JsonPrimitive)?.booleanOrNull
                    ?: (res?.get("success") as? JsonPrimitive)?.contentOrNull?.equals("true", true)
                    ?: (res?.get("status") as? JsonPrimitive)?.contentOrNull?.equals("success", true)
                    ?: (res?.get("code") as? JsonPrimitive)?.contentOrNull?.let { it == "200" }
                    ?: false
                val serverMessage = (res?.get("message") as? JsonPrimitive)?.contentOrNull
                    ?: (res?.get("error") as? JsonPrimitive)?.contentOrNull
                if (successFlag) {
                    AppServices.updateServerContext(imei = dev)
                    imeiMessage = serverMessage?.takeIf { it.isNotBlank() } ?: "IMEI/Device ID berhasil diperbarui."
                    imeiIsError = false
                    imeiPass = ""
                } else {
                    imeiFail(serverMessage?.takeIf { it.isNotBlank() } ?: "Gagal memperbarui IMEI/Device ID. Periksa kredensial dan dukungan endpoint server.")
                }
            } catch (e: Exception) {
                imeiFail("Gagal memperbarui IMEI/Device ID: ${e.message}")
            } finally {
                imeiBusy = false
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun submitPhoto() {
        if (!allowEditCreds) { photoMessage = "Perubahan profil dikunci administrator."; photoIsError = true; return }
        val file = photoFile
        if (file == null) { photoMessage = "Pilih foto profil terlebih dahulu."; photoIsError = true; return }
        if (file.bytes.size > 5 * 1024 * 1024) { photoMessage = "Ukuran foto terlalu besar (maks 5 MB)."; photoIsError = true; return }
        photoBusy = true
        photoMessage = null
        scope.launch {
            try {
                val b64 = kotlin.io.encoding.Base64.encode(file.bytes)
                val imageName = file.name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "foto.png" }
                if (isPonorogo) {
                    val pon = AppServices.ponorogoProfile
                    val pegawaiId = pon?.get("id_pegawai")?.let { (it as? JsonPrimitive)?.contentOrNull }
                        ?: pon?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }
                        ?: pon?.get("nip")?.let { (it as? JsonPrimitive)?.contentOrNull }
                        .orEmpty()
                    if (pegawaiId.isBlank()) {
                        photoMessage = "ID pegawai tidak tersedia. Login ulang mode Ponorogo diperlukan."
                        photoIsError = true
                        photoBusy = false
                        return@launch
                    }
                    val response = AppServices.ponorogoClient.send(
                        "/login/do_Simpan_Ubah_Foto",
                        buildJsonObject {
                            put("id_pegawai", pegawaiId)
                            put("foto", b64)
                        },
                    ) as? JsonObject
                    val ok = (response?.get("success") as? JsonPrimitive)?.booleanOrNull
                        ?: (response?.get("success") as? JsonPrimitive)?.contentOrNull?.equals("true", true)
                        ?: false
                    if (!ok) {
                        photoMessage = (response?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Gagal memperbarui foto profil."
                        photoIsError = true
                        photoBusy = false
                        return@launch
                    }
                    val updatedPhoto = (response?.get("foto") as? JsonPrimitive)?.contentOrNull
                        ?: (response?.get("path") as? JsonPrimitive)?.contentOrNull
                        ?: "data:image/jpeg;base64,$b64"
                    AppServices.ponorogoProfile = pon?.let { JsonObject(it + ("foto" to JsonPrimitive(updatedPhoto))) }
                    photoMessage = (response?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Foto profil berhasil diperbarui."
                    photoIsError = false
                    photoFile = null
                } else {
                    val res = AppServices.rpcClient.callRaw(
                        RpcObjects.UPDATE_FOTO,
                        buildJsonObject {
                            put("image", imageName)
                            put("image64", b64)
                        },
                        AppServices.serverContext.value,
                        profile.id,
                    )
                    val obj = res.result as? JsonObject
                    val ok = res.error == null && (
                        (obj?.get("result") as? JsonPrimitive)?.booleanOrNull == true ||
                            (obj?.get("url") as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true ||
                            (obj?.get("message") as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
                        )
                    if (ok) {
                        val msg = (obj?.get("message") as? JsonPrimitive)?.contentOrNull
                            ?: (obj?.get("url") as? JsonPrimitive)?.contentOrNull?.let { "Foto profil diperbarui." }
                            ?: "Foto profil berhasil diperbarui."
                        photoMessage = msg
                        photoIsError = false
                        photoFile = null
                    } else {
                        photoMessage = res.error
                            ?: (obj?.get("message") as? JsonPrimitive)?.contentOrNull
                            ?: "Gagal memperbarui foto profil."
                        photoIsError = true
                    }
                }
            } catch (e: Exception) {
                photoMessage = "Gagal memperbarui foto profil: ${e.message}"
                photoIsError = true
            } finally {
                photoBusy = false
            }
        }
    }

    fun deletePonorogoPhoto() {
        if (!isPonorogo) return
        if (!allowEditCreds) { photoMessage = "Perubahan profil dikunci administrator."; photoIsError = true; return }
        val pon = AppServices.ponorogoProfile
        val pegawaiId = pon?.get("id_pegawai")?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: pon?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: pon?.get("nip")?.let { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        if (pegawaiId.isBlank()) { photoMessage = "ID pegawai tidak tersedia. Login ulang mode Ponorogo diperlukan."; photoIsError = true; return }
        photoBusy = true
        photoMessage = null
        scope.launch {
            try {
                val response = AppServices.ponorogoClient.send(
                    "/login/do_Simpan_Ubah_Foto",
                    buildJsonObject {
                        put("id_pegawai", pegawaiId)
                        put("foto", "null")
                    },
                ) as? JsonObject
                val ok = (response?.get("success") as? JsonPrimitive)?.booleanOrNull
                    ?: (response?.get("success") as? JsonPrimitive)?.contentOrNull?.equals("true", true)
                    ?: false
                if (!ok) {
                    photoMessage = (response?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Gagal menghapus foto profil."
                    photoIsError = true
                    photoBusy = false
                    return@launch
                }
                AppServices.ponorogoProfile = pon?.let { JsonObject(it + ("foto" to JsonPrimitive(""))) }
                photoMessage = (response?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Foto profil berhasil dihapus."
                photoIsError = false
            } catch (e: Exception) {
                photoMessage = e.message ?: "Gagal menghapus foto profil."
                photoIsError = true
            } finally {
                photoBusy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy && !imeiBusy) onDismiss() },
        icon = { Icon(Icons.Filled.Lock, contentDescription = "Keamanan akun") },
        title = { Text("Akun & Keamanan") },
        text = {
            ModalScrollColumn(maxHeightDp = 560, itemSpacing = 10) {
                Text(profile.label, fontWeight = FontWeight.SemiBold)
                Text("${profile.nama} • ${profile.mode.name}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                if (!allowEditCreds) {
                    Surface(color = Color(0xFFFEF3C7), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            "Perubahan kredensial/perangkat dikunci oleh administrator untuk profil ini.",
                            modifier = Modifier.padding(10.dp),
                            fontSize = 12.sp,
                            color = Color(0xFF92400E),
                        )
                    }
                }
                Text("Ubah Password", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                if (isPonorogo) {
                    Text(
                        "Mode Ponorogo: ubah password melalui endpoint server (/login/Ubah_PasswordMobile).",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Mode BKD/RPC: ubah password server pusat (update_profil).",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val canEditPassword = allowEditCreds && !busy && !imeiBusy && !photoBusy
                PasswordField("Password lama / saat ini", lama, { lama = it; if (message != null) message = null }, showLama, { showLama = !showLama }, canEditPassword)
                PasswordField("Password baru", baru, { baru = it; if (message != null) message = null }, showBaru, { showBaru = !showBaru }, canEditPassword)
                PasswordField("Konfirmasi password baru", konfirmasi, { konfirmasi = it; if (message != null) message = null }, showBaru, { showBaru = !showBaru }, canEditPassword)
                Text(
                    if (isPonorogo) "Kebijakan minimum: 4 karakter. Pastikan password baru aman."
                    else "Kebijakan: minimal 8 karakter, mengandung huruf besar, angka, dan karakter khusus.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                message?.let {
                    Surface(
                        color = if (isError) Color(0xFFFEE2E2) else Color(0xFFDCFCE7),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            it,
                            modifier = Modifier.padding(10.dp),
                            fontSize = 12.sp,
                            color = if (isError) Color(0xFF991B1B) else Color(0xFF166534),
                        )
                    }
                }

                if (isPonorogo) {
                    HorizontalDivider()
                    Text("Perbarui IMEI / Device ID", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(
                        "Gunakan jika akun terikat perangkat. Masukkan kredensial Ponorogo yang valid untuk memperbarui Device ID.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val canEditDevice = allowEditCreds && !busy && !imeiBusy && !photoBusy
                    OutlinedTextField(
                        value = imeiUser,
                        onValueChange = { imeiUser = it; if (imeiMessage != null) imeiMessage = null },
                        label = { Text("Username / NIP") },
                        singleLine = true,
                        enabled = canEditDevice,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PasswordField("Password akun", imeiPass, { imeiPass = it; if (imeiMessage != null) imeiMessage = null }, showImeiPass, { showImeiPass = !showImeiPass }, canEditDevice)
                    OutlinedTextField(
                        value = imeiValue,
                        onValueChange = { imeiValue = it; if (imeiMessage != null) imeiMessage = null },
                        label = { Text("IMEI / Device ID baru") },
                        singleLine = true,
                        enabled = canEditDevice,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { submitImei() }, enabled = canEditDevice) {
                            Text(if (imeiBusy) "Memproses..." else "Update IMEI")
                        }
                    }
                    imeiMessage?.let {
                        Surface(
                            color = if (imeiIsError) Color(0xFFFEE2E2) else Color(0xFFDCFCE7),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                it,
                                modifier = Modifier.padding(10.dp),
                                fontSize = 12.sp,
                                color = if (imeiIsError) Color(0xFF991B1B) else Color(0xFF166534),
                            )
                        }
                    }

                }
                HorizontalDivider()
                Text("Update Foto Profil", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    if (isPonorogo) "Foto akan dipangkas persegi sebelum dikirim ke akun mobile Ponorogo."
                    else "Foto akan dipangkas persegi sebelum diunggah ke profil server BKD. Maksimal 5 MB.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val canEditPhoto = allowEditCreds && !busy && !imeiBusy && !photoBusy
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (!AppServices.hasFilePicker) {
                                photoMessage = "Pemilih file tidak tersedia di platform ini."
                                photoIsError = true
                            } else {
                                AppServices.requestFilePicker("Pilih foto profil", listOf("jpg", "jpeg", "png")) { picked ->
                                    if (picked != null) { photoCropSource = picked; photoMessage = null; photoIsError = false }
                                }
                            }
                        },
                        enabled = canEditPhoto,
                    ) { Text(if (photoFile == null) "Pilih Foto" else "Ganti Foto") }
                    photoFile?.let {
                        Text(it.name, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        TextButton(onClick = { photoFile = null; if (photoMessage != null) photoMessage = null }, enabled = canEditPhoto) { Text("Hapus") }
                    } ?: Text("Belum ada foto dipilih.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { submitPhoto() }, enabled = canEditPhoto && photoFile != null) {
                        Text(if (photoBusy) "Mengunggah..." else "Pangkas & Unggah Foto")
                    }
                    val currentPhoto = AppServices.ponorogoProfile?.let { pon ->
                        listOf("foto", "profilePic", "foto_profil").firstNotNullOfOrNull { key ->
                            (pon[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
                        }
                    }
                    if (isPonorogo && currentPhoto != null) {
                        TextButton(onClick = { confirmDeletePhotoOpen = true }, enabled = canEditPhoto) {
                            Text("Hapus Foto Profil", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                photoMessage?.let {
                    Surface(
                        color = if (photoIsError) Color(0xFFFEE2E2) else Color(0xFFDCFCE7),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(it, modifier = Modifier.padding(10.dp), fontSize = 12.sp, color = if (photoIsError) Color(0xFF991B1B) else Color(0xFF166534))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { submit() }, enabled = allowEditCreds && !busy && !imeiBusy && !photoBusy) {
                Text(if (busy) "Menyimpan..." else "Simpan Password")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !busy && !imeiBusy && !photoBusy) { Text("Tutup") }
        },
    )
    if (confirmDeletePhotoOpen) {
        AlertDialog(
            onDismissRequest = { if (!photoBusy) confirmDeletePhotoOpen = false },
            title = { Text("Hapus Foto Profil?") },
            text = { Text("Foto profil akun Ponorogo akan dihapus dari server.") },
            confirmButton = {
                Button(
                    onClick = { confirmDeletePhotoOpen = false; deletePonorogoPhoto() },
                    enabled = !photoBusy,
                ) { Text(if (photoBusy) "Menghapus..." else "Hapus") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmDeletePhotoOpen = false }, enabled = !photoBusy) { Text("Batal") }
            },
        )
    }
    photoCropSource?.let { source ->
        ImageCropDialog(
            file = source,
            onDismiss = { photoCropSource = null },
            onCropped = { cropped ->
                photoFile = cropped
                photoCropSource = null
                photoMessage = "Foto berhasil dipangkas."
                photoIsError = false
            },
        )
    }
}

@Composable
private fun PasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = onToggleVisible) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Sembunyikan password" else "Tampilkan password",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

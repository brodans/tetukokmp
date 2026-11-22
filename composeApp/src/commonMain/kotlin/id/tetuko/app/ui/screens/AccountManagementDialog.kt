package id.tetuko.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import id.tetuko.app.ui.components.ModalScrollColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.tetuko.app.service.AppServices
import id.tetuko.app.ui.AppLoginResult
import id.tetuko.core.domain.model.AppNavigation
import id.tetuko.core.domain.model.AppPermissions
import id.tetuko.core.domain.model.AppRole
import id.tetuko.core.domain.model.DefaultProfiles
import id.tetuko.core.domain.model.LocalServerCredential

/**
 * Local account/permission management (desktop-first feasible adapter for Tauri account parity).
 *
 * Provides account CRUD (create/edit/delete), role/permission assignment (profiles, per-profile
 * menu allow-list, granular flags), per-account server credentials, and self password change.
 * Data is persisted by the platform store (desktop: encrypted local store with bcrypt hashes).
 * This is intentionally local-only; it is not cloud/Firestore multi-user sync.
 */
@Composable
fun AccountManagementDialog(
    currentAccountId: String?,
    isAdmin: Boolean,
    onDismiss: () -> Unit,
) {
    val store = AppServices.localAccountStore
    var tab by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    val accounts = remember(refresh) { store?.listAccounts().orEmpty() }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.AdminPanelSettings, contentDescription = "Manajemen akun") },
        title = { Text("Manajemen Akun & Akses") },
        text = {
            if (store == null) {
                Text(
                    "Adapter akun lokal tidak tersedia di platform ini.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column {
                    ScrollableTabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Akun") })
                        Tab(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            enabled = isAdmin,
                            text = { Text("Kredensial Server") },
                        )
                        Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Password Saya") })
                        Tab(
                            selected = tab == 3,
                            onClick = { tab = 3 },
                            enabled = isAdmin,
                            text = { Text("Admin Aplikasi") },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    when (tab) {
                        0 -> AccountUsersTab(
                            store = store,
                            accounts = accounts,
                            isAdmin = isAdmin,
                            onChanged = { refresh++ },
                        )
                        1 -> AccountServerCredsTab(
                            store = store,
                            accounts = accounts,
                            onChanged = { refresh++ },
                        )
                        2 -> AccountSelfPasswordTab(store = store, accountId = currentAccountId)
                        3 -> AccountAdminTab(store = store, accounts = accounts)
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Tutup")
            }
        },
    )
}

@Composable
private fun AccountAdminTab(
    store: id.tetuko.app.service.LocalAccountStore,
    accounts: List<id.tetuko.core.domain.model.LocalAppAccountSafe>,
) {
    val admin = accounts.firstOrNull { it.role == AppRole.ADMIN }
    var username by remember(admin?.id, admin?.username) { mutableStateOf(admin?.username.orEmpty()) }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    fun save() {
        val target = admin ?: run { message = "Akun admin tidak ditemukan."; isError = true; return }
        val normalizedUsername = username.trim().lowercase()
        if (!Regex("^[a-z0-9_.-]{2,32}$").matches(normalizedUsername)) {
            message = "Username admin harus 2–32 karakter: huruf, angka, titik, garis bawah, atau tanda hubung."
            isError = true
            return
        }
        if (currentPassword.isBlank()) { message = "Password saat ini wajib diisi untuk verifikasi."; isError = true; return }
        if (newPassword.isNotBlank() && newPassword.length < 4) { message = "Password baru minimal 4 karakter."; isError = true; return }
        if (newPassword.isNotBlank() && newPassword != confirmPassword) { message = "Konfirmasi password baru tidak cocok."; isError = true; return }
        if (normalizedUsername == target.username && newPassword.isBlank()) { message = "Tidak ada perubahan untuk disimpan."; isError = true; return }
        val authentication = runCatching { store.authenticate(target.username, currentPassword) }
        if (authentication.isFailure) {
            message = authentication.exceptionOrNull()?.message ?: "Gagal memverifikasi akun admin."
            isError = true
            return
        }
        if (authentication.getOrNull() == null) {
            message = "Password verifikasi salah."
            isError = true
            return
        }
        val result = runCatching {
            store.updateAccount(
                id = target.id,
                username = normalizedUsername.takeIf { it != target.username },
                newPassword = newPassword.ifBlank { null },
            )
        }
        if (result.isSuccess) {
            username = normalizedUsername
            currentPassword = ""
            newPassword = ""
            confirmPassword = ""
            message = "Akun admin berhasil diperbarui."
            isError = false
        } else {
            message = result.exceptionOrNull()?.message ?: "Gagal memperbarui akun admin."
            isError = true
        }
    }

    ModalScrollColumn(maxHeightDp = 520, itemSpacing = 10) {
        Text("Akun Admin Aplikasi", fontWeight = FontWeight.SemiBold)
        Text(
            "Username saat ini: @${admin?.username ?: "tidak tersedia"}. Perubahan akun mengikuti penyimpanan yang sedang aktif.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username admin baru") },
            singleLine = true,
            enabled = admin != null,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = currentPassword,
            onValueChange = { currentPassword = it },
            label = { Text("Password verifikasi saat ini") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            enabled = admin != null,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = newPassword,
            onValueChange = { newPassword = it },
            label = { Text("Password admin baru (opsional)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            enabled = admin != null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (newPassword.isNotBlank()) {
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                label = { Text("Konfirmasi password admin baru") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                enabled = admin != null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        message?.let {
            Surface(
                color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp),
            ) { Text(it, modifier = Modifier.padding(10.dp), fontSize = 12.sp) }
        }
        Button(onClick = { save() }, enabled = admin != null) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Simpan Akun Admin")
        }
    }
}

@Composable
private fun AccountUsersTab(
    store: id.tetuko.app.service.LocalAccountStore,
    accounts: List<id.tetuko.core.domain.model.LocalAppAccountSafe>,
    isAdmin: Boolean,
    onChanged: () -> Unit,
) {
    var mode by remember { mutableStateOf("idle") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(AppRole.USER) }
    var perms by remember { mutableStateOf(AppPermissions.defaultUser()) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    fun reset() {
        mode = "idle"; editingId = null; username = ""; password = ""; confirm = ""
        role = AppRole.USER; perms = AppPermissions.defaultUser(); message = null; isError = false
    }

    fun beginEdit(a: id.tetuko.core.domain.model.LocalAppAccountSafe) {
        mode = "edit"; editingId = a.id; username = a.username; password = ""; confirm = ""
        role = a.role; perms = a.permissions; message = null; isError = false
    }

    fun save() {
        if (busy) return
        val u = username.trim().lowercase().replace(Regex("\\s+"), "")
        if (u.isBlank()) { message = "Username wajib diisi."; isError = true; return }
        if (!Regex("^[a-z0-9_.-]+$").matches(u)) {
            message = "Username hanya boleh huruf kecil, angka, titik, garis bawah, dan tanda hubung."
            isError = true; return
        }
        val isCreate = mode == "create"
        if (isCreate && password.isBlank()) { message = "Password wajib diisi."; isError = true; return }
        if (password.isNotBlank() && password.length < 4) { message = "Password minimal 4 karakter."; isError = true; return }
        if ((isCreate || password.isNotBlank()) && password != confirm) {
            message = "Konfirmasi password tidak cocok."; isError = true; return
        }
        if (role == AppRole.USER && listOf(perms.profiles.jatim, perms.profiles.madiun, perms.profiles.ponorogo).none { it }) {
            message = "Pilih minimal satu akses profil server."; isError = true; return
        }
        busy = true; message = null
        val ok = runCatching {
            if (isCreate) {
                store.createAccount(u, password, role, perms)
            } else {
                store.updateAccount(
                    id = editingId ?: error("Akun tidak ditemukan."),
                    username = u,
                    newPassword = password.ifBlank { null },
                    role = role,
                    permissions = perms,
                )
            }
        }
        busy = false
        if (ok.isFailure) {
            message = ok.exceptionOrNull()?.message ?: "Gagal menyimpan akun."; isError = true
        } else {
            message = if (isCreate) "Akun berhasil dibuat." else "Akun berhasil diperbarui."
            isError = false
            reset(); onChanged()
        }
    }

    ModalScrollColumn(maxHeightDp = 520, itemSpacing = 10) {
        Text(
            "Manajemen akun aplikasi (lokal). Peran/izin mengatur akses profil, menu, dan aksi sensitif.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!isAdmin) {
            Text(
                "Hanya admin yang dapat menambah, mengubah, atau menghapus akun.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
            )
        }
        message?.let {
            Surface(
                color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp),
            ) { Text(it, modifier = Modifier.padding(10.dp), fontSize = 12.sp) }
        }

        if (mode == "idle") {
            if (isAdmin) {
                Button(onClick = { mode = "create"; role = AppRole.USER; perms = AppPermissions.defaultUser() }) {
                    Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp)); Text("Tambah Akun")
                }
            }
            accounts.forEach { a ->
                Card(shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("@${a.username}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            AssistChip(onClick = {}, enabled = false, label = { Text(if (a.role == AppRole.ADMIN) "Admin" else "User") })
                        }
                        Text(
                            "Profil: ${
                                listOf("jatim", "madiun", "ponorogo").filter { a.permissions.canAccessProfile(it) }.joinToString(", ").ifBlank { "Tidak ada" }
                            }",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (isAdmin) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { beginEdit(a) }) { Text("Ubah") }
                                if (confirmDelete == a.id) {
                                    Button(onClick = {
                                        val r = runCatching { store.deleteAccount(a.id) }
                                        if (r.isFailure) {
                                            message = r.exceptionOrNull()?.message ?: "Gagal menghapus."; isError = true
                                        } else {
                                            message = "Akun dihapus."; isError = false; confirmDelete = null; onChanged()
                                        }
                                    }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Konfirmasi Hapus") }
                                    OutlinedButton(onClick = { confirmDelete = null }) { Text("Batal") }
                                } else {
                                    OutlinedButton(onClick = { confirmDelete = a.id }) { Text("Hapus") }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (mode == "create") "Akun Baru" else "Ubah Akun", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        singleLine = true,
                        enabled = isAdmin,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(if (mode == "create") "Password" else "Password Baru (kosongkan jika tidak diubah)") },
                        singleLine = true,
                        enabled = isAdmin,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text("Konfirmasi Password") },
                        singleLine = true,
                        enabled = isAdmin,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = role == AppRole.ADMIN, onClick = {
                            role = AppRole.ADMIN; perms = AppPermissions.AdminFull
                        }, label = { Text("Admin") }, enabled = isAdmin)
                        FilterChip(selected = role == AppRole.USER, onClick = {
                            role = AppRole.USER; perms = AppPermissions.defaultUser()
                        }, label = { Text("User") }, enabled = isAdmin)
                    }
                    AccountPermissionEditor(role = role, perms = perms, enabled = isAdmin, onPermsChange = { perms = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { save() }, enabled = isAdmin && !busy) { Text(if (busy) "Menyimpan..." else "Simpan") }
                        OutlinedButton(onClick = { reset() }) { Text("Batal") }
                    }
                }
            }
        }
    }
}

/** Compact permission editor reused by the account form. */
@Composable
private fun AccountPermissionEditor(
    role: AppRole,
    perms: AppPermissions,
    enabled: Boolean,
    onPermsChange: (AppPermissions) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Akses Profil & Menu", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        if (role == AppRole.ADMIN) {
            Text(
                "Admin memiliki akses penuh ke seluruh profil dan menu.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        DefaultProfiles.all.forEach { profile ->
            val profileAllowed = perms.profiles.allowed(profile.id)
            Card(shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(profile.label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), fontSize = 13.sp)
                        Switch(
                            checked = profileAllowed,
                            enabled = enabled,
                            onCheckedChange = {
                                onPermsChange(perms.copy(profiles = perms.profiles.copy(
                                    jatim = if (profile.id == "jatim") it else perms.profiles.jatim,
                                    madiun = if (profile.id == "madiun") it else perms.profiles.madiun,
                                    ponorogo = if (profile.id == "ponorogo") it else perms.profiles.ponorogo,
                                )))
                            },
                        )
                    }
                    if (profileAllowed) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = {
                                val all = AppNavigation.pagesFor(profile).map { it.id }.toSet()
                                val next = perms.allowedMenuIdsByProfile?.toMutableMap() ?: mutableMapOf()
                                next[profile.id] = all
                                onPermsChange(perms.copy(allowedMenuIds = null, allowedMenuIdsByProfile = next))
                            }, enabled = enabled) { Text("Semua menu") }
                            TextButton(onClick = {
                                val next = perms.allowedMenuIdsByProfile?.toMutableMap() ?: mutableMapOf()
                                next[profile.id] = setOf("tabBeranda")
                                onPermsChange(perms.copy(allowedMenuIds = null, allowedMenuIdsByProfile = next))
                            }, enabled = enabled) { Text("Hanya Beranda") }
                        }
                        val allowed = perms.effectiveMenuAllowList(profile.id)
                        AppNavigation.pagesFor(profile).forEach { page ->
                            val checked = page.id == "tabBeranda" || allowed?.contains(page.id) != false
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = checked,
                                    enabled = enabled && page.id != "tabBeranda",
                                    onCheckedChange = { on ->
                                        val nextSet = (allowed ?: AppNavigation.pagesFor(profile).map { it.id }.toSet()).toMutableSet()
                                        if (on) nextSet.add(page.id) else nextSet.remove(page.id)
                                        nextSet.add("tabBeranda")
                                        val next = perms.allowedMenuIdsByProfile?.toMutableMap() ?: mutableMapOf()
                                        next[profile.id] = nextSet
                                        onPermsChange(perms.copy(allowedMenuIds = null, allowedMenuIdsByProfile = next))
                                    },
                                )
                                Text(page.label, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
        Text("Izin Fitur / Aksi", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        FlagToggle("Ubah kredensial server", perms.allowEditServerCreds, enabled) {
            onPermsChange(perms.copy(allowEditServerCreds = it))
        }
        FlagToggle("Cari pegawai", perms.allowSearchPegawai, enabled) { onPermsChange(perms.copy(allowSearchPegawai = it)) }
        FlagToggle("Cari/target izin", perms.allowSearchIzin, enabled) { onPermsChange(perms.copy(allowSearchIzin = it)) }
        FlagToggle("Cari instansi/log", perms.allowSearchInstansi, enabled) { onPermsChange(perms.copy(allowSearchInstansi = it)) }
        FlagToggle("Cari database pegawai", perms.allowSearchDatabase, enabled) { onPermsChange(perms.copy(allowSearchDatabase = it)) }
        FlagToggle("Cari produktivitas", perms.allowSearchProduktivitas, enabled) { onPermsChange(perms.copy(allowSearchProduktivitas = it)) }
        FlagToggle("Cari presensi", perms.allowSearchPresensi, enabled) { onPermsChange(perms.copy(allowSearchPresensi = it)) }
        FlagToggle("Cari laporan", perms.allowSearchLaporan, enabled) { onPermsChange(perms.copy(allowSearchLaporan = it)) }
        FlagToggle("Cari UNOR/struktur", perms.allowSearchUnor, enabled) { onPermsChange(perms.copy(allowSearchUnor = it)) }
        FlagToggle("Cari/review produktivitas", perms.allowSearchReview, enabled) { onPermsChange(perms.copy(allowSearchReview = it)) }
        FlagToggle("Kontrol automasi", perms.allowAutomationControl, enabled) { onPermsChange(perms.copy(allowAutomationControl = it)) }
        FlagToggle("Aksi sensitif", perms.allowSensitiveActions, enabled) { onPermsChange(perms.copy(allowSensitiveActions = it)) }
        Text("Sub-fitur Tauri", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        FlagToggle("Pengajuan izin/cuti", perms.tabPengajuanIzin, enabled) { onPermsChange(perms.copy(tabPengajuanIzin = it)) }
        FlagToggle("Presensi terkini", perms.tabLogPresensiTerkini, enabled) { onPermsChange(perms.copy(tabLogPresensiTerkini = it)) }
        FlagToggle("Log presensi lengkap", perms.tabLogLengkap, enabled) { onPermsChange(perms.copy(tabLogLengkap = it)) }
        FlagToggle("Laporan per pegawai", perms.reportPerPegawai, enabled) { onPermsChange(perms.copy(reportPerPegawai = it)) }
        FlagToggle("Laporan aktivitas per pegawai", perms.reportPerPegawaiAktivitas, enabled) { onPermsChange(perms.copy(reportPerPegawaiAktivitas = it)) }
        FlagToggle("Skor produktivitas per instansi", perms.reportSkorPerInstansi, enabled) { onPermsChange(perms.copy(reportSkorPerInstansi = it)) }
        FlagToggle("Aktivitas per instansi", perms.reportAktivitasPerInstansi, enabled) { onPermsChange(perms.copy(reportAktivitasPerInstansi = it)) }
        FlagToggle("Rekap TPP aktivitas", perms.reportRekapTppAktivitas, enabled) { onPermsChange(perms.copy(reportRekapTppAktivitas = it)) }
    }
}

@Composable
private fun FlagToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 12.sp)
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun AccountServerCredsTab(
    store: id.tetuko.app.service.LocalAccountStore,
    accounts: List<id.tetuko.core.domain.model.LocalAppAccountSafe>,
    onChanged: () -> Unit,
) {
    var targetId by remember { mutableStateOf(accounts.firstOrNull()?.id.orEmpty()) }
    LaunchedEffect(accounts) {
        if (accounts.isNotEmpty() && accounts.none { it.id == targetId }) targetId = accounts.first().id
    }
    var refreshForm by remember { mutableIntStateOf(0) }
    val initialAccounts = remember(targetId, refreshForm) {
        DefaultProfiles.all.associate { profile ->
            profile.id to store.getServerCredAccounts(targetId, profile.id)
        }
    }
    var edited by remember(targetId, refreshForm) { mutableStateOf(initialAccounts) }
    var selectedIndices by remember(targetId, refreshForm) {
        mutableStateOf(DefaultProfiles.all.associate { it.id to 0 })
    }
    var activeIndices by remember(targetId, refreshForm) {
        mutableStateOf(DefaultProfiles.all.associate { profile ->
            val active = store.getServerCreds(targetId, profile.id)?.username
            profile.id to (initialAccounts[profile.id].orEmpty().indexOfFirst { it.username.equals(active, true) }.coerceAtLeast(0))
        })
    }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    ModalScrollColumn(maxHeightDp = 520, itemSpacing = 10) {
        Text(
            "Kredensial server disimpan terenkripsi per akun aplikasi. Kredensial ini dipakai sebagai isi awal saat login server.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Akun:", fontSize = 12.sp)
            accounts.forEach { a ->
                FilterChip(
                    selected = targetId == a.id,
                    onClick = { targetId = a.id; message = null },
                    label = { Text("@${a.username}", fontSize = 11.sp) },
                )
            }
        }
        message?.let {
            Surface(
                color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp),
            ) { Text(it, modifier = Modifier.padding(10.dp), fontSize = 12.sp) }
        }
        if (accounts.isEmpty()) {
            Text("Belum ada akun. Buat akun terlebih dahulu pada tab Akun.", fontSize = 12.sp)
        }
        DefaultProfiles.all.forEach { profile ->
            val profileAccounts = edited[profile.id].orEmpty()
            val selectedIndex = selectedIndices[profile.id]?.coerceIn(0, (profileAccounts.size - 1).coerceAtLeast(0)) ?: 0
            val activeIndex = activeIndices[profile.id]?.coerceIn(0, (profileAccounts.size - 1).coerceAtLeast(0)) ?: 0
            val selectedCredential = profileAccounts.getOrNull(selectedIndex)
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(profile.label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    if (profileAccounts.isNotEmpty()) {
                        profileAccounts.forEachIndexed { index, credential ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(
                                    selected = selectedIndex == index,
                                    onClick = { selectedIndices = selectedIndices + (profile.id to index) },
                                    label = { Text(credential.username.ifBlank { "Akun baru ${index + 1}" }, fontSize = 11.sp) },
                                )
                                Spacer(Modifier.width(6.dp))
                                RadioButton(
                                    selected = activeIndex == index,
                                    onClick = { activeIndices = activeIndices + (profile.id to index) },
                                )
                                Text("Aktif", fontSize = 11.sp)
                            }
                        }
                        selectedCredential?.let { credential ->
                            OutlinedTextField(
                                value = credential.username,
                                onValueChange = { value ->
                                    edited = edited + (profile.id to profileAccounts.mapIndexed { i, item -> if (i == selectedIndex) item.copy(username = value) else item })
                                },
                                label = { Text("NIP / Username server") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = credential.password,
                                onValueChange = { value ->
                                    edited = edited + (profile.id to profileAccounts.mapIndexed { i, item -> if (i == selectedIndex) item.copy(password = value) else item })
                                },
                                label = { Text("Password server") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = credential.imei,
                                onValueChange = { value ->
                                    edited = edited + (profile.id to profileAccounts.mapIndexed { i, item -> if (i == selectedIndex) item.copy(imei = value) else item })
                                },
                                label = { Text("IMEI / Device ID (opsional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        Text("Belum ada kredensial untuk profil ini.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val next = profileAccounts + LocalServerCredential()
                            edited = edited + (profile.id to next)
                            selectedIndices = selectedIndices + (profile.id to next.lastIndex)
                        }) {
                            Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp)); Text("Tambah akun")
                        }
                        OutlinedButton(
                            enabled = selectedCredential != null,
                            onClick = {
                                val next = profileAccounts.filterIndexed { index, _ -> index != selectedIndex }
                                edited = edited + (profile.id to next)
                                selectedIndices = selectedIndices + (profile.id to 0)
                                activeIndices = activeIndices + (profile.id to 0)
                            },
                        ) { Text("Hapus akun") }
                        Button(onClick = {
                            if (targetId.isBlank()) { message = "Pilih akun terlebih dahulu."; isError = true; return@Button }
                            val cleanAccounts = profileAccounts.filter { it.username.isNotBlank() }
                            val activeUsername = profileAccounts.getOrNull(activeIndex)?.username
                            val result = runCatching {
                                store.setServerCredAccounts(targetId, profile.id, cleanAccounts, activeUsername)
                            }
                            message = if (result.isSuccess) "Kredensial ${profile.label} tersimpan." else (result.exceptionOrNull()?.message ?: "Gagal menyimpan.")
                            isError = result.isFailure
                            refreshForm++
                            onChanged()
                        }) { Text("Simpan") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountSelfPasswordTab(
    store: id.tetuko.app.service.LocalAccountStore,
    accountId: String?,
) {
    var old by remember { mutableStateOf("") }
    var baru by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    fun submit() {
        val id = accountId?.takeIf { it.isNotBlank() }
        if (id == null) { message = "Akun tidak dikenali. Keluar dan masuk ulang."; isError = true; return }
        if (old.isBlank() || baru.isBlank() || confirm.isBlank()) {
            message = "Semua kolom wajib diisi."; isError = true; return
        }
        if (baru.length < 4) { message = "Password baru minimal 4 karakter."; isError = true; return }
        if (baru != confirm) { message = "Konfirmasi password tidak cocok."; isError = true; return }
        val account = store.getAccount(id)
        if (account == null) { message = "Akun tidak ditemukan."; isError = true; return }
        if (store.authenticate(account.username, old) == null) {
            message = "Password lama salah."; isError = true; return
        }
        val result = runCatching { store.updateAccount(id, newPassword = baru) }
        message = if (result.isSuccess) "Password akun aplikasi berhasil diubah." else (result.exceptionOrNull()?.message ?: "Gagal mengubah password.")
        isError = result.isFailure
        if (result.isSuccess) { old = ""; baru = ""; confirm = "" }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Ubah kata sandi akun aplikasi (bukan kata sandi server presensi).",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(value = old, onValueChange = { old = it }, label = { Text("Password lama") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = baru, onValueChange = { baru = it }, label = { Text("Password baru") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = confirm, onValueChange = { confirm = it }, label = { Text("Konfirmasi password baru") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        message?.let {
            Surface(
                color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp),
            ) { Text(it, modifier = Modifier.padding(10.dp), fontSize = 12.sp) }
        }
        Button(onClick = { submit() }) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp)); Text("Simpan Password")
        }
    }
}

/** Forced password change for the bootstrap admin (default credentials must not persist). */
@Composable
fun ForcePasswordChangeDialog(
    onChanged: (String) -> AppLoginResult,
) {
    var baru by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Filled.AdminPanelSettings, contentDescription = "Keamanan akun") },
        title = { Text("Ubah Password Admin Awal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Akun admin dibuat dengan kata sandi bawaan. Demi keamanan, kata sandi harus diubah sebelum melanjutkan.",
                    fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = baru,
                    onValueChange = { baru = it },
                    label = { Text("Password baru (min. 4 karakter)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("Konfirmasi password baru") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                message?.let {
                    Surface(
                        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                    ) { Text(it, modifier = Modifier.padding(10.dp), fontSize = 12.sp) }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (baru.length < 4) { message = "Password minimal 4 karakter."; isError = true; return@Button }
                if (baru != confirm) { message = "Konfirmasi tidak cocok."; isError = true; return@Button }
                val result = onChanged(baru)
                if (!result.ok) { message = result.message ?: "Gagal mengubah password."; isError = true }
            }) { Text("Simpan & Lanjutkan") }
        },
    )
}

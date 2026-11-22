package id.tetuko.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.tetuko.app.ui.screens.ForcePasswordChangeDialog
import id.tetuko.app.ui.screens.MasukScreen
import id.tetuko.app.ui.screens.ServerHubScreen
import id.tetuko.app.ui.screens.ServerLoginScreen
import id.tetuko.core.domain.model.AppRoute

@Composable
fun TetukoApp(state: AppState = rememberAppState()) {
    TetukoTheme(darkTheme = state.isDarkMode) {
        LaunchedEffect(state.appAuthed) {
            if (state.appAuthed) state.consumeDeferredDeepLink()
        }
        if (state.mustChangeDefaultPassword && state.route !is AppRoute.Masuk) {
            ForcePasswordChangeDialog(onChanged = { state.completeDefaultPasswordChange(it) })
        }
        val allowed = state.allowedProfileIds()
        when (state.route) {
            is AppRoute.Masuk -> {
                MasukScreen(onLogin = { u, p -> state.loginApp(u, p) })
            }
            is AppRoute.Login -> {
                val pending = state.pendingProfile
                when {
                    pending != null && state.permissions.canAccessProfile(pending.id) -> {
                        val assigned = remember(pending.id, state.currentAccountId) { state.serverCredsFor(pending.id) }
                        val assignedAccounts = remember(pending.id, state.currentAccountId) { state.serverCredAccountsFor(pending.id) }
                        ServerLoginScreen(
                            profile = pending,
                            onBack = { state.cancelPendingProfile() },
                            onLogin = { name, nip, imei, username, password ->
                                state.completeServerLogin(name, nip, imei, username, password)
                            },
                            onImeiRecovered = { username, password, imei ->
                                state.saveServerCredentials(pending, username, password, imei)
                            },
                            savedServerAccounts = assignedAccounts,
                            onSelectServerCredential = { state.selectServerCredential(pending.id, assignedAccounts, it.username) },
                            allowEditCreds = state.permissions.allowEditServerCreds,
                            initialUsername = assigned?.username.orEmpty(),
                            initialPassword = assigned?.password.orEmpty(),
                            initialImei = assigned?.imei.orEmpty(),
                        )
                    }
                    pending != null -> {
                        LaunchedEffect(pending.id, state.permissions) {
                            state.cancelPendingProfile()
                        }
                        if (allowed != null && allowed.isEmpty()) {
                            NoAccessFallback(
                                title = "Akses profil tidak tersedia",
                                message = "Akun ini tidak memiliki akses ke profil server mana pun.",
                                onLogout = { state.logoutApp() },
                            )
                        } else {
                            ServerHubScreen(
                                onSelectProfile = { state.chooseProfile(it) },
                                onLogoutApp = { state.logoutApp() },
                                isDark = state.isDarkMode,
                                allowedProfileIds = allowed,
                                canManageAccounts = state.canManageAccounts(),
                                currentAccountId = state.currentAccountId,
                            )
                        }
                    }
                    allowed != null && allowed.isEmpty() -> {
                        NoAccessFallback(
                            title = "Akses profil tidak tersedia",
                            message = "Akun ini tidak memiliki akses ke profil server mana pun. Hubungi administrator untuk mengatur izin.",
                            onLogout = { state.logoutApp() },
                        )
                    }
                    else -> {
                        ServerHubScreen(
                            onSelectProfile = { state.chooseProfile(it) },
                            onLogoutApp = { state.logoutApp() },
                            isDark = state.isDarkMode,
                            allowedProfileIds = allowed,
                            canManageAccounts = state.canManageAccounts(),
                            currentAccountId = state.currentAccountId,
                        )
                    }
                }
            }
            is AppRoute.App -> {
                val profile = state.currentProfile
                when {
                    profile != null && state.permissions.canAccessProfile(profile.id) -> {
                        // Route derivation sanitizes active page; AppShell renders within allowed menus.
                        AppShell(
                            state = state,
                            profile = profile,
                            onLogoutRequest = { state.showLogoutConfirm = true },
                        )
                    }
                    profile != null -> {
                        LaunchedEffect(profile.id, state.permissions) {
                            state.forceServerLogout()
                        }
                        NoAccessFallback(
                            title = "Sesi server dihentikan",
                            message = "Profil aktif tidak lagi diizinkan. Silakan pilih profil yang tersedia.",
                            onLogout = { state.logoutApp() },
                            actionLabel = "Ke Pemilihan Server",
                        )
                    }
                    allowed != null && allowed.isEmpty() -> {
                        NoAccessFallback(
                            title = "Akses profil tidak tersedia",
                            message = "Akun ini tidak memiliki akses ke profil server mana pun.",
                            onLogout = { state.logoutApp() },
                        )
                    }
                    else -> {
                        ServerHubScreen(
                            onSelectProfile = { state.chooseProfile(it) },
                            onLogoutApp = { state.logoutApp() },
                            isDark = state.isDarkMode,
                            allowedProfileIds = allowed,
                            canManageAccounts = state.canManageAccounts(),
                            currentAccountId = state.currentAccountId,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoAccessFallback(
    title: String,
    message: String,
    onLogout: () -> Unit,
    actionLabel: String = "Keluar Aplikasi",
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = onLogout) {
                    Text(actionLabel)
                }
            }
        }
    }
}

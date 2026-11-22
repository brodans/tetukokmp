# TETUKO KMP Parity Audit (vs Tauri)

Status: living document. Source audit: direct code inspection of `/data/PROJECT/tetuko` (Tauri) and `/data/TETUKO/tetukokmp` (KMP).

## Goal
KMP must reach Tauri feature parity, then exceed it in reliability, automation power, background robustness, and UX professionalism.

## Progress Log

- [x] P0.1 Canonical automation model moved to `core/domain/src/commonMain/kotlin/id/tetuko/core/domain/model/Automation.kt` (preset/log/photo/queue/codecs/state/ledger interface).
- [x] P0.2 `AppServices` compatibility aliases/delegating codecs added; key imports migrated.
- [x] P0.3 Android automation worker refactored to shared codecs, stronger idempotency/missed-window/retry/ambiguous handling, richer log reason codes, manual runner hook (`androidApp/src/androidMain/kotlin/id/tetuko/app/TetukoAndroidAutomation.kt`, `MainActivity.kt`).
- [x] P0.4 Added shared planning helpers `core/domain/.../AutomationPlanning.kt` and `StoreBackedAutomationLedger` in composeApp service; Android/desktop schedulers now use shared window parsing helpers.
- [x] P1.1 Android manifest hardened: `RECEIVE_BOOT_COMPLETED`, `SCHEDULE_EXACT_ALARM`, receiver registered for `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`.
- [x] P2.1 PresetScreen UX improved: next-run preview, readiness badge, platform-neutral scheduler copy, dry-run reuse; manual runner wired on Android.
- [x] P3.1 Desktop autostart now supports Linux XDG, Windows HKCU Run, macOS LaunchAgent (`DesktopAutostart.kt`).
- [x] P3.2 Desktop tray shows scheduler status/paused state, periodic refresh, pause/resume actions already present.
- [x] P1.4 Exact-alarm diagnostics hook added (`AppServices.isAutomationExactAlarmAvailable`, Android settings opener, PresetScreen warning/button).
- [x] Compile checks: `:core:domain:compileKotlinJvm`, `:composeApp:compileKotlinDesktop` pass; `:desktopApp:compileKotlin` recheck in progress. Android full assemble blocked in this env by missing Android SDK.
- [~] P0.5 Shared execution policy/planning/coordinator added (`AutomationExecution.kt`, `AutomationRunCoordinator.kt`); DesktopAutomationScheduler and Android worker now use coordinator for decision/logging. `AutomationManualResult` extended with reason/error/http fields. Submit BKD/Ponorogo logic still duplicated (next refactor).
- [ ] P1.2 Android background reliability UX: exact-alarm permission flow, battery optimization guidance, foreground/critical execution option, alarm reschedule diagnostics.
- [ ] P1.3 Desktop scheduler hardening: clock drift/wake catch-up, single-instance guard, richer health/status API, persistent execution ledger.
- [ ] P2.2 Preset power-up: stock photo manager UX, queue visibility/reset per preset, import/export validation, conflict resolution, preflight details, execution history filters.
- [ ] P3.3 Tray/mini-window parity+: next-run, last result, quick open preset/logs, Windows/mac polish.
- [ ] P4 Full menu/handling parity matrix and automated tests.

## Parity Matrix

| Domain | Tauri evidence | KMP evidence | Status | Next action |
|---|---|---|---|---|
| Automation engine | Rust native manager/scheduler/state/events (`src-tauri/src/automation.rs`, commands in `lib.rs`) | Desktop `DesktopAutomationScheduler.kt`; Android `TetukoAndroidAutomation.kt`; hooks in `AppServices.kt` | **Partial/Risky** | Extract shared executor contract + ledger; avoid logic drift |
| Preset model | Unified+legacy preset libs, IndexedDB, queue, stock photo, random time (`src/lib/presetOtomatisasi.ts`, `presetAutomasi.ts`) | `core/domain/model/Automation.kt`, `PresetScreen` in `CommonPages.kt`, codecs/hooks | **Partial** | Audit PresetScreen UX; add parity features and stronger validation |
| Preset execution logs | Native/JS log sync events | `AutomationLogEntry` + desktop/android log append | **Partial** | Unify schema, correlation IDs everywhere, UI filter/export |
| Stock photo & queue | IndexedDB stock + shuffled queue per preset | `AutomationStockPhoto`, `AutomationPhotoQueueState` codecs; desktop/android pick/consume | **Partial** | Add full management UI, hash dedup, integrity checks, migration |
| Manual run | Tauri native execution path | Desktop `manualAutomationRunner` wired; Android now wired via `runManual` | **Improved/Partial** | Same preflight/result semantics across platforms |
| Background desktop | Tauri hide-to-tray, native process alive | Desktop scheduler coroutine + tray close-to-tray + embedded proxy | **Partial** | Add wake/sleep/catch-up, health surface, single-instance lock |
| Background Android | N/A in Tauri (desktop) | WorkManager 15m + exact alarm best-effort + notification | **Partial/Risky** | Add permission UX, boot receiver checks, battery optimization guidance, optional foreground mode |
| System tray | Rich tray menu, quick nav, autostart, click toggle (`lib.rs`) | AWT tray, quick nav, mini toggle, autostart, close-to-tray (`DesktopApp.kt`) | **Partial** | Add status/next-run/health items, cross-OS polish |
| Autostart | `tauri-plugin-autostart` | Linux XDG only (`DesktopAutostart.kt`) | **Gap** | Implement Windows/mac autostart providers |
| Mini window | Tauri window behavior/tray-centric | `MiniWindowManager`, `MiniWindow.kt` | **Partial** | Verify feature parity and quick controls |
| Notifications | Tauri/native notifications + events | Desktop tray notifications, Android NotificationCompat | **Partial** | Unified notification policy, action buttons, deep links |
| Menu/navigation | Tauri frontend pages/routes | KMP `Navigation.kt` defines BKD/Ponorogo pages; `Shell`/screens | **Partial** | Build full per-page parity checklist against Tauri pages |
| Security/storage | Tauri stores/native integrations | Android keystore-encrypted account store, desktop secure store, web cookie store | **Partial** | Audit secret handling, backup/export, redaction, migration |
| Reliability/observability | Native event/sync/logs | API traffic redaction, automation logs, scheduler states | **Gap** | Unified ledger, diagnostics export, health checks, tests |

## Key Risks Found

1. **Logic drift**: Desktop scheduler and Android worker still duplicate BKD/Ponorogo execution logic. Shared codecs reduce schema drift, but execution drift remains.
2. **Android background limits**: 15-minute WorkManager period + battery constraints means missed windows possible; exact alarm requires permission/OS cooperation.
3. **Typealias/object compatibility**: AppServices now delegates codecs; enum typealias used - compile OK on desktop, but keep eye on Kotlin/target edge cases.
4. **No SDK verification**: Android compile/runtime not verified in current environment.
5. **Large UI surface**: `CommonPages.kt` contains major PresetScreen; behavior parity not fully audited.

## Recommended Execution Order

1. Build shared automation executor + ledger in core (pure Kotlin, testable).
2. Make desktop scheduler consume shared executor.
3. Make Android worker/manual runner consume shared executor.
4. Add Android background permission/boot/battery UX and diagnostics.
5. Upgrade PresetScreen to parity+ (stock photo manager, queue visibility, dry-run, detailed logs, import/export).
6. Complete tray/autostart cross-platform polish.
7. Run full menu parity audit and add regression tests.

## Update batch (lanjutan otomatis)
- [x] `.env` KMP dibuat di `tetukokmp/.env` dari mapping `/data/TETUKO/.env` (VITE_* -> runtime KMP), tanpa menampilkan nilai rahasia. `gitignore` sudah menutup `.env`.
- [x] Hardcoded fallback endpoint/allow-list sensitif dibersihkan: workflow Linux, ServerProfile defaults, ProxyConfig/ProxyServer, Android automation gateway fallback, env examples; ProxyConfig reloadable untuk test; tests disesuaikan dan lulus.
- [x] Android notifikasi automasi diperbaiki: content intent ke MainActivity, channel description, prioritas tinggi untuk FAILED/AMBIGUOUS, pending intent immutable-safe.
- [x] Android manifest receiver menangani `TIME_SET` dan `TIMEZONE_CHANGED` selain boot/package replaced.
- [x] Desktop tray: label konteks automasi (bukan media) "Tunda/Aktifkan Kembali Eksekusi Otomatis", tambah aksi cepat Preset/Log.
- [x] Desktop mini controller: hapus pause/resume gaya media; tambah aksi Preset/Log, status scheduler.
- [x] Workflow dipisah per device: `.github/workflows/linux.yml` dan `.github/workflows/android.yml`; workflow monolitik lama dihapus.
- [~] Verifikasi: `:core:domain:jvmTest`, `:proxyServer:test :core:data:jvmTest`, `:composeApp:compileKotlinDesktop :desktopApp:compileKotlinJvm` PASS. Android full assemble masih terblokir SDK di env ini.

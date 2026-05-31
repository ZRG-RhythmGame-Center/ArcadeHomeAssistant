# Plan: Maimai Android Parity + Tests + .NET Coverage Closeout

## TL;DR

Bring `apps/mobile-android/` from "23 source files / 0 tests / parity-broken" to "feature-parity with the Flutter UX, R1+R2 findings closed, full unit + Compose UI coverage" while filling remaining .NET unit/integration gaps for `DeviceEndpoints`, `DeviceChangeNotifier`, `NAudioDeviceNotificationSource`, `WebSocketSession`, `ProcessRunner`, `TrayApp`, and `CoreAudioService`. 7 waves, 57 tasks, TDD-first (RED→GREEN→SURFACE), reviewer gate after each wave, all long-running shell work via PTY, manual-install verification, and Flutter app archived to `apps/mobile-flutter-archived/` after the final gate. No CI added this round; local + reviewer-agent gates only.

## Scope reminder (HARD)

- In scope: `apps/mobile-android/`, `services/windows-agent/`.
- Out of scope: `apps/pc-web/`. `apps/mobile/` (Flutter) is in scope only for archive (Wave 7.8).
- TDD mandatory; reviewer gate per wave; PTY-only for `gradlew assembleRelease`, `dotnet test`, emulator, agent run; bash OK only for short read-only commands.

## Resolved decisions

1. **Flutter archival**: After Wave 7 reviewer gate G passes, `git mv apps/mobile apps/mobile-flutter-archived` (Wave 7.8). Directory stays in repo (no delete). A brief `apps/mobile-flutter-archived/README.md` is added explaining it's superseded by `apps/mobile-android/`.
2. **Coverage gate**: 70% line coverage for `data/` and `ui/` packages, excluding `*Test*`, `BuildConfig`, and `ui/theme/Theme.kt`. Wired into `jacocoTestReport` + `jacocoVerification` in Wave 2.6.
3. **Release signing**: Keep current debug-key release signing for LAN-only distribution. Wave 7.9 adds the explicit note to root `README.md` and `apps/mobile-android/README.md`: "release APK is signed with the Android debug keystore for LAN-only distribution. For Play Store distribution, generate a release keystore and add `signingConfigs.create(\"release\")` per Android docs." Reflected as a permanent line item in this plan's Risk register (R5) and not treated as a defect.
4. **Emulator availability**: Wave 7.2 follows this fallback ladder, in order:
   1. `adb devices` — if a device or emulator is already connected, use it.
   2. `emulator -list-avds` — pick the first ARM64 AVD; otherwise the first x86_64 AVD.
   3. If only x86_64 AVDs exist, install the **debug** APK (universal) for end-to-end QA on x86_64 because the release APK is `arm64-v8a`-only (`abi.include("arm64-v8a")`, `isUniversalApk = false`). Note this in `.omo/notepads/maimai-home-assistant-full/learnings.md` so it is reviewable.
   4. If no AVD and no device, fail Wave 7.2 explicitly and ask the user to attach a device. Do not silently skip.
5. **CI**: Out of scope. No GitHub Actions added. Local PTY verification + 5-agent reviewer gate is the entire quality gate. Stated explicitly in the Deliverable summary so the user is aware that future CI work must be planned separately.

## Scenarios (the contract)

Each scenario must end with two artifacts: (a) RED→GREEN test run logs (saved to `.omo/runs/<wave>/<scenario>.log` via PTY) and (b) a real-surface artifact (curl response, screencap, or `adb logcat` excerpt).

1. **S1 — Connection happy path.** Surface: ConnectionScreen. Pass: enter `192.168.1.x:8765`, tap "测试连接", green success card appears with `machineName / version / capabilities`, manual "进入" navigates to AudioScreen. Evidence: `ConnectionViewModelTest.testConnection_success_emitsConnectedStatus`, `ConnectionScreenTest.successCardShowsAndNavigates` (Compose UI), screencap of success card.
2. **S2 — LAN discovery → silent verify → navigate.** Surface: ConnectionScreen scan list. Pass: tap a discovered service, ViewModel calls `agentClient.fetchStatus`, on success sets `connectedStatus`, screen navigates. Evidence: `ConnectionViewModelTest.useDiscoveredService_triggersSilentVerifyAndNavigate`, screencap of scan list + post-tap navigation.
3. **S3 — Cleartext LAN HTTP works on Android 9+.** Surface: AndroidManifest + network-security-config + app-layer LAN allowlist. Pass: `usesCleartextTraffic="true"` declared; `network_security_config.xml` permits cleartext via base-config; `LanAddressPolicy` enforces RFC1918 (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16) + loopback + .local mDNS at every HTTP/WS call site (Android NSC does not support CIDR ranges in `<domain>` — see [w1-gate-a verdict](../reviews/w1-gate-a/verdict.md)). Evidence: `ManifestSecurityTest` (Robolectric), `LanAddressPolicyTest`, `verify-manifest.ps1`.
4. **S4 — Coroutine cancellation propagates through OkHttp call.** Surface: `AgentClient`. Pass: cancelling the calling coroutine cancels the OkHttp `Call` and rethrows `CancellationException`. Evidence: `AgentClientCancellationTest.cancelDuringSlowResponse_throwsCancellation` with MockWebServer throttle.
5. **S5 — Audio control with WebSocket refresh + no slider race.** Surface: AudioScreen. Pass: WS pushes `audio.state`, UI updates without snapping the slider while the user is actively dragging. Evidence: `AudioViewModelTest.dragWhileEventArrives_keepsLocalSliderValue`, `AudioScreenTest.sliderRespectsDragGate`, screencap.
6. **S6 — Files root selector + capability gating.** Surface: FilesScreen ModalBottomSheet root picker. Pass: roots load via WS-or-pull, picker is BottomSheet with leading folder icon, readOnly roots hide upload FAB AND mutation actions. Evidence: `FilesViewModelTest.readOnlyRoot_hidesMutations`, `FilesScreenTest.bottomSheetRootSelectorWorks`.
7. **S7 — File mutations: rename/move/delete UX parity.** Surface: FilesScreen. Pass: action sheet is BottomSheet (not AlertDialog), delete hidden for directories, rename/move dialogs autofocus, trim, reject empty, delete confirm uses red `MaterialTheme.colorScheme.error`. SnackBar (not inline `Text`) for success/error. Evidence: `FilesViewModelTest` mutation cases, `FilesScreenTest` Compose tests, screencaps.
8. **S8 — Truncation banner reflects server limit.** Surface: FilesScreen. Pass: banner reads `已截断，仅显示前 ${limit} 项` where `limit` matches the request limit (default 200). Evidence: `FilesScreenTest.truncationBannerShowsCorrectLimit`.
9. **S9 — WebSocket reconnect + scheme + MulticastLock.** Surface: EventStream + DiscoveryService. Pass: `https://...` becomes `wss://`, exponential backoff cap = 30s, reconnect Job tracked and cancelled on `disconnect()`, `WifiManager.MulticastLock` acquired before NSD discovery and released on completion / cancellation. Evidence: `EventStreamTest.reconnectStopsAfterDisconnect`, `EventStreamTest.httpsAddressBecomesWss`, `DiscoveryServiceTest.acquiresAndReleasesMulticastLock`.
10. **S10 — .NET coverage closeout.** Surface: `services/windows-agent/`. Pass: new tests for `DeviceEndpoints`, `DeviceChangeNotifier`, `NAudioDeviceNotificationSource`, `WebSocketSession`, `ProcessRunner`, plus `CoreAudioService` integration smoke. Evidence: `dotnet test --collect:"XPlat Code Coverage"` PTY log, ReportGenerator HTML, ≥130 tests total all green.
11. **S11 — Release APK build + manual install.** Surface: `app-release.apk`. Pass: `gradlew assembleRelease` (PTY) succeeds, ABI-split arm64-v8a APK is `<= 2.5 MB`. Evidence: PTY log of build, `adb install` log (debug APK fallback on x86_64-only emulators per Resolved Decision #4), screencap of running app.
12. **S12 — Reviewer gates pass.** Surface: review reports under `.omo/reviews/<wave>/`. Pass: oracle (R1 follow-up), parity (R2 follow-up), security, code-quality each return UNCONDITIONAL APPROVAL. Evidence: review markdown files committed.

## Wave structure

Each wave ends with a reviewer gate. Subagent must NOT start the next wave until the gate is approved.

### Wave 1 — Critical / Security / Cancellation safety

- [ ] 1. `app/src/main/AndroidManifest.xml` + `app/src/main/res/xml/network_security_config.xml`: Add `usesCleartextTraffic="true"` and `networkSecurityConfig` whitelisting RFC1918 ranges - expect HTTP to LAN agent succeeds on Android 9+. RED: `app/src/test/kotlin/.../ManifestSecurityTest.kt::cleartextTrafficEnabled`, `::networkSecurityConfigWhitelistsRfc1918` (Robolectric). Surface: `aapt dump badging app-debug.apk | grep usesCleartextTraffic`. Effort: S. Subagent: `unspecified-low`. Parallel: with 2.
- [ ] 2. `AndroidManifest.xml`: Add `android.permission.NEARBY_WIFI_DEVICES` (sdk≥33, `usesPermissionFlags="neverForLocation"`); keep `CHANGE_WIFI_MULTICAST_STATE`. RED: `ManifestPermissionsTest.kt::declaresNearbyWifiDevicesOnSdk33Plus`. Effort: S. Parallel: yes.
- [ ] 3. `data/AgentClient.kt`: Wrap `execute()` in `withContext(Dispatchers.IO)`; rethrow `CancellationException` BEFORE the catch-all; build `OkHttpClient` once in `ServiceLocator` with full timeouts. RED: `AgentClientCancellationTest.kt::cancelDuringSlowResponse_throwsCancellation`, `AgentClientDispatcherTest.kt::executesOnIoDispatcher`. Effort: M. Subagent: `quick`. Parallel: no (blocks W3 tests).
- [ ] 4. `data/DiscoveryService.kt`: Acquire `WifiManager.MulticastLock("maimai-mdns")` before discovery, release in `finally`. Replace `runBlocking` in `suspendResolve` with proper `suspendCancellableCoroutine`. RED: `DiscoveryServiceTest.kt::acquiresAndReleasesMulticastLock`, `::doesNotRunBlockingInside`. Effort: M. Parallel: no.
- [ ] 5. `data/EventStream.kt`: Track reconnect `Job`, cancel on `disconnect()`. Map `http→ws` and `https→wss`; do not collapse to plain `ws://`. RED: `EventStreamTest.kt::reconnectJobCancelledOnDisconnect`, `::httpsAddressBecomesWss`, `::httpAddressBecomesWs`. Effort: M. Parallel: with 4.
- [ ] 6. `data/AgentClient.kt::downloadFile`: Throw `AgentRequestException(Network)` when `response.body == null`. RED: `AgentClientTest.kt::downloadFile_nullBody_throws`. Effort: S. Parallel: with 5 once 3 lands.

**Reviewer Gate A** — oracle (R1 critical+major closure verification).

### Wave 2 — Test stack scaffold

- [ ] 7. `app/build.gradle.kts`: Add `de.mannodermaus.android-junit5:2.0.1` plugin; testImplementation deps (JUnit 5 BOM 5.14.1, Truth 1.4.2, Turbine 1.2.1, MockK 1.13.14 + agent + android, mockwebserver 4.12.0, Robolectric 4.13, kotlinx-coroutines-test 1.8.1, junit-vintage-engine for Compose). Set `testOptions.unitTests.isIncludeAndroidResources = true`. `tasks.withType<Test>{useJUnitPlatform()}`. Surface: `gradlew :app:dependencies --configuration testRuntimeClasspath` (PTY). Effort: M. Subagent: `quick` w/ skill `customize-opencode`. Parallel: no.
- [ ] 8. `app/src/test/kotlin/com/maimai/home/MainDispatcherRule.kt`. RED: `MainDispatcherRuleTest.kt::setsAndResetsMain`. Effort: S. Parallel after 7.
- [ ] 9. `app/src/test/kotlin/com/maimai/home/SmokeTest.kt`: Trivial JUnit5 + Truth assertion. Effort: S. Parallel after 7.
- [ ] 10. `app/src/test/kotlin/com/maimai/home/RobolectricSmokeTest.kt`: Robolectric loads, `App` resolves. Effort: S. Parallel after 7.
- [ ] 11. `app/src/androidTest/kotlin/com/maimai/home/ComposeSmokeTest.kt`: `createComposeRule()` + `Text("hi")` smoke. Surface: instrumented run via PTY against emulator. Effort: S. Parallel after 7.
- [ ] 12. JaCoCo: add plugin, `jacocoTestReport`, `jacocoVerification` rule = 70% line coverage on `com/maimai/home/data/**` and `com/maimai/home/ui/**` excluding `*Test*`, `BuildConfig`, `ui/theme/Theme.kt`. Wire `tasks.check { dependsOn("jacocoVerification") }`. Surface: HTML report opens. Effort: M. Parallel after 7.

**Reviewer Gate B** — code-quality (test stack hygiene).

### Wave 3 — Data layer fixes + characterization tests

- [ ] 13. `AgentClientTest.kt` (MockWebServer, ~25 cases): success+error matrix for every endpoint listed in `AgentClient.kt:37-133`, including W1.3 cancellation and W1.6 null-body cases. Effort: L. Subagent: `unspecified-high`. Parallel: yes.
- [ ] 14. `EventStreamTest.kt`: connect, message, disconnect, reconnect, scheme variants. Uses `MockWebServer.enqueue(MockResponse().withWebSocketUpgrade(...))`. Effort: M. Parallel: yes.
- [ ] 15. `DiscoveryServiceTest.kt`: ShadowNsdManager — discovers, resolves, dedupes, releases multicast lock. Effort: M. Parallel: yes.
- [ ] 16. `AgentPreferencesTest.kt`: DataStore round-trip with `PreferenceDataStoreFactory.create(testScope, ..., "agent_test")`. Effort: S. Parallel: yes.
- [ ] 17. `data/AgentPreferences.kt`: Move `DEFAULT_AGENT_ADDRESS` to `BuildConfig` (debug build sample, release empty). RED: `AgentPreferencesTest.kt::defaultIsNotHardcodedLan`. Effort: S. Depends on 16.
- [ ] 18. `data/models/AgentStatus.kt`: Add `baseUrl: String? = null`; `Capabilities` parses unknown fields without breaking. RED: `AgentStatusSerializationTest.kt::unknownCapabilityField_isIgnored`, `::baseUrlReadWhenPresent`. Effort: S. Parallel: yes.
- [ ] 19. Replace hardcoded `前 500 项` with `R.string.files_truncated_format` taking the request limit; expose `FileListingResult.limit` from server. RED: tested via W4.5. Effort: S. Parallel: yes.

**Reviewer Gate C** — oracle (R1 minor closure) + parity (R2 data-shape parity).

### Wave 4 — ViewModel fixes + tests

- [ ] 20. `ConnectionViewModelTest.kt`: `testConnection`, `scanLan`, `useDiscoveredService` (silent verify + navigate), error mapping. Effort: M. Parallel: yes.
- [ ] 21. `ui/connection/ConnectionViewModel.kt`: `useDiscoveredService` also calls `fetchStatus` to populate `connectedStatus`. RED in 20. Effort: S. Depends on 20.
- [ ] 22. `AudioViewModelTest.kt`: refresh, refreshDevices error-surfacing, setVolume race protection, switchDevice success+failure, WS event handling. Effort: L. Parallel: yes.
- [ ] 23. `ui/audio/AudioViewModel.kt`: surface `refreshDevices` errors; add `isVolumeBusy`; `onVolumeDragStart()` / `onVolumeDragEnd()` to gate WS-pushed updates while dragging. RED in 22. Effort: M. Depends on 22.
- [ ] 24. `FilesViewModelTest.kt`: loadRoots, selectRoot, openFolder, navigateToPath, refresh, download, upload, delete (file vs directory), rename (validate empty/trim), move, readOnly gating, WS `files.changed` subscription, server-truncation-limit surfaced. Effort: L. Parallel: yes.
- [ ] 25. `ui/files/FilesViewModel.kt`: WS subscription keyed by current root+path; debounce refresh; `canMutate` derived from `selectedRoot.readOnly`. RED in 24. Effort: M. Depends on 24.

**Reviewer Gate D** — code-quality + parity.

### Wave 5 — UI parity (Compose)

- [ ] 26. `ConnectionScreen.kt`: LoadingCard / EmptyCard / ErrorCard composables; success card with explicit "进入设备" button (no auto-navigation). RED: `ConnectionScreenTest.kt::successCardShowsAndManualNavigate`. Effort: M.
- [ ] 27. `ConnectionScreen.kt`: copy → "连接中..." / "扫描中...", strings via resources. Effort: S.
- [ ] 28. `AudioScreen.kt`: `DisposableEffect(viewModel)`; SnackBarHost; `Switch` → `IconToggleButton(VolumeOff/VolumeUp)`; slider gated by `isRefreshing` + drag flag; refresh icon. RED: `AudioScreenTest.kt::muteRendersAsIcon`, `::sliderRespectsDragGate`, `::snackBarShowsOnError`. Effort: L.
- [ ] 29. `FilesScreen.kt`: action `AlertDialog` → `ModalBottomSheet`; delete hidden for directories; rename/move dialogs autofocus + trim + non-empty validation; SnackBar host. RED: `FilesScreenTest.kt::actionSheetIsBottomSheet`, `::deleteHiddenForDirectory`, `::renameRequiresNonEmpty`. Effort: L.
- [ ] 30. `FilesScreen.kt`: leading folder/file icons, trailing chevron for directories, FAB icon `Icons.Filled.Upload`. Effort: M.
- [ ] 31. `FilesScreen.kt`: breadcrumb chip row with `AssistChip` segments. Effort: M.
- [ ] 32. `FilesScreen.kt`: pull-to-refresh via `PullToRefreshBox`. Effort: M.
- [ ] 33. `FilesScreen.kt`: red delete confirm (`MaterialTheme.colorScheme.error`). Effort: S.
- [ ] 34. `FilesScreen.kt`: dynamic truncation copy using server limit (W3.7). Effort: S.
- [ ] 35. `FilesScreen.kt`: `ModalBottomSheet` root selector replaces `DropdownMenu`; empty state "未发现任何文件根". Effort: M.
- [ ] 36. `ui/AppUi.kt` + `ui/nav/MaimaiNavHost.kt`: dynamic `${machineName}` titles; `BackHandler` to clear connected status. Effort: S.
- [ ] 37. Strings: extract every Chinese literal to `res/values/strings.xml` and `res/values-zh/strings.xml`. Replace upload/download timeout 30s → 5min in `ServiceLocator`. RED: `ServiceLocatorTest.kt::okHttpUploadDownloadTimeoutIs5min`. Effort: M.
- [ ] 38. Network error specificity: extend `mapError` for DNS / connect-refused / timeout. RED: `AgentClientTest.kt` extension. Effort: M.
- [ ] 39. `proguard-rules.pro`: tighten broad keeps; rely on serialization plugin's keeps. Surface: `assembleRelease` succeeds, `mapping.txt` shows obfuscation. Effort: S.
- [ ] 40. `app/build.gradle.kts`: drop `androidx.appcompat:appcompat`. Verify build. Effort: S.

**Reviewer Gate E** — parity (R2 closure verification).

### Wave 6 — .NET tests + small refactors for testability

- [ ] 41. `tests/MaimaiHomeAgent.Tests/Audio/DeviceEndpointsTests.cs` — copy `AudioEndpointsTests` host pattern: 200 happy path, 400 invalid GUID, 404 device-not-found via `IAudioService` mock raising `AudioDeviceNotFoundException`, projection shape. Effort: M. Parallel: yes.
- [ ] 42. `tests/.../Audio/DeviceChangeNotifierTests.cs` — `StartAsync` registers exactly once (idempotent), `StopAsync` unregisters even when source throws `COMException`, callback path publishes via `EventPublisher` mock. Effort: M. Parallel: yes.
- [ ] 43. `tests/.../Audio/NAudioDeviceNotificationSourceTests.cs` — second `Register` throws `InvalidOperationException`, `Unregister` after register OK, `Unregister` without register no-ops, render-only filter on `OnDefaultDeviceChanged`. Effort: M. Parallel: yes.
- [ ] 44. `tests/.../Realtime/WebSocketSessionTests.cs` — direct unit tests for `CloseAsync` exception swallow, `SendRawAsync` semaphore serialization, `ReceiveLoopAsync` exception path, `DisposeAsync` idempotent. Use a tiny in-memory `WebSocket` pair pipe. Effort: L. Parallel: yes.
- [ ] 45. `tests/.../Startup/ProcessRunnerTests.cs` — `cmd /c echo hello` returns ExitCode 0; `cmd /c exit 7` returns ExitCode 7; cancellation kills the process; stderr captured. `[Trait("Category","Windows")]`. Effort: M. Parallel: yes.
- [ ] 46. `tests/.../Audio/CoreAudioServiceTests.cs` — Windows-only smoke: `GetStateAsync()` sane, `ListDevicesAsync()` non-empty. `[Trait("Category","Integration")]`, excluded from default CI. Effort: M. Parallel: yes.
- [ ] 47. `tests/.../Tray/TrayAppTests.cs` plus refactor: extract `ITrayIconHost` + `IUiThreadPump`, write characterization smoke test FIRST, then refactor under green; assert StartAsync calls Create on host, toggle invokes `AutoStartManager`, Stop disposes, exit triggers `_lifetime.StopApplication`. Effort: L. Sequential.
- [ ] 48. Delete `tests/.../UnitTest1.cs` after replacing with `StatusEndpointTests.cs` integration test of `/api/status`. Effort: S. Parallel: yes.

**Reviewer Gate F** — code-quality + oracle (.NET coverage closure).

### Wave 7 — Full QA + manual verification + Flutter archive

- [ ] 49. PTY: `gradlew testDebugUnitTest jacocoTestReport jacocoVerification`. Save log to `.omo/runs/w7/android-unit.log`. Effort: S.
- [ ] 50. PTY (with fallback ladder from Resolved Decision #4): detect device → `emulator -list-avds` → boot ARM64 if present, else x86_64; run `gradlew connectedDebugAndroidTest`. If only x86_64, use `app-debug.apk` (universal) per fallback. Save log + screencaps via `adb shell screencap`; document fallback in `.omo/notepads/maimai-home-assistant-full/learnings.md`. Effort: M.
- [ ] 51. PTY: `gradlew assembleRelease`; verify APK size `<= 2.5 MB`, ABI = `arm64-v8a`. Evidence: `aapt dump badging`. Effort: S.
- [ ] 52. PTY: `adb install -r app-release.apk` (or debug fallback per ladder); `adb logcat | findstr maimai`; capture screencaps for ConnectionScreen / AudioScreen / FilesScreen against the running agent. Effort: M.
- [ ] 53. PTY: `dotnet test services/windows-agent/MaimaiHomeAgent.sln --collect:"XPlat Code Coverage"`; `reportgenerator` HTML; save log + report under `.omo/runs/w7/`. Effort: S.
- [ ] 54. PTY: `dotnet run --project services/windows-agent/src/MaimaiHomeAgent`; `Invoke-RestMethod` smoke against `/api/status`, `/api/audio/state`, `/api/audio/devices`, `/api/file-roots`. Effort: M.
- [ ] 55. Reviewer gate G — F1-F5 (see "Final reviewer gate"). All five must return UNCONDITIONAL APPROVAL before tasks 56 and 57 run. Effort: M.
- [ ] 56. **Flutter archive**: `git mv apps/mobile apps/mobile-flutter-archived`. Create `apps/mobile-flutter-archived/README.md` with text "Flutter App (archived). This Flutter implementation has been superseded by the native Kotlin/Compose app at `apps/mobile-android/`. Kept here for reference only; do not modify." Update root `README.md` to reflect the rename. Single atomic commit `w7: archive Flutter app to apps/mobile-flutter-archived/`. Effort: S. Depends on 55 APPROVED.
- [ ] 57. **README updates**: Update root `README.md` and create/update `apps/mobile-android/README.md` with: How to install (debug + release, `adb install -r`, sideload caveats); Release signing note ("release APK is signed with the Android debug keystore for LAN-only distribution. For Play Store distribution, generate a release keystore and add `signingConfigs.create(\"release\")` per Android docs"); How to run tests (`gradlew testDebugUnitTest jacocoTestReport jacocoVerification` and `gradlew connectedDebugAndroidTest`); How to run the agent (cross-link to `services/windows-agent/README.md`). Single atomic commit `w7: docs: install + release-signing notes`. Effort: S. Depends on 55.

**Reviewer Gate G** — final gate (F1-F5).

## Final reviewer gate

After Wave 7 evidence is committed, spawn these 5 review agents in parallel:

- [ ] F1. **Oracle review** — every R1 finding (21 items) closed with file:line evidence. Criteria: NO Critical/Major remaining; Minor only acceptable if explicitly waived in this plan.
- [ ] F2. **Parity review** — every R2 finding (37 items) closed; UI screencaps match Flutter reference. Criteria: zero blockers, zero important left open.
- [ ] F3. **Security review** — cleartext config scope (RFC1918 enforced at app layer via `LanAddressPolicy`; NSC accepts cleartext globally because Android does not support CIDR in `<domain>` — see [w1-gate-a verdict](../reviews/w1-gate-a/verdict.md)), permissions justified, no secrets in `BuildConfig`, manifest `exported` flags correct. Criteria: no high/critical findings.
- [ ] F4. **Code-quality review** — TDD discipline (RED commit visible per task), naming, ProGuard correctness, .NET test coverage delta, no dead code. Criteria: no smells in changed lines.
- [ ] F5. **QA hands-on review** — replay PTY logs, re-run a sample of curl + screencap commands, verify a clean machine builds and runs both artifacts. Criteria: all S1-S12 reproducible.

All 5 must return UNCONDITIONAL APPROVAL. Conditional → loop back to the implicated wave, re-run gate. Tasks 56 and 57 only execute after this gate passes.

## Dependency matrix

| Task | Blocks | Blocked by |
|---|---|---|
| 1, 2 | W3 | — |
| 3 | 6, 13 | — |
| 4 | 15 | — |
| 5 | 14 | — |
| 6 | 13 | 3 |
| 7 | every later | 1-6 (recommended) |
| 8-12 | W3+ | 7 |
| 13-19 | W4 | W2 done |
| 17 | 27 | 16 |
| 18 | W5 | — |
| 19 | 34 | — |
| 20 | 21 | W3 done |
| 22 | 23 | W3 done |
| 24 | 25 | W3 done |
| 26-40 | 50-52 | W4 done |
| 47 | 53 | — |
| 41-48 | 53 | W2 done |
| 49-54 | 55 | W6 done |
| 55 | 56, 57 | 49-54 done |
| 56 | 57 | 55 APPROVED |
| 57 | — | 55 APPROVED |

## Risk register

1. **Compose UI tests under JUnit5 + Vintage** may surface dispatcher conflicts between `UnconfinedTestDispatcher` (v1) and `StandardTestDispatcher` (v2). Mitigation: keep Compose UI tests under JUnit4 `ComposeTestRule` in `androidTest`; Jupiter only for unit tests; Vintage engine bridges. Isolate Compose tests in instrumented set if conflicts appear.
2. **NAudio COM mocking** — `[GeneratedComClass]` private inner class is hard to substitute. Mitigation: keep the test focused on the public Register/Unregister surface and the Render-only filter; add an internal seam method if needed.
3. **`TrayApp` refactor (W6.7)** touches Win32/STA-thread production code. Mitigation: characterization test BEFORE refactor; thin abstractions (`ITrayIconHost`, `IUiThreadPump`); revert plan documented in commit body.
4. **NSD discovery on emulator** is often flaky (multicast disabled). Mitigation: unit test with ShadowNsdManager; manual verification on a physical device or note the emulator limitation in `apps/mobile-android/README.md` (W7.9).
5. **Release signing uses the debug keystore** (`signingConfigs.getByName("debug")` at `app/build.gradle.kts:28`). Per Resolved Decision #3, this is intentional for LAN distribution. Mitigation: documented in W7.9 README and root README; not a defect for this round.

## Atomic commit strategy

- One commit per task. Branch: `kotlin-android-parity-and-tests`.
- TDD discipline visible in history: each behaviour-change task has at least 2 commits — `(RED)` first (failing tests added) and `(GREEN)` second (production change). Pure refactor commits use `(REFACTOR)` and require characterization tests in a prior `(RED)` commit.
- Subject prefix: `<wave>: <area>: <one-line>`. Examples:
  - `w1: manifest: enable cleartext traffic (RED)`
  - `w1: manifest: enable cleartext traffic (GREEN)`
  - `w3: AgentClient: cancellation propagates through OkHttp (RED)`
  - `w3: AgentClient: cancellation propagates through OkHttp (GREEN)`
  - `w6: Realtime: WebSocketSessionTests cover Close/Send/Receive branches`
  - `w7: archive Flutter app to apps/mobile-flutter-archived/`
  - `w7: docs: install + release-signing notes`
- Body: bullet list of files touched + reference to Scenario IDs (`Closes S4, S9`).
- No squashing — preserve RED→GREEN history for the final reviewer.
- No `--no-verify`. Pre-commit hooks must pass. If a hook fails, fix in a fresh follow-up commit, never amend.
- Push to a new branch (`-u origin kotlin-android-parity-and-tests`); never push to `main`. PR creation deferred to user request.

## PTY usage (HARD rule reminder)

Every shell that runs longer than ~2 seconds OR streams output OR holds state goes through `pty_spawn`. Specifically required for:

- `gradlew testDebugUnitTest`, `gradlew assembleRelease`, `gradlew connectedDebugAndroidTest`, `gradlew jacocoTestReport`, `gradlew jacocoVerification`
- `emulator …`, `adb install …`, `adb logcat …`, `adb shell screencap …`
- `dotnet test --collect:"XPlat Code Coverage"`, `dotnet run …`, `reportgenerator …`
- Any background `dotnet run` of the agent during smoke

Short read-only commands (`git status`, `git log`, `dotnet --version`, `gh pr view`) may use the bash tool. Logs from PTY runs saved under `.omo/runs/<wave>/<task-id>.log`.

## Deliverable summary

- **Plan file path**: `.omo/plans/maimai-android-parity-and-tests.md`.
- **Wave count**: 7 implementation waves + 1 final gating wave (F1-F5 = Reviewer Gate G).
- **Total task count**: **57** (W1: 6, W2: 6, W3: 7, W4: 6, W5: 15, W6: 8, W7: 9).
- **Estimated total effort**: ≈ 18.5 effort-points (S=0.5, M=1, L=2).
- **Top 3 risks**: (1) JUnit5 + Compose UI compatibility, (2) NAudio COM mocking complexity, (3) `TrayApp` Win32 test refactor blast radius.
- **CI**: explicitly out of scope.

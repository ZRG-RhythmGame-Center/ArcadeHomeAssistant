# Wave 6 .NET Test Summary

**Date**: 2026-05-31  
**Branch**: kotlin-android-parity-and-tests  
**Result**: ✅ 170 passed, 0 failed  
**Filter**: `Category!=Integration` (CoreAudioServiceTests excluded — requires live COM)  
**Run time**: ~10.7s

## Test Classes Added (Wave 6)

| Class | Tests | Task |
|-------|-------|------|
| Audio.DeviceEndpointsTests | 7 | W6.41 |
| Audio.DeviceChangeNotifierTests | 10 | W6.42 |
| Audio.NAudioDeviceNotificationSourceTests | 10 | W6.43 |
| Realtime.WebSocketSessionTests | 13 | W6.44 |
| Startup.ProcessRunnerTests | 7 | W6.45 |
| Audio.CoreAudioServiceTests | 8 | W6.46 (Integration — excluded from default run) |
| Tray.TrayAppTests | 8 | W6.47 |
| StatusEndpointTests | 13 | W6.48 |

**W6 new tests**: 76 (68 in default run + 8 Integration)

## All Test Classes (full suite)

| Class | Tests |
|-------|-------|
| Audio.AudioEndpointsTests | 14 |
| Audio.AudioStaDispatcherTests | 4 |
| Audio.CoreAudioServiceTests | 8 (Integration) |
| Audio.DeviceChangeNotifierTests | 10 |
| Audio.DeviceEndpointsTests | 7 |
| Audio.NAudioDeviceNotificationSourceTests | 10 |
| Discovery.MdnsAdvertiserTests | 3 |
| Files.FileListingEndpointsTests | 10 |
| Files.FileRootsConfigEndpointsTests | 4 |
| Files.FileMutationEndpointsTests | 24 |
| Files.PathGuardTests | 24 |
| Realtime.EventHubTests | 4 |
| Realtime.EventPublisherTests | 8 |
| Realtime.WebSocketSessionTests | 13 |
| Startup.AutoStartManagerTests | 7 |
| Startup.ProcessRunnerTests | 7 |
| StatusEndpointTests | 13 |
| Tray.TrayAppTests | 8 |
| **Total** | **178** |

## Production Seam Changes (W6)

- `IAudioStaDispatcher` interface extracted from `AudioStaDispatcher` — enables `InlineDispatcher` in tests without STA thread
- `DeviceChangeNotifier` now takes `IAudioStaDispatcher` (was concrete `AudioStaDispatcher`)
- `ProcessRunner.RunAsync` now kills the child process on `CancellationToken` cancellation
- `TrayApp` refactored with `ITrayIconHost` + `IUiThreadPump` seams (Win32 implementations in `TrayImplementations.cs`)

## Findings (follow-up tasks)

1. `/api/status` response does not include a `baseUrl` field — mobile client may require it; file a follow-up to add it to `Program.cs`
2. `NAudioDeviceNotificationSource` has 3 pre-existing SYSLIB1099 COM interop warnings — not introduced by W6
3. `CoreAudioServiceTests` (W6.46) are `[Trait("Category","Integration")]` and require a Windows machine with audio hardware; excluded from default CI runs

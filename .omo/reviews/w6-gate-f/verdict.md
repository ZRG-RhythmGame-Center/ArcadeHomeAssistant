# Reviewer Gate F - Wave 6 .NET Tests + Refactors - APPROVED

Date: 2026-05-31
Branch: kotlin-android-parity-and-tests
Reviewer: oracle
Wave 6 commits: 51d45eb..1a4fd77 (12 commits across W6.41-48)
Gate F fix commit: 1132a43 "gate-d+f fix: ... /api/status baseUrl + airtight tests"
Production hotfix: 47e719d "w6: hotfix: register IAudioStaDispatcher in production DI container"

## Verdict timeline

- **Round 1**: REJECTED with 6 concerns.
- **Round 2 (1132a43)**: APPROVED UNCONDITIONALLY.

## Round 2 final verdict

> APPROVED
> Concerns 1, 2, and 3 are closed by concrete code and tests in 1132a43.
> Concerns 4, 5, and 6 do not warrant blocking Wave 6: the seam is already
> documented enough in code/tests, CI Integration exclusion is a follow-up
> outside the stated Wave 6 closure requirement, and the MutableSharedFlow
> test ordering fix matches the production subscription lifecycle.

## Findings closed

1. ProcessRunner kill-on-cancel authorized + implemented (plan task 45 explicit). Strengthened test in 1132a43 snapshots PIDs + asserts process killed.
2. ProcessRunner test now proves the child process is killed via PID polling.
3. /api/status baseUrl field added (Program.cs) + GetStatus_ContainsBaseUrl test.

Acknowledged non-blockers (4-6):
4. IAudioStaDispatcher seam documented in interface XML doc + test class doc.
5. CI auto-exclude is follow-up; documented invocation is `--filter Category!=Integration`.
6. EventStreamMockServerTest stability fix matches production subscription lifecycle.

## Production hotfix caught in Wave 7.54

Commit 47e719d: the IAudioStaDispatcher seam refactor (W6.42) introduced
a startup crash because Program.cs only registered the concrete
AudioStaDispatcher class. Caught by Wave 7.54 hands-on smoke when the
agent crashed at boot. Fix: add an interface alias forwarding to the
concrete singleton.

## Evidence

- Test count: `.omo/runs/w6/w6-dotnet-test-GREEN.log` (170 tests + 8 integration)
- Coverage: `.omo/runs/w7/dotnet-coverage.cobertura.xml` (line-rate 71.81%)
- Smoke: `.omo/runs/w7/dotnet-smoke.log` (5/5 endpoints)

## Wave 6 deliverables (8 tasks)

- W6.41 DeviceEndpointsTests (4 cases: 200/400/404/projection)
- W6.42 DeviceChangeNotifierTests (lifecycle idempotency + EventPublisher)
- W6.43 NAudioDeviceNotificationSourceTests (register/unregister/render filter)
- W6.44 WebSocketSessionTests (close/send/receive/dispose)
- W6.45 ProcessRunnerTests (stdout/exit/cancellation kill/stderr)
- W6.46 CoreAudioServiceTests (Integration trait, Windows-only smoke)
- W6.47 TrayApp seam refactor (ITrayIconHost + IUiThreadPump) + characterization
- W6.48 StatusEndpointTests integration test, UnitTest1 deleted

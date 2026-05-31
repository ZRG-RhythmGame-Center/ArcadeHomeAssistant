# Final QA Re-verification — maimai-home-assistant-full

Date:        2026-05-31 09:36 (re-test after pairing whitelist fix)
Environment: FRZ-XIAOXIN, Windows, .NET 9
Source fix:  Security/AuthMiddleware.cs IsWhitelisted() now also exempts
             /api/pairing/code and /api/pairing/active.
Binary:      services\windows-agent\src\MaimaiHomeAgent\bin\publish\win-x64\MaimaiHomeAgent.exe
             (re-published 2026-05-31 09:36; size 141,876,345 bytes)

## Scenario results

### S1 — GET /api/status — PASS
HTTP 200; machineName=FRZ-XIAOXIN, version=1.0.0.0, uptimeSeconds=16,
capabilities = {audioVolume, audioMute, audioDeviceSwitch,
fileManagement, discoveryBroadcast} all true.

### S2 — GET /api/audio/state without token — PASS
HTTP 401, as expected.

### S3 — POST /api/pairing/code (REGRESSION) — PASS
HTTP 200, body: {"code":"326884","expiresAt":"2026-05-31T01:38:50..."}
The previously-blocking AuthMiddleware whitelist now lets the request
through to the endpoint's own loopback check. Code minted from
loopback as documented.

### S3b — POST /api/pairing/exchange — PASS
HTTP 200, body: {"token":"Sa5lueBZ9T0RLFZ6jLSP9QoqxRUkZ0_Jos-kv4f-tPA",
"tokenId":"ba448f5e80cb4d70a04e60b867b8d907",
"expiresAt":"2026-08-29T01:36:51..."}.
Token length 43, used for S4-S8.

### S4 — GET /api/audio/state with token — PASS
HTTP 200, body:
{"masterVolume":0.32,"muted":false,
 "defaultDeviceId":"747ccd43-3dc1-4513-b6e3-9d2277c02f10"}

### S5 — GET /api/file-roots — PASS
HTTP 200, body: [] (empty as expected, no roots configured).

### S6 — GET /api/files?rootId=test&path=../../Windows — PASS
HTTP 404 (not 500). Acceptable per scenario (403 or 404).

### S7 — GET /audio — PASS
HTTP 200, content-type text/html, 394 bytes.
Body starts with "<!doctype html>" — React/Vite SPA shell.

### S8 — WebSocket /api/events?token=... — PASS
ClientWebSocket.ConnectAsync completed; State=Open before close.
No event emitted within 3s; expected since no audio/file changes
occurred during the test window.

### S9 — Published exe cold start — PASS
Stopped previous instance, started published self-contained exe via
Start-Process; /api/status returned HTTP 200 in 0.68s
(target: within 15s).

## Edge cases probed
- 4 explicit edge cases (no-token 401, traversal 404, exchange-after-code-mint,
  fresh-exe cold start)
- Pairing/code regression: the previously-failing endpoint now behaves as
  documented in source

## Verdict line
Scenarios [9/9 pass] | Integration [PASS] | Edge Cases [4 tested] | VERDICT: APPROVE

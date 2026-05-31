# Wave 4 Summary

## Result: GREEN ✓

- **Debug**: 117 tests, 0 failures
- **Release**: 117 tests, 0 failures
- **Baseline (W3)**: 68 tests → **Wave 4 adds 49 new tests**

## Tasks Completed

### Task 20 — ConnectionViewModelTest (12 tests)
- `testConnection_success_emitsConnectedStatus`
- `testConnection_savesAddressOnSuccess`
- `testConnection_networkError_setsErrorMessage`
- `testConnection_timeoutError_setsTimeoutMessage`
- `testConnection_notFoundError_setsNotFoundMessage`
- `testConnection_nonAgentException_fallsBackToNetworkError`
- `scanLan_success_populatesDiscoveredList`
- `scanLan_failure_setsErrorMessage`
- `useDiscoveredService_triggersSilentVerifyAndNavigate` ← RED→GREEN (task 21)
- `useDiscoveredService_fetchStatusFailure_setsErrorMessage`
- `connectedStatus_nonNull_impliesNavigationReady`
- `clearConnectedStatus_resetsToNull`

### Task 21 — ConnectionViewModel.useDiscoveredService fix
- Now calls `agentClient.fetchStatus(service.address)` and sets `connectedStatus` on success.
- Closes R1#5/R2.

### Task 22 — AudioViewModelTest (14 tests)
- refresh happy/fail, refreshDevices error surface, setVolume isVolumeBusy, drag gate (start/end),
  switchDevice success/fail, WS audio.state (normal + malformed), WS audio.device.changed, connectionState propagation.

### Task 23 — AudioViewModel fixes
- Injectable primary constructor (`agentClient`, `eventFlow`, `connectionStateFlow`).
- `refreshDevices()` now surfaces failures to `errorMessage` (was silently swallowed).
- `AudioUiState.isVolumeBusy` added; `setVolume` sets it true/false around the request.
- `onVolumeDragStart()` / `onVolumeDragEnd()` added; WS `audio.state` events buffered during drag.

### Task 24 — FilesViewModelTest (23 tests)
- loadRoots (success/fail/preserve), selectRoot, openFolder (single/nested), navigateToPath,
  refresh, canMutate (writable/readOnly/noRoot), download (success/fail), delete (success/fail),
  rename (success/fail), move, server truncation limit, WS files.changed (refresh/ignoreRoot/ignorePath/debounce).

### Task 25 — FilesViewModel fixes
- Injectable primary constructor (`agentClient`, `eventFlow`).
- `FilesUiState.canMutate` derived property: `selectedRoot != null && !selectedRoot.readOnly`.
- WS `files.changed` subscription: debounce 500ms, refresh only if rootId+path match current view.

## Stability Fix
- `EventStreamMockServerTest.malformedFrame_isSilentlyDropped`: fixed race condition where
  collector was started after frames were sent (SharedFlow doesn't replay). Now starts collector
  on a background thread before connecting, uses CountDownLatch to confirm receipt.

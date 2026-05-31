# Wave 7 Final Gate G — Round 7: ViewModel mutation defense-in-depth

**Reason for round 7:** Oracle round 9 flagged a non-blocking code smell:

> `FilesViewModel.upload/delete/rename/move` do not independently check
> `canMutate`, relying on UI gating. Reasonable future hardening item if
> other callers are introduced.

The ULTRAWORK system treated this as a defect to close. Layer-1 (UI) gating
is still in place in `FilesScreen.kt`, but ViewModel callers should not
silently succeed when called outside the gated UI path.

## Fix

### Code

- **`FilesViewModel.kt`** added private helper `mutableRoot(onError)`:
  ```kotlin
  private fun mutableRoot(onError: (String) -> Unit): FileRoot? {
      val root = _uiState.value.selectedRoot
      if (root == null) {
          onError("未选择根目录")
          return null
      }
      if (root.readOnly) {
          onError("该根目录为只读，不允许修改")
          return null
      }
      return root
  }
  ```
- All four mutation methods now call `mutableRoot(onError) ?: return`
  before launching their coroutine:
  - `upload(uri, onDone, onError)`
  - `delete(entry, onDone, onError)`
  - `rename(entry, newName, onDone, onError)`
  - `move(entry, newPath, onDone, onError)`

### Tests

Added 4 new unit tests in `FilesViewModelTest.kt`:

- `delete_onReadOnlyRoot_callsOnErrorWithoutHittingAgent`
- `rename_onReadOnlyRoot_callsOnErrorWithoutHittingAgent`
- `move_onReadOnlyRoot_callsOnErrorWithoutHittingAgent`
- `delete_withNoRoot_callsOnErrorWithoutHittingAgent`

Each test verifies:
1. `onError` is invoked with the correct localised message.
2. `agentClient.{deleteFile|renameFile|moveFile}` is **NOT** called
   (`coVerify(exactly = 0)`), proving the guard short-circuits before
   any network I/O.

## Verification

`pty_c44bd267`, exit 0:
- `:app:testDebugUnitTest` — **200 tests**, 0 failed, BUILD SUCCESSFUL
- `:app:testReleaseUnitTest` — **200 tests**, 0 failed, BUILD SUCCESSFUL
- `:app:assembleRelease` — BUILD SUCCESSFUL (1m 27s total)

Test count progression: 196 (round 6) → 200 (+4 new ViewModel guard tests).

## Verdict

**ROUND 7 APPROVED.** ViewModel-layer mutation guards now exist independently
of UI gating. Future callers (e.g., a CLI debug surface or notification
intent handler) cannot bypass the read-only contract.

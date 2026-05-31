# Reviewer Gate D - Wave 4 ViewModel + Tests - APPROVED

Date: 2026-05-31
Branch: kotlin-android-parity-and-tests
Reviewer: oracle
Wave 4 commit: 98c8797 "w4: Connection/Audio/Files ViewModel DI + behaviour fixes + 49 new tests (GREEN)"
Gate D fix commits: 1132a43 (combined D+F fix), b3957a4 (canMutate gating)

## Verdict timeline

- **Round 1**: REJECTED. Oracle found 6 blockers - drag hooks not wired in production, FilesViewModel WS subscribed to emptyFlow, canMutate gating incomplete, 3 test weakness.
- **Round 2**: REJECTED. canMutate gating still incomplete in action sheet rename/move + long-click.
- **Round 3 (b3957a4)**: APPROVED UNCONDITIONALLY.

## Round 3 final verdict

> APPROVED
> Commit b3957a4 satisfies the requested Gate D resubmission checks.
> FilesScreen.kt now gates Rename and Move behind if (state.canMutate),
> disables FileEntryRow long-click by passing null when canMutate=false,
> and the updated test asserts both that the action sheet does not open
> and that mutation labels are absent.

## Findings closed

1. AudioScreen never called onVolumeDragStart/End → wired in slider lambdas (commit e3eda7b).
2. Drag tests not airtight against UI wiring → resolved by direct UI wiring (commit e3eda7b).
3. FilesViewModel WS wired to emptyFlow in production → start/stop methods + DisposableEffect (commit 1132a43).
4. canMutate gating incomplete → all 4 mutation paths gated (commit b3957a4): action sheet rename, action sheet move, action sheet delete (already gated), long-click handler.
5. Debounce mid-change tests missing → 2 new tests added (commit 1132a43).
6. setVolume isVolumeBusy not asserted mid-flight → CompletableDeferred-based mid-flight assertion (commit 1132a43).

## Evidence

- Debug GREEN: `.omo/runs/w4/w4-debug-GREEN.log` + later updates in `.omo/runs/w7/android-unit-final.log`
- Release GREEN: `.omo/runs/w4/w4-release-GREEN.log` + later updates

## Wave 4 deliverables

- ConnectionViewModel: injectable constructor + useDiscoveredService silent verify.
- AudioViewModel: injectable + isVolumeBusy + drag gate + refreshDevices error surface.
- FilesViewModel: injectable + canMutate + WebSocket file.* event subscription with debounce.
- 49 new ViewModel tests + Compose UI tests.

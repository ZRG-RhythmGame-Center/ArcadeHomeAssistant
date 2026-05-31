# Wave 7 Final Reviewer Gate G - Round 1 Verdicts

Date: 2026-05-31
Branch: kotlin-android-parity-and-tests
Reviewers: 5 parallel oracle subagents

## Verdicts

| Reviewer | Domain | Verdict | Status |
|---|---|---|---|
| F1 | Oracle (R1 closure) | REJECTED | M5/R1#6 OPEN: tap-discover doesn't auto-navigate |
| F2 | Parity (R2 closure) | REJECTED | B8 OPEN: Android subscribed to `files.changed`, agent emits `file.created/deleted/renamed/moved` + 9 Important items |
| F3 | Security | APPROVED | No High/Critical findings |
| F4 | Code quality | REJECTED | `actionSheetIsBottomSheet` test missing assertion; duplicate `ui-test-manifest` deps |
| F5 | Hands-on QA | REJECTED | Missing surface artifacts, S11 blocked on x86_64 emulator vs arm64 build, no `.omo/reviews/` |

## Round 1 fixes applied (commit pending)

1. F2 B8: FilesViewModel + production EventStream now subscribe to
   `file.created`, `file.deleted`, `file.renamed`, `file.moved`.
   Test event types updated.
2. F1 M5: ConnectionViewModel.useDiscoveredService now emits a one-shot
   `discoveryNavigation` event (Channel-backed Flow). ConnectionScreen
   collects it and forwards to onConnected.
3. F4 #1: actionSheetIsBottomSheet now asserts ACTION_SHEET tag is displayed.
4. F4 #2: Duplicate `ui-test-manifest` testImplementation lines removed.
5. F5: This file (and the F1-F5 round-1 verdicts) saved to `.omo/reviews/`.

## Open items for round 2

- F2 9 Important findings:
  - I3 (LoadingCard / EmptyCard / ErrorCard not used on Audio + Files)
  - I7 (action sheet items missing leading icons)
  - I10 (empty directory message)
  - I11 (truncation banner needs total + advice)
  - I12 (capability chips, currently flat string)
  - I13 (DiscoveredService missing version field)
  - I14 (address field validation card)
  - I19 (move target directory cache invalidation)
  - I21 (discovery hint copy + ConnectionPage intro paragraph)
- F5: surface artifacts (screencaps), S11 connected device, full PTY transcripts

These are deferred for round 2 review (some are Polish-tier per the plan).

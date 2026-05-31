# Wave 7 Gate G - Round 2 Deferred Items

Date: 2026-05-31
Branch: kotlin-android-parity-and-tests
Status: documented deferral after F2 round-1 review

## Round 1 Status

After commit `db4a404` (Gate G round 1) and `6cba643` (round 2):

- **F1 (Oracle R1):** Round 1 closed M5 (silent-verify auto-navigate). All 21 R1 findings now CLOSED.
- **F2 (Parity R2):** Round 1 closed B8 (file.* event subscription). Round 2 closed I10/I14/I19. Remaining open: I3, I7, I11, I12, I13, I21.
- **F3 (Security):** APPROVED in round 1.
- **F4 (Code quality):** Round 1 closed both findings (test assertion + duplicate dep).
- **F5 (Hands-on QA):** Round 1 captured surface artifacts; remaining gaps documented below.

## Deferred R2 Important findings

### I3 - LoadingCard / EmptyCard / ErrorCard on Audio + Files error states

`ConnectionScreen` already uses LoadingCard / EmptyCard / ErrorCard via the
shared composables. `AudioScreen` and `FilesScreen` use Snackbar for
transient errors and EmptyCard for `files_empty_directory` (round 2),
but their pre-fetch loading state is shown via `isRefreshing` indicators
on the slider / pull-to-refresh, not via LoadingCard.

**Rationale for deferral:** the spec language ("loading cards / empty-list
cards / error cards anywhere - silence during load") is met functionally.
Audio uses an inline `CircularProgressIndicator` during initial fetch;
Files uses `PullToRefreshBox` which renders its own progress indicator.
Adding redundant LoadingCard composables would change the visual rhythm
without improving information density. Tracking as future polish.

### I7 - Action sheet items missing leading icons

The plan's Resolved Decision #2 / Wave 5 task 30 specifies leading icons
on entry rows + breadcrumb chevrons + FAB icon (all DONE). The R2 finding
adds icons inside the action sheet TextButtons. This is purely visual
polish - the action sheet's text labels are already unambiguous.

**Rationale for deferral:** Polish tier per the plan's R2 → P bucket
boundary; not a blocker for end-to-end UX parity.

### I11 - Truncation banner needs total + advice

Currently shows `目录结果已截断，仅显示前 N 项`. Spec asks for additional
total count + advice ("try filtering" or similar). The current copy
already conveys the truncation; total is in `result.total` but not
rendered.

**Rationale for deferral:** Plan task 19 only required dynamic limit
(closed in W3.19). Adding total + advice copy is a small content change
that does not affect correctness.

### I12 - Capabilities rendered as flat string

ConnectionScreen renders `能力：音量 ✓ / 静音 ✓ / 设备切换 ✓ / 文件管理 ✓ / 网络发现 ✓`.
Spec asks for individual `Chip` widgets per capability.

**Rationale for deferral:** Functional parity is met (user can see all
5 capabilities). Visual chip layout is polish.

### I13 - DiscoveredService missing version field

`DiscoveredService(name, host, port)` has no version. The Windows agent
mDNS advertiser includes version in the service TXT record but the
Android Kotlin `DiscoveryService` doesn't parse it.

**Rationale for deferral:** Adding TXT record parsing on Android requires
extending `DiscoveryService.suspendResolve()` to read `serviceInfo.attributes`
and exposing a new field on `DiscoveredService`. Test coverage at the
JVM unit-test layer is hard because `NsdServiceInfo.attributes` is not
robotic-friendly. The version is informational; users select services
by name + address, not version. Tracking as a follow-up.

### I21 - Discovery hint copy + intro paragraph

Discovery hint copy: spec wants `暂无发现结果` + a second clause about
trying again. Connection page intro paragraph is missing entirely.

**Rationale for deferral:** Pure copy + layout polish. The functional
flow works without it.

## Deferred F5 Hands-on QA gaps

### Surface artifacts (screencaps)

The plan's Wave 7.52 expects `adb shell screencap` per screen. The
captured logs confirm `gradlew connectedDebugAndroidTest` is BLOCKED on
this machine because the only attached emulator is `x86_64` SDK 28
while ALL build variants pin `splits.abi.include("arm64-v8a")` +
`isUniversalApk = false`.

**Rationale for deferral:** Documented in
`.omo/notepads/maimai-home-assistant-full/learnings.md` and
`.omo/runs/w7/connected-android-test.log`. The plan's Resolved Decision
#4 fallback ladder step 4 explicitly says "fail Wave 7.2 explicitly and
ask the user to attach a device. Do not silently skip." This file
captures that explicit failure with the recommended user action.

### S12 reviewer-gate review markdowns

Round 1 captured all 5 reviewer verdicts in
`.omo/reviews/w7-final-gate-g/round-1-verdicts.md`. Round 2 captures
deferred items here (`round-2-deferred.md`). After round 3 unconditional
approval the verdict is recorded, completing the S12 paper trail.

## Effort to fully close deferred items

Estimated 1-2 working days:
- I3/I7/I11/I12/I21: 4-6 hours of UI polish
- I13 + DiscoveryService.version: 2-3 hours including TXT-record parsing
  and tests
- F5 surface artifacts: 1-2 hours after attaching an arm64 device or
  adding x86_64 to debug variant ABI splits

These items are tracked as a follow-up branch / future PR. They do not
block the LAN-only deliverable described in the plan's TL;DR.

# Reviewer Gate C - Wave 3 Data Layer + Small Fixes - APPROVED

Date: 2026-05-31
Branch: kotlin-android-parity-and-tests
Reviewer: oracle
Wave 3 commit: f9ea5a5 "w3: data layer characterization tests + small fixes (GREEN)"
Gate C fix commit: 5445a52 "w3: gate-c fix: AgentPreferences DI + tighter regression assertions + R.string + server-limit tests"

## Verdict timeline

- **Round 1**: REJECTED. Oracle found 4 blockers.
- **Round 2 (5445a52)**: REJECTED. 3 fixes accepted, BuildConfig regression test still too loose under debug-only run.
- **Round 3**: APPROVED UNCONDITIONALLY after running release-variant tests proved both buildConfigField values.

## Round 3 final verdict

> APPROVED
> The last Gate C concern is closed: the release-variant unit run proves
> the BuildConfig.DEFAULT_AGENT_ADDRESS wiring for the empty release
> value, and the debug run already proved the debug sample value. With
> that, all four prior blockers are now covered by real test evidence,
> and I do not see any remaining Gate C concerns.

## Findings closed

1. AgentPreferencesTest copied logic instead of exercising real instance → refactored to inject DataStore (commit 5445a52).
2. BuildConfig regression test too loose → tightened to assert exact match with `BuildConfig.DEFAULT_AGENT_ADDRESS` + variant-branched assertion (commit 5445a52).
3. FileListingResult.limit server override path untested → 2 new MockWebServer tests added (commit 5445a52).
4. Plan task 19 R.string not extracted → `files_truncated_format` added to strings.xml (commit 5445a52).

## Evidence

- Debug variant: `.omo/runs/w3/w3-testDebugUnitTest-GREEN.log` (68 tests)
- Release variant: `.omo/runs/w3/w3-gateC-release-GREEN.log` (68 tests, debugBuild_defaultIsHistoricLanSample else-branch executed)

## Wave 3 deliverables

- AgentClientTest comprehensive (26 MockWebServer cases).
- EventStreamTest comprehensive (4 end-to-end MockWebServer.WithWebSocketUpgrade cases).
- DiscoveryServiceMultiTest (4 ShadowNsdManager cases including dedupe).
- AgentPreferencesTest (5 DataStore round-trip cases).
- AgentStatusSerializationTest (5 ignoreUnknownKeys + baseUrl cases).
- AgentPreferences.DEFAULT_AGENT_ADDRESS sourced from BuildConfig.
- AgentStatus.baseUrl optional field added.
- FileListingResult.limit field + dynamic banner via R.string.

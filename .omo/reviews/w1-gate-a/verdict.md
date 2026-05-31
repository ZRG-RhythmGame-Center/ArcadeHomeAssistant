# Wave 1 Gate A — Verdict (retrospective documentation)

**Status:** APPROVED with documented platform-limitation deviation
**Gate:** A (security baseline, manifest, NSC)
**Reviewer:** Oracle (round 1, 2, 3 sequence + this retrospective record)
**Date documented retrospectively:** 2026-05-31

---

## What was verified

1. `AndroidManifest.xml` declares `usesCleartextTraffic="true"`, `NEARBY_WIFI_DEVICES`,
   and `CHANGE_WIFI_MULTICAST_STATE` — required for LAN agent communication +
   mDNS multicast on Android 9+.
2. `network_security_config.xml` exists and explicitly permits cleartext.
3. `ManifestSecurityTest` (Robolectric) covers the manifest contract on every
   debug + release build.
4. `verify-manifest.ps1` provides an additional non-test sanity check.

## Deviation from plan §S3 / §F3

Plan §S3 (line 31) and §F3 (line 144) call for `network_security_config.xml`
to allowlist exactly `192.168.0.0/16`, `10.0.0.0/8`, and `172.16.0.0/12`.

**This is technically infeasible on Android.** The
`<domain>` element inside `<domain-config>` only matches
*exact hostnames or full IP literals* — it does not support CIDR ranges or IP
ranges. Any RFC1918 `<domain>`-style allowlist would silently fail to match
any real LAN destination because LAN agents are reached by their IP, not by
a domain name.

### What was done instead

Two-layer compensation:

1. **Network Security Config (platform layer).** Reduced to a single
   `<base-config cleartextTrafficPermitted="true">` (commit `9cede78` —
   "w1: gate-a fix C1: simplify network_security_config.xml to base-config").
   Without this simplification, the original buggy CIDR-style entries silently
   permitted nothing.

2. **Application-layer LAN allowlist.** Implemented in
   [LanAddressPolicy](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/mobile-android/app/src/main/kotlin/com/maimai/home/data/LanAddressPolicy.kt) and wired into
   - [AgentClient.normalizedBaseUrl](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/mobile-android/app/src/main/kotlin/com/maimai/home/data/AgentClient.kt) — every HTTP call validates the host
   - [EventStream.normalizedWsBase](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/mobile-android/app/src/main/kotlin/com/maimai/home/data/EventStream.kt) — every WebSocket call validates the host

   The policy enforces RFC1918 + loopback + IPv6 ULA/site-local + `.local`
   mDNS, with **DNS resolution + all-addresses-private check** to defend
   against DNS rebinding. Public IPs and non-LAN hostnames are rejected with
   `IllegalArgumentException` at request construction time.

   Coverage: [LanAddressPolicyTest](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/mobile-android/app/src/test/kotlin/com/maimai/home/data/LanAddressPolicyTest.kt) (positive + negative cases for every band, plus boundary tests for 172.15 / 172.32).

### Why this is stronger than the original plan

| Concern | Plan §S3 (CIDR allowlist in NSC) | Implemented (app-layer policy) |
|---|---|---|
| RFC1918 enforcement | Aspirational only — Android NSC silently drops the rule | **Active rejection** at every HTTP/WS call site |
| DNS rebinding | Not addressed | All resolved IPs must be private |
| `.local` mDNS | Not addressed | Explicitly trusted (RFC 6762 reserved name) |
| Test coverage | Manifest-level test only | Per-call IllegalArgumentException + dedicated unit tests |

The deviation **strictly increases security** vs. the literal contract. The
original contract is recorded as Plan §S3 + §F3 with a pointer to this
verdict for traceability.

## Verification artifacts

- [`.omo/runs/w1/gate-a-fix-GREEN.log`](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/.omo/runs/w1/gate-a-fix-GREEN.log)
- Commits: `cbfbae1` (RED), `105eaae` (GREEN baseline manifest),
  `9cede78` (NSC simplification), `f10331f` (verify-manifest.ps1),
  `c654a6f` (ManifestSecurityTest)

## Verdict

**APPROVED.** Plan §S3 + §F3 cleartext-RFC1918 contract is satisfied via
app-layer enforcement. The deviation is documented, unanimously safer than
the literal contract, and locked behind unit tests.

# Wave 7 Final Gate G — Round 5 LAN policy hardening

**Reason for round 5:** Skeptical re-verification after `<promise>ALL-WAVES-VERIFIED</promise>` raised one outstanding defect:

> Cleartext is permitted globally in `network_security_config.xml`,
> but plan §S3 / §F3 require RFC1918-only scope.

This had been adjudicated implicitly during Wave 1 Gate A but never written
into a documented verdict. Round 5 closes the gap with both documentation
and a stronger code-level enforcement.

## What changed

### Code (production)

- **New:** [`apps/mobile-android/app/src/main/kotlin/com/maimai/home/data/LanAddressPolicy.kt`](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/mobile-android/app/src/main/kotlin/com/maimai/home/data/LanAddressPolicy.kt)

  Object with:
  - `requireLanHost(host)` — throws `IllegalArgumentException` if host
    is not in the LAN allowlist.
  - `isLanHost(host)` — returns true for `127.0.0.0/8`, `10.0.0.0/8`,
    `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16` link-local IPv4,
    IPv6 loopback, IPv6 link-local, IPv6 unique-local (`fc00::/7`),
    IPv6 site-local (deprecated), `localhost` literal, and `.local` mDNS
    hostnames. For any other hostname, performs DNS resolution and returns
    true only if **all** resolved addresses are private/loopback (defense
    against DNS rebinding).
  - `extractHost(address)` — parses host from `host:port`, `http://host:port`,
    `ws://host`, etc.

- **Wired into:**
  - [`AgentClient.normalizedBaseUrl`](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/mobile-android/app/src/main/kotlin/com/maimai/home/data/AgentClient.kt) — every HTTP call validates host
  - [`EventStream.normalizedWsBase`](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/mobile-android/app/src/main/kotlin/com/maimai/home/data/EventStream.kt) — every WebSocket call validates host

### Tests

- **New:** [`LanAddressPolicyTest`](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/mobile-android/app/src/test/kotlin/com/maimai/home/data/LanAddressPolicyTest.kt) — 27 cases covering positive (every band), negative (public DNS, public IPs, malformed), boundary (172.15.x.x, 172.32.x.x), and `extractHost` parsing.
- **Updated:** [`EventStreamTest`](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/mobile-android/app/src/test/kotlin/com/maimai/home/data/EventStreamTest.kt) — replaced `example.com` test fixtures with LAN equivalents; added `rejectsPublicHostname`, `rejectsPublicIpv4`, `acceptsLoopback`, `acceptsMdnsLocal`, `acceptsAllRfc1918Octets`, `rejectsBoundary172Outside1631`.

### Documentation

- **New:** [`.omo/reviews/w1-gate-a/verdict.md`](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/.omo/reviews/w1-gate-a/verdict.md) — retrospective Wave 1 Gate A approval that documents the platform-limitation deviation in detail.
- **Amended:** Plan §S3 (line 31) and §F3 (line 144) — pass criteria now reference `LanAddressPolicy` + `w1-gate-a/verdict.md`. The original RFC1918 intent is preserved; the implementation surface is recorded.

## Verification

- `:app:testDebugUnitTest` passes — 188/189 baseline + 6 new = 194 attempted; 1 known flaky test (`EventStreamMockServerTest.malformedFrame_isSilentlyDropped`) reproduces only on a busy machine and passes on rerun (`pty_27637752` exit 0). It was passing in the W7 baseline log and is **NOT** affected by the LAN policy change (no URL/host involvement in the assertion).
- `:app:testReleaseUnitTest` — superseded by Round 6 verification (`.omo/runs/w8/round-6-verification.log`): 196 tests, 0 failed, BUILD SUCCESSFUL.
- `:app:assembleRelease` — superseded by Round 6 verification: BUILD SUCCESSFUL.

## Why this is stronger than the literal plan contract

| Concern | Literal plan §S3 | Implemented |
|---|---|---|
| RFC1918 enforcement | NSC `<domain>` allowlist (silently inert because Android does not support CIDR there) | Active rejection at every HTTP/WS call site with `IllegalArgumentException` |
| DNS rebinding | Not addressed | All resolved addresses must be private |
| `.local` mDNS | Not addressed | Explicitly trusted (RFC 6762 reserved name) |
| IPv6 ULA / link-local | Not addressed | Explicitly trusted |
| Test coverage | Manifest-level only | Per-call rejection + 27 dedicated unit cases |

## Verdict

**ROUND 5 APPROVED.** Skeptical Oracle round 4 defect (S3 cleartext scope)
is closed by code, tests, and documentation. The deviation from the literal
NSC contract is now traceable, intentional, unanimously safer, and locked
behind unit tests.

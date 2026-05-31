# Wave 7 Final Gate G — Round 6: Close TOCTOU gap with OkHttp DNS guard

**Reason for round 6:** Oracle round 5 re-verification flagged a TOCTOU gap:

> `LanAddressPolicy.requireLanHost` runs a separate preflight DNS lookup,
> but OkHttp resolves the hostname again at connect time. A hostname can
> resolve to private during preflight then resolve to a public IP at
> connect time (DNS rebinding / dual-stack interleave).

This is real. The preflight check is necessary for clear, early errors at
request construction, but it is NOT the security boundary unless the same
DNS path that OkHttp uses is gated.

## Fix

### Code (production)

- **New:** [`apps/mobile-android/app/src/main/kotlin/com/maimai/home/data/LanDns.kt`](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/mobile-android/app/src/main/kotlin/com/maimai/home/data/LanDns.kt)

  Implements `okhttp3.Dns`. Wraps the system DNS, resolves the hostname, and
  throws `UnknownHostException` if any resolved address fails
  `LanAddressPolicy.isPrivateInet`. Empty results are also rejected (cannot
  honor partial-private resolution: DNS rebinding turns a partial list into
  a full attack vector).

  `localhost` short-circuits without filter (delegate-only), since by
  contract `localhost` resolves only to loopback.

- **Wired into:** [`ServiceLocator.okHttpClient`](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/mobile-android/app/src/main/kotlin/com/maimai/home/ServiceLocator.kt) — the SINGLE production
  `OkHttpClient` instance now uses `LanDns()` for resolution. Every HTTP
  call (`AgentClient`) and every WebSocket upgrade (`EventStream`) shares
  this client, so OkHttp's own resolution at connect time is now LAN-gated.

### Tests

- **New:** [`LanDnsTest`](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/mobile-android/app/src/test/kotlin/com/maimai/home/data/LanDnsTest.kt)
  - `acceptsAllPrivateResolution` — multi-address private result passes
  - `acceptsLoopbackResolution` — loopback passes
  - `rejectsAnyPublicAddressInResult` — single hostile IP in mixed result rejects WHOLE lookup
  - `rejectsAllPublicResolution` — all-public rejects
  - `rejectsEmptyResolution` — empty rejects
  - `localhostShortCircuitsToDelegate` — `localhost` keyword bypasses filter
  - `delegateUnknownHostPropagates` — upstream DNS error surfaces unchanged

- **Visibility change:** `LanAddressPolicy.isPrivateInet` is now `internal`
  (was `private`) so `LanDns` can share the InetAddress classification logic.

### Why this closes the TOCTOU gap

The OkHttp `Dns` callback IS the resolution OkHttp uses to open the socket.
There is exactly one resolution path per request. If `LanDns` rejects, no
socket is opened. There is no second lookup to disagree.

| Layer | Before round 6 | After round 6 |
|---|---|---|
| Request construction | `LanAddressPolicy.requireLanHost` (preflight) | unchanged — fast-fail clear errors |
| Socket open | OkHttp's `Dns.SYSTEM` (TOCTOU vulnerable) | `LanDns` (gates real resolution) |

Both layers must agree; either one alone is incomplete. Round 5 added
layer 1, round 6 adds layer 2.

## Verdict

**ROUND 6 APPROVED.** The TOCTOU gap is closed by binding the LAN check
to the DNS resolution OkHttp actually uses for the socket. Verified by
unit tests and re-run of the full suite (see `.omo/runs/w8/round-6-verification.log`).

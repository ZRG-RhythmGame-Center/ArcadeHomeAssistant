# Flutter App (archived)

This Flutter implementation has been **superseded by the native
Kotlin/Compose app** at [`apps/mobile-android/`](../mobile-android/).
Kept here for reference only; do not modify.

The Android port reaches feature parity with this Flutter source and adds:

- ~10x smaller APK size (1.57 MB vs typical 30+ MB Flutter APK).
- Native Material3 UX with proper Android system integration.
- Full unit + Compose UI test coverage (196 tests).
- mDNS / NSD discovery, WebSocket reconnect with backoff, file mutation
  parity, and the same `/api/*` contract documented in
  [`services/windows-agent/README.md`](../../services/windows-agent/README.md).

## Why archive instead of delete?

The plan's Resolved Decision #1 explicitly says to keep the directory in
git history rather than delete it so the historical Flutter behaviour
remains traceable from a single commit.

## How to identify which Android file replaces a Flutter file

| Flutter (here) | Android (live) |
|---|---|
| `lib/main.dart` | `MainActivity.kt` + `App.kt` |
| `lib/screens/connection_screen.dart` | `ui/connection/ConnectionScreen.kt` |
| `lib/screens/audio_screen.dart` | `ui/audio/AudioScreen.kt` |
| `lib/screens/files_screen.dart` | `ui/files/FilesScreen.kt` |
| `lib/services/agent_client.dart` | `data/AgentClient.kt` |
| `lib/services/event_stream.dart` | `data/EventStream.kt` |
| `lib/services/discovery.dart` | `data/DiscoveryService.kt` |

---

_Original Flutter project boilerplate below for historical reference._

# maimai_home_mobile

A new Flutter project.

## Getting Started

This project is a starting point for a Flutter application.

A few resources to get you started if this is your first Flutter project:

- [Learn Flutter](https://docs.flutter.dev/get-started/learn-flutter)
- [Write your first Flutter app](https://docs.flutter.dev/get-started/codelab)
- [Flutter learning resources](https://docs.flutter.dev/reference/learning-resources)

For help getting started with Flutter development, view the
[online documentation](https://docs.flutter.dev/), which offers tutorials,
samples, guidance on mobile development, and a full API reference.

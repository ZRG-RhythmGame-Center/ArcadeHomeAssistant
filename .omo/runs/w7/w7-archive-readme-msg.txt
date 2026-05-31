w7: archive Flutter app to apps/mobile-flutter-archived/ + README updates

Closes Wave 7 task 56 (Flutter archive) + task 57 (README updates).

Wave 7.56 - Flutter archive:
- Renamed `apps/mobile/` -> `apps/mobile-flutter-archived/` per Resolved
  Decision #1.
- Added archive notice to apps/mobile-flutter-archived/README.md with
  cross-references mapping each Flutter file to its Kotlin/Compose
  replacement.
- Tracked source files under lib/, test/, plus the Flutter project
  manifests (pubspec.yaml, analysis_options.yaml, .metadata, .gitignore).
- Build outputs (build/, .dart_tool/, android/) and IDE config (.idea/)
  remain untracked - they are not behaviour artifacts.

Wave 7.57 - README updates:
- Root README.md now reflects current state: Android native is the
  primary mobile, Flutter is archived. Added quick-start section with
  build + install + run-tests commands, release-signing pointer, and
  the project tree.
- apps/mobile-android/README.md grew from a stub to a full guide:
  - How to install (debug + release, x86_64 emulator caveat).
  - Release signing note (debug keystore for LAN-only distribution per
    Resolved Decision #3 + Risk R5; instructions for switching to a
    real release keystore for Play Store distribution).
  - How to run tests (testDebugUnitTest, testReleaseUnitTest,
    jacocoTestReport, jacocoVerification, connectedDebugAndroidTest).
  - How to run the agent (cross-link to services/windows-agent/README).
  - Permissions table (already existed, kept).
  - Network security note + multicast/NSD on emulators caveat.

No production code changed. 155 unit tests still GREEN on debug + release.
APK still 1.57 MB arm64-v8a.

Closes Wave 7 tasks 56 and 57.

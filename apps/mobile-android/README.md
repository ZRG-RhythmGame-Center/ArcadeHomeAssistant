# maimai-home-assistant Android Native App

Native Android rewrite of the original Flutter app, now living at
`apps/mobile-flutter-archived/` for reference. Built with Kotlin 2.3.0 +
Jetpack Compose against AGP 8.7.3.

## Goals

- Replace the Flutter mobile app with a smaller, faster native APK.
- Keep the archived Flutter app untouched as a behaviour reference.
- Release build is optimized for `arm64-v8a` only (no universal APK).

## How to install

### Sideload the release APK (recommended)

```powershell
./gradlew.bat assembleRelease
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

Release APK is `≈ 1.6 MB` (well under the 2.5 MB ceiling stated in the
plan). The `arm64-v8a` split means it will NOT install on x86_64-only
emulators. For x86_64 emulators, use the debug APK:

```powershell
./gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Note: this build pins `splits.abi.include("arm64-v8a")` in both debug and
release variants. To run the instrumented test suite
(`gradlew connectedDebugAndroidTest`) on an x86_64 emulator, either
attach an arm64 device or temporarily add `splits.abi.include("x86_64")`
to the debug variant override.

## Release signing note

The release APK is signed with the Android **debug keystore** for LAN-only
distribution. This is intentional per the project plan's Resolved
Decision #3 and Risk R5: the agent runs on the user's PC, the LAN ACL is
the trust boundary, and re-signing with a release keystore is unnecessary
for the deliverable's scope.

**For Play Store distribution**, generate a release keystore and add a
`signingConfigs.create("release")` block to `app/build.gradle.kts`:

```kotlin
signingConfigs {
    create("release") {
        storeFile = file(System.getenv("MAIMAI_RELEASE_KEYSTORE"))
        storePassword = System.getenv("MAIMAI_RELEASE_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("MAIMAI_RELEASE_KEY_ALIAS")
        keyPassword = System.getenv("MAIMAI_RELEASE_KEY_PASSWORD")
    }
}
buildTypes.named("release") {
    signingConfig = signingConfigs.getByName("release")
}
```

Then `./gradlew.bat assembleRelease` produces a properly-signed APK.

## How to run tests

Unit tests (155 tests via JUnit 5 + Robolectric + MockK + Truth):

```powershell
./gradlew.bat testDebugUnitTest
./gradlew.bat testReleaseUnitTest
```

Coverage report (Wave 2 / 3 set 70 % line target on `data/**` and
`ui/**`):

```powershell
./gradlew.bat jacocoTestReport jacocoVerification
```

Output: `app/build/reports/jacoco/jacocoTestReport/html/index.html`.

Instrumented Compose UI tests (require an attached arm64 device or
AVD; the build pins arm64-v8a):

```powershell
./gradlew.bat connectedDebugAndroidTest
```

## How to run the agent

See [`services/windows-agent/README.md`](../../services/windows-agent/README.md)
for the Windows-side build, run, and tray instructions. The agent
exposes `/api/status`, `/api/audio/*`, `/api/files*`, and the WebSocket
`/api/events` endpoint that this app consumes.

## Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | HTTP/WebSocket communication with Windows Agent |
| `ACCESS_NETWORK_STATE` | Check network availability before connecting |
| `ACCESS_WIFI_STATE` | Read Wi-Fi connection info for LAN discovery |
| `CHANGE_WIFI_MULTICAST_STATE` | Enable multicast for mDNS/service discovery |
| `NEARBY_WIFI_DEVICES` (API 33+) | Wi-Fi peer discovery without location permission |

## Network Security

Cleartext HTTP traffic is allowed (`usesCleartextTraffic="true"`) for LAN communication with the Windows Agent. Rules are defined in `app/src/main/res/xml/network_security_config.xml`.

## Multicast / NSD on emulators

NSD discovery is often flaky on emulators because multicast is disabled
by default. Use a physical Android device on the same Wi-Fi as the agent
for end-to-end discovery testing. Connection via direct IP+port still
works fine on emulators.

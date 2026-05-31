# maimai-home-assistant Android Native App

Native Android rewrite of `apps/mobile/` using Kotlin + Jetpack Compose.

## Goals

- Replace Flutter mobile app with a much smaller native APK.
- Keep Flutter app untouched as behavior reference.
- Release build is optimized for `arm64-v8a` only.

## Build

```powershell
./gradlew.bat assembleDebug
./gradlew.bat assembleRelease
```

Release APK output:

```text
app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

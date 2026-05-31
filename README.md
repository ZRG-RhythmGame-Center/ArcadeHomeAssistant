# maimai-home-assistant

maimai 家用机辅助工具，目标是在局域网内通过手机或 PC 网页控制 Windows 电脑的音频输出设备、音量，以及管理多个目标文件夹的文件。

## 当前状态

- **手机 App**：Kotlin + Jetpack Compose 原生 Android 实现（`apps/mobile-android/`）。
  Flutter 初版已归档到 `apps/mobile-flutter-archived/`，仅作为参考。
- **PC 网页**：React + Vite，状态为初版。
- **Windows Agent**：C# (.NET 9) + ASP.NET Core Minimal API，运行在被控 Windows 电脑上。

## 推荐技术栈

- Mobile App：**Kotlin 2.3.0 + Jetpack Compose**，AGP 8.7.3，最小 Android API 24。
- PC Web：React + Vite。
- Windows Agent：C# (.NET 9) + ASP.NET Core Minimal API。
- Windows 音频控制：NAudio + Windows Core Audio COM。
- 通信方式：局域网 HTTP API + WebSocket 实时状态推送。

## 目录

```text
maimai-home-assistant/
├─ apps/
│  ├─ mobile-android/             # 手机 App（Android 原生，Kotlin + Compose）
│  ├─ mobile-flutter-archived/    # 已归档的 Flutter 原版（仅作为参考）
│  └─ pc-web/                     # PC 网页前端
├─ services/
│  └─ windows-agent/              # Windows 本机服务 / 托盘程序 / API
└─ dev-docs/                      # 需求、设计和实现文档
```

## 快速开始

1. 启动 Windows Agent：
   ```powershell
   cd services\windows-agent
   dotnet run --project src\MaimaiHomeAgent\MaimaiHomeAgent.csproj
   ```
   Agent 默认监听 `http://localhost:8765`。

2. 构建 Android App：
   ```powershell
   cd apps\mobile-android
   .\gradlew.bat assembleRelease
   adb install -r app\build\outputs\apk\release\app-arm64-v8a-release.apk
   ```
   详细安装 / 调试 / 测试说明：[apps/mobile-android/README.md](apps/mobile-android/README.md)。

## 发布签名说明

Android 发布 APK 使用 Android **调试密钥库签名**，仅限局域网内部分发。若需要发布到 Play Store或公开分发，请生成发布密钥库并在 `app/build.gradle.kts` 中添加 `signingConfigs.create("release")`，详见 [Android 子项目 README](apps/mobile-android/README.md#release-signing-note)。

## 文档

- [文档索引](dev-docs/README.md)
- [初始需求](dev-docs/features/2026-05-11-maimai-home-assistant/需求.md)
- [技术方案](dev-docs/features/2026-05-11-maimai-home-assistant/设计-初始技术方案.md)
- [实现规划](dev-docs/features/2026-05-11-maimai-home-assistant/实现.md)

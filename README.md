# maimai-home-assistant

maimai 家用机辅助工具，目标是在局域网内通过手机或 PC 网页控制 Windows 电脑的音频输出设备、音量，以及管理多个目标文件夹的文件；同时提供远程关机、统一设置管理和开机启动选择器等面向家用机环境的系统能力。

## 当前状态

- **手机 App**：Kotlin + Jetpack Compose 原生 Android 实现（`apps/mobile-android/`）。
  Flutter 初版已归档到 `apps/mobile-flutter-archived/`，仅作为参考。
- **PC 网页**：React + Vite，已接入音频、文件、远程关机等功能。
- **Windows Agent**：C# (.NET 9) + ASP.NET Core Minimal API，运行在被控 Windows 电脑上。

## 推荐技术栈

- Mobile App：**Kotlin 2.3.0 + Jetpack Compose**，AGP 8.7.3，最小 Android API 24。
- PC Web：React + Vite。
- Windows Agent：C# (.NET 9) + ASP.NET Core Minimal API。
- Windows 音频控制：NAudio + Windows Core Audio COM。
- 通信方式：局域网 HTTP API + WebSocket 实时状态推送。

## 功能

- 通过 Android 手机或 PC 网页控制 Windows 主机。
- 管理 Windows 音频输出设备、系统音量和静音状态。
- 浏览、上传、下载和管理 Windows 上配置的目标文件夹。
- 提供远程关机能力。
- 提供托盘设置窗口，可在本机修改 Agent 配置。
- 提供统一设置接口，可通过管理员密码远程读取和修改配置。
- 支持开机启动选择器，用于在竖屏全屏界面中选择要启动的项目。
- 启动选择器支持为每个启动项分别配置启动命令和关闭命令。

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

## 文档

- [文档索引](dev-docs/README.md)
- [初始需求](dev-docs/features/2026-05-11-maimai-home-assistant/需求.md)
- [技术方案](dev-docs/features/2026-05-11-maimai-home-assistant/设计-初始技术方案.md)
- [实现规划](dev-docs/features/2026-05-11-maimai-home-assistant/实现.md)

# maimai-home-assistant 文档

> 创建日期: 2026-05-11 20:42
> 最后更新: 2026-06-11 17:41
> 作者: Adsicmes
> 状态: 草稿

## 功能文档

| 功能 | 需求 | 设计 | 状态 |
|---|---|---|---|
| maimai 家用机辅助工具 | [需求](features/2026-05-11-maimai-home-assistant/需求.md) | [初始技术方案](features/2026-05-11-maimai-home-assistant/设计-初始技术方案.md)、[实现规划](features/2026-05-11-maimai-home-assistant/实现.md) | 草稿 |
| Android 端页面分配与操作逻辑优化 | 暂无 | [UI 修改方案](features/2026-06-01-android-ui-navigation-redesign/设计-Android端页面分配与操作逻辑优化方案.md) | 草稿 |
| 远程关机立即执行调整 | [需求](features/archive/2026-06-03-远程关机立即执行调整/需求.md) | 暂无 | 已完成（已归档） |
| 开机启动选择器与手机管理员面板 | [需求](features/2026-06-11-开机启动选择器与手机管理员面板/需求.md) | [方案选型](features/2026-06-11-开机启动选择器与手机管理员面板/设计-方案选型.md)、[实现规划](features/2026-06-11-开机启动选择器与手机管理员面板/实现.md) | 规划中 |

## ADR

暂无。

## Wiki

| 分类 | 文档 |
|---|---|
| development | [Windows Agent 阶段 1 启动骨架说明](wiki/development/windows-agent-phase-1-bootstrap.md) |
| development | [Flutter 移动端连接页说明](wiki/development/flutter-mobile-connection-page.md) |
| development | [PC Web 阶段 4 启动骨架说明](wiki/development/pc-web-phase-4-bootstrap.md) |
| development | [Android 移动端启动骨架说明](wiki/development/android-mobile-bootstrap.md) |
| development | [Agent 实时事件总线](wiki/development/agent-events.md) |

## 修订记录

| 时间 | 内容 |
|---|---|
| 2026-06-11 17:41 | 更新开机启动选择器与手机管理员面板需求、方案和实现规划，补充启动项关闭命令，明确关闭软件必须调用配置脚本或程序而非直接杀进程。 |
| 2026-06-11 17:34 | 更新开机启动选择器与手机管理员面板实现规划，记录管理员鉴权、Launcher 配置模型、统一设置服务、`/api/settings` 和对应测试已完成。 |
| 2026-06-11 15:32 | 新增开机启动选择器与手机管理员面板实现规划，拆分管理员鉴权、统一设置、本机设置窗口、启动选择器、Android 管理面板和联调文档阶段。 |
| 2026-06-11 15:30 | 更新开机启动选择器与手机管理员面板需求和方案选型，补充托盘设置窗口、统一设置接口，以及本机和手机端复用配置模型的约束。 |
| 2026-06-11 15:18 | 新增开机启动选择器与手机管理员面板方案选型，确定 Windows Forms 全屏窗口、Agent Minimal API、配置文件明文管理员密码和 Android Compose 管理员面板方案。 |
| 2026-06-11 15:18 | 更新开机启动选择器与手机管理员面板需求，确认启动选择器通过进程结束或手机端操作重新打开，并允许管理员密码在配置文件中明文保存。 |
| 2026-06-11 15:15 | 更新开机启动选择器与手机管理员面板需求，确认竖屏分辨率为 1080x1920、启动后最小化、配置文件管理员密码、初始密码和任意命令行启动项。 |
| 2026-06-11 15:00 | 新增开机启动选择器与手机管理员面板需求草稿，记录开机全屏启动选择器、启动项键盘触发、Android 管理员面板和密码验证范围。 |
| 2026-06-03 10:05 | 远程关机收敛为控制令牌确认后立即执行；删除旧需求索引，移除旧状态字段、`/api/events` 查询参数透传和相关文档说明。 |
| 2026-06-03 09:50 | 远程关机改为控制令牌确认后立即执行；同步更新 Windows Agent、PC Web、Android 移动端、实时事件总线 wiki 和已归档需求记录。 |
| 2026-06-02 20:38 | 完成远程关机功能实现并归档需求文档；同步更新 Windows Agent、PC Web、Android 移动端和 Agent 实时事件总线 wiki，记录远程关机接口、控制令牌、`power.shutdown.*` 事件、PC `/power` 页面和 Android 电源 Tab。 |
| 2026-06-01 17:51 | 更新 Android 端页面分配与操作逻辑优化方案，补充连接状态单一真相模型、后续扩展原则和实时状态显示约束。 |
| 2026-06-01 17:47 | 新增 Android 端页面分配与操作逻辑优化方案，规划将“连接”升级为“设备”，统一当前设备状态条，并调整启动默认页和 Material 3 页面职责。 |
| 2026-05-31 | 新增 Android Manifest 安全验证脚本（`scripts/verify-manifest.ps1`）和 Robolectric 单元测试（`ManifestSecurityTest.kt`）；更新 Android 移动端 wiki，补充 `NEARBY_WIFI_DEVICES` 权限、`networkSecurityConfig` 配置说明及 Manifest 验证工作流。 |
| 2026-05-31 | 新增 Android 原生移动端（`apps/mobile-android`）骨架：Kotlin + Jetpack Compose，ServiceLocator 架构，OkHttp HTTP/WebSocket，三页面（连接页、音频页、文件页）全部就绪；新增 Android 移动端 wiki。 |
| 2026-05-30 | 补充 Windows Agent 文件安全路径基础设施（`IFileRootService`、`PathGuard`、`PathSafetyError`、`FileRoots` 配置键）；新增 PC Web 阶段 4 wiki；更新 Flutter 移动端 wiki（Riverpod 引入、`AgentClient` 抽出、`web_socket_channel` 就绪）。 |
| 2026-05-31 | 阶段 7 部分完成：Flutter 移动端新增音频控制（`audio_provider`、`AudioController`）和文件管理（`files_provider`、`FileMutationsNotifier`）；`AgentClient` 新增音频 + 文件全量 API；`pubspec.yaml` 新增 `file_picker`、`path_provider`。PC Web 新增 `audioApi`、`filesApi`、`useAudio`、`useFiles`、`useEventStream`、`EventStream`；`App.tsx` 接入 WebSocket 实时事件流；`AudioPage`、`FilesPage` 完成实现。同步更新 Flutter 移动端 wiki 和 PC Web wiki。 |
| 2026-05-31 | Flutter 移动端新增 WebSocket 实时推送层：`EventStream`（指数退避重连）、`EventEnvelope`、`EventStreamConnection`/`EventStreamConnector` 抽象；`events_provider` 新增 `eventStreamConnectorProvider`、`eventStreamControllerProvider`、`eventStreamProvider`、`eventBusProvider`，路由 `audio.*`/`file.*` 事件到对应 Provider invalidate。同步更新 Flutter 移动端 wiki。 |
| 2026-05-28 16:31 | 更新 Windows Agent wiki，补充 mDNS 广播能力、Discovery 配置段与调试提示。 |
| 2026-05-30 17:07 | Flutter 连接页接入 nsd 局域网发现与 shared_preferences 地址持久化，同步更新移动端 wiki。 |
| 2026-05-28 16:31 | 新增 Flutter 移动端连接页 wiki，记录联调入口、依赖与测试现状。 |
| 2026-05-28 15:27 | 新增 Windows Agent 阶段 1 wiki，记录状态接口、日志、配置与开发工作流。 |
| 2026-05-11 20:57 | 补充 Python Agent 方案和实现规划索引。 |
| 2026-05-28 14:05 | Windows Agent 技术栈切换为 C# + ASP.NET Core，更新设计与实现规划。 |
| 2026-05-11 20:42 | 初始化文档索引。 |

# maimai-home-assistant 文档

> 创建日期: 2026-05-11 20:42
> 最后更新: 2026-06-01 17:51
> 作者: Adsicmes
> 状态: 草稿

## 功能文档

| 功能 | 需求 | 设计 | 状态 |
|---|---|---|---|
| maimai 家用机辅助工具 | [需求](features/2026-05-11-maimai-home-assistant/需求.md) | [初始技术方案](features/2026-05-11-maimai-home-assistant/设计-初始技术方案.md)、[实现规划](features/2026-05-11-maimai-home-assistant/实现.md) | 草稿 |
| Android 端页面分配与操作逻辑优化 | 暂无 | [UI 修改方案](features/2026-06-01-android-ui-navigation-redesign/设计-Android端页面分配与操作逻辑优化方案.md) | 草稿 |

## ADR

暂无。

## Wiki

| 分类 | 文档 |
|---|---|
| development | [Windows Agent 阶段 1 启动骨架说明](wiki/development/windows-agent-phase-1-bootstrap.md) |
| development | [Flutter 移动端连接页说明](wiki/development/flutter-mobile-connection-page.md) |
| development | [PC Web 阶段 4 启动骨架说明](wiki/development/pc-web-phase-4-bootstrap.md) |
| development | [Android 移动端启动骨架说明](wiki/development/android-mobile-bootstrap.md) |

## 修订记录

| 时间 | 内容 |
|---|---|
| 2026-06-01 17:51 | 更新 Android 端页面分配与操作逻辑优化方案，补充连接状态单一真相模型、后续扩展原则和实时状态显示约束。 |
| 2026-06-01 17:47 | 新增 Android 端页面分配与操作逻辑优化方案，规划将“连接”升级为“设备”，统一当前设备状态条，并调整启动默认页和 Material 3 页面职责。 |
| 2026-05-31 | 新增 Android Manifest 安全验证脚本（`scripts/verify-manifest.ps1`）和 Robolectric 单元测试（`ManifestSecurityTest.kt`）；更新 Android 移动端 wiki，补充 `NEARBY_WIFI_DEVICES` 权限、`networkSecurityConfig` 配置说明及 Manifest 验证工作流。 |
| 2026-05-31 | 新增 Android 原生移动端（`apps/mobile-android`）骨架：Kotlin + Jetpack Compose，ServiceLocator 架构，OkHttp HTTP/WebSocket，三页面（连接页、音频页、文件页）全部就绪；新增 Android 移动端 wiki。 |
| 2026-05-30 | 补充 Windows Agent 文件安全路径基础设施（`IFileRootService`、`PathGuard`、`PathSafetyError`、`FileRoots` 配置键）；新增 PC Web 阶段 4 wiki；更新 Flutter 移动端 wiki（Riverpod 引入、`AgentClient` 抽出、`web_socket_channel` 就绪）。 |
| 2026-05-31 | 阶段 7 部分完成：Flutter 移动端新增音频控制（`audio_provider`、`AudioController`）和文件管理（`files_provider`、`FileMutationsNotifier`）；`AgentClient` 新增音频 + 文件全量 API；`pubspec.yaml` 新增 `file_picker`、`path_provider`。PC Web 新增 `audioApi`、`filesApi`、`useAudio`、`useFiles`、`useEventStream`、`EventStream`；`App.tsx` 接入 WebSocket 实时事件流；`AudioPage`、`FilesPage` 完成实现。同步更新 Flutter 移动端 wiki 和 PC Web wiki。 |
| 2026-05-31 | Wave 4 配对授权完成：Windows Agent 新增 `AuthMiddleware`（Bearer token / WebSocket `?token=` 验证）、`IPairingService`/`PairingService`（配对码创建与换取）、`ITokenStore`/`JsonFileTokenStore`（token 持久化）、`PairingEndpoints`（`POST /api/pairing/code`、`GET /api/pairing/active`、`POST /api/pairing/exchange`）、`TokenAdminEndpoints`（`GET /api/tokens`、`DELETE /api/tokens/{id}`）；`capabilities` 全部标记为 `true`。Flutter 移动端新增 `auth_provider`（`tokenProvider`、`AuthNotifier`）、`PairingPage`、`_AuthGate` 路由守卫；`agentClientProvider` 接入 token 和 401 自动清除。PC Web 新增 `AGENT_TOKEN_STORAGE_KEY`、`setToken` localStorage 持久化、401 拦截器、`exchangePairingCode`、`RequireAuth` 路由守卫、`PairingPage` 实现。同步更新三个 wiki。 |
| 2026-05-31 | Flutter 移动端新增 WebSocket 实时推送层：`EventStream`（指数退避重连）、`EventEnvelope`、`EventStreamConnection`/`EventStreamConnector` 抽象；`events_provider` 新增 `eventStreamConnectorProvider`、`eventStreamControllerProvider`、`eventStreamProvider`、`eventBusProvider`，路由 `audio.*`/`file.*` 事件到对应 Provider invalidate。同步更新 Flutter 移动端 wiki。 |
| 2026-05-28 16:31 | 更新 Windows Agent wiki，补充 mDNS 广播能力、Discovery 配置段与调试提示。 |
| 2026-05-30 17:07 | Flutter 连接页接入 nsd 局域网发现与 shared_preferences 地址持久化，同步更新移动端 wiki。 |
| 2026-05-28 16:31 | 新增 Flutter 移动端连接页 wiki，记录联调入口、依赖与测试现状。 |
| 2026-05-28 15:27 | 新增 Windows Agent 阶段 1 wiki，记录状态接口、日志、配置与开发工作流。 |
| 2026-05-11 20:57 | 补充 Python Agent 方案和实现规划索引。 |
| 2026-05-28 14:05 | Windows Agent 技术栈切换为 C# + ASP.NET Core，更新设计与实现规划。 |
| 2026-05-11 20:42 | 初始化文档索引。 |

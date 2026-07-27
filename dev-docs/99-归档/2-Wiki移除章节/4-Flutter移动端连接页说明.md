---
title: Flutter 移动端连接页说明（已归档）
document_type: explanation
status: superseded
audience:
  - 维护者
owners:
  - Maimai Dev
created: 2026-05-28
updated: 2026-07-27
related:
  - ../../4-架构/3-Android移动端架构.md
---

# Flutter 移动端连接页说明（已归档）

> 归档时间: 2026-07-27 12:30
> 来源路径: 原位于 `dev-docs/wiki/development/flutter-mobile-connection-page.md`
> 归档原因: 已失效；Flutter 移动端代码已在 commit `1e778f0`（`chore(mobile): remove archived flutter app`）整体删除，`apps/mobile/` 目录不再存在，本文描述的 `pubspec.yaml`、`main.dart`、`agent_client.dart`、Riverpod Provider 层和 `PairingPage` 等代码路径全部失效，移动端实现已由 `apps/mobile-android/` 替代，参见 [Android移动端架构](../../4-架构/3-Android移动端架构.md)。
> 原状态: 草稿（2026-05-28 创建，2026-05-31 最后更新）

## 目的

记录移动端已落地的连接页、配对授权、音频控制、文件管理与实时推送层的实现现状。

## 对应代码

> 以下代码路径已在 commit `1e778f0` 整体删除，仅作为历史记录保留；当前移动端实现位于 `apps/mobile-android/`，参见 [4-架构/3-Android移动端架构.md](../../4-架构/3-Android移动端架构.md)。

- `apps/mobile/pubspec.yaml`
- `apps/mobile/lib/main.dart`
- `apps/mobile/lib/services/agent_client.dart`
- `apps/mobile/lib/state/connection_provider.dart`
- `apps/mobile/lib/state/discovery_provider.dart`
- `apps/mobile/lib/state/storage_provider.dart`
- `apps/mobile/test/widget_test.dart`
- `apps/mobile/lib/pages/pairing_page.dart`
- `apps/mobile/lib/state/auth_provider.dart`

## 当前实现

- 输入 Agent 地址并手动连接
- 扫描局域网中广播的 Agent（mDNS / DNS-SD）
- 本地持久化上次输入/选择的 Agent 地址
- 配对授权：输入 6 位配对码换取长期 Bearer token，持久化到 SharedPreferences
- `_AuthGate` 路由守卫：App 启动时读取已存 token，有 token 进入 AudioPage，无 token 进入 PairingPage；401 自动清除 token 并跳回 PairingPage
- 音频控制页（`AudioPage`）和文件管理页（`FilesPage`）
- WebSocket 实时推送层：`EventStream`（指数退避重连）+ `events_provider`（`eventBusProvider` 路由 `audio.*`/`file.*` 事件）

- 输入 Agent 地址并手动连接
- 扫描局域网中广播的 Agent（mDNS / DNS-SD）
- 本地持久化上次输入/选择的 Agent 地址

页面当前能力：

- 输入 Agent 地址，例如 `192.168.1.100:8765`
- 自动补全 `http://` scheme
- 请求 Windows Agent 的 `GET /api/status`
- 展示连接成功 / 失败状态
- 扫描局域网内的 `_maimai-home._tcp` 服务
- 点击发现结果后自动回填到地址输入框，并保存到本地存储
- 成功后展示：
  - `machineName`
  - `version`
  - `uptimeSeconds`
  - `capabilities`（含 `discoveryBroadcast` 指示 Agent 是否在广播 mDNS）

## 依赖

当前移动端运行时依赖（版本见 `pubspec.yaml`）：

| 包 | 版本约束 | 用途 |
|---|---|---|
| `dio` | `^5.9.0` | HTTP 请求（由 `AgentClient` 封装） |
| `nsd` | `^5.0.1` | 局域网 mDNS/NSD 服务发现 |
| `shared_preferences` | `^2.5.3` | 本地持久化（Agent 地址、Bearer token 等配置） |
| `flutter_riverpod` | `^2.5.0` | 状态管理框架 |
| `web_socket_channel` | `^3.0.0` | WebSocket 实时推送，已接入 `/api/events` |
| `file_picker` | `^8.1.0` | 文件选择器，供文件上传页面使用 |
| `path_provider` | `^2.1.4` | 获取设备本地存储路径，供文件下载落盘使用 |
| `cupertino_icons` | `^1.0.8` | iOS 风格图标字体 |

开发依赖：`flutter_lints ^6.0.0`、`mocktail ^1.0.4`、`fake_async ^1.3.0`。

### dependency_overrides

`file_picker 8.x` 传递依赖 `flutter_plugin_android_lifecycle 6.0.0`，该版本要求 `compileSdk 36`。
当前 Android 构建已升级至 `compileSdk 36`（`android/app/build.gradle.kts`），同时在 `pubspec.yaml` 中通过 `dependency_overrides` 将 `flutter_plugin_android_lifecycle` 固定为 `2.0.22`，以保持现有构建配置可用。
如后续插件迁移完成，可移除此 override。

### Android 构建工具链版本

| 项目 | 版本 |
|---|---|
| `compileSdk` | 36 |
| AGP（Android Gradle Plugin） | 9.0.1 |
| Kotlin | 2.3.20 |
| Java / JVM target | 17 |
| Dart SDK | `^3.12.0` |
## 页面行为约定

### 地址输入

输入框允许用户输入：

- `192.168.1.100:8765`
- `http://192.168.1.100:8765`
- `https://example-host:8765`

页面内部会做基础规范化：

- 无 scheme 时自动补 `http://`
- 无法解析 host 时直接报错，不发请求

## 局域网发现

扫描使用 `nsd` 包（原 `apps/mobile/pubspec.yaml`，已删除），默认扫描 service type：

```text
_maimai-home._tcp
```

实现要点：

- 点击 “扫描局域网” 调用 `startDiscovery`，超时 6 秒后自动停止
- 使用 `addServiceListener` 接收服务发现事件（ServiceStatus.found / lost）
- 发现后调用 `resolve` 拿到 host/port/TXT 详情
- TXT records 以 UTF-8 解码，优先读取：
  - `name`（机器名）
  - `version`（Agent 版本）
- `Service.host` 为空时，退者使用 `service.addresses` 首地址
- 列表以机器名排序，同一服务重复上报时只更新不重复
- 点击发现项后调用 `_useDiscoveredAgent`，回填地址框并写入 SharedPreferences

平台床拋：

- Android 需要 `INTERNET`、`CHANGE_WIFI_MULTICAST_STATE`，高版本附加 `NEARBY_WIFI_DEVICES`
- Android 模拟器对 mDNS 支持不可靠，**发现功能以真机验收为准**
- iOS / macOS 需要 `NSLocalNetworkUsageDescription` 与 `NSBonjourServices`
- 路由器禁用 multicast 或开启 Private DNS 都可能导致扫不到

## 地址持久化

- 使用 `shared_preferences`（原 `apps/mobile/pubspec.yaml`，已删除）存储上次使用的 Agent 地址
- key：`saved_agent_address`
- 活动写入时机：
  - 连接成功后保存当前输入地址
  - 点击发现结果后保存该服务的 host:port
- App 启动时从持久化读取地址，读不到时使用默认 `192.168.1.100:8765`

## 生命周期与清理

- 扫描过程中会保存 `Discovery` 与 `ServiceListener` 引用
- 手动点击 “再次扫描” 或超时后调用 `_stopDiscovery`：
  - `removeServiceListener`
  - `stopDiscovery(discovery)`
- `dispose` 不调 `setState`，只同步释放 nsd 资源，避免“在已 defunct 的 Element 上 setState”问题

## 架构：Riverpod Provider 层

当前状态管理已全面迁入 Riverpod，主要 Provider：

| Provider | 类型 | 职责 |
|---|---|---|
| `agentAddressProvider` | `StateProvider<String>` | 当前输入的 Agent 地址（未规范化） |
| `agentClientFactoryProvider` | `Provider<AgentClient Function(String)>` | 工厂，测试时可注入 mock Dio |
| `connectionStateProvider` | `StateNotifierProvider<ConnectionNotifier, ConnectionStatus>` | 连接状态机（idle / connecting / connected / error） |
| `discoveryProvider` | `StateNotifierProvider<DiscoveryNotifier, DiscoveryState>` | mDNS 扫描状态和发现列表 |
| `discoveryListProvider` | `Provider<List<DiscoveredAgent>>` | 仅列表的便捷视图 |
| `discoveryBackendProvider` | `Provider<DiscoveryBackend>` | nsd 适配器，测试时可替换为内存假对象 |
| `agentClientProvider` | `Provider<AgentClient>` | 绑定 `agentAddressProvider` 的 AgentClient 实例，供音频 Provider 使用 |
| `audioStateProvider` | `FutureProvider<AudioState>` | `GET /api/audio/state`，mutation 成功后 invalidate |
| `audioControllerProvider` | `AsyncNotifierProvider<AudioController, void>` | 音频变更指令（setVolume / setMute / switchDevice） |
| `fileApiClientProvider` | `Provider<AgentClient>` | 文件操作专用 AgentClient，测试时可 override |
| `fileRootsProvider` | `FutureProvider<List<FileRoot>>` | `GET /api/file-roots` |
| `fileListingProvider` | `FutureProvider.family<FileListingResult, (rootId, path)>` | `GET /api/files`，按 (rootId, path) 分族缓存 |
| `fileMutationsProvider` | `NotifierProvider<FileMutationsNotifier, void>` | 文件变更指令（delete / rename / move / upload） |
| `eventStreamConnectorProvider` | `Provider<EventStreamConnector>` | WebSocket 连接器工厂，测试时可注入假连接器 |
| `eventStreamControllerProvider` | `Provider<EventStream?>` | 持有当前 `EventStream` 实例，地址变更时自动重建 |
| `eventStreamProvider` | `StreamProvider<EventEnvelope>` | 实时事件流，UI 层 `ref.watch` 此 Provider |
| `eventBusProvider` | `Provider<void>` | 事件路由：将 `audio.state`/`audio.device.changed`/`file.*` 分发到对应 Provider invalidate |
| `tokenProvider` | `StateProvider<String?>` | 当前 Bearer token（或 null），供 AgentClient 拦截器和 WebSocket URI 构建器订阅 |
| `authNotifierProvider` | `StateNotifierProvider<AuthNotifier, String?>` | token 持久化主体：`loadToken`（启动时读取）、`saveToken`（配对成功后写入）、`clearToken`（登出或 401 触发） |

`AgentClient` 已从 `main.dart` 抽出到 `services/agent_client.dart`，包含：

- `AgentStatus.fromJson`：解析 `/api/status` 响应
- `AgentClient.normalizeBaseUrl`：地址规范化（公开静态方法，页面可预验证）
- `AgentClient.describeError`：将 `AgentClientException` 映射为中文错误描述
- 音频 API：`fetchAudioState`、`fetchAudioDevices`、`setVolume`、`setMute`、`switchDevice`
- 文件 API：`fetchFileRoots`、`fetchFiles`、`uploadFile`、`downloadFile`、`deleteFile`、`renameFile`、`moveFile`
- 配对 API：`exchangePairingCode(code, deviceLabel)` → 返回 Bearer token 字符串
- 文件 DTO：`FileRoot`、`FileEntry`、`FileListingResult`（在 `files_provider.dart` 中 re-export）
`apps/mobile/test/widget_test.dart`（已删除）当前覆盖：

- 页面标题存在
- “测试连接”、“扫描局域网” 按钮存在
- “局域网发现” 区块存在
- 默认地址预填 `192.168.1.100:8765`
- `test/widget/pairing_page_test.dart`：`PairingPage` 组件渲染、输入校验、配对成功跳转、401 错误提示
- `test/services/agent_client_test.dart`：`exchangePairingCode` 成功路径、401 路径、网络错误路径
- `test/state/auth_provider_test.dart`：`AuthNotifier` 生命周期（loadToken / saveToken / clearToken）、SharedPreferences 持久化校验

仍是最小 smoke test。后续可补：

- 地址规范化单元测试
- `AgentStatus.fromJson` 解析测试
- `DiscoveredAgent.fromService` 解析测试
- Dio 错误映射测试

## 联调方式

确保 Windows Agent 已运行，并且 [Program.cs](../../../services/windows-agent/src/MaimaiHomeAgent/Program.cs) 暴露的 `/api/status` 可被手机访问。

建议联调步骤：

1. 在 Windows 机器确认 Agent 正在监听 `0.0.0.0:8765`
2. 在手机和 Windows 电脑连接同一局域网
3. 在 App 中输入 Windows 机器 IP，如 `192.168.x.x:8765`
4. 点击“测试连接”
5. 成功后确认机器名与运行时间显示正常

## 下一步

建议按这个顺序继续推进：

1. ~~接入 WebSocket `/api/events` 实时推送~~ **已完成**：`EventStream`（指数退避重连）+ `events_provider`（`eventBusProvider` 路由 `audio.*`/`file.*` 事件）
2. ~~实现文件浏览页（`FilesPage`）~~ **已完成**：接入 `fileRootsProvider` + `fileListingProvider`，支持上传/下载/删除/重命名
3. ~~接入配对授权流程~~ **已完成**：`PairingPage`、`AuthNotifier`、`_AuthGate` 路由守卫均已实现

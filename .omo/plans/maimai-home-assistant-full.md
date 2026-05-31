# maimai-home-assistant 完整实现规划

## TL;DR

> **核心目标**: 完成 `maimai-home-assistant` 三端体系（Windows Agent + 移动端 + PC Web）的剩余实现工作（原始 9 阶段中的 2-9 + 移动端补全），交付一个 LAN-only 的家用机辅助工具：手机或 PC 网页通过局域网控制 Windows 电脑的音频输出/音量/静音，并管理白名单根目录内的文件。
>
> **交付物**:
> - Windows Agent：音频控制 API（音量/静音/设备切换）+ 文件白名单管理 API + WebSocket 实时推送 + 配对鉴权 + 托盘 + 自启 + 单文件 exe
> - PC Web：React+Vite+TS+TanStack Query+Zustand 实现的音频控制页与文件管理页，由 Agent 静态托管
> - Mobile App：Flutter+Riverpod 重构 + 音频控制页 + 文件管理页 + WebSocket 客户端 + 配对流程
>
> **预估工作量**: XL（5 个执行 wave + 1 个 review wave，29 个实现 task + 4 个 review task）
> **并行执行**: YES — Wave 1 高并行 7 任务；Wave 2 拆为 2a (5 任务并行) + 2b (1 任务) + 2c (1 任务)；Wave 3 高并行 6 任务；Wave 4 5 任务；Wave 5a 3 任务并行，5b 1 任务单独；Final Wave 4 个 reviewer 并行
> **关键路径**: T1 (audio STA + 充分性验证) → T8 (audio API) → T13 (event publisher) → T16 (PC Web audio) → T22 (鉴权) → T26 (托盘) → T29 (单文件发布) → F1-F4 → 用户 okay

---

## Context

### Original Request
"创建实现整个项目的plan" — 用户已有 `dev-docs/features/2026-05-11-maimai-home-assistant/实现.md` 描述的 9 阶段路线图，已完成 Windows Agent 阶段 1 与移动端连接页。本 plan 覆盖剩余阶段（2-9）以及移动端从连接页到完整功能的补全。

### Interview Summary

**Plan 范围与多机决策**：
- 范围：剩余工作。已完成阶段（Phase 1 Agent 骨架 + Mobile 连接页 + mDNS 广播/扫描）作为 Wave 1 的前置条件。
- 多目标机：MVP 单机优先；地址/token 存储结构按多机扩展预留（地址列表、按机器分桶）。
- iOS：完全不管，不做 Info.plist 占位。
- CI/CD：暂不做，本地 build/test 兜底。

**架构决策**：
- Mobile：尽早引入 Riverpod，第一波就抽 `AgentClient`。
- PC Web：TanStack Query 管 API，Zustand 管本地 UI 状态。
- PC Web 部署：Agent 静态托管 + Vite 开发反代（`app.UseStaticFiles` + `app.MapFallbackToFile`）。
- Agent 启动：托盘程序 + 用户授权登录自启（任务计划），不做 Windows Service。
- 文件保护：服务端硬约束（mutating ops 需 `confirm:true` 必填）+ 前端二次确认。
- 测试策略：TDD（RED-GREEN-REFACTOR）+ 每个 task 必须有 agent 执行的 QA 场景。

### Research Findings
- 现有 `services/windows-agent/src/MaimaiHomeAgent/Program.cs` 已实现 `/api/status` + Serilog（路径展开 `%LOCALAPPDATA%`）+ `Makaretu.Dns.Multicast` 广播 `_maimai-home._tcp` 服务，capabilities 中只有 `discoveryBroadcast=true`。
- `apps/mobile/lib/main.dart` 单文件实现连接页：dio + nsd + shared_preferences，无分层架构。
- `apps/pc-web/` 仅有 `.gitkeep`。
- Phase 1 wiki [windows-agent-phase-1-bootstrap.md](dev-docs/wiki/development/windows-agent-phase-1-bootstrap.md) 与 Mobile 连接页 wiki [flutter-mobile-connection-page.md](dev-docs/wiki/development/flutter-mobile-connection-page.md) 提供了已落地代码的细节与下一步建议。

### Metis Review

**已识别并修复的关键盲点**（全部已纳入本 plan）：
- COM 线程模型：AudioSwitcher 必须在专用 STA 线程上访问，需要 `Channel<T>` dispatch + `IMMNotificationClient` 回调 marshaling。
- mDNS 网络变化：`NetworkChange.NetworkAddressChanged` 触发服务重启，否则换 WiFi 后停止广播。
- WebSocket 心跳与重连：服务端 ping/pong + 客户端指数退避。
- WebSocket 鉴权：从 day 1 用 `?token=` query param 接入（前期 no-op，Wave 4 启用），避免后期改造握手。
- 路径安全：URL-decode → `GetFullPath` → 前缀检查 → `ResolveLinkTarget(returnFinalTarget:true)` 二次检查 → `FileAttributes.ReparsePoint` 链路检查。
- Windows 长路径：app manifest 加 `<longPathAware>true</longPathAware>`。
- H.NotifyIcon.Wpf 与单文件发布兼容性：必须在 Wave 1 验证，决定 `<UseWPF>true</UseWPF>` 或切换至非 WPF 变体。
- React Router 客户端路由：Agent 必须 `app.MapFallbackToFile("index.html")`，在 Wave 1 完成。
- Pairing code 竞态：用 `ConcurrentDictionary` + atomic remove-and-return，源 IP 绑定 + 60-120s TTL，单次使用立即失效。
- Token 存储：`%LOCALAPPDATA%` 用 atomic temp+rename 写入避免并发损坏。
- 音频请求并发：STA dispatcher 加 `SemaphoreSlim` 或 bounded channel，超限返回 503。
- 文件上传：显式 `MaxRequestBodySize`，不依赖 Kestrel 默认。
- 设备拔出/不可用：捕获 `COMException` → 结构化错误 + WebSocket 推送 `device_unavailable`。

**默认值（用户未明确选择，将以合理默认应用，summary 中披露）**：
- 文件单次上传上限：100 MB（可配置）。
- Token 生命周期：90 天（可撤销，无 refresh）。
- 多设备 token：列表存储（每个配对设备一条记录），同名设备覆盖。
- WebSocket 鉴权：query param `?token=`（浏览器 WebSocket 无法设自定义 header）。
- 文件覆盖：默认拒绝（409），需 `overwrite:true` 显式覆盖。
- 文件夹删除：MVP 不递归删除目录，只支持删除文件；目录删除留作后续。
- 目录列表分页：硬上限 500 项，超限 `truncated:true` + 提示前端用搜索/分页。
- 隐藏/系统文件：默认隐藏 `Hidden`/`System` 文件，前端可加 toggle（MVP 不做 toggle）。
- 跨卷移动：MVP 不支持，仅支持同根目录内移动；跨根目录移动留作后续。

---

## Work Objectives

### Core Objective
完成 maimai-home-assistant 三端体系的剩余实现，交付一个可在局域网内正常使用、单 exe 分发的家用机辅助工具。

### Concrete Deliverables

**Windows Agent**：
- `IAudioService` 抽象 + STA 线程 dispatcher
- `GET /api/audio/state`、`POST /api/audio/volume`、`POST /api/audio/mute`
- `GET /api/audio/devices`、`POST /api/audio/default-device`
- `IMMNotificationClient` 设备事件 → WebSocket 推送
- `IFileRootService` + 路径安全器（`PathGuard`）
- `GET /api/file-roots`、`GET /api/config`、`PUT /api/config/file-roots`
- `GET /api/files`、`POST /api/files/upload`、`GET /api/files/download`、`POST /api/files/rename`、`POST /api/files/move`、`DELETE /api/files`
- `/api/events` WebSocket（心跳 + 重连友好 + token 鉴权）
- `IPairingService` + `ITokenStore`：配对码生成/兑换、token 列表、撤销
- 鉴权中间件 + WebSocket token 校验
- `app.UseStaticFiles` + `app.MapFallbackToFile("index.html")` 托管 PC Web
- H.NotifyIcon 托盘菜单（显示状态、生成配对码、自启 toggle、退出）
- 任务计划自启（用户授权后写入用户级任务）
- `dotnet publish` 单文件 self-contained exe

**PC Web**（`apps/pc-web/`）：
- Vite + React 18 + TypeScript 项目脚手架
- TanStack Query + Zustand + react-router-dom 数据/状态/路由
- 音频控制页（设备列表、音量、静音、切换）
- 文件管理页（白名单根目录、目录浏览、上传/下载/删除/重命名/移动）
- 配对页（输入配对码、保存 token）
- WebSocket 客户端（自动重连、状态同步）
- vitest 单元测试

**Mobile App**（`apps/mobile/`）：
- 抽 `AgentClient`（dio 封装 + 错误映射）
- 引入 Riverpod，连接页迁移到 provider
- 音频控制页（参照 Phase 7 任务）
- 文件管理页
- WebSocket 客户端（web_socket_channel + 重连）
- 配对流程
- flutter_test 关键路径覆盖

### Definition of Done

- [ ] 单文件 exe 在干净 Windows 机器（无预装 .NET runtime）双击启动后 10 秒内 `curl http://localhost:8765/api/status` 返回 200
- [ ] 同局域网手机和浏览器都能完成：发现 Agent → 配对 → 控制音量/切换设备 → 浏览/上传/下载/删除文件
- [ ] 所有文件 API 拒绝越权（路径穿越、符号链接、绝对路径）
- [ ] 设备拔插/系统手动切换默认设备时，前端在 5 秒内通过 WebSocket 收到事件
- [ ] 关闭手机屏幕（WebSocket 半开连接）45 秒后服务端检测并主动关闭连接
- [ ] 用户登出 Windows 后再登入，15 秒内 Agent 自动启动并对外提供服务（自启已开）
- [ ] `dotnet build`、`dotnet test`、`flutter test`、`vitest run` 全部通过

### Must Have

- 音频：主音量、静音、默认设备查看与切换
- 文件：白名单内浏览、上传、下载、删除（仅文件）、重命名、同根目录内移动
- 实时：WebSocket 推送音频状态变化与设备插拔
- 安全：配对码 + token + 路径安全 + 危险操作 confirm 硬约束
- 分发：单文件 self-contained exe + 托盘 + 用户级自启
- 三端：Windows Agent + Mobile App + PC Web 全部可用

### Must NOT Have (Guardrails)

> 来自 Metis 评审，强制锁定。执行 agent 不得越界。

- ❌ 公网远程控制
- ❌ 用户系统、角色权限、OAuth、token refresh
- ❌ 多用户切换、多机器同时控制（MVP 单机优先）
- ❌ 文件预览、缩略图、递归目录树、拖拽上传
- ❌ 跨根目录文件移动、跨卷文件移动
- ❌ 递归删除目录（MVP 仅支持删除文件）
- ❌ 每应用音量、均衡器、空间音频
- ❌ WebSocket 事件回放、断连后补漏（客户端重连后 REST 全量拉取）
- ❌ 托盘菜单超出：显示状态、生成配对码、自启 toggle、退出
- ❌ 控制 maimai 游戏进程内部
- ❌ HTTPS（MVP 仅 HTTP）
- ❌ Windows Service 安装路径（仅托盘 + 任务计划）
- ❌ iOS 平台占位工作
- ❌ CI/CD 配置
- ❌ 任何形式的远程日志上传 / 遥测
- ❌ 任意磁盘访问（任何路径必须落入配置过的根目录）
- ❌ "由用户手动确认 / 在浏览器查看 / 检查托盘图标"类的验收方式（必须 agent 可执行）

### Spec Framework Integration

未检测到 SDD 框架（无 `openspec/`、`.specify/`、`_bmad/` 目录）。本节不适用。

---

## Verification Strategy (MANDATORY)

> **零人工干预**：所有验收必须 agent 可执行。"用户手动测试"类标准被禁止。

### Test Decision
- **基础设施存在**：YES（Windows Agent 已有 xUnit；Mobile 已有 flutter_test；PC Web 待初始化 vitest）
- **自动化测试**：YES（TDD）。每个实现 task 先写失败测试 → 实现到通过 → 重构。
- **测试框架**：xUnit + Microsoft.AspNetCore.Mvc.Testing（Agent）/ flutter_test + mocktail（Mobile）/ vitest + @testing-library/react（PC Web）
- **TDD 节奏**：RED（写测试 + 跑出失败）→ GREEN（最小实现）→ REFACTOR

### QA Policy

每个 task 必须有 agent 可执行的 QA 场景，证据落到 `.omo/evidence/task-{N}-{slug}.{ext}`。

- **API/Backend**：`Bash (curl)` 或 `Invoke-WebRequest` 发请求 → 解析 JSON → 断言字段值与状态码。
- **Library/Module**：`dotnet run` / `dotnet test` 启动并验证输出。
- **Frontend (PC Web)**：`playwright` 启动 Chromium → 访问页面 → 选择器交互 → DOM 断言 + 截图。
- **TUI/CLI**：`interactive_bash`（tmux）跑 `dotnet run` → 抓启动日志 → grep 关键字符串。
- **Mobile**：`flutter test` widget test + 集成测试（emulator 上联调留作 review wave 的 F3 真机 QA）。

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 1 (Foundation - 高并行 7 个 task):
├── 1. Audio service abstraction + STA dispatcher [unspecified-high]
├── 2. WebSocket infrastructure (no-op auth) [unspecified-high]
├── 3. mDNS NetworkAddressChanged restart hook [quick]
├── 4. Path safety + file root schema [unspecified-high]
├── 5. Pairing/token domain model + atomic store [unspecified-high]
├── 6. Mobile AgentClient extraction + Riverpod scaffold [visual-engineering]
└── 7. PC Web Vite scaffold + agent fallback wiring [visual-engineering]

Wave 2a (Endpoints 基础 - 5 个 task 并行, depends on Wave 1):
├── 8. Audio state/volume/mute API [unspecified-high]
├── 9. Audio devices + default-device API + IMMNotificationClient [deep]
├── 10. File listing API + pagination [unspecified-high]
├── 12. File-roots config API [quick]
└── 14. Long-path manifest + path-safety hardening tests [quick]

Wave 2b (File mutation - 1 个 task, depends on T10、T14):
└── 11. File mutate API (upload/download/delete/rename/move) [unspecified-high]

Wave 2c (Event publisher - 1 个 task, depends on T8、T9、T11):
└── 13. WebSocket event publisher hooks (audio + file events) [unspecified-high]

Wave 3 (Frontend integration - 高并行 6 个 task, depends on Wave 2):
├── 15. Mobile audio control page + Riverpod providers [visual-engineering]
├── 16. PC Web audio control page [visual-engineering]
├── 17. Mobile file browser page [visual-engineering]
├── 18. PC Web file browser page [visual-engineering]
├── 19. Mobile WebSocket client (web_socket_channel + reconnect) [unspecified-high]
└── 20. PC Web WebSocket client + TanStack Query invalidation [unspecified-high]

Wave 4 (Auth - 5 个 task, depends on Wave 3):
├── 21. Pairing code generation API + tray hook [unspecified-high]
├── 22. Token exchange + auth middleware (HTTP + WebSocket) [unspecified-high]
├── 23. Token revocation API [quick]
├── 24. Mobile pairing flow [visual-engineering]
└── 25. PC Web pairing flow [visual-engineering]

Wave 5a (Tray + AutoStart + Build pipeline - 3 个 task 并行, depends on Wave 4):
├── 26. H.NotifyIcon tray menu [unspecified-high]
├── 27. Task Scheduler auto-start integration [unspecified-high]
└── 28. PC Web build pipeline + agent wwwroot integration [unspecified-high]

Wave 5b (Final packaging - 1 个 task, depends on Wave 5a):
└── 29. Single-file self-contained publish profile [unspecified-high]

Wave FINAL (4 reviewer agents, parallel):
├── F1. Plan compliance audit (oracle)
├── F2. Code quality review (unspecified-high)
├── F3. Real manual QA (unspecified-high)
└── F4. Scope fidelity check (deep)
→ Present results → Get explicit user okay

Critical Path: T1 (audio STA) → T8 (audio API) → T13 (event publisher) → T16 (PC Web audio) → T22 (auth) → T26 (tray) → T29 (single-file publish) → F1-F4 → user okay
Parallel Speedup: ~70% faster than sequential
Max Concurrent: 7 (Wave 1)
```

### Dependency Matrix

| Task | Depends On | Blocks |
|------|------------|--------|
| 1 | Phase-1 (done) | 8, 9 |
| 2 | Phase-1 (done) | 13, 19, 20, 22 |
| 3 | Phase-1 (done) | — (cross-cutting) |
| 4 | Phase-1 (done) | 10, 11, 12, 14 |
| 5 | Phase-1 (done) | 21, 22 |
| 6 | Mobile-conn (done) | 15, 17, 19, 24 |
| 7 | Phase-1 (done) | 16, 18, 20, 25, 28 |
| 8 | 1 | 13, 15, 16 |
| 9 | 1, 2 | 13, 15, 16 |
| 10 | 4 | 17, 18 |
| 11 | 4, 10, 14 | 13, 17, 18 |
| 12 | 4 | 17, 18 |
| 13 | 2, 8, 9, 11 | 19, 20 |
| 14 | 4 | 11 (security gate) |
| 15 | 6, 8, 9 | F3 |
| 16 | 7, 8, 9 | 28, F3 |
| 17 | 6, 10, 11, 12 | F3 |
| 18 | 7, 10, 11, 12 | 28, F3 |
| 19 | 2, 6, 13, 15 | F3 |
| 20 | 2, 7, 13, 16 | 28, F3 |
| 21 | 5 | 24, 25, 26 |
| 22 | 2, 5 | 24, 25, F1 |
| 23 | 5, 22 | F1 |
| 24 | 6, 21, 22 | F3 |
| 25 | 7, 21, 22 | 28, F3 |
| 26 | 21, 23 | 29 |
| 27 | 5 | 29, F3 |
| 28 | 7, 16, 18, 20, 25 | 29 |
| 29 | 26, 27, 28 | F1, F3 |

### Agent Dispatch Summary

- **Wave 1 (7)**: T1 → `unspecified-high`, T2 → `unspecified-high`, T3 → `quick`, T4 → `unspecified-high`, T5 → `unspecified-high`, T6 → `visual-engineering`, T7 → `visual-engineering`
- **Wave 2a (5)**: T8 → `unspecified-high`, T9 → `deep`, T10 → `unspecified-high`, T12 → `quick`, T14 → `quick`
- **Wave 2b (1)**: T11 → `unspecified-high`
- **Wave 2c (1)**: T13 → `unspecified-high`
- **Wave 3 (6)**: T15 → `visual-engineering`, T16 → `visual-engineering`, T17 → `visual-engineering`, T18 → `visual-engineering`, T19 → `unspecified-high`, T20 → `unspecified-high`
- **Wave 4 (5)**: T21 → `unspecified-high`, T22 → `unspecified-high`, T23 → `quick`, T24 → `visual-engineering`, T25 → `visual-engineering`
- **Wave 5a (3)**: T26 → `unspecified-high`, T27 → `unspecified-high`, T28 → `unspecified-high`
- **Wave 5b (1)**: T29 → `unspecified-high`
- **FINAL (4)**: F1 → `oracle`, F2 → `unspecified-high`, F3 → `unspecified-high`, F4 → `deep`

---

## TODOs

### Wave 1 — Foundation (高并行 7 个 task)

- [x] 1. services/windows-agent/src/MaimaiHomeAgent/Audio/: 引入 IAudioService 抽象 + 专用 STA 线程 dispatcher，封装 AudioSwitcher.AudioApi.CoreAudio - 主线程零 COM 调用

  **What to do**:
  - 新建 `Audio/IAudioService.cs`：方法签名 `Task<AudioState> GetStateAsync()`、`Task SetVolumeAsync(double level)`、`Task SetMuteAsync(bool muted)`、`Task<IReadOnlyList<AudioDevice>> ListDevicesAsync()`、`Task SetDefaultDeviceAsync(Guid deviceId)`。
  - 新建 `Audio/AudioStaDispatcher.cs`：单一专用线程（`Thread { IsBackground = true }`，`SetApartmentState(ApartmentState.STA)`），用 `Channel<Func<Task>>` 接收工作项；`SemaphoreSlim` 队列上限 5，超限 throw `AudioServiceBusyException`（→ 503）。
  - 新建 `Audio/CoreAudioService.cs`：实现 `IAudioService`，所有 COM 调用通过 dispatcher。捕获 `COMException` → 抛 `AudioOperationException`。
  - 新建 `Audio/AudioModels.cs`：`AudioState`（masterVolume 0-1、muted bool、defaultDeviceId Guid?）、`AudioDevice`（id Guid、name string、isDefault bool、state DeviceState）。
  - 在 `Program.cs` 注册 `AudioStaDispatcher` 为 singleton（生命周期挂 `IHostApplicationLifetime`），`IAudioService` 为 singleton。
  - **不要在 task 1 暴露 HTTP 端点**——那是 task 8/9。

  **Must NOT do**:
  - 不要在 ASP.NET 请求线程直接 new `CoreAudioController`（必须经 dispatcher）。
  - 不要捕获所有异常吞掉日志（必须 Serilog 记录）。
  - 不要做每应用音量、均衡器（scope 外）。

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: 涉及 COM 互操作 + 多线程 dispatcher 设计，需要充分思考但属系统编程而非纯算法。
  - **Skills**: 无（项目内技术栈，无外挂 skill 需求）

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1（与 T2、T3、T4、T5、T6、T7 并行）
  - **Blocks**: T8（音频 API 端点）、T9（设备 API + IMMNotificationClient）
  - **Blocked By**: 无（Phase 1 已完成）

  **References**:

  **Pattern References**:
  - `services/windows-agent/src/MaimaiHomeAgent/Program.cs:1-90` — 现有 host builder + Serilog + mDNS 注册模式，新增 service 走相同 DI 注册风格。
  - `services/windows-agent/src/MaimaiHomeAgent/MaimaiHomeAgent.csproj` — `AudioSwitcher.AudioApi.CoreAudio` 依赖已存在，`NoWarn=NU1701` 抑制目标框架警告。

  **API/Type References**:
  - `AudioSwitcher.AudioApi.CoreAudio.CoreAudioController` — 主入口；`GetDevices(DeviceType.Playback, DeviceState.Active)` 列设备；`DefaultPlaybackDevice` 取/设默认设备；`Volume`、`Mute` 属性。
  - `System.Threading.Channels.Channel<T>` + `ChannelReader<T>.WaitToReadAsync` — STA 线程消费工作项的标准模式。

  **Test References**:
  - `services/windows-agent/tests/MaimaiHomeAgent.Tests/UnitTest1.cs` — xUnit + WebApplicationFactory smoke 模板。本任务测试不需要起 web host，可纯 unit test dispatcher 行为。

  **External References**:
  - AudioSwitcher 文档（GitHub README）— STA 线程要求与 `IMMNotificationClient` 注意事项。

  **WHY Each Reference Matters**:
  - `Program.cs` 给执行 agent 现有 DI/Serilog 注册习惯，避免引入新风格。
  - `Channel<T>` 模式是 .NET 标准 STA dispatcher 实现方法，比锁更易测试。
  - 现有 `csproj` 中 `NoWarn=NU1701` 是已知决策，不要去删。

  **Acceptance Criteria**:

  **TDD（测试先行）**:
  - [ ] 新建 `tests/MaimaiHomeAgent.Tests/Audio/AudioStaDispatcherTests.cs`：(1) 验证工作项在 STA 线程上执行（`Thread.CurrentThread.GetApartmentState() == ApartmentState.STA`）；(2) 验证队列满 → 抛 `AudioServiceBusyException`；(3) 验证 dispatcher dispose 后取消所有 pending 工作项。
  - [ ] `dotnet test tests/MaimaiHomeAgent.Tests/MaimaiHomeAgent.Tests.csproj --filter Audio`：6 tests pass / 0 fail。

  **QA Scenarios (MANDATORY)**:

  ```
  Scenario: STA 线程隔离验证
    Tool: Bash (dotnet test)
    Preconditions: dispatcher 已注册并运行
    Steps:
      1. 调度 100 个并发工作项，每个返回当前线程 ApartmentState
      2. 收集所有结果
      3. 断言 100 个结果全部为 ApartmentState.STA
      4. 断言所有工作项的 ManagedThreadId 相同（同一 STA 线程）
    Expected Result: 100/100 STA + 单一线程 ID
    Failure Indicators: 任何一个 MTA 或多个不同 thread id
    Evidence: .omo/evidence/task-1-sta-isolation.txt（dotnet test 输出）

  Scenario: 队列限流降级
    Tool: Bash (dotnet test)
    Preconditions: dispatcher 上限 5
    Steps:
      1. 投递 1 个长时工作项占用 dispatcher 100ms
      2. 同时投递 5 个工作项 → 应排队（接受）
      3. 投递第 7 个 → 应抛 AudioServiceBusyException
    Expected Result: 第 7 个被拒绝，前 6 个最终全部成功
    Evidence: .omo/evidence/task-1-queue-limit.txt
  ```

  **Commit**: YES — `feat(agent): add audio STA dispatcher and IAudioService abstraction`
  - Files: `services/windows-agent/src/MaimaiHomeAgent/Audio/*.cs`、`services/windows-agent/tests/MaimaiHomeAgent.Tests/Audio/*.cs`、`Program.cs`（DI 注册）
  - Pre-commit: `dotnet build` + `dotnet test --filter Audio`

- [x] 2. services/windows-agent/src/MaimaiHomeAgent/Realtime/: 搭建 WebSocket /api/events 基础设施（连接注册表 + 心跳 + token query-param 占位） - 后续 task 直接发布事件

  **What to do**:
  - 在 `Program.cs` 调用 `app.UseWebSockets()`。
  - 新建 `Realtime/EventHub.cs`：`ConcurrentDictionary<Guid, WebSocketSession>` 注册表，方法 `BroadcastAsync(EventEnvelope ev)`、`AddAsync(WebSocket ws, string? token, CancellationToken ct)`、`RemoveAsync(Guid id)`。
  - 新建 `Realtime/WebSocketSession.cs`：包含 `WebSocket`、`Guid Id`、`string? Token`、`DateTimeOffset LastPongAt`，提供 `SendJsonAsync`、`SendPingAsync`、`ReceiveLoopAsync`。
  - 新建 `Realtime/EventEnvelope.cs`：`type` (string, e.g. `audio.state`、`audio.device.changed`、`file.created`)、`payload` (JsonElement)、`timestamp`。
  - 心跳：服务端每 30s 发 ping，60s 无 pong 则 close 并 remove。用 `IHostedService` 后台任务实现。
  - 端点：`app.Map("/api/events", async (HttpContext ctx, EventHub hub) => ...)`，从 `ctx.Request.Query["token"]` 读 token；**Wave 4 之前 token 校验为 no-op**（仅记录，不拒绝）。
  - 暴露 `EventHub` 为 singleton DI。

  **Must NOT do**:
  - 不要做事件回放/补漏（断连后客户端 REST 全量拉，不要在服务端缓事件）。
  - 不要在心跳里捕获并继续运行（连接异常必须 close + remove）。
  - 不要在 Wave 1 加 token 强制校验（Wave 4 才启用）。

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: 涉及 WebSocket 协议、并发集合、心跳定时；难度中高。

  **Parallelization**:
  - **Can Run In Parallel**: YES（Wave 1）
  - **Blocks**: T13（事件发布）、T19、T20（客户端）、T22（鉴权升级）
  - **Blocked By**: 无

  **References**:
  - `services/windows-agent/src/MaimaiHomeAgent/Program.cs` — 现有 minimal API 风格。
  - .NET 9 文档：`Microsoft.AspNetCore.Builder.WebSocketMiddleware`、`System.Net.WebSockets.WebSocket`。

  **Acceptance Criteria**:

  **TDD**:
  - [ ] `tests/MaimaiHomeAgent.Tests/Realtime/EventHubTests.cs`：(1) Add → Broadcast 收到；(2) 心跳超时连接被移除；(3) 双连接 broadcast 都收到；(4) Remove 后再 broadcast 不抛。
  - [ ] `dotnet test --filter Realtime`：4 tests pass。

  **QA Scenarios**:

  ```
  Scenario: WebSocket 连通性 + 心跳
    Tool: Bash (websocat 或 curl --include --no-buffer + --upgrade)
    Preconditions: Agent 启动监听 8765
    Steps:
      1. 用 websocat 连接 ws://127.0.0.1:8765/api/events?token=test
      2. 等待 35 秒
      3. 期间 hub.BroadcastAsync 投一个测试事件
      4. 客户端应收到 ping frame 与测试事件 JSON
      5. 阻塞 65 秒不响应 ping
      6. 服务端应主动关闭连接，websocat 退出
    Expected Result: 事件 JSON 收到 + 65s 后连接关闭
    Evidence: .omo/evidence/task-2-ws-heartbeat.log

  Scenario: 多连接 broadcast
    Tool: Bash (两个 websocat 客户端 + dotnet test 触发 broadcast)
    Steps:
      1. 客户端 A 连接 /api/events
      2. 客户端 B 连接 /api/events
      3. 调用 EventHub.BroadcastAsync 测试事件
      4. A 和 B 都应在 1 秒内收到
    Expected Result: 双客户端均收到
    Evidence: .omo/evidence/task-2-ws-broadcast.log
  ```

  **Commit**: YES — `feat(agent): scaffold WebSocket /api/events with connection registry and heartbeat`

- [x] 3. services/windows-agent/src/MaimaiHomeAgent/Discovery/: mDNS 服务在 NetworkAddressChanged 事件触发时自动重启 - 切换 WiFi 后仍可被发现

  **What to do**:
  - 在现有 mDNS 启动逻辑（`Program.cs` 中已有 `MulticastService` + `ServiceDiscovery`）外封装 `Discovery/MdnsAdvertiser.cs`。
  - 实现 `IHostedService`：`StartAsync` 注册 `NetworkChange.NetworkAddressChanged += OnNetworkChanged`，启动 mDNS；`StopAsync` 解注册并 stop。
  - `OnNetworkChanged` 用 debounce（500ms 合并多次事件）→ stop 现有 advertiser → 重建 → advertise。线程安全用 `SemaphoreSlim(1,1)` 防重入。
  - 保留现有 `Discovery` 配置段（`Enabled`/`ServiceType`/`InstanceName`/`Port`/`StatusPath`/`Protocol`/`Version`）行为不变。

  **Must NOT do**:
  - 不要改 mDNS service type（保持 `_maimai-home._tcp`）。
  - 不要在 NetworkChanged 处理中 throw 阻断 host shutdown。

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 改造范围小（一个文件 + Program.cs 替换注册），属外科手术式重构。

  **Parallelization**:
  - **Can Run In Parallel**: YES（Wave 1）
  - **Blocks**: 无（基础设施改善，不阻塞具体功能）

  **References**:
  - `services/windows-agent/src/MaimaiHomeAgent/Program.cs` — 现有 mDNS 启动代码段。
  - `dev-docs/wiki/development/windows-agent-phase-1-bootstrap.md` — mDNS 调试提示部分提到 multicast/IGMP 与 Private DNS 干扰。
  - `System.Net.NetworkInformation.NetworkChange` — .NET 内置事件源。

  **Acceptance Criteria**:

  **TDD**:
  - [ ] `tests/MaimaiHomeAgent.Tests/Discovery/MdnsAdvertiserTests.cs`：mock `MulticastService`，触发 NetworkChanged 事件，断言 advertiser 经历 stop → start。

  **QA Scenarios**:

  ```
  Scenario: 网络切换后重新广播（手动 + 自动化混合）
    Tool: interactive_bash (tmux) + Bash (dns-sd)
    Preconditions: Agent 运行中，开机日志包含 "mDNS service advertised"
    Steps:
      1. 后台 tmux session 跑 `dns-sd -B _maimai-home._tcp` 持续监听
      2. 在 PowerShell 用 `Disable-NetAdapter <name>; Start-Sleep 2; Enable-NetAdapter <name>` 模拟网络切换
      3. 等待 10 秒
      4. 检查 Agent 日志含两次 "mDNS service advertised"（首次启动 + 重启）
      5. dns-sd 输出在第二次广播后仍能看到服务
    Expected Result: 重启日志出现 + 服务再次可发现
    Evidence: .omo/evidence/task-3-mdns-restart.log + .omo/evidence/task-3-dnssd-output.txt

  Scenario: 心跳期间不重启
    Tool: Bash (Agent 日志 grep)
    Steps:
      1. Agent 运行 5 分钟无网络变化
      2. grep 日志中 "mDNS service advertised" 计数
    Expected Result: 计数 = 1（仅启动时一次）
    Evidence: .omo/evidence/task-3-no-spurious-restart.txt
  ```

  **Commit**: YES — `feat(agent): re-advertise mDNS on NetworkAddressChanged events`

---

- [x] 4. services/windows-agent/src/MaimaiHomeAgent/Files/: 引入 PathGuard + IFileRootService 与 FileRoot schema - 路径安全是中枢，必须点到足够全

  **What to do**:
  - 新建 `Files/FileRoot.cs`：record with `string Id`、`string Name`、`string Path`（可能含环境变量）、`bool ReadOnly`。
  - 新建 `Files/IFileRootService.cs`：`IReadOnlyList<FileRoot> ListRoots()`、`FileRoot? FindById(string id)`、`Result UpdateRoots(IEnumerable<FileRoot> roots)`（实现与持久化留在 task 12）。
  - 新建 `Files/PathGuard.cs`：静态方法 `Result<string> ResolveSafe(FileRoot root, string relativePath)` — 项目仅这一个入口拼路径。顺序：
    1. URL-decode relativePath（实际在 minimal API 路由层已跨 layer decode）。
    2. 拒绝包含 NUL、控制字符、Windows 保留字符（`<>:"|?*`）的路径。
    3. 拒绝绝对路径（`Path.IsPathRooted`）。
    4. `Path.GetFullPath(Path.Combine(root.Path, relativePath))` 规范化。
    5. 前缀检查：`fullPath.StartsWith(root.Path + Path.DirectorySeparatorChar, OrdinalIgnoreCase)` 或严格相等 root.Path。
    6. 出现 `FileInfo(fullPath).Exists` 时调 `ResolveLinkTarget(returnFinalTarget:true)`，如返回非 null 且跨出 root，拒绝。
    7. 路径链逐层检查 `FileAttributes.ReparsePoint`（从 root 逐级 walk 到 fullPath，任一级含 ReparsePoint 则拒绝）。
  - 返回 `Result<string>`（项目内推荐用 OneOf 或自定义 record），不 throw；调用方负责转 HTTP 403。
  - 错误枚举：`InvalidChar`、`Absolute`、`OutsideRoot`、`SymlinkEscape`、`ReparsePointInPath`。
  - 在 `Program.cs` DI 注册 `IFileRootService` 为 singleton（从 `appsettings.json` 读 `FileRoots` 段，环境变量展开）。

  **Must NOT do**:
  - 不要允许 `..` 进入规范化后路径（`GetFullPath` 会解析，但务必走前缀检查验证）。
  - 不要在 PathGuard 里做文件读写（只负责验证 + 返回合法路径）。
  - 不要依赖 `Uri.UnescapeDataString` 做主规范化（仅辅助）。

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: 安全关键路径，需要充分考虑边界 case。

  **Parallelization**:
  - **Can Run In Parallel**: YES（Wave 1）
  - **Blocks**: T10（文件 listing）、T11（文件 mutate）、T12（config API）、T14（long-path manifest 与加固测试）
  - **Blocked By**: 无

  **References**:
  - `dev-docs/features/2026-05-11-maimai-home-assistant/实现.md` 阶段 5 — 定义了 FileRoots 配置结构与路径安全要求。
  - `dev-docs/features/2026-05-11-maimai-home-assistant/需求.md` 末尾 — "文件 API 无法越权访问未配置目录" 验收标准。
  - `System.IO.FileSystemInfo.ResolveLinkTarget(returnFinalTarget: true)` — 需要 .NET 6+，现项目 .NET 9 可用。

  **Acceptance Criteria**:

  **TDD（必须覆盖全部错误 case）**:
  - [ ] `tests/MaimaiHomeAgent.Tests/Files/PathGuardTests.cs` Theory tests:
    - `"../../Windows/System32"` → `OutsideRoot`
    - `"C:\\Windows"` → `Absolute`
    - `"normal/file.txt"` → OK
    - `"file<.txt"` → `InvalidChar`
    - `"\u0000evil"` → `InvalidChar`
    - 跨路径路分隔符（`/` 与 `\`）一致性 OK
    - 使用 reflection / temp 创建临时路径 + symlink 在 root 里指向 root 外 → `SymlinkEscape`（需 admin 权限创建软链接，本地跳过也可）。
  - [ ] `dotnet test --filter Files.PathGuard`：≥7 cases 全过。

  **QA Scenarios**:

  ```
  Scenario: 路径穿越被拒绝
    Tool: Bash (dotnet test 运行 PathGuardTests)
    Steps:
      1. 运行全部 PathGuard theory tests
      2. 输出 JSON test report
      3. 断言 0 fail / 7+ pass
    Evidence: .omo/evidence/task-4-pathguard-tests.trx

  Scenario: 现实路径手工验证（补证嵌套调用）
    Tool: Bash (PowerShell + dotnet run + ResolveSafe 调用 console harness)
    Steps:
      1. 创建临时 root C:\temp\fr-test-{timestamp}
      2. 里面创建子目录 sub/file.txt
      3. 调 ResolveSafe(root, "sub/file.txt")
      4. 断言 OK 且返回路径以 root 开头
      5. 调 ResolveSafe(root, "sub/../../escape.txt")
      6. 断言 OutsideRoot
    Evidence: .omo/evidence/task-4-real-paths.txt
  ```

  **Commit**: YES — `feat(agent): add PathGuard and IFileRootService skeleton`

- [x] 5. services/windows-agent/src/MaimaiHomeAgent/Security/: PairingService + ITokenStore 领域模型与 atomic store - Wave 4 使用

  **What to do**:
  - 新建 `Security/PairingCode.cs`：record `(string Code, IPAddress BoundIp, DateTimeOffset ExpiresAt, string DeviceLabel)`。
  - 新建 `Security/IPairingService.cs`：`PairingCode CreateCode(IPAddress sourceIp, string deviceLabel, TimeSpan? ttl = null)`、`Result<TokenRecord> Exchange(string code, IPAddress sourceIp)`（atomic remove-and-return）。
  - 默认 TTL = 120s；`Exchange` 返回后仍使 code 失效（即使未到 TTL）。使用 `ConcurrentDictionary<string, PairingCode>` + `TryRemove`。
  - 新建 `Security/TokenRecord.cs`：record `(string Id, string Token, string DeviceLabel, DateTimeOffset CreatedAt, DateTimeOffset? ExpiresAt, IPAddress IssuedToIp)`。
  - 新建 `Security/ITokenStore.cs`：`Task<IReadOnlyList<TokenRecord>> ListAsync()`、`Task AddAsync(TokenRecord t)`、`Task<bool> RemoveAsync(string id)`、`Task<TokenRecord?> ValidateAsync(string token)`。
  - 新建 `Security/JsonFileTokenStore.cs`：文件路径 `%LOCALAPPDATA%/maimai-home-assistant/tokens.json`。写入用 atomic temp+rename（`File.WriteAllTextAsync(temp); File.Move(temp, target, overwrite:true)`），读写加 `SemaphoreSlim`。读不到或解析失败 → 初始化为空并警告。
  - Token 生成：`RandomNumberGenerator.GetBytes(32)` → base64url。默认生命周期 90 天 (`now + TimeSpan.FromDays(90)`)。
  - DI 注册为 singleton。

  **Must NOT do**:
  - 不要实现 token refresh 端点（scope 外）。
  - 不要实现角色/权限（scope 外）。
  - 不要在 token 存储里明文保存 token（考虑 SHA-256 hash 存储，原始 token 仅首次返回）；本MVP 允许明文但要在 README 标注。

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: 并发与原子性 + 加密依赖，需谨慎。

  **Parallelization**:
  - **Can Run In Parallel**: YES（Wave 1）
  - **Blocks**: T21（配对码生成 API）、T22（token 交换 + 鉴权）、T23（撤销）
  - **Blocked By**: 无

  **References**:
  - `dev-docs/features/2026-05-11-maimai-home-assistant/实现.md` 阶段 8 — 配对与 token 设计概要。
  - `System.Security.Cryptography.RandomNumberGenerator` — 加密随机。
  - `System.Text.Json` — 序列化。

  **Acceptance Criteria**:

  **TDD**:
  - [ ] `tests/MaimaiHomeAgent.Tests/Security/PairingServiceTests.cs`:
    - Create → Exchange 返回有效 token
    - Exchange 后再 Exchange 同 code → 失败（single-use）
    - Expired code Exchange → 失败
    - 错误 IP Exchange → 失败
    - 100 并发调用 Exchange 同一 code → 恰好 1 个成功
  - [ ] `tests/MaimaiHomeAgent.Tests/Security/JsonFileTokenStoreTests.cs`:
    - Add → List 含新 token
    - Add 后进程崩溃模拟（删除 temp）→ 文件未损坏
    - 50 并发 Add → 全部可读回
  - [ ] `dotnet test --filter Security`：≥8 tests pass。

  **QA Scenarios**:

  ```
  Scenario: 并发配对交换只一个胜出
    Tool: Bash (dotnet test 上面的测试)
    Evidence: .omo/evidence/task-5-pairing-race.trx

  Scenario: token 文件原子写入验证
    Tool: Bash + Get-FileHash
    Steps:
      1. 初始化空 tokens.json
      2. 启 50 个任务并发 AddAsync
      3. 检查文件仅一个有效 JSON
      4. 检查临时后缀文件均已被重命名清理
    Evidence: .omo/evidence/task-5-atomic-write.txt
  ```

  **Commit**: YES — `feat(agent): pairing service and atomic JSON token store`

- [x] 6. apps/mobile/lib/: 重构为 packages/services/state 分层 + 引入 flutter_riverpod + 抽 AgentClient - 为后续页面提供状态底层

  **What to do**:
  - 在 `apps/mobile/pubspec.yaml` 增加 `flutter_riverpod: ^2.5.0`（限定稳定 major）、`web_socket_channel: ^3.0.0`，`dio` 保留。
  - 新建目录结构：
    - `lib/services/agent_client.dart` — dio 封装（baseUrl 动态、错误映射为 `AgentException` enum：Network/Unauthorized/PathInvalid/...）。
    - `lib/services/agent_client.dart` 提供 `Future<AgentStatus> fetchStatus()`（备后续扩展）。
    - `lib/state/connection_provider.dart` — Riverpod `StateNotifierProvider`：连接状态类 `ConnectionState`（idle/connecting/connected/error）、当前 Agent 地址。
    - `lib/state/discovery_provider.dart` — 封装现有 `nsd` 扫描逻辑为 `StreamProvider`。
    - `lib/state/storage_provider.dart` — 包装 `shared_preferences`（读写上次地址 + 开机预载）。
    - `lib/pages/connection_page.dart` — 从 `main.dart` 迁出原页面代码，改用 `ConsumerWidget` + provider。
  - `main.dart` 只保留 `runApp(ProviderScope(child: MaterialApp(home: ConnectionPage())))`。
  - 在 `test/widget_test.dart` 出现破坏时调整（原有断言不应被破坏）。
  - 【重要】连接页现有能力（手动输入 + nsd 扫描 + 地址持久化）保持 1:1 行为一致，不能退化。

  **Must NOT do**:
  - 不要同时加音频页、文件页（T15、T17）。
  - 不要引入额外状态库（仅 flutter_riverpod）。
  - 不要动 nsd / shared_preferences 的主要 API 套路（仅重新组织）。

  **Recommended Agent Profile**:
  - **Category**: `visual-engineering`
    - Reason: Flutter 页面 + 状态架构重构，UI 层退路验证需前端思维。
  - **Skills**: 无（项目内技术栈）

  **Parallelization**:
  - **Can Run In Parallel**: YES（Wave 1）
  - **Blocks**: T15（移动音频页）、T17（移动文件页）、T19（WebSocket 客户端）、T24（配对流程）
  - **Blocked By**: Mobile 连接页（已完成）

  **References**:
  - `apps/mobile/lib/main.dart` — 原始单文件实现，迁移原型。
  - `apps/mobile/test/widget_test.dart` — smoke test 预期（"测试连接"、"扫描局域网"按钮、默认地址），重构后仍要过。
  - `dev-docs/wiki/development/flutter-mobile-connection-page.md` — 当前页面全部行为约定。
  - Riverpod 官方文档：https://riverpod.dev/docs/getting_started — ProviderScope / StateNotifierProvider 基本用法。

  **Acceptance Criteria**:

  **TDD**:
  - [ ] 增加 `test/state/connection_provider_test.dart`：
    - 初始状态 = idle
    - connect() 调用后过渡到 connecting → connected（mock dio）
    - 401 错误 → error 且错误类型 = AgentException.unauthorized
  - [ ] `test/services/agent_client_test.dart`：URL 规范化（无 scheme 补 http://）、 timeout 错误映射。
  - [ ] `flutter test apps/mobile`：原有 widget_test 叠加新增 tests 全部 pass。

  **QA Scenarios**:

  ```
  Scenario: 重构后连接页能力不退化
    Tool: Bash (flutter test)
    Steps:
      1. flutter test apps/mobile --reporter expanded
      2. 检查原有 4 个 smoke 断言都过（页面标题、两个按钮、默认地址）
      3. 新增的 provider tests 都过
    Evidence: .omo/evidence/task-6-flutter-test.log

  Scenario: 启动后预载最后使用地址
    Tool: Bash (flutter test integration)
    Steps:
      1. 预设 shared_preferences mock 返回 "192.168.55.1:8765"
      2. pump ConnectionPage
      3. 断言 TextField 初值 = "192.168.55.1:8765"
    Evidence: .omo/evidence/task-6-prefilled-address.log
  ```

  **Commit**: YES — `refactor(mobile): introduce Riverpod and AgentClient layer`

- [x] 7. apps/pc-web/: 初始化 Vite + React 18 + TS + TanStack Query + Zustand + react-router 脉入 + Agent 静态托管 fallback - PC Web 点火

  **What to do**:
  - 在 `apps/pc-web/` 下运行 `pnpm create vite . --template react-ts`（手工 untar），删除多余示例资产。
  - `package.json` deps：`react@^18`、`react-dom@^18`、`react-router-dom@^6`、`@tanstack/react-query@^5`、`zustand@^4`、`axios@^1`；dev deps：`vite`、`typescript`、`@types/react`、`vitest`、`@testing-library/react`、`@testing-library/jest-dom`、`jsdom`、`eslint`、`@typescript-eslint/*`。
  - `tsconfig.json` strict、`vite.config.ts` 含：
    - `proxy: { '/api': 'http://127.0.0.1:8765', '/api/events': { target: 'ws://127.0.0.1:8765', ws: true } }`
    - `build.outDir: '../../services/windows-agent/src/MaimaiHomeAgent/wwwroot'`（build 后产物直接输出到 Agent 静态目录）；清理原输出用选项 `emptyOutDir: true`。
  - 创建文件骨架：
    - `src/main.tsx` + `src/App.tsx`
    - `src/router.tsx` — routes：`/`(redirect /audio)、`/audio`、`/files`、`/pairing`
    - `src/lib/queryClient.ts` — TanStack Query client（staleTime 30s，retry 1）
    - `src/stores/agentStore.ts` — Zustand store（agent baseUrl、token）
    - `src/services/agentApi.ts` — axios 封装，读取 store baseUrl/token 设置请求 header
    - `src/pages/AudioPage.tsx`、`src/pages/FilesPage.tsx`、`src/pages/PairingPage.tsx` — 占位 Hello 页面，后续 task 补。
  - 在 services/windows-agent/src/MaimaiHomeAgent/Program.cs 增加：
    - `app.UseDefaultFiles()` + `app.UseStaticFiles()`（`wwwroot` 为默认，创建该目录使 Build 不报错，加 `.gitkeep`）
    - `app.MapFallbackToFile("index.html")` — 让客户端路由不 404
    - `/api/*` 路由优先匹配（minimal API 路由顺序安排不受 fallback 影响）。
  - 补充 root README：`apps/pc-web/README.md` 记 dev/build/test 命令。

  **Must NOT do**:
  - 不要为 `/audio`、`/files` 填真实逻辑（占位 Hello 即可）。
  - 不要引入 UI 库（Mantine/Radix）——本MVP 不限制，交由后续 task 选择。
  - 不要动 services/windows-agent 的 csproj 结构（仅加 wwwroot 目录，需保证 build 不报 missing folder）。

  **Recommended Agent Profile**:
  - **Category**: `visual-engineering`
    - Reason: 前端脚手架 + 工具链交叉。

  **Parallelization**:
  - **Can Run In Parallel**: YES（Wave 1）
  - **Blocks**: T16、T18、T20、T25（PC Web 页面）、T28（build pipeline）
  - **Blocked By**: 无

  **References**:
  - Vite 文档：https://vitejs.dev/config/server-options.html#server-proxy — proxy 反代与 WebSocket。
  - TanStack Query v5 快速入门：https://tanstack.com/query/latest/docs/framework/react/quick-start
  - Zustand v4：https://github.com/pmndrs/zustand
  - ASP.NET Core MapFallbackToFile：https://learn.microsoft.com/en-us/aspnet/core/fundamentals/routing#mapfallbacktofile

  **Acceptance Criteria**:

  **TDD**:
  - [ ] `apps/pc-web/src/lib/queryClient.test.ts`：验证 default options 设置。
  - [ ] `apps/pc-web/src/stores/agentStore.test.ts`：Zustand store get/set baseUrl 、token 后都能返回。
  - [ ] `pnpm --dir apps/pc-web vitest run`：2 tests pass。
  - [ ] `pnpm --dir apps/pc-web tsc --noEmit`：0 errors。
  - [ ] Agent 侧：`dotnet build` 还是过。

  **QA Scenarios**:

  ```
  Scenario: 客户端路由不 404
    Tool: Bash (curl + dotnet run)
    Steps:
      1. 在 wwwroot 手工放一份 index.html（本场景不需 vite build）
      2. dotnet run
      3. curl http://127.0.0.1:8765/audio --include
      4. 断言 200 + Content-Type text/html + body 为 index.html 内容
      5. curl http://127.0.0.1:8765/api/status --include
      6. 断言 200 + JSON（说明 fallback 未吞掉 API）
    Evidence: .omo/evidence/task-7-fallback-routing.log

  Scenario: vite proxy 开发验证
    Tool: Bash (同时启 Agent 与 vite dev)
    Steps:
      1. 在一个 tmux pane 启 Agent（监听 8765）
      2. 在另一个 tmux pane 启 `pnpm --dir apps/pc-web dev`（默认 5173）
      3. curl http://127.0.0.1:5173/api/status
      4. 断言 200 + Agent 返回的 JSON
    Evidence: .omo/evidence/task-7-vite-proxy.log
  ```

  **Commit**: YES — `feat(pc-web): scaffold Vite + React 18 + TanStack Query + Zustand` + `feat(agent): serve PC Web wwwroot with SPA fallback`

---

### Wave 2 — Endpoints (Wave 2a 5 任务并行 + Wave 2b 1 任务 + Wave 2c 1 任务)

- [x] 8. services/windows-agent/src/MaimaiHomeAgent/Audio/AudioEndpoints.cs: 暴露 GET /api/audio/state、POST /api/audio/volume、POST /api/audio/mute - HTTP 层包装上面 IAudioService

  **What to do**:
  - 新建 `Audio/AudioEndpoints.cs`，提供静态方法 `MapAudioEndpoints(this IEndpointRouteBuilder)`，在 `Program.cs` 调用。
  - `GET /api/audio/state` → `audioService.GetStateAsync()` → `Results.Ok(AudioStateDto)`。
  - `POST /api/audio/volume` body `{ "level": 0.0..1.0 }` → 范围验证 [0,1] → `SetVolumeAsync` → 返回新 state。越界 → 400 且包含 `validation_error` body。
  - `POST /api/audio/mute` body `{ "muted": true|false }` → `SetMuteAsync` → 返回新 state。
  - 全部路由捕获 `AudioServiceBusyException` → 503 + `Retry-After: 1`; `AudioOperationException` → 502 + `device_unavailable`。
  - 调用 `EventHub.BroadcastAsync(audio.state, AudioStateDto)` 发布状态变动（仅在 SetVolume/SetMute 后，GetState 不推送）。
  - 更新 `Program.cs` 中 `/api/status` 的 capabilities：`audioVolume = true`、`audioMute = true`。

  **Must NOT do**:
  - 不要重复实现 COM 调用（必须走 IAudioService）。
  - 不要仅在错误路径 broadcast（只在成功路径推 `audio.state`）。

  **Recommended Agent Profile**: `unspecified-high`
  - Reason: 需思考错误映射与事件推送顺序。

  **Parallelization**:
  - **Can Run In Parallel**: YES（Wave 2a，与 T9、T10、T12、T14 并行）
  - **Blocks**: T13（事件发布 hooks）、T15、T16（前端页面）
  - **Blocked By**: T1（IAudioService）

  **References**:
  - `dev-docs/features/2026-05-11-maimai-home-assistant/实现.md` 阶段 2 — API 路由定义。
  - `services/windows-agent/src/MaimaiHomeAgent/MaimaiHomeAgent.http` — 现有 .http 文件，补上三个新路由的示例请求。
  - .NET Minimal API：https://learn.microsoft.com/en-us/aspnet/core/fundamentals/minimal-apis

  **Acceptance Criteria**:

  **TDD**:
  - [ ] `tests/MaimaiHomeAgent.Tests/Audio/AudioEndpointsTests.cs` (WebApplicationFactory)：GetState、SetVolume、SetMute happy path + 越界 volume + busy 503。
  - [ ] `dotnet test --filter Audio.Endpoints`：≥6 pass。

  **QA Scenarios**:

  ```
  Scenario: 调节音量后状态同步
    Tool: Bash (curl + dotnet run 启动 Agent)
    Preconditions: Windows 主机运行，可获取实际设备
    Steps:
      1. curl http://127.0.0.1:8765/api/audio/state → 记录初始 level
      2. curl -X POST .../api/audio/volume -d '{"level":0.30}' -H 'Content-Type: application/json'
      3. curl http://127.0.0.1:8765/api/audio/state → 断言 level ≈ 0.30 (±0.02)
      4. 还原初始 level（避免留下副作用）
    Evidence: .omo/evidence/task-8-volume-roundtrip.log

  Scenario: 参数越界拒绝
    Tool: Bash (curl)
    Steps:
      1. POST volume level=1.5 → 预期 400 + body 含 error 字段
      2. POST volume level=-0.1 → 预期 400
      3. POST volume 不含 body → 预期 400
    Evidence: .omo/evidence/task-8-validation.log
  ```

  **Commit**: YES — `feat(agent): expose /api/audio/state /volume /mute endpoints`

- [x] 9. services/windows-agent/src/MaimaiHomeAgent/Audio/: GET /api/audio/devices + POST /api/audio/default-device + IMMNotificationClient 事件推送 - 设备拔插/外部切换同步

  **What to do**:
  - 扩展 `IAudioService.ListDevicesAsync()` 、`SetDefaultDeviceAsync(Guid)` 与平台层实现。
  - 添加路由 `GET /api/audio/devices` → `[ { id, name, isDefault, state } ]`。
  - 路由 `POST /api/audio/default-device` body `{ deviceId }` → 同时设置 Console/Multimedia/Communications 三角色。设备 ID 不存在/不可用 → 404 + `device_not_found`。
  - 新建 `Audio/DeviceChangeNotifier.cs` 实现 `IMMNotificationClient`，所有回调 marshall 到 STA dispatcher 后读取状态 → `EventHub.BroadcastAsync(audio.device.changed, ...)`。重点事件：`OnDefaultDeviceChanged`、`OnDeviceAdded`、`OnDeviceRemoved`、`OnDeviceStateChanged`。
  - `IHostedService` 启动时注册 notifier、停止时注销。
  - 默认设备被拔下时 Windows 会自动选择新设备；Notifier 推送 `defaultDevice = newId`。
  - `/api/status` capabilities `audioDeviceSwitch = true`。

  **Must NOT do**:
  - 不要在请求线程直接调用 COM（仍走 dispatcher）。
  - 不要 swallow `IMMNotificationClient` 回调中的异常（Serilog 记录，不要碞灭 notifier）。
  - 不做 per-app/per-stream 控制（scope 外）。

  **Recommended Agent Profile**: `deep`
  - Reason: 跨线程 COM 事件 + 多角色默认设备，最高难度。

  **Parallelization**:
  - **Can Run In Parallel**: YES（Wave 2a，与 T8、T10、T12、T14 并行）
  - **Blocks**: T13、T15、T16
  - **Blocked By**: T1、T2

  **References**:
  - `AudioSwitcher.AudioApi.CoreAudio.CoreAudioController` — `SetAsDefaultMultimedia()`、`SetAsDefaultCommunications()`。
  - `NAudio.CoreAudioApi.MMDeviceEnumerator.RegisterEndpointNotificationCallback`（AudioSwitcher 未覆盖时可补）。
  - Metis 提示：`IMMNotificationClient` 回调在任意 COM 线程，访问设备只能在 STA。

  **Acceptance Criteria**:

  **TDD**:
  - [ ] `tests/MaimaiHomeAgent.Tests/Audio/DeviceEndpointsTests.cs`：ListDevices 返回面包含字段 + SetDefaultDevice 未知 ID → 404。
  - [ ] `tests/MaimaiHomeAgent.Tests/Audio/DeviceChangeNotifierTests.cs`：mock notifier 打火事件 → EventHub 收到预期 payload。
  - [ ] `dotnet test --filter Audio`：原有 + 新增都 pass。

  **QA Scenarios**:

  ```
  Scenario: API 列设备 + 切换默认 (全脚本化)
    Tool: Bash (curl + AudioDeviceCmdlets PowerShell module)
    Preconditions: 实机安装了 AudioDeviceCmdlets (`Install-Module -Name AudioDeviceCmdlets -Scope CurrentUser -Force`)与≥2 个播放设备
    Steps:
      1. curl /api/audio/devices → JSON parse 拿一个非默认 deviceId origDefault=$(Get-AudioDevice -Playback).ID
      2. POST /api/audio/default-device {deviceId}
      3. PowerShell `(Get-AudioDevice -Playback).ID` → 断言等于 POST 的 deviceId
      4. POST 还原原默认（使用 origDefault）
    Expected Result: PowerShell 查询返回的默认 ID = curl 调用传入的 deviceId
    Evidence: .omo/evidence/task-9-device-switch.log + powershell-default-id.txt

  Scenario: 外部手动切换同步 broadcast (脚本化触发)
    Tool: Bash (websocat + AudioDeviceCmdlets)
    Preconditions: 同上
    Steps:
      1. 后台启 websocat ws://127.0.0.1:8765/api/events?token=test 重定向 stdout 到 events.log
      2. PowerShell `Set-AudioDevice -ID <另一设备 ID>` 脚本化触发默认切换（走系统 API 与“声音”设置中手点同路径）
      3. 等待 5s
      4. grep events.log 含 'audio.device.changed'
      5. 还原原默认
    Expected Result: events.log 的事件计数 ≥1，包含新 deviceId
    Evidence: .omo/evidence/task-9-external-switch-broadcast.log + events-grep.txt
  ```

  **Commit**: YES — `feat(agent): audio devices listing, switching, and IMMNotificationClient broadcast`

- [x] 10. services/windows-agent/src/MaimaiHomeAgent/Files/FileListingEndpoints.cs: GET /api/files 与 GET /api/file-roots - listing 与分页

  **What to do**:
  - `GET /api/file-roots` → `IFileRootService.ListRoots()` 返回 `[ { id, name, readOnly } ]`（不返回真实路径）。
  - `GET /api/files?rootId=...&path=...&offset=0&limit=200` → `PathGuard.ResolveSafe` 验证 → `DirectoryInfo.EnumerateFileSystemInfos`。过滤 hidden/system。
  - 返回结构：`{ entries: [ { name, kind: "file"|"dir", size, modified } ], total, truncated }`。`limit` 上限 500。`truncated = true` 表示超限裁剪。
  - 路径不存在 → 404；不是目录 → 400；路径不安全 → 403 + 错误枚举类型。
  - `/api/status` capabilities `fileManagement = true` (本MVP 零件启用)

  **Must NOT do**:
  - 不要返回 hidden/system 文件（MVP）。
  - 不要递归枚举。
  - 不要返回真实磁盘路径给客户端（只返 rootId + relPath 表示）。

  **Recommended Agent Profile**: `unspecified-high`

  **Parallelization**:
  - **Can Run In Parallel**: YES（Wave 2a，与 T8、T9、T12、T14 并行）
  - **Blocks**: T11（mutate 复用 path 校验 + listing 验证）、T17（移动文件页）、T18（PC Web 文件页）
  - **Blocked By**: T4

  **References**:
  - `dev-docs/features/2026-05-11-maimai-home-assistant/实现.md` 阶段 5/6 — API 表。

  **Acceptance Criteria**:

  **TDD**:
  - [ ] `tests/MaimaiHomeAgent.Tests/Files/FileListingEndpointsTests.cs`：root 不存在 → 400/404；越界 → 403；normal → 200 with entries；超 limit → truncated true。

  **QA Scenarios**:

  ```
  Scenario: listing 与裁剪
    Tool: Bash (准备 600 个空文件于临时 root)
    Steps:
      1. 准备临时 root，创建 600 个空文件
      2. 启 Agent（配置该 root）
      3. curl /api/files?rootId=test&path=&limit=500
      4. 断言 entries.length=500 + truncated=true + total=600
      5. 清理临时 root
    Evidence: .omo/evidence/task-10-listing-truncate.log

  Scenario: 越界返回 403
    Tool: Bash
    Steps:
      1. curl '/api/files?rootId=test&path=../../Windows'
      2. 断言 status=403 + body 包含 path_outside_root
    Evidence: .omo/evidence/task-10-traversal.log
  ```

  **Commit**: YES — `feat(agent): file listing endpoints with pagination and root metadata`

- [x] 11. services/windows-agent/src/MaimaiHomeAgent/Files/FileMutationEndpoints.cs: upload/download/delete/rename/move + confirm:true 硬约束 - 文件写路径中枢

  **What to do**:
  - `POST /api/files/upload` — `multipart/form-data`：fields rootId, path (目标相对路径，含文件名), file。`overwrite=false` 默认拒绝覆盖 (409)；需显式 `overwrite=true`。`MaxRequestBodySize = 100MB`（在 endpoint 上加 `[RequestSizeLimit(100 * 1024 * 1024)]` 或同等限制）。
  - `GET /api/files/download?rootId=...&path=...` → `Results.File` 返回。
  - `DELETE /api/files` body `{ rootId, path, confirm: true }` → confirm 不为 true → 400 + `confirm_required`；存在且是文件 → 删除；是目录 → 400 (`directory_delete_unsupported`)。
  - `POST /api/files/rename` body `{ rootId, path, newName, confirm: true, overwrite: false }` → newName 检查（不含路径分隔符、不是 Windows 保留名） → 验证 + 重命名。
  - `POST /api/files/move` body `{ rootId, fromPath, toPath, confirm: true, overwrite: false }` → 仅同 rootId（不跨 root）→ 验证 + 同卷重命名（`File.Move`）。跨卷 → 400 + `cross_volume_move_unsupported`。
  - readOnly root 上调用任何写操作 → 403 + `read_only_root`。
  - 成功后 `EventHub.BroadcastAsync(file.created|file.deleted|file.renamed|file.moved, ...)`。

  **Must NOT do**:
  - 不要接受 confirm 为缺失或 false — 说明错误 code 后拒绝。
  - 不要允许递归删除目录（MVP 只处理单文件）。
  - 不要依赖默认 Kestrel 请求体限制。
  - 不要静默覆盖已存在文件。

  **Recommended Agent Profile**: `unspecified-high`

  **Parallelization**:
  - **Can Run In Parallel**: NO（Wave 2b 单件完成 task，需 T10 listing 与 T14 path-safety 加固全部完成）
  - **Blocks**: T13（事件发布）、T17/T18（前端）
  - **Blocked By**: T4、T10（listing 与 path safety 复用）、T14（path-safety 加固作为安全闸门）

  **References**:
  - `dev-docs/features/2026-05-11-maimai-home-assistant/实现.md` 阶段 6。
  - .NET `IFormFile` 流式写入：`stream.CopyToAsync(fileStream, ct)`。
  - Kestrel 请求限制：https://learn.microsoft.com/en-us/aspnet/core/fundamentals/servers/kestrel/options#maximum-request-body-size

  **Acceptance Criteria**:

  **TDD**:
  - [ ] `tests/MaimaiHomeAgent.Tests/Files/FileMutationEndpointsTests.cs`：upload (success / overwrite reject 409 / overwrite=true success / size limit 413)、delete (confirm 缺失 400 / dir 400 / file OK)、rename (同名 409 / non-existent 404)、move (同卷 OK / 跨卷 400)、readOnly 403。
  - [ ] `dotnet test --filter Files.Mutation`：≥12 pass。

  **QA Scenarios**:

  ```
  Scenario: 完整上传-下载-重命名-删除闭环
    Tool: Bash (curl)
    Steps:
      1. curl -F file=@small.txt -F rootId=test -F path=demo.txt /api/files/upload → 200
      2. curl '/api/files/download?rootId=test&path=demo.txt' -o downloaded.txt → 内容一致
      3. POST /api/files/rename {rootId,path:demo.txt,newName:final.txt,confirm:true} → 200
      4. DELETE /api/files {rootId,path:final.txt,confirm:true} → 200
    Evidence: .omo/evidence/task-11-crud-roundtrip.log

  Scenario: confirm 缺失拒绝
    Tool: Bash (curl)
    Steps:
      1. DELETE /api/files {rootId,path:any.txt}（无 confirm）→ 预期 400 + body 含 confirm_required
    Evidence: .omo/evidence/task-11-confirm-required.log

  Scenario: 超大文件被拒绝
    Tool: Bash (PowerShell创建 105MB 随机文件上传)
    Steps:
      1. 生成 105MB 文件
      2. curl upload → 预期 413 (Payload Too Large)
      3. 清理临时文件
    Evidence: .omo/evidence/task-11-size-limit.log
  ```

  **Commit**: YES — `feat(agent): file mutation endpoints with confirm guard and size limit`

- [x] 12. services/windows-agent/src/MaimaiHomeAgent/Files/FileRootsConfigEndpoints.cs: GET /api/config + PUT /api/config/file-roots - 在线修改白名单

  **What to do**:
  - `GET /api/config` → 返回当前运行参数：Discovery 配置、file roots（仅 id/name/readOnly）、listenAddress。
  - `PUT /api/config/file-roots` body 为 `[ { id, name, path, readOnly } ]`：验证 id 唯一、path 可验证存在且为目录；Update 后持久化到 `appsettings.user.json`（与主 appsettings 同层，优先级高于 default）。原子写入 (temp + rename)。
  - 返回后 `IFileRootService` 热重载。
  - **Wave 4 之前本接口不鉴权**（调试便利）；Wave 4 后随中间件需 token。

  **Must NOT do**:
  - 不要修改仓库中的 appsettings.json。
  - 不要接受路径为空串或不存在。

  **Recommended Agent Profile**: `quick`

  **Parallelization**:
  - **Can Run In Parallel**: YES（Wave 2a，与 T8、T9、T10、T14 并行）
  - **Blocks**: T17/T18（前端需 list roots）
  - **Blocked By**: T4

  **References**:
  - `services/windows-agent/src/MaimaiHomeAgent/appsettings.json` — 现有配置布局。
  - `Microsoft.Extensions.Configuration` — reload 机制。

  **Acceptance Criteria**:

  **TDD**:
  - [ ] `FileRootsConfigEndpointsTests.cs`：Get 返回预期 shape；Put 重复 id 400；Put path 不存在 400；Put OK 后 Get 反映新状态。

  **QA Scenarios**:

  ```
  Scenario: 热更新 file roots
    Tool: Bash (curl)
    Steps:
      1. PUT /api/config/file-roots [ {id:fr-1, name:Test, path:C:\\temp\\fr-test, readOnly:false} ]
      2. curl /api/file-roots → 含 fr-1
      3. 重启 Agent
      4. curl /api/file-roots → 仍含 fr-1（证明持久化）
    Evidence: .omo/evidence/task-12-config-hot-reload.log
  ```

  **Commit**: YES — `feat(agent): file roots config endpoints with atomic persistence`

- [x] 13. services/windows-agent/src/MaimaiHomeAgent/Realtime/EventPublisher.cs: 连接 Audio/File 事件到 EventHub - 事件定义中枢

  **What to do**:
  - 新建 `Realtime/EventPublisher.cs`：装饰 EventHub，提供领域语义方法：`PublishAudioStateChanged(AudioStateDto)`、`PublishAudioDeviceChanged(...)`、`PublishFileEvent(FileEventDto)`。统一 envelope.type 字段。2
  - 重构 task 8/9/11 的 endpoint 改为调用 `EventPublisher` 而非直接 EventHub。
  - 定义事件运输类型 `EventTypes` (string 常量 class)：`audio.state`、`audio.device.changed`、`file.created`、`file.deleted`、`file.renamed`、`file.moved`、`device.unavailable`。
  - 填充详细 payload schema 到 `dev-docs/wiki/development/agent-events.md`（新建 wiki）。

  **Must NOT do**:
  - 不要提供事件回放/补漏。
  - 不要报包 EventHub——仅装饰。

  **Recommended Agent Profile**: `unspecified-high`

  **Parallelization**:
  - **Can Run In Parallel**: NO（Wave 2c 单件完成 task，需 T8、T9、T11 全部就绪）
  - **Blocks**: T19、T20（客户端）
  - **Blocked By**: T2、T8、T9、T11

  **References**:
  - Metis review 提出 “WebSocket 基础设施在 Wave 1 完成后，Wave 2 可直接发布事件”。

  **Acceptance Criteria**:

  **TDD**:
  - [ ] `EventPublisherTests.cs`：调用领域方法 → EventHub.BroadcastAsync 被调用（mock），字段与 payload 验证。
  - [ ] 所有原先直接调 EventHub 的位置都改为 EventPublisher。

  **QA Scenarios**:

  ```
  Scenario: 事件全链路 E2E
    Tool: Bash (websocat + curl)
    Steps:
      1. websocat ws://...:8765/api/events?token=t 保持
      2. POST /api/audio/volume {level:0.5} → 预期 WS 收到 type=audio.state payload 含 level=0.5
      3. 上传一个文件 → 预期 WS 收到 type=file.created
      4. 删除该文件 → 预期 type=file.deleted
    Evidence: .omo/evidence/task-13-event-e2e.log
  ```

  **Commit**: YES — `feat(agent): unify domain event publishing through EventPublisher`

- [x] 14. services/windows-agent/src/MaimaiHomeAgent/MaimaiHomeAgent.manifest + 深化 path-safety 测试: longPathAware + ReparsePoint 严闭 - Windows 安全严封闭检查

  **What to do**:
  - 新建应用 manifest `MaimaiHomeAgent.manifest`，含 `<application xmlns="..."><windowsSettings><longPathAware xmlns="...">true</longPathAware></windowsSettings></application>`。
  - 在 `MaimaiHomeAgent.csproj` 加 `<ApplicationManifest>MaimaiHomeAgent.manifest</ApplicationManifest>`。
  - 在 `Files/PathGuard.cs` 加强：在检查完 symlink 后也走一遭 “路径链逐层检 ReparsePoint”逻辑。如果中间任何一层有 ReparsePoint 且指向 root 外 → 拒绝。
  - 补充 `PathGuardTests.cs`：
    - 280-char 路径（> 260）仕能规范化
    - 构造 directory junction 指向 root 内 → OK
    - directory junction 指向 root 外 → ReparsePointInPath
  - 文件上传实际路径写入时使用 `\\?\` 前缀（如路径 > 260）会安全点。

  **Must NOT do**:
  - 不要仅依赖 `FileInfo.LinkTarget`（现状不足）。
  - 不要该动 longPathAware 错误 — 需 manifest 生效。

  **Recommended Agent Profile**: `quick`

  **Parallelization**:
  - **Can Run In Parallel**: YES（Wave 2a，与 T8、T9、T10、T12 并行）
  - **Blocks**: T11（作为安全闸门限制，T11 依赖本 task 的测试覆盖）
  - **Blocked By**: T4

  **References**:
  - https://learn.microsoft.com/en-us/windows/win32/fileio/maximum-file-path-limitation — longPathAware 说明。
  - `System.IO.FileSystemInfo.Attributes & FileAttributes.ReparsePoint` — 检查点。

  **Acceptance Criteria**:

  **TDD**:
  - [ ] PathGuard 新增々3 个 tests 全过。
  - [ ] `dotnet test --filter Files.PathGuard`：原 7 + 新 3 = 10 pass。

  **QA Scenarios**:

  ```
  Scenario: long path 能被受理
    Tool: Bash (PowerShell mklink + dotnet test)
    Steps:
      1. 创建 280-char 临时路径与文件
      2. 调 PathGuard 验证 → 应返回 OK
    Evidence: .omo/evidence/task-14-long-path.log

  Scenario: directory junction 跨越拒绝
    Tool: Bash (PowerShell New-Item -ItemType Junction)
    Steps:
      1. mklink /J root\out -> C:\Windows
      2. 调 PathGuard.ResolveSafe(root, 'out\\notepad.exe')
      3. 验证返回 ReparsePointInPath
    Evidence: .omo/evidence/task-14-junction-rejected.log
  ```

  **Commit**: YES — `chore(agent): add longPathAware manifest and harden path-safety against junctions`

---

### Wave 3 — Frontend integration (高并行 6 个 task)

- [x] 15. apps/mobile/lib/pages/audio_page.dart + lib/state/audio_provider.dart: Mobile 音频控制页 - 调音量/静音/切设备

  **What to do**:
  - 新建 `lib/state/audio_provider.dart`：
    - `audioStateProvider` (`FutureProvider<AudioState>`) — 读 /api/audio/state
    - `audioDevicesProvider` (`FutureProvider<List<AudioDevice>>`) — 读 /api/audio/devices
    - `audioControllerProvider` (`Notifier`)：`setVolume(double)`、`setMute(bool)`、`switchDevice(String id)`——调用 `AgentClient`，成功后 invalidate 上面的 provider。
  - `lib/services/agent_client.dart` 增加对应方法。
  - `lib/pages/audio_page.dart`：
    - 顶部连接状态条
    - 中部：当前默认设备名 + 音量滑条（onChangeEnd 包裹避免复发）+ 静音开关
    - 下部：设备列表 ListView，点击切换
    - error widget：在 503 busy / 502 device_unavailable / 网络错误时错位显示重试按钮
  - 从 ConnectionPage 连接成功后提供入口 push 到 audio page (主要页面)。

  **Must NOT do**:
  - 不要在滑条拖动实时 spam API（仅在 onChangeEnd 发 set）。
  - 不要加均衡器/spatial UI（scope 外）。

  **Recommended Agent Profile**: `visual-engineering`
  - Reason: 需交互调优 + 错误態处理 + 页面布局。

  **Parallelization**:
  - **Can Run In Parallel**: YES（Wave 3）
  - **Blocks**: F3
  - **Blocked By**: T6、T8、T9

  **References**:
  - `apps/mobile/lib/pages/connection_page.dart`（T6 产出）— ConsumerWidget 样本。
  - `dev-docs/wiki/development/flutter-mobile-connection-page.md` 下一步部分 — 提示 “接入 GET /api/audio/state 与音量静音控制”。
  - Riverpod FutureProvider 与 Notifier 官方文档。

  **Acceptance Criteria**:

  **TDD**:
  - [ ] `test/state/audio_controller_test.dart`：setVolume 后 audioState invalidate 、setMute 、switchDevice 同样。
  - [ ] `test/widget/audio_page_test.dart`：
    - mock provider，渲染页面，断言 默认设备名出现
    - 拖动滑条 onChangeEnd 调用 setVolume
    - error 状态显示重试按钮
  - [ ] `flutter test apps/mobile`：全过。

  **QA Scenarios**:

  ```
  Scenario: 手机页面动现实 Agent
    Tool: Bash (flutter run + adb shell uiautomator)或 integration_test
    Steps:
      1. 启动 Agent (带 audio T8/T9 已完成)
      2. flutter run -d <emulator-id>
      3. 连接后点击 “音频” 入口
      4. 调音量到 0.40 → 接口收到 → Windows 系统音量同步
    Evidence: .omo/evidence/task-15-mobile-audio-integration.log
  ```

  **Commit**: YES — `feat(mobile): audio control page with Riverpod providers`

- [x] 16. apps/pc-web/src/pages/AudioPage.tsx + hooks/useAudio.ts: PC Web 音频页面 - 与移动端平行一致

  **What to do**:
  - 新建 `src/hooks/useAudio.ts`：`useAudioState()`、`useAudioDevices()`、`useSetVolume()`、`useSetMute()`、`useSwitchDevice()` — 全部使用 TanStack Query 的 useQuery / useMutation，成功后 invalidate keys `['audio','state']` / `['audio','devices']`。
  - 新建 `src/services/audioApi.ts` — axios 调用。
  - `src/pages/AudioPage.tsx`：
    - 头部：连接状态 + Agent 名
    - 中部：音量 slider（react-aria 或朴素 input range）+ mute toggle
    - 下部：设备列表按钮
    - 错误 toast（可选 react-hot-toast 或手写轻量 toast）
  - 路由 `/audio` 连这个页面。

  **Must NOT do**:
  - 不要引入 UI 库（Mantine/Radix）— 本MVP 使用原生 HTML + 轻量 CSS。
  - 不要做拖拽上传、多选、文件预览。

  **Recommended Agent Profile**: `visual-engineering`

  **Parallelization**:
  - **Can Run In Parallel**: YES（Wave 3）
  - **Blocks**: T28、F3
  - **Blocked By**: T7、T8、T9

  **References**:
  - TanStack Query 示例 https://tanstack.com/query/latest/docs/framework/react/examples/basic
  - `src/lib/queryClient.ts` （T7 产出）。

  **Acceptance Criteria**:

  **TDD**:
  - [ ] `src/hooks/useAudio.test.ts`：@testing-library/react `renderHook`，mock axios 验证 query/mutation 行为。
  - [ ] `src/pages/AudioPage.test.tsx`：验证页面渲染、滑条交互、错误状态。
  - [ ] `pnpm vitest run`：全过。
  - [ ] `pnpm tsc --noEmit`：0 errors。

  **QA Scenarios**:

  ```
  Scenario: PC Web 页面控制音量
    Tool: Playwright (playwright skill)
    Preconditions: Agent 运行，vite dev 起，Agent 有可用设备
    Steps:
      1. browser.goto http://127.0.0.1:5173/audio
      2. wait for selector input[type="range"][aria-label="主音量"]
      3. fill input with value 0.5 → dispatch change → blur 触发 mutation
      4. 等待 fetch 返回
      5. assert text “音量 50%” 出现
      6. 截图
    Evidence: .omo/evidence/task-16-pcweb-audio.png
  ```

  **Commit**: YES — `feat(pc-web): audio control page with TanStack Query hooks`

- [x] 17. apps/mobile/lib/pages/files_page.dart + state/files_provider.dart: Mobile 文件页 - 浏览/上传/下载/重命名/删除/同根移动

  **What to do**:
  - 新增 providers：`fileRootsProvider`、`fileListingProvider.family((rootId, path))`、`fileMutationsProvider` (Notifier)。
  - 页面 `files_page.dart`：root 选择器 + 面包屑导航 + ListView。
  - 操作 bottom sheet：上传（`file_picker` 或原生 intent）、重命名、删除（二次确认 dialog）、移动（访问同 root 下另一路径）。
  - 请求体必携 `confirm: true`（由 mutation provider 统一设置）。
  - 下载使用 `dio.download` 或原生下载到 app下载目录。

  **Must NOT do**:
  - 不要递归浏览。
  - 不要做文件预览缩略图。
  - 不要跨 root 移动。

  **Recommended Agent Profile**: `visual-engineering`

  **Parallelization**:
  - **Can Run In Parallel**: YES（Wave 3）
  - **Blocks**: F3
  - **Blocked By**: T6、T10、T11、T12

  **References**:
  - `dev-docs/features/2026-05-11-maimai-home-assistant/实现.md` 阶段 7。
  - `pubspec.yaml`：加 `file_picker: ^8.0.0` 依赖。

  **Acceptance Criteria**:

  **TDD**:
  - [ ] `test/state/files_provider_test.dart`：fetch listing、delete 后 invalidate、rename 后 invalidate。
  - [ ] `test/widget/files_page_test.dart`：带 root 列表、面包屑导航、删除二次确认弹出。

  **QA Scenarios**:

  ```
  Scenario: 手机上传后文件出现
    Tool: integration_test (mobile)
    Steps:
      1. 连接 Agent
      2. 进入文件页 root=test
      3. tap upload 选择 sample.txt
      4. 等待上传完成 → 列表出现 sample.txt
    Evidence: .omo/evidence/task-17-mobile-upload.log
  ```

  **Commit**: YES — `feat(mobile): file browser with upload/download/rename/delete/move`

- [x] 18. apps/pc-web/src/pages/FilesPage.tsx + hooks/useFiles.ts: PC Web 文件页 - 与移动端一致能力

  **What to do**:
  - `src/hooks/useFiles.ts`：`useFileRoots()`、`useFileListing(rootId, path)`、`useUpload()`、`useDelete()`、`useRename()`、`useMove()`、`useDownload()`。
  - 页面 `FilesPage.tsx`：root 侧栏 + 路径导航 + 表格。上传用原生 `<input type="file">`，不做拖拽。
  - 删除、重命名、移动都弹原生 confirm dialog（window.confirm 或一个简单的 modal）。
  - 页面根据查询 `truncated:true` 显示提示。
  - 路由 `/files`。

  **Must NOT do**:
  - 不要拖拽、多选、预览（scope 外）。

  **Recommended Agent Profile**: `visual-engineering`

  **Parallelization**:
  - **Can Run In Parallel**: YES（Wave 3）
  - **Blocks**: T28、F3
  - **Blocked By**: T7、T10、T11、T12

  **References**:
  - axios FormData：https://axios-http.com/docs/post_example

  **Acceptance Criteria**:

  **TDD**:
  - [ ] `useFiles.test.ts`：upload mutation 验证 confirm 标记与 key invalidation。
  - [ ] `FilesPage.test.tsx`：root 选择 + listing 渲染 + delete 二次确认。
  - [ ] `pnpm vitest run` 、`pnpm tsc --noEmit`。

  **QA Scenarios**:

  ```
  Scenario: PC Web 上传流程
    Tool: Playwright
    Steps:
      1. goto /files
      2. select root test
      3. click “上传” → setInputFiles 临时生成的 sample.bin
      4. 等列表中 sample.bin 出现
      5. 点删除 → confirm 二次 → 文件消失
    Evidence: .omo/evidence/task-18-pcweb-files.png + delete-after.png
  ```

  **Commit**: YES — `feat(pc-web): file browser page with upload/download/rename/delete/move`

- [x] 19. apps/mobile/lib/services/event_stream.dart + state/events_provider.dart: Mobile WebSocket 客户端 + Riverpod stream + 指数退避重连 - 实时状态同步

  **What to do**:
  - 新建 `services/event_stream.dart`：`web_socket_channel` 连 `ws://<host>:<port>/api/events?token=<t>`。
  - 重连策略：指数退避（1s → 2s → 4s → ... max 30s），错误/关闭/超时都走同逻辑。重连后调用传入的 `onReconnected` callback（上层依赖调 invalidate 重拉 audio + files）。
  - 反序列化 `EventEnvelope`：`type`、`payload`、`timestamp`。
  - `events_provider.dart`：`StreamProvider<EventEnvelope>` 。
  - 在 audio_provider 与 files_provider 中订阅事件 → 选择性 invalidate。

  **Must NOT do**:
  - 不要在应用 background 时保持连接（按 OS 默认行为、不另外加 wakelock）。
  - 不要存储事件历史（fire-and-forget）。

  **Recommended Agent Profile**: `unspecified-high`

  **Parallelization**:
  - **Can Run In Parallel**: YES（Wave 3）
  - **Blocks**: F3
  - **Blocked By**: T2、T6、T13、T15

  **References**:
  - https://pub.dev/packages/web_socket_channel
  - Riverpod StreamProvider 。

  **Acceptance Criteria**:

  **TDD**:
  - [ ] `test/services/event_stream_test.dart`：
    - mock WebSocket close → 验证 1s 后重连
    - 连续失败 → backoff 递增
    - 成功连接后 backoff 重置

  **QA Scenarios**:

  ```
  Scenario: Agent 重启后手机自动重连
    Tool: Bash + adb logcat (或 flutter integration_test)
    Steps:
      1. 启 Agent 并连上手机
      2. kill Agent
      3. 重启 Agent
      4. 期待 30s 内手机状态重新为 connected，audio state 重新拉取
    Evidence: .omo/evidence/task-19-mobile-reconnect.log
  ```

  **Commit**: YES — `feat(mobile): WebSocket event stream with exponential backoff reconnect`

- [x] 20. apps/pc-web/src/lib/eventStream.ts + hooks/useEventStream.ts: PC Web WebSocket 客户端 + TanStack Query invalidation - 实时同步

  **What to do**:
  - `src/lib/eventStream.ts`：原生 `WebSocket` API。退避重连与 Mobile 一致。提供 `subscribe(handler)` 接口。
  - `src/hooks/useEventStream.ts`：`useEffect` 订阅 + 选择性 `queryClient.invalidateQueries`：
    - `audio.state` → invalidate `['audio','state']`
    - `audio.device.changed` → invalidate `['audio','devices','state']`
    - `file.*` → invalidate `['files','listing', rootId, path]`
  - 在 App 顶层调 `useEventStream()`。

  **Must NOT do**:
  - 不要 invalidate 全部 (仅选择性)。
  - 不要 reload 页面。

  **Recommended Agent Profile**: `unspecified-high`

  **Parallelization**:
  - **Can Run In Parallel**: YES（Wave 3）
  - **Blocks**: T28、F3
  - **Blocked By**: T2、T7、T13、T16

  **References**:
  - https://developer.mozilla.org/en-US/docs/Web/API/WebSocket
  - TanStack Query invalidateQueries。

  **Acceptance Criteria**:

  **TDD**:
  - [ ] `useEventStream.test.ts`：mock WebSocket，推入 audio.state 事件 → 验证 invalidateQueries 被调与预期 keys。
  - [ ] backoff 与重连与 mobile 对齐的 unit test。

  **QA Scenarios**:

  ```
  Scenario: Windows 手动切设备 → PC Web 同步
    Tool: Playwright + PowerShell Set-AudioDevice
    Steps:
      1. browser.goto /audio
      2. note 当前默认设备名
      3. 后台 PowerShell 切为另一设备
      4. 期待 5s 内页面样式变化（当前设备高亮转移）
      5. 截图 before/after
    Evidence: .omo/evidence/task-20-pcweb-realtime-before.png + after.png
  ```

  **Commit**: YES — `feat(pc-web): WebSocket event stream with selective query invalidation`

---

### Wave 4 — Auth (5 个 task)

- [x] 21. services/windows-agent/src/MaimaiHomeAgent/Security/PairingEndpoints.cs: POST /api/pairing/code (中期限制) + GET /api/pairing/active - 生成配对码

  **What to do**:
  - `POST /api/pairing/code` body `{ deviceLabel }` → 检查调用源是否为本机（`HttpContext.Connection.RemoteIpAddress.IsLoopback`）或托盘发起；非本机/未授权 → 401。
  - 调 `IPairingService.CreateCode`，返回 `{ code, expiresAt }`（不返回 IP 绑定信息给客户端）。
  - `GET /api/pairing/active` → 返回当前未过期的配对码（主要供托盘菜单调用）。
  - **本 task 仅提供 API**；UI 在 T26 (托盘) 、T24/T25 (客户端输入码)。
  - 临时为了在开发期调试方便，提供 CLI 标志 `--debug-pairing-code` → 启动时提前生成一个固定码并打印。

  **Must NOT do**:
  - 不要返回该码的详细定位数据。
  - 不要让未鉴权请求调用。

  **Recommended Agent Profile**: `unspecified-high`

  **Parallelization**:
  - **Can Run In Parallel**: YES（Wave 4）
  - **Blocks**: T24、T25、T26
  - **Blocked By**: T5

  **References**:
  - `dev-docs/features/2026-05-11-maimai-home-assistant/实现.md` 阶段 8。

  **Acceptance Criteria**:

  **TDD**:
  - [ ] `PairingEndpointsTests.cs`：远程 IP 调用 → 401；loopback 调用 → 200 + 含 code。

  **QA Scenarios**:

  ```
  Scenario: 远程生成 code 被拒绝
    Tool: Bash (curl from 局域网另一台机器或模拟非 loopback header)
    Steps:
      1. 从同局域网另一机器 curl POST /api/pairing/code
      2. 预期 401
    Evidence: .omo/evidence/task-21-remote-pairing-blocked.log

  Scenario: 本机生成 code
    Steps:
      1. 本机 curl POST /api/pairing/code -d '{"deviceLabel":"Phone"}'
      2. 预期 200 且 body 含 6 位数字 code、expiresAt
    Evidence: .omo/evidence/task-21-local-pairing.log
  ```

  **Commit**: YES — `feat(agent): pairing code generation API with loopback restriction`

- [x] 22. services/windows-agent/src/MaimaiHomeAgent/Security/AuthMiddleware.cs + token 交换 - 鉴权启动 (HTTP + WebSocket)

  **What to do**:
  - `POST /api/pairing/exchange` body `{ code, deviceLabel }` → `IPairingService.Exchange` → atomic remove + 返回 `{ token, tokenId, expiresAt }`（token 写入 token store）。
  - 新建 `Security/AuthMiddleware.cs`：HTTP 路由读 `Authorization: Bearer ...` 或 query `?token=...`（WebSocket）。未鉴权请求 → 401（HTTP）或 close 连接（1008）（WebSocket）。
  - 白名单路由（无需鉴权）：`/api/status`、`/api/pairing/exchange`、静态资源、`MapFallbackToFile`。
  - WebSocket 鉴权：在 `/api/events` handler 入口检查 `?token=`（T2 预留），失败 → close 1008（policy violation）。
  - Token 验证：`ITokenStore.ValidateAsync(token)` → 返回 TokenRecord。过期 → 401。
  - 将鉴权后的 `TokenRecord` 填入 `HttpContext.Items["Token"]` 供下游调用。
  - 更新现有接口使默认鉴权（T7 中 /api/audio/*, /api/files/*, /api/file-roots, /api/config 都走中间件）。

  **Must NOT do**:
  - 不要 cookie auth。
  - 不要 refresh token。
  - 不要同时接受 query 与 header——HTTP 仅 header，WebSocket 仅 query。

  **Recommended Agent Profile**: `unspecified-high`

  **Parallelization**:
  - **Can Run In Parallel**: YES（Wave 4）
  - **Blocks**: T24、T25、F1
  - **Blocked By**: T2、T5

  **References**:
  - https://learn.microsoft.com/en-us/aspnet/core/fundamentals/middleware — 中间件订订。

  **Acceptance Criteria**:

  **TDD**:
  - [ ] `AuthMiddlewareTests.cs`：白名单路由无 token 运作。非白名单无 token → 401。过期 token → 401。有效 token → 200 + ctx.Items["Token"] 填充。
  - [ ] WebSocket 拒绝测试：验证连接被 close 且 close code 1008。

  **QA Scenarios**:

  ```
  Scenario: 未鉴权 HTTP 拒绝
    Tool: Bash (curl)
    Steps:
      1. curl /api/audio/state 不带 token → 401
      2. curl /api/audio/state -H 'Authorization: Bearer wrong' → 401
    Evidence: .omo/evidence/task-22-auth-http.log

  Scenario: 未鉴权 WebSocket close
    Tool: Bash (websocat)
    Steps:
      1. websocat ws://...:8765/api/events?token=wrong
      2. 预期 close 且返回错误码 1008
    Evidence: .omo/evidence/task-22-auth-ws.log
  ```

  **Commit**: YES — `feat(agent): bearer auth middleware and pairing exchange (HTTP + WebSocket)`

- [x] 23. services/windows-agent/src/MaimaiHomeAgent/Security/TokenAdminEndpoints.cs: GET/DELETE /api/tokens - 列出与撤销

  **What to do**:
  - `GET /api/tokens` → 返回 TokenRecord 列表（**不含 token 主体**，仅 id/label/createdAt/expiresAt/issuedToIp）。需鉴权。
  - `DELETE /api/tokens/{id}` → ITokenStore.RemoveAsync。
  - 可选：如果删除的是当前请求使用的 token，返回 200 + 提示 `self_deleted`（客户端后续会 401）。

  **Must NOT do**:
  - 不要返回明文 token。

  **Recommended Agent Profile**: `quick`

  **Parallelization**:
  - **Can Run In Parallel**: YES（Wave 4）
  - **Blocks**: F1
  - **Blocked By**: T5、T22

  **Acceptance Criteria**:

  **TDD**:
  - [ ] `TokenAdminEndpointsTests.cs`：鉴权后 List；Delete 后 List 不含。删自己 token → 200 + 提示。

  **QA Scenarios**:

  ```
  Scenario: 撤销后 后续调用 401
    Tool: Bash (curl)
    Steps:
      1. 使用 token A curl /api/tokens → 读到自己 id
      2. DELETE /api/tokens/<self-id>
      3. 同 token A curl /api/audio/state → 401
    Evidence: .omo/evidence/task-23-token-revoke.log
  ```

  **Commit**: YES — `feat(agent): token list and revoke admin endpoints`

- [x] 24. apps/mobile/lib/pages/pairing_page.dart + state/auth_provider.dart: Mobile 配对流程 - 输入 code 换 token

  **What to do**:
  - 新建 `state/auth_provider.dart`：`tokenProvider` 读写 SharedPreferences `agent_token`。
  - `pages/pairing_page.dart`：输入 code + 调 `/api/pairing/exchange` → 保存 token → 跳到 audio_page。
  - 拦截接口 401 → 自动清空 token 并跳回配对页。
  - `agent_client.dart` 增加 `Authorization: Bearer <token>` 拦截器。
  - `event_stream.dart` 拼接 `?token=`。

  **Must NOT do**:
  - 不要保存 token 明文以外的集成使用记录。
  - 不要实现 refresh。

  **Recommended Agent Profile**: `visual-engineering`

  **Parallelization**:
  - **Can Run In Parallel**: YES（Wave 4）
  - **Blocks**: F3
  - **Blocked By**: T6、T21、T22

  **References**:
  - SharedPreferences：https://pub.dev/packages/shared_preferences

  **Acceptance Criteria**:

  **TDD**:
  - [ ] `auth_provider_test.dart`：load empty、save 后 load 返回、clear 。
  - [ ] `pairing_page_test.dart`：输入错码 → 错误提示；正确码 → 跳转。
  - [ ] 401 拦截 unit test。

  **QA Scenarios**:

  ```
  Scenario: 手机配对 → 控音量 (全脚本化)
    Tool: flutter integration_test + Bash
    Preconditions: Agent 运行，Android emulator 可访问宿主机。
    Steps:
      1. Bash: curl -X POST http://127.0.0.1:8765/api/pairing/code -d '{"deviceLabel":"E2E"}' → 解析返回 code (loopback 调用 T21 授权)
      2. flutter integration_test 启动 PairingPage，调 driver fillText(code) + tap(配对按钮)
      3. 等待 1s，assert 跳转到 audio_page
      4. driver 拖动 volume slider 到 0.50 → onChangeEnd 触发 mutation
      5. Bash: curl /api/audio/state -H "Authorization: Bearer $token" → 断言 level ≈ 0.5
    Expected Result: integration_test 通过 + curl 返回的 level 与预期一致
    Evidence: .omo/evidence/task-24-mobile-pairing.log + curl-state.json
  ```

  **Commit**: YES — `feat(mobile): pairing flow with bearer token auth`

- [x] 25. apps/pc-web/src/pages/PairingPage.tsx + auth store: PC Web 配对流程

  **What to do**:
  - `agentStore` 增加 token 字段 + persist 到 `localStorage`（当前仕设为 `localStorage` 手动读写，不依赖 zustand persist 中间件）。
  - `axios` 拦截器：请求附 Bearer；响应 401 → 清空 token + redirect /pairing。
  - `PairingPage.tsx`：输入 code 调用 `/api/pairing/exchange`。
  - 路由 guard：未登录 → 除 /pairing 外全重定向 /pairing。

  **Must NOT do**:
  - 不要用 cookie 存 token。
  - 不要在路由 guard 跳 PairingPage 后丢原 URL（需 redirect-after-login）。

  **Recommended Agent Profile**: `visual-engineering`

  **Parallelization**:
  - **Can Run In Parallel**: YES（Wave 4）
  - **Blocks**: T28、F3
  - **Blocked By**: T7、T21、T22

  **Acceptance Criteria**:

  **TDD**:
  - [ ] `PairingPage.test.tsx`：错码 → 错误提示、正确 → redirect /audio。
  - [ ] axios 拦截 401 测试。
  - [ ] 路由 guard test。

  **QA Scenarios**:

  ```
  Scenario: PC Web 配对 → 控音量
    Tool: Playwright
    Steps:
      1. 本机生 code
      2. browser goto /audio → 被重定向 /pairing
      3. 输入 code 提交
      4. 跳回 /audio 能调音量
    Evidence: .omo/evidence/task-25-pcweb-pairing.png
  ```

  **Commit**: YES — `feat(pc-web): pairing flow with bearer token persistence`

---

### Wave 5a — Tray + AutoStart + Build pipeline (3 个 task 并行)

- [x] 26. services/windows-agent/src/MaimaiHomeAgent/Tray/: H.NotifyIcon 托盘菜单 - 生成配对码 / 切自启 / 退出

  **What to do**:
  - **首先验证**：`H.NotifyIcon.Wpf` 与单文件发布是否兼容（Metis flag）：在机器跑一次 `dotnet publish -r win-x64 --self-contained -p:PublishSingleFile=true` 验证 ; 如需 WPF 则加 `<UseWPF>true</UseWPF>` 并列 `IncludeNativeLibrariesForSelfExtract=true`；如冲突不可调和 → 切换到非 WPF 变体 `H.NotifyIcon` (Win32) 并调整依赖。
  - 新建 `Tray/TrayApp.cs`：在 `Program.cs` 中依环境 `RuntimeInformation.IsOSPlatform(OSPlatform.Windows)` 加载；依 `IHostedService` 生命周期启停。
  - 菜单项（仅 4 项，严遵 Metis 锁定范围）：
    1. “状态: 运行中 / 未运行”（仅显示，不可点）
    2. “生成配对码” → 调 `IPairingService.CreateCode(loopback ip)` → 在弹出小窗上显示 code 与倒计时
    3. “开机自启” (toggle, 调 T27 service)
    4. “退出” → 打心 `IHostApplicationLifetime.StopApplication`
  - 托盘图标：项目 `Resources/tray.ico`（仓性 16/32 存档）。
  - 不提供 log 查看、settings UI（scope 外）。

  **Must NOT do**:
  - 不要加额外菜单项。
  - 不要在菜单中直接启动启动项配置 UI。
  - 不要让托盘 锉死主进程（需以 IHostedService 集成运行）。

  **Recommended Agent Profile**: `unspecified-high`
  - Reason: 需验证 WPF 与单文件兼容，metis 点出是高风险。

  **Parallelization**:
  - **Can Run In Parallel**: YES（Wave 5a，与 T27、T28 并行；本 task 是 T29 重要 risk gate）
  - **Blocks**: T29
  - **Blocked By**: T21、T23

  **References**:
  - https://github.com/HavenDV/H.NotifyIcon
  - .NET single-file publish gotchas。

  **Acceptance Criteria**:

  **TDD**:
  - [ ] `tests/MaimaiHomeAgent.Tests/Tray/TrayBridgeTests.cs`：
    - named pipe 接收 `invoke:generate-pairing-code` → 调 IPairingService.CreateCode 一次
    - 未知命令 → 忽略不报错
  - [ ] WPF 与单文件差异验证：`dotnet publish -c Release -r win-x64 --self-contained -p:PublishSingleFile=true` 本机跑一次不报错。
  - [ ] `dotnet test --filter Tray`：≥2 pass。

  **QA Scenarios**:

  ```
  Scenario: 托盘生成配对码 → 可交换 (全脚本化)
    Tool: Bash (Start-Process + curl + IPC)
    Preconditions: publish profile 未必要；运行本机 debug build
    Steps:
      1. PowerShell Start-Process Agent (--debug-tray-bridge 启动标志暴露 named pipe 供测试模拟 tray click)
      2. tmux 启后，轮询 GET http://127.0.0.1:8765/api/status 等待 200
      3. 通过 named pipe 发送 'invoke:generate-pairing-code' 模拟菜单点击
      4. curl http://127.0.0.1:8765/api/pairing/active 读出当前 code
      5. curl POST /api/pairing/exchange {code} → 返回 token
      6. curl /api/audio/state -H 'Authorization: Bearer <token>' → 200
      7. Stop-Process 控制 Agent 退出
    Expected Result: pipe 触发后 active 能列出 code；exchange 返回 token；token 能调 audio API
    Evidence: .omo/evidence/task-26-tray-pairing.log + .omo/evidence/task-26-pipe-bridge.log
    Note: 托盘图标可视性 (画面上是否出现) 由 F3 在交互环境中验证
  ```

  **Commit**: YES — `feat(agent): tray icon and minimal context menu`

- [x] 27. services/windows-agent/src/MaimaiHomeAgent/Startup/: 任务计划自启集成 - 用户授权后写入 user-level 任务

  **What to do**:
  - 新建 `Startup/AutoStartManager.cs`：提供 `bool IsEnabled()`、`Task EnableAsync()`、`Task DisableAsync()`。
  - 实现使用任务计划（调 `schtasks.exe` 子进程，避免依赖 COM TaskScheduler）：
    - Enable：`schtasks /Create /TN MaimaiHomeAgent /SC ONLOGON /TR "<absolute-exe-path>" /RL LIMITED /F`（仅当前用户）。
    - Disable：`schtasks /Delete /TN MaimaiHomeAgent /F`。
    - IsEnabled：`schtasks /Query /TN MaimaiHomeAgent` exit code 判断 + 校验任务中的路径与当前进程路径一致（Metis 提示）。
  - 在 tray 菜单“开机自启” toggle 中调用。
  - 需用户使用 admin 权限启动 Agent 才能修改？——`schtasks /SC ONLOGON /RL LIMITED` 本机以当前用户身份创建任务不需 admin。
  - 出错 → 记录 Serilog，返回失败，tray 显示错误必育 toast。

  **Must NOT do**:
  - 不要写入注册表 Run 键（都以 SCH 任务实现）。
  - 不要该动系统-级任务。

  **Recommended Agent Profile**: `unspecified-high`

  **Parallelization**:
  - **Can Run In Parallel**: YES（Wave 5a，与 T26、T28 并行）
  - **Blocks**: T29、F3
  - **Blocked By**: T5（需 IPairingService 供 tray 菜单不相关；仅依赖 T5 及之前已补齐的项目骨架）

  **References**:
  - https://learn.microsoft.com/en-us/windows/win32/taskschd/schtasks

  **Acceptance Criteria**:

  **TDD**:
  - [ ] `tests/MaimaiHomeAgent.Tests/Startup/AutoStartManagerTests.cs`：
    - 代理 `Process.Start` 验证 EnableAsync 传入参数中含 `/Create /TN MaimaiHomeAgent /SC ONLOGON /RL LIMITED`
    - DisableAsync 传入含 `/Delete /TN MaimaiHomeAgent /F`
    - IsEnabled：任务存在且路径匹配 → true；路径不匹配 → false；任务不存在 → false
    - schtasks 子进程返回非 0 → 记录错误且返回失败（不抛）
  - [ ] `dotnet test --filter Startup`：≥4 pass。

  **QA Scenarios**:

  ```
  Scenario: AutoStart 启用后任务存在且路径一致 (全脚本化)
    Tool: Bash (PowerShell + dotnet test)
    Preconditions: 以当前用户运行，未预先存在同名任务
    Steps:
      1. dotnet test --filter AutoStart（T27 单元测试）→ 全过
      2. 在 PowerShell 调 AutoStartManager.EnableAsync()（通过临时 console harness 或 dotnet run 下的临时调试接口）
      3. schtasks /Query /TN MaimaiHomeAgent /V /FO LIST > task-info.txt
      4. 断言 task-info.txt 含 当前 exe 路径与 Triggers=“At log on”
      5. 调 DisableAsync()
      6. schtasks /Query 退出码 ≠0 表示已删
    Expected Result: enable 后任务出现且路径匹配；disable 后任务被删
    Evidence: .omo/evidence/task-27-autostart-task-info.txt + .omo/evidence/task-27-autostart-removed.txt
    Note: 真实 logoff/logon 周期由 F3 在交互环境中执行
  ```

  **Commit**: YES — `feat(agent): user-level auto-start via Task Scheduler`

- [x] 28. apps/pc-web/build pipeline + services/windows-agent wwwroot 最终接入 - PC Web 产物陪 Agent 发布

  **What to do**:
  - 在 `services/windows-agent/MaimaiHomeAgent.sln` 同级加 `build.ps1`（可选），或在 `services/windows-agent/src/MaimaiHomeAgent/MaimaiHomeAgent.csproj` 加 BeforeBuild target：检查 `wwwroot/index.html` 是否存在；不存在则在嵌层 console 打印 “PC Web build artifacts not found, run pnpm --dir apps/pc-web build first” — 不中断 build。
  - 优化 `apps/pc-web/vite.config.ts`：`build.outDir` 指向 `../../services/windows-agent/src/MaimaiHomeAgent/wwwroot`（同 T7。但本 task 中验证路径最终成立）。
  - `apps/pc-web/package.json` scripts：`build`、`build:agent`（= `vite build` 创建该到 wwwroot）、`dev`、`test`。
  - 补充 `services/windows-agent/README.md` 发布步骤。
  - 严限 wwwroot 中只进 Vite 产物与 .gitkeep，错误资产需走 .gitignore。

  **Must NOT do**:
  - 不要调用 pnpm 在 dotnet build 过程（交叉工具链会累赘，依 Metis 边界谈论）。

  **Recommended Agent Profile**: `unspecified-high`

  **Parallelization**:
  - **Can Run In Parallel**: YES（Wave 5a，与 T26、T27 并行）
  - **Blocks**: T29
  - **Blocked By**: T7、T16、T18、T20、T25

  **Acceptance Criteria**:

  **TDD**:
  - [ ] `apps/pc-web/package.json` 包含 scripts: `dev`、`build`、`build:agent`、`test`（提供 script harness 检查）。
  - [ ] `apps/pc-web/vite.config.ts` build.outDir 指向 `services/windows-agent/src/MaimaiHomeAgent/wwwroot`（unit test 读配置字段验证）。
  - [ ] csproj `BeforeBuild` target 在 wwwroot 缺失 index.html 时只 warn 不中断 build（`dotnet build` 在空 wwwroot 下进口应 exit code 0）。
  - [ ] `services/windows-agent/README.md` 含“如何发布 PC Web 产物”步骤 (`grep -F 'pnpm --dir apps/pc-web build' services/windows-agent/README.md` 返回 ≥1 行)。

  **QA Scenarios**:

  ```
  Scenario: build 后 wwwroot 含产物
    Tool: Bash
    Steps:
      1. pnpm --dir apps/pc-web install --frozen-lockfile
      2. pnpm --dir apps/pc-web build
      3. ls services/windows-agent/src/MaimaiHomeAgent/wwwroot/index.html → 存在
      4. dotnet run agent → curl http://127.0.0.1:8765/audio → 200 且 body 含项目名称
    Evidence: .omo/evidence/task-28-pcweb-build.log
  ```

  **Commit**: YES — `chore(pc-web,agent): wire pc-web build artifacts to agent wwwroot`

### Wave 5b — Final packaging (1 个 task)

- [x] 29. services/windows-agent/src/MaimaiHomeAgent/Properties/PublishProfiles/win-x64.pubxml: dotnet publish 单文件 self-contained - 发布交付 exe

  **What to do**:
  - 创建发布 profile：
    - `<RuntimeIdentifier>win-x64</RuntimeIdentifier>`
    - `<SelfContained>true</SelfContained>`
    - `<PublishSingleFile>true</PublishSingleFile>`
    - `<IncludeNativeLibrariesForSelfExtract>true</IncludeNativeLibrariesForSelfExtract>`
    - `<EnableCompressionInSingleFile>true</EnableCompressionInSingleFile>`
    - `<DebugType>embedded</DebugType>`
    - **不启用 trimming**（AudioSwitcher / NAudio 未验证 trimming 兼容。备注在 README）
  - 补充发布脚本 `services/windows-agent/publish.ps1`：clean → pnpm build pc-web → dotnet publish → 检查生成的 exe 体积与存在。
  - `services/windows-agent/README.md` 补充“如何发布”步骤，含防火墙放行提示。
  - 补充 dev-docs/wiki 新增 `windows-agent-publish.md` 记录产物位置、验证步骤。
  - 生成产物预计 50-80 MB，含 WPF（如 T26 需 WPF）。

  **Must NOT do**:
  - 不要启用 trimming。
  - 不要发布 framework-dependent（选择 self-contained）。

  **Recommended Agent Profile**: `unspecified-high`

  **Parallelization**:
  - **Can Run In Parallel**: NO（Wave 5b单件完成 task）
  - **Blocks**: F1、F3
  - **Blocked By**: T26、T27、T28

  **References**:
  - https://learn.microsoft.com/en-us/dotnet/core/deploying/single-file/overview

  **Acceptance Criteria**:

  **TDD**:
  - [ ] `tests/MaimaiHomeAgent.Tests/Publish/PublishProfileTests.cs`：
    - 验证 `Properties/PublishProfiles/win-x64.pubxml` 存在且含 SelfContained=true、PublishSingleFile=true、RuntimeIdentifier=win-x64
    - 验证未启用 `<PublishTrimmed>` 或 `<TrimMode>`
  - [ ] 文档验证：`services/windows-agent/README.md` 含发布步骤与防火墙提示 (`grep -F 'dotnet publish' services/windows-agent/README.md` 返回 ≥1 行)。
  - [ ] 发布脚本 `services/windows-agent/publish.ps1` 存在且 syntax check 通过（`pwsh -NoProfile -Command "$null = [scriptblock]::Create((Get-Content -Raw publish.ps1))"`）。

  **QA Scenarios**:

  ```
  Scenario: publish 产物启动验证 (全脚本化)
    Tool: Bash (Start-Process + curl)
    Preconditions: publish profile 已完成。在作者开发机上运行（F3 会在额外的 clean VM 上重跱）
    Steps:
      1. dotnet publish -c Release → 产物路径记为 $exePath
      2. PowerShell `$proc = Start-Process -FilePath $exePath -PassThru -WindowStyle Hidden`
      3. 轮询 curl http://127.0.0.1:8765/api/status，最多 15s，期间 200
      4. Stop-Process -Id $proc.Id
    Expected Result: 15s 内 status 返回 200
    Evidence: .omo/evidence/task-29-publish-launch.log

  Scenario: exe 体积验证
    Tool: Bash
    Steps:
      1. publish 后 ls -lh
      2. 断言 ≤ 100 MB
    Evidence: .omo/evidence/task-29-exe-size.log

  Scenario (delegated to F3): 干净 VM 部署与托盘 + logoff/logon 跨周期验证
    Tool: F3 review agent (交互环境与 VM)
    Note: 本 task 仅验证产物可启动与体积；clean VM 部署与 logon 周期验证在 F3
  ```

  **Commit**: YES — `chore(agent): single-file self-contained publish profile and publish script`

---

---

## Final Verification Wave (MANDATORY — after ALL implementation tasks)

> 4 review agents 并行运行。**全部 APPROVE** 后呈现给用户，等待用户显式 "okay" 才能完成。
>
> **不要在用户给出明确批准前自动 mark F1-F4 为 checked。** 任何 REJECT 或用户反馈 → 修复 → 重跑 → 再次呈现 → 继续等待。

- [x] F1. **Plan Compliance Audit** — `oracle`

  读 `.omo/plans/maimai-home-assistant-full.md` 端到端。Must Have 列表逐条验证（读文件、curl 接口、跑命令）；Must NOT Have 列表反向搜索（找到一处即 REJECT 并附 file:line）。检查 `.omo/evidence/` 下证据齐全。比对交付物清单与实际产出。

  **输出**: `Must Have [N/N] | Must NOT Have [N/N] | Tasks [N/N] | VERDICT: APPROVE/REJECT`

- [x] F2. **Code Quality Review** — `unspecified-high`

  跑 `dotnet build`、`dotnet test`、`flutter test`、`flutter analyze`、`vitest run`、`tsc --noEmit`。审计所有改动文件：`as any`/`@ts-ignore`、空 catch、生产代码 `console.log`、注释掉的代码、未用 import。AI slop：冗长注释、过度抽象、`data`/`result`/`temp` 等无意义命名。

  **输出**: `Build [PASS/FAIL] | Lint [PASS/FAIL] | Tests [N pass/N fail] | Files [N clean/N issues] | VERDICT`

- [x] F3. **Real Manual QA** — `unspecified-high`（+ `playwright` skill）

  从干净状态启动。逐 task 跑 QA 场景，截图/录屏到 `.omo/evidence/final-qa/`。跨 task 集成（音量调节后 WebSocket 事件到达；上传后 listing 反映；切换设备后两端同步）。边界：空白名单、空目录、超大文件、网络断开重连、Windows 设置同步切换设备。

  **输出**: `Scenarios [N/N pass] | Integration [N/N] | Edge Cases [N tested] | VERDICT`

- [x] F4. **Scope Fidelity Check** — `deep`

  逐 task 比对 "What to do" 与 git diff：1:1 实现，无遗漏无超范围。"Must NOT do" 反向搜索。跨 task 污染检测（task N 改了 task M 的领域文件）。识别 unaccounted 文件。

  **输出**: `Tasks [N/N compliant] | Contamination [CLEAN/N issues] | Unaccounted [CLEAN/N files] | VERDICT`

---

## Commit Strategy

> 每个 task 完成后立即 commit。commit 粒度 ≈ task 粒度（避免大 commit）。Wave 跨越时检查工作树干净。

- **Convention**: `type(scope): desc`，scope 用 `agent` / `mobile` / `pc-web` / `docs` / `infra`
- **Pre-commit**: `dotnet build` (Agent 改) / `flutter analyze` (Mobile 改) / `pnpm tsc --noEmit && pnpm vitest run` (PC Web 改)
- **Examples**:
  - `feat(agent): add audio STA dispatcher and IAudioService abstraction`
  - `feat(agent): expose /api/audio/state /volume /mute endpoints`
  - `feat(mobile): extract AgentClient and migrate connection page to Riverpod`
  - `feat(pc-web): scaffold Vite + React 18 + TanStack Query + Zustand`
  - `feat(agent): pairing code service with atomic exchange and IP binding`
  - `chore(agent): single-file self-contained publish profile`

---

## Success Criteria

### Verification Commands

```powershell
# Agent build & test
# Agent build & test（如本机 `dotnet` 不在 PATH，请设环境变量 DOTNET_EXE 指定完整路径后再调用）
$dotnet = if ($env:DOTNET_EXE) { $env:DOTNET_EXE } else { 'dotnet' }
& $dotnet build services\windows-agent\MaimaiHomeAgent.sln -nologo
& $dotnet test services\windows-agent\MaimaiHomeAgent.sln -nologo

# Mobile
flutter analyze apps/mobile
flutter test apps/mobile

# PC Web
pnpm --dir apps/pc-web tsc --noEmit
pnpm --dir apps/pc-web vitest run

# Smoke：启动 Agent 后
Invoke-WebRequest -Uri "http://127.0.0.1:8765/api/status" -UseBasicParsing
Invoke-WebRequest -Uri "http://127.0.0.1:8765/api/audio/state" -UseBasicParsing -Headers @{ Authorization = "Bearer $token" }
```

### Final Checklist

- [ ] 所有 Must Have 项均已实现且可被 agent 验证
- [ ] 所有 Must NOT Have 项均不存在于代码库（grep 反向搜索通过）
- [ ] Wave 1-5 的 29 个 task 全部 checked
- [ ] F1-F4 4 个 review agent 全部 APPROVE
- [ ] 用户对 F1-F4 结果给出显式 okay
- [ ] 单文件 exe 已产出（T29 生成 + F3 在干净 VM 跨周期验证）
- [ ] Plan 内明确认领的 task-级增量 wiki 已交付：T13 `dev-docs/wiki/development/agent-events.md` + T29 `dev-docs/wiki/development/windows-agent-publish.md`
- [ ] 阶段 2-9 综合 wiki 与移动端各页 wiki 的同步 **不在本 plan 范围内**，由 docs-gate 在各实现 task 提交后逐任务判定是否需补充；若 docs-gate 认为需补，在实现交付后作为独立 commit 补入

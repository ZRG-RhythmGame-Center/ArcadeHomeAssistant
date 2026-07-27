---
title: Agent 实时事件总线
document_type: reference
status: current
audience:
  - 维护者
owners:
  - Maimai Dev
created: 2026-05-31
updated: 2026-07-27
source_of_truth:
  - code:services/windows-agent/src/MaimaiHomeAgent/Realtime
---

# Agent 实时事件总线（/api/events）

> 适用范围：Windows Agent 通过 WebSocket 端点 `/api/events` 向移动 App 与 PC 网页推送的所有实时事件。

> 关键文件：
>
> - 服务端：`services/windows-agent/src/MaimaiHomeAgent/Realtime/`
>   - `EventHub.cs` — WebSocket 会话登记与广播 fan-out
>   - `EventEnvelope.cs` — 通信信封（`type` / `payload` / `timestamp`）
>   - `EventTypes.cs` — 9 个事件类型字符串常量
>   - `EventPublisher.cs` — 领域语义封装，所有生产者只与本类交互
>   - `FileEventDto.cs` — 文件事件 payload 形状
> - 远程关机 payload：`services/windows-agent/src/MaimaiHomeAgent/Power/RemoteShutdownModels.cs`

> 总原则：**所有生产者只调用 `EventPublisher`，不要直接 `EventHub.BroadcastAsync`**。这样事件类型、JSON 选项、时间戳来源都集中在一处。

## 信封结构

每一条 WebSocket 文本帧都是一个独立 JSON 对象（lower‑camel 字段）：

```json
{
  "type": "audio.state",
  "payload": { /* 不同事件不同形状，详见下表 */ },
  "timestamp": "2026-05-31T08:30:15.123Z"
}
```

- `type`：事件类型字符串，必须取自 `EventTypes` 中的常量（当前 18 个）。
- `payload`：随事件类型变化；客户端按 `type` 分支解析。
- `timestamp`：UTC ISO‑8601，由 `EventPublisher` 在广播时打。

## 事件类型与触发时机

| Type 常量 | 字符串值 | 触发位置 | Payload 形状 |
| --- | --- | --- | --- |
| `EventTypes.AudioState` | `audio.state` | `POST /api/audio/volume`、`POST /api/audio/mute`、`POST /api/audio/default-device` 成功后 | `AudioStateDto`：`{ masterVolume: number, muted: bool, defaultDeviceId: Guid? }` |
| `EventTypes.AudioDeviceChanged` | `audio.device.changed` | `DeviceChangeNotifier` 收到 Core Audio 设备增删改回调（默认设备变化、设备插拔、状态变化） | `DeviceResponse[]`：`{ id: string, name: string, isDefault: bool, state: string }[]` |
| `EventTypes.FileCreated` | `file.created` | T11 文件 watcher 检测到根目录下新文件/新目录创建（上锁后） | `FileEventDto`：`{ rootId: string, path: string, newPath: null }` |
| `EventTypes.FileDeleted` | `file.deleted` | T11 文件 watcher 检测到删除 | `FileEventDto`：`{ rootId, path, newPath: null }` |
| `EventTypes.FileRenamed` | `file.renamed` | T11 同目录内重命名 | `{ rootId: string, fromPath: string, toPath: string }`（fromPath = 旧名，toPath = 新名） |
| `EventTypes.FileMoved` | `file.moved` | T11 跨目录移动（仍在同一根内） | `{ rootId: string, fromPath: string, toPath: string }`（fromPath = 源路径，toPath = 目标路径） |
| `EventTypes.DeviceUnavailable` | `device.unavailable` | 设备调用抛出 `AudioOperationException`，同时认定该设备已不可达 | `{ deviceId: string }` |
| `EventTypes.PowerShutdownExecuting` | `power.shutdown.executing` | `POST /api/power/shutdown` 令牌校验通过并调用 `IRemoteShutdownExecutor.ExecuteShutdownAsync()` 前 | `RemoteShutdownEventDto`：`{ state: "executing", executedAt: string, error: null }` |
| `EventTypes.PowerShutdownFailed` | `power.shutdown.failed` | `shutdown.exe` 返回失败、权限不足、非预期异常等执行失败路径 | `RemoteShutdownEventDto`：`{ state: "failed", executedAt: string, error: string }` |
| `EventTypes.SettingsUpdated` | `settings.updated` | `IAgentSettingsService.UpdateAsync()` 配置写入成功后 | 无固定 payload（空对象） |
| `EventTypes.LauncherShown` | `launcher.shown` | `LauncherService.ShowAsync()` 成功展示启动器窗口后 | `{ shownAt: string }` |
| `EventTypes.LauncherHidden` | `launcher.hidden` | `LauncherService.HideAsync()` 隐藏窗口后 | `{ hiddenAt: string }` |
| `EventTypes.LauncherMinimized` | `launcher.minimized` | 启动项命令执行成功后窗口最小化时 | `{ id: string, name: string, minimizedAt: string }` |
| `EventTypes.LauncherItemStarted` | `launcher.item.started` | `StartItemAsync()` 开始执行启动命令时（命令执行前） | `{ id: string, name: string, startedAt: string }` |
| `EventTypes.LauncherItemFailed` | `launcher.item.failed` | 启动命令返回非 0 退出码或抛出异常 | `{ id: string, name: string, error: string, failedAt: string }` |
| `EventTypes.LauncherItemStopStarted` | `launcher.item.stop.started` | `StopActiveItemAsync()` 开始执行关闭命令时 | `{ id: string, name: string, startedAt: string }` |
| `EventTypes.LauncherItemStopCompleted` | `launcher.item.stop.completed` | 关闭命令成功完成（退出码 0） | `{ id: string, name: string, completedAt: string }` |
| `EventTypes.LauncherItemStopFailed` | `launcher.item.stop.failed` | 关闭命令失败、超时（10 秒）或抛出异常 | `{ id?: string, name?: string, error: string, failedAt: string }` |

## 客户端集成约定

- 连接 URL：`ws://<agent-host>:8765/api/events`
- 心跳：服务端 `HeartbeatService` 周期性 ping；客户端只需对 ping 帧回 pong 即可（自动由内置 WebSocket 库完成）。
- 重连：客户端断线后应指数退避重连，并在重连成功后主动刷新自身关注的查询，因为本通道不做事件回放。PC Web 当前刷新 `['audio']`、`['files']`、`['power']`，Android 各 ViewModel 在重连回调或事件分发后刷新对应页面状态。
- 事件去重：`type` 不携带版本号；客户端拿到 `audio.state` 后直接覆盖本地状态即可，无需比较时间戳。
- 远程关机事件只用于提示客户端刷新状态；客户端仍应通过 `GET /api/power/shutdown` 对账当前执行中或失败状态。

## 不做的事（明确范围）

- **不**做事件持久化；离线期间发生的事件全部丢失。
- **不**做事件回放（replay）。需要历史的客户端必须自己按需 `GET`。
- **不**为 payload 建立跨版本 schema 适配层。改 schema 时同步升级所有客户端。

## 已落地客户端

- PC Web：`apps/pc-web/src/hooks/useEventStream.ts` 订阅 `/api/events`，按 `audio.*`、`file.*`、`power.shutdown.*` 分流并 invalidate TanStack Query 缓存。
- Android：`apps/mobile-android/app/src/main/kotlin/com/maimai/home/data/EventStream.kt` 负责 WebSocket 连接与重连；`FilesViewModel` 和 `PowerViewModel` 分别消费文件事件和远程关机事件。
- 当前 Agent 仍是 LAN-only：`/api/events` 不要求鉴权，也不读取查询参数作为身份标识。

---

## 修订记录

| 时间 | 作者 | 变更说明 |
|------|------|----------|
| 2026-06-12 | Maimai Dev | 补充 `settings.updated` 和 8 个 `launcher.*` 事件类型；事件总数更新为 18。 |
| 2026-06-03 10:05 | Maimai Dev | 移除 `/api/events` 查询参数透传说明，事件会话只保留 WebSocket 连接和广播职责。 |
| 2026-06-03 09:50 | Maimai Dev | 远程关机事件收敛为立即执行和失败两类，事件总数改为 9，payload 改为 `executedAt` 形状，移除排程/撤销事件说明。 |
| 2026-06-02 20:38 | Maimai Dev | 新增远程关机事件类型，更新客户端重连和已落地事件消费说明，移除旧 Wave 待办描述并归档。 |

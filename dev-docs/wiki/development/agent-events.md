# Agent 实时事件总线（/api/events）

> 适用范围：Windows Agent 通过 WebSocket 端点 `/api/events` 向移动 App 与 PC 网页推送的所有实时事件。
>
> 关键文件：
>
> - 服务端：`services/windows-agent/src/MaimaiHomeAgent/Realtime/`
>   - `EventHub.cs` — WebSocket 会话登记与广播 fan-out
>   - `EventEnvelope.cs` — 通信信封（`type` / `payload` / `timestamp`）
>   - `EventTypes.cs` — 7 个事件类型字符串常量
>   - `EventPublisher.cs` — 领域语义封装，所有生产者只与本类交互
>   - `FileEventDto.cs` — 文件事件 payload 形状
>
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

- `type`：事件类型字符串，必须取自 `EventTypes` 中的 7 个常量。
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

## 客户端集成约定

- 连接 URL：`ws://<agent-host>:8765/api/events`（`?token=` 查询参数为兼容性保留的 no-op，客户端无需传送）
- 心跳：服务端 `HeartbeatService` 周期性 ping；客户端只需对 ping 帧回 pong 即可（自动由内置 WebSocket 库完成）。
- 重连：客户端断线后应指数退避重连，并在重连成功后**主动 `GET /api/audio/state` 与 `GET /api/audio/devices`** 做一次对账，因为本通道不做事件回放。
- 事件去重：`type` 不携带版本号；客户端拿到 `audio.state` 后直接覆盖本地状态即可，无需比较时间戳。

## 不做的事（明确范围）

- **不**做事件持久化；离线期间发生的事件全部丢失。
- **不**做事件回放（replay）。需要历史的客户端必须自己按需 `GET`。
- **不**对 payload 做向后兼容版本化。改 schema 时同步升级所有客户端。

## 后续 Wave

- T19 / T20 将分别在 Mobile / PC Web 实现 WebSocket 客户端，按本表定义解析 `type`。
- 鉴权（`token` query 参数验证）在 Wave 4 一并完成；当前实现仅记录到会话用于诊断。

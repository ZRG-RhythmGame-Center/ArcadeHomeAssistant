---
title: Android 功能缺口调研
document_type: research
status: current
initiative: 移动端完整管理能力
audience:
  - 维护者
owners:
  - Maimai Dev
created: 2026-07-16
updated: 2026-07-27
depends_on:
  - ../2-产品需求.md
related:
  - ../4-技术设计.md
---

# Android 功能缺口调研

## 调研范围

以 Windows Agent、Android app 和 PC Web 当前源码为准，盘点手机端已实现能力、不可用链路和 Agent 已有但 Android 尚未接入的能力。Flutter 归档实现不作为交付基线。

本项目只在可信局域网内使用。按照项目准则，后续功能不设计登录、密码、控制令牌、API Key 或配对流程；现有相关实现作为技术债移除。

## 当前能力

| 能力 | Android 现状 | 结论 |
|---|---|---|
| 设备连接 | 支持 mDNS 扫描、手动 IP:port、连接测试和单地址持久化 | 基本可用 |
| 音频控制 | 支持音量、静音、默认输出设备切换和实时刷新 | 基本完整 |
| 文件浏览 | 支持根目录切换、目录导航、列表与只读门控 | 基本可用 |
| 文件操作 | 有上传、下载、删除、重命名和移动 UI | 上传实际不可用，覆盖与分页不完整 |
| 远程关机 | 有状态、令牌输入、二次确认和立即关机 | 与免凭据准则冲突 |
| 实时事件 | 支持 `audio.*`、`file.*`、`power.shutdown.*` | 心跳协议不闭合，连接会周期性断开 |
| 启动器 | 无页面、模型、API 调用和事件处理 | 完全缺失 |
| Agent 设置 | 无查看或编辑入口 | 完全缺失 |

## P0：先修可用性与产品准则冲突

### 1. 修复文件上传目标路径

Agent 的上传 `path` 表示包含文件名的完整目标相对路径；Android 当前只传当前目录。根目录上传会返回 `path_required`，子目录上传通常返回 `path_is_directory`。

改造要求：Android 从 `ContentResolver` 取得文件名并拼出目标路径；补充根目录、子目录和特殊字符文件名测试。

### 2. 闭合 WebSocket 心跳

Agent 每 30 秒发送 `{"type":"ping"}`，并要求客户端回包维持活跃；Android 将该消息按完整 `EventEnvelope` 解码失败后丢弃，不发送响应。连接约 60 秒后会被服务端关闭并重连，实时状态存在空窗和额外开销。

改造要求：统一心跳协议。客户端收到应用层 ping 后立即回复，或由服务端改用标准 WebSocket ping/pong；补充保持连接超过两个心跳周期的集成测试。

### 3. 移除手机端密码和令牌流程

当前电源页要求输入 `RemoteShutdown.ControlToken`，Agent 的关机、设置和启动器管理接口仍有历史鉴权。该实现与 LAN 免认证准则冲突，也会阻塞管理面板接入。

改造要求：

- Agent 关机能力只由 `RemoteShutdown.Enabled` 决定，`POST /api/power/shutdown` 不检查 Authorization。
- `/api/settings` 与 `/api/launcher/*` 不检查管理员密码。
- Android 删除控制令牌字段及相关状态、错误提示和测试，保留明确的关机二次确认。
- 删除 Android 管理员登录规划，连接 Agent 后直接展示管理能力。

### 4. 完善文件结果处理

- 列表截断时 Android 只展示“前 N 项”提示，没有继续加载或分页操作。
- 上传、重命名和移动固定 `overwrite=false`，遇到同名冲突后没有覆盖确认或改名入口。
- 下载写入 app 专属外部目录，只回显绝对路径；用户难以从系统“下载”中直接找到或选择保存位置。

改造要求：增加“加载更多”或自动分页、409 后覆盖确认、使用 Storage Access Framework 或 MediaStore 选择可见保存位置。

## P1：补齐核心远程管理

### 1. 启动器控制

Android 应接入 Agent 已有的启动器状态、显示、启动和关闭能力：

- 展示启动器是否可见、当前活动项、运行状态和最近错误。
- 远程显示启动器。
- 从已启用启动项中选择并启动。
- 调用配置的 `stopCommandLine` 关闭当前项，不能直接杀进程。
- 消费 `launcher.*` 事件并在 WebSocket 重连后全量刷新。

### 2. Agent 设置管理

连接后直接提供设置入口，接入 `GET/PUT /api/settings`：

- Windows 开机自启。
- 启动器自动显示、延迟、画布、壁纸和全局按键。
- 启动项新增、编辑、删除、启用和排序。
- 文件根目录新增、编辑、删除和只读设置。
- 远程关机启用开关，不提供控制令牌或管理员密码字段。

### 3. 补齐能力模型和事件

Android `Capabilities` 缺少 `settingsManagement` 和 `launcher`，因此无法按 Agent 声明决定入口是否展示。需补齐字段，并处理 `settings.updated`、全部 `launcher.*` 事件及重连刷新。

## P2：提升日常使用效率

### 1. 多设备管理

当前只持久化一个地址，没有最近设备、收藏、重命名、删除或快速切换。多台家用机时需要反复扫描或输入地址。

建议保存已验证设备列表，标记当前设备，并支持一键切换和移除；不加入配对或凭据概念。

### 2. 连接与能力状态

设备页只显示现有六个 capability，且业务页各自创建 WebSocket。建议补齐设置/启动器能力展示，并将连接与事件流收敛为应用级单一状态，避免 Tab 切换造成重复连接和状态不一致。

### 3. 文件管理增强

Agent 当前没有新建目录、目录删除和跨根目录移动 API，因此 Android 也无法提供这些常见操作。若确有日常需求，应先扩展 Agent 契约，再补手机端；不在客户端伪造能力。

## 端点与事件缺口总表

基于 2026-07-28 源码盘点：Agent 暴露 23 个 REST 端点、18 个 WebSocket 事件；Android 已接入 16 端点、10 事件。以下为尚未接入的清单。

### 未接入的 REST 端点

| Agent 端点 | 用途 | Android 现状 | 处理阶段 |
|---|---|---|---|
| `POST /api/admin/session` | 历史管理员密码验证 | 未接入 | P0-3 移除（Agent 端 + 规划） |
| `GET /api/settings` | 读取全局设置 | 未接入 | P1-2 |
| `PUT /api/settings` | 保存全局设置 | 未接入 | P1-2 |
| `GET /api/launcher/status` | 启动器状态 | 未接入 | P1-1 |
| `POST /api/launcher/show` | 显示启动器 | 未接入 | P1-1 |
| `POST /api/launcher/start` | 启动指定项 | 未接入 | P1-1 |
| `POST /api/launcher/stop` | 关闭当前项 | 未接入 | P1-1 |
| `GET /api/config` | 完整配置上下文 | 未接入 | P2 按需 |
| `PUT /api/config/file-roots` | 文件根目录热更新 | 未接入 | P1-2 与 PUT /api/settings 合并实现 |

### 未监听的 WebSocket 事件

| 事件类型 | Android 现状 | 处理阶段 |
|---|---|---|
| `settings.updated` | 未处理 | P1-3 |
| `launcher.shown` | 未处理 | P1-1 |
| `launcher.hidden` | 未处理 | P1-1 |
| `launcher.minimized` | 未处理 | P1-1 |
| `launcher.item.started` | 未处理 | P1-1 |
| `launcher.item.failed` | 未处理 | P1-1 |
| `launcher.item.stop.started` | 未处理 | P1-1 |
| `launcher.item.stop.completed` | 未处理 | P1-1 |
| `launcher.item.stop.failed` | 未处理 | P1-1 |
| 应用层 `ping`（非 EventEnvelope） | 解码失败丢弃，约 60 秒被服务端断开 | P0-2 |

### Capabilities 字段缺口

Agent `/api/status` 返回 8 项 capability，Android 模型仅覆盖 6 项，缺失：

- `settingsManagement`
- `launcher`

需补齐字段并据此决定管理 Tab 是否展示入口。旧 Agent 返回缺字段时按 `false` 兼容。

## 推荐交付顺序

1. P0-1 至 P0-3：修复上传和心跳，完成 Agent/Android 免凭据改造（含 Agent 侧删除 `/api/admin/session` 路由与 `Admin` 配置块）。
2. P0-4：补齐文件分页、覆盖和用户可见下载。
3. P1-1：先交付只读启动器状态，再接显示、启动、关闭操作。
4. P1-2 与 P1-3：交付无登录的设置管理、完整 capabilities 和实时同步。
5. P2：根据实际多设备和文件工作流反馈排期。

## 验收重点

- 连接同一 LAN 的 Agent 后，不出现密码、令牌、Key 或登录输入。
- 根目录和子目录上传均成功；同名文件能由用户明确选择覆盖或取消。
- WebSocket 连续运行至少 3 分钟不因心跳超时重连。
- 手机端可显示、启动和关闭启动器项目，并能在 PC 端状态变化后自动同步。
- 手机端可读取和保存全部目标设置，服务端校验错误有明确反馈。
- 远程关机仍需二次确认，但不需要任何凭据。

## 未纳入本轮的内容

- 公网访问、账号体系、多用户权限、证书和加密传输。
- iOS 客户端。
- Flutter 归档实现维护。
- Agent 尚无接口支持的新建目录、目录删除和跨根目录移动的直接实现。

## 修订记录

| 时间 | 作者 | 变更说明 |
|---|---|---|
| 2026-07-28 | Maimai Dev | 基于源码盘点补充端点/事件/Capabilities 缺口总表，明确 Agent 侧需移除 `/api/admin/session`。 |
| 2026-07-16 11:35 | Maimai Dev | 基于当前源码完成 Android 功能缺口调研，并按 LAN 免凭据准则重新排序交付优先级。 |

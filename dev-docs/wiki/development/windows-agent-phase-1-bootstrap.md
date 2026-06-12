# Windows Agent 阶段 1 启动骨架说明

> 创建日期: 2026-05-28 15:27
> 最后更新: 2026-06-12 22:28
> 作者: Adsicmes
> 状态: 草稿

## 目的

记录 Windows Agent 已落地的启动骨架、LAN-only 安全边界、远程关机控制令牌、运行方式、状态接口、日志与开发注意事项。

## 当前代码状态

对应代码位置：

- [Program.cs](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/services/windows-agent/src/MaimaiHomeAgent/Program.cs)
- [MaimaiHomeAgent.csproj](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/services/windows-agent/src/MaimaiHomeAgent/MaimaiHomeAgent.csproj)
- [appsettings.json](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/services/windows-agent/src/MaimaiHomeAgent/appsettings.json)
- [launchSettings.json](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/services/windows-agent/src/MaimaiHomeAgent/Properties/launchSettings.json)
- [MaimaiHomeAgent.http](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/services/windows-agent/src/MaimaiHomeAgent/MaimaiHomeAgent.http)

阶段 1 已实现：

- ASP.NET Core Minimal API 启动骨架
- `GET /api/status` 状态接口
- Serilog 控制台 + 文件日志
- 默认监听 `http://0.0.0.0:8765`
- 本地 `.http` 调试请求模板
- `Makaretu.Dns.Multicast` mDNS / DNS-SD 服务广播
- `IFileRootService` / `FileRootService`：从 `appsettings.json` 的 `FileRoots` 段加载根目录配置，支持热重载
- `PathGuard.ResolveSafe`：安全路径解析，防止路径穿越、绝对路径、符号链接逃逸
- `PathSafetyError` 枚举：`InvalidChar`、`Absolute`、`OutsideRoot`、`SymlinkEscape`、`ReparsePointInPath`
- `PathGuardResult`：类型化结果，调用方映射到 HTTP 403/400，不使用异常驱动控制流
- `Power/` 远程关机模块：
  - `RemoteShutdownOptions`：绑定 `appsettings.json` 的 `RemoteShutdown` 段
  - `IRemoteShutdownExecutor` / `WindowsRemoteShutdownExecutor`：封装 `shutdown.exe /s /t 0`，测试中可替换执行器避免真实关机
  - `IRemoteShutdownService` / `RemoteShutdownService`：维护立即执行、执行中、失败状态和实时事件发布；状态 DTO 只包含 `available`、`state`、`error`
  - `PowerEndpoints.MapPowerEndpoints()`：映射远程关机状态读取和立即执行接口
- 当前安全边界：
  - 普通 `/api/*` 接口仍是 LAN-only 匿名访问；`Program.cs` 没有全局认证 middleware
  - `/api/events` 不读取查询参数作为身份标识
  - 远程关机单独要求 `Authorization: Bearer <RemoteShutdown.ControlToken>`，且使用固定时间比较校验令牌
- 本机桌面 UI：
  - 设置窗口和应用启动器使用 Avalonia UI。
  - `AvaloniaUiThread` 在独立 STA 线程初始化 Avalonia Dispatcher。
  - 托盘消息循环使用原生 `Win32MessagePump`，不再依赖 WinForms `Application.Run()`。
  - 项目不再启用 `UseWindowsForms`。

## 当前公开接口

### `GET /api/status`

用途：返回当前 Agent 基础状态，用于移动端和 PC Web 做连通性探测。

当前返回字段：

- `machineName`
- `version`
- `startedAt`
- `uptimeSeconds`
- `capabilities`
  - `audioVolume`
  - `audioMute`
  - `audioDeviceSwitch`
  - `fileManagement`
  - `discoveryBroadcast`
  - `remoteShutdown`
  - `settingsManagement`
  - `launcher`

当前 `capabilities` 中：

- `audioVolume`、`audioMute`、`audioDeviceSwitch`、`fileManagement`、`discoveryBroadcast` 均为 `true`
- `remoteShutdown` 由 `IRemoteShutdownService.IsAvailable` 动态计算：仅当 `RemoteShutdown.Enabled = true`、`RemoteShutdown.ControlToken` 非空且 `IRemoteShutdownExecutor.IsSupported = true` 时为 `true`
- `settingsManagement` 固定为 `true`（当 Agent 注册了统一设置接口时）
- `launcher` 固定为 `true`（当 Agent 注册了启动选择器服务时）

### 安全边界说明

当前源码没有 `Security/` 目录、`AuthMiddleware`、配对码端点或 token 管理端点。开发者不要假设普通音频、文件或状态 API 已被 Bearer token 保护。

远程关机是当前唯一带独立控制令牌的 HTTP 能力，令牌只从请求头读取：

```http
Authorization: Bearer <RemoteShutdown.ControlToken>
```

### 远程关机接口

| 端点 | 说明 | 认证要求 |
|---|---|---|
| `GET /api/power/shutdown` | 返回远程关机能力、执行中或失败状态 | 无，仅读状态 |
| `POST /api/power/shutdown` | 令牌校验和 `{ "confirm": true }` 通过后立即调用系统关机 | `Authorization: Bearer <RemoteShutdown.ControlToken>` |

状态响应：

```json
{
  "available": true,
  "state": "idle",
  "error": null
}
```

`state` 取值为 `idle`、`executing` 或 `failed`。执行时间只出现在 `power.shutdown.executing` / `power.shutdown.failed` 事件 payload 中，不放入状态响应。

错误约定：

- 400：缺少 `confirm: true`
- 401：控制令牌缺失或不匹配
- 409：远程关机正在执行
- 503：远程关机不可用，例如配置未启用、令牌为空、非 Windows 平台或执行器不支持
- 502：系统关机命令执行失败

### 管理员鉴权

统一设置接口和启动选择器管理接口均要求管理员密码，通过请求头传递：

```http
Authorization: Bearer <Admin.Password>
```

密码在 `appsettings.json` 的 `Admin:Password` 字段配置，默认值为 `seganmsl`。鉴权失败返回 `401`，响应体包含明确错误码。

### 统一设置接口

| 端点 | 说明 | 认证要求 |
|---|---|---|
| `GET /api/settings` | 读取完整配置快照（`AgentSettingsSnapshot`） | 管理员密码 |
| `PUT /api/settings` | 保存配置（`AgentSettingsUpdateRequest`），成功返回新快照 | 管理员密码 |

`AgentSettingsSnapshot` 包含以下字段：

- `adminPasswordConfigured`：管理员密码是否已配置（布尔值，不返回密码明文）
- `autoStartEnabled`：开机自启状态
- `launcher`：启动选择器配置（见 `LauncherSettingsDto`）
- `fileRoots`：文件根目录列表
- `remoteShutdown`：远程关机配置

`LauncherSettingsDto` 字段：`showOnAgentStart`、`canvasWidth`、`canvasHeight`、`navigateLeftKey`、`navigateRightKey`、`confirmKey`、`stopKey`、`items`。

`LauncherItemSettingsDto` 字段：`id`、`name`、`title`、`note`、`iconPath`、`commandLine`、`workingDirectory`、`stopCommandLine`、`stopWorkingDirectory`、`key`、`order`、`enabled`。

注意：`key` 是历史兼容字段，当前本机 Avalonia 设置窗口不再提供启动项独立按键编辑项，服务端也不再要求启动项按键必填或唯一。启动器交互以全局 `navigateLeftKey`、`navigateRightKey`、`confirmKey`、`stopKey` 为准。

`stopKey` 默认为 `F11`，是全局关闭快捷键，由托盘 Win32 消息泵注册。按下后调用当前启动项的关闭命令；关闭完成后启动器重新显示。注意：`F12` 为 Windows 调试器保留键，配置校验会拒绝该值。启动器窗口中的左移、右移和确认键只在启动器窗口激活时生效。

注意：`title` 是历史兼容字段，当前本机 Avalonia 设置窗口不再提供启动项标题编辑项，服务端也不再要求标题必填。启动器卡片展示 `name`。

校验失败返回 `400`/`409`，响应体包含错误码列表。空 `adminPassword` 不会清空现有密码。

### 启动选择器管理接口

| 端点 | 说明 | 认证要求 |
|---|---|---|
| `GET /api/launcher/status` | 读取启动选择器窗口和当前运行启动项状态 | 无 |
| `POST /api/launcher/show` | 重新显示启动选择器 | 管理员密码 |
| `POST /api/launcher/start` | 按启动项 ID 启动指定启动项（`{ "itemId": "..." }`） | 管理员密码 |
| `POST /api/launcher/stop` | 调用当前运行启动项的关闭命令 | 管理员密码 |

`LauncherStatusDto` 字段：`isVisible`、`hasActiveItem`、`activeItemId`、`activeItemName`、`state`、`lastError`。

`state` 取值：`idle`、`starting`、`running`、`stopping`。

## 配置与运行约定

### 监听地址

[appsettings.json](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/services/windows-agent/src/MaimaiHomeAgent/appsettings.json) 与 [launchSettings.json](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/services/windows-agent/src/MaimaiHomeAgent/Properties/launchSettings.json) 都显式设为：

```text
http://0.0.0.0:8765
```

含义：

- 本机可通过 `127.0.0.1:8765` 访问
- 局域网设备可通过宿主机 IP 访问
- 启动时可能看到 “Overriding address(es)” 日志，这是 launch profile 与 Kestrel 配置同时存在时的正常提示

### HTTPS

当前阶段故意不启用 HTTPS 重定向。

原因：

- 局域网 MVP 阶段先保证手机/浏览器直连成功
- 自签证书会增加调试和配对成本
- 后续若需要更严格的传输保护，再单独设计证书与配对方案

### RemoteShutdown

[appsettings.json](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/services/windows-agent/src/MaimaiHomeAgent/appsettings.json) 新增 `RemoteShutdown` 段：

```json
"RemoteShutdown": {
  "Enabled": false,
  "ControlToken": ""
}
```

字段说明：

- `Enabled`：总开关，默认 `false`，未启用时 `capabilities.remoteShutdown = false`
- `ControlToken`：远程关机控制令牌，默认空；为空时即使 `Enabled = true` 也不可用

注意：远程关机没有延迟参数。PC Web 与 Android 都只发送 `{ "confirm": true }`，服务端在令牌校验通过后立即执行关机。

## 日志方案

当前使用依赖：

- `Serilog.AspNetCore`
- `Serilog.Sinks.File`

日志行为：

- 控制台输出启动日志和请求日志
- 文件日志输出到：

```text
%LOCALAPPDATA%\maimai-home-assistant\logs\agent-YYYYMMDD.log
```

实现细节：

- [Program.cs](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/services/windows-agent/src/MaimaiHomeAgent/Program.cs) 启动时会把 `%LOCALAPPDATA%` 展开成真实路径，再覆盖 Serilog 文件 sink 的 `path`
- 这样可避免 Serilog 直接读取 Windows 风格环境变量时路径不解析的问题

## mDNS 广播方案

当前 Agent 启动时会同时广播一个 mDNS / DNS-SD 服务，供 Flutter 移动端后续扫描。

实现细节：

- 广播库：`Makaretu.Dns.Multicast`
- service type：`_maimai-home._tcp`
- port：`8765`
- instance name：默认使用 `Environment.MachineName`
- TXT records：
  - `name=<machine-name>`
  - `version=<agent-version>`
  - `path=/api/status`
  - `proto=http`

配置位置：

- [appsettings.json](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/services/windows-agent/src/MaimaiHomeAgent/appsettings.json) 的 `Discovery` 段

可配置字段：

- `Enabled`
- `ServiceType`
- `InstanceName`
- `Port`
- `StatusPath`
- `Protocol`
- `Version`

当前行为边界：

- 只做服务广播，不做自动配对
- 只暴露发现所需元信息，不在 TXT record 中放敏感信息
- 广播能力不影响现有 `/api/status` HTTP 链路

## 依赖说明

[MaimaiHomeAgent.csproj](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/services/windows-agent/src/MaimaiHomeAgent/MaimaiHomeAgent.csproj) 当前新增的关键依赖：

- `AudioSwitcher.AudioApi.CoreAudio`
- `Avalonia`
- `Avalonia.Desktop`
- `Avalonia.Themes.Fluent`
- `Makaretu.Dns.Multicast`
- `Serilog.AspNetCore`
- `Serilog.Sinks.File`

注意：

- `AudioSwitcher.AudioApi.CoreAudio` 当前声明的是较老的目标框架元数据
- 项目里通过 `NoWarn=NU1701` 保留该依赖，以便阶段 2 直接接入音频能力
- 这不是“已验证音频功能正常”的结论，只代表当前阶段构建通过；真正进入阶段 2 后仍需做实机验证

## 构建与验证工作流

当前已验证通过的命令：

```powershell
& "C:\Users\abbey\scoop\apps\dotnet9-sdk\9.0.314\dotnet.exe" build src\MaimaiHomeAgent\MaimaiHomeAgent.csproj -nologo
& "C:\Users\abbey\scoop\apps\dotnet9-sdk\9.0.314\dotnet.exe" test MaimaiHomeAgent.sln -nologo
```

手动接口验证：

```powershell
Invoke-WebRequest -Uri "http://127.0.0.1:8765/api/status" -UseBasicParsing
```

结果：

- build 成功
- test 成功
- `/api/status` 返回 200 和 JSON
- 启动日志出现 `mDNS service advertised`，说明广播初始化成功

### mDNS 调试提示

当前自动化环境已验证 Agent 启动时会打印类似日志：

```text
mDNS service advertised. Instance=FRZ-XIAOXIN ServiceType=_maimai-home._tcp Port=8765
```

这说明：

- 广播代码已执行
- 配置已被读取
- 广播生命周期已挂到 Host 启停流程上

但这还不等于“移动端一定能发现”，真机联调时仍需额外确认：

- Windows 防火墙未拦截进程网络访问
- 手机和 Windows 电脑在同一 Wi-Fi
- 路由器未禁用 multicast / IGMP
- Android 端关闭可能影响 `.local` 解析的 Private DNS 配置

## 测试现状

当前测试覆盖：

- 状态 payload 关键字段名校验
- `PathGuardTests.cs`：覆盖典型穿越攻击向量（`..`、绝对路径、符号链接、控制字符等）
- `Files/FileListingEndpointsTests.cs` / `FileMutationEndpointsTests.cs`：文件操作集成测试
- `Power/PowerEndpointsTests.cs`：远程关机状态读取、未授权拒绝、立即执行、确认缺失、不可用和执行失败
- `Realtime/EventPublisherTests.cs`：远程关机事件信封广播

最近一次 Avalonia 迁移验证：

```powershell
$env:DOTNET_ROLL_FORWARD='Major'; dotnet build services/windows-agent/src/MaimaiHomeAgent/MaimaiHomeAgent.csproj -p:BaseOutputPath="C:\Users\abbey\AppData\Local\Temp\opencode\maimai-test-bin\"
$env:DOTNET_ROLL_FORWARD='Major'; dotnet test services/windows-agent/tests/MaimaiHomeAgent.Tests/MaimaiHomeAgent.Tests.csproj -p:BaseOutputPath="C:\Users\abbey\AppData\Local\Temp\opencode\maimai-test-bin\"
```

结果：build 成功；测试通过 236 个，失败 0 个，跳过 0 个。






## 开发者注意事项

### dotnet 不在 PATH

当前环境里 `dotnet` 未加入 PATH，实际使用的是：

```text
C:\Users\abbey\scoop\apps\dotnet9-sdk\9.0.314\dotnet.exe
```

如果后续脚本、文档或 Rider 配置出现 `dotnet` 找不到，需要优先检查 PATH 或继续使用绝对路径。

### LSP 现状

当前自动化环境未安装 `csharp-ls`，因此无法用 LSP 做 C# 诊断；本次验证主要依赖：

- `dotnet build`
- `dotnet test`
- 手动请求接口

如果后续需要在自动化里补齐静态诊断，可安装：

```powershell
dotnet tool install -g csharp-ls
```

## 下一步

建议接下来优先做：

1. 为远程关机控制令牌提供更友好的本机配置入口，避免用户手工编辑 `appsettings.json`
2. 单文件打包与防火墙放行说明继续保持同步，尤其是 PC Web `/power` 静态路由

---

## 修订记录

| 时间 | 作者 | 变更说明 |
|------|------|----------|
| 2026-06-12 22:28 | Maimai Dev | 记录全局关闭快捷键 `stopKey`，默认 `F11`（`F12` 为 Windows 调试器保留键，配置校验会拒绝该值），触发后关闭当前启动项并重新显示启动器。 |
| 2026-06-12 19:21 | Maimai Dev | 记录启动项标题配置项已从 Avalonia 设置窗口移除，`title` 字段仅作为历史兼容字段保留，启动器卡片展示 `name`。 |
| 2026-06-12 19:15 | Maimai Dev | 记录启动项独立按键配置项已从 Avalonia 设置窗口移除，`key` 字段仅作为历史兼容字段保留。 |
| 2026-06-12 19:09 | Maimai Dev | 补充 Windows Agent 本机桌面 UI 迁移状态：设置窗口和启动器已迁移到 Avalonia，托盘消息循环改为原生 Win32，项目不再启用 WinForms；同步记录 Avalonia 依赖和验证结果。 |
| 2026-06-12 | Maimai Dev | 补充管理员鉴权、统一设置接口（`/api/settings`）、启动选择器管理接口（`/api/launcher/*`）说明；`capabilities` 增加 `settingsManagement` 和 `launcher` 字段。 |
| 2026-06-03 10:05 | Maimai Dev | 远程关机状态响应收敛为 `available`、`state`、`error`，移除旧状态字段和 `/api/events` 查询参数透传。 |
| 2026-06-03 09:50 | Maimai Dev | 远程关机改为控制令牌确认后立即执行，移除延迟配置、撤销接口、排程状态和对应测试描述。 |
| 2026-06-02 20:38 | Maimai Dev | 新增远程关机模块、配置、接口、安全边界和测试说明；移除已不存在的配对/TokenAdmin 接口与 Security 测试描述并归档。 |

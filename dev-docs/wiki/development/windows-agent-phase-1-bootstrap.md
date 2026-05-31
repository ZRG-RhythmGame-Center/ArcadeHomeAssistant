# Windows Agent 阶段 1 启动骨架说明

> 创建日期: 2026-05-28 15:27
> 最后更新: 2026-05-31
> 作者: Adsicmes
> 状态: 草稿

## 目的

记录 Windows Agent 已落地的启动骨架、认证与配对层、运行方式、状态接口、日志与开发注意事项。

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
- `Security/` 认证与配对层（Wave 4 / 阶段 8）：
  - `IPairingService` / `PairingService`：创建一次性配对码（绑定来源 IP，默认 TTL 120 秒）、换取长期 token（原子单次消费）、查询当前活跃码
  - `ITokenStore` / `JsonFileTokenStore`：token 持久化到 `%LOCALAPPDATA%\maimai-home-assistant\tokens.json`，支持校验、列表、撤销
  - `AuthMiddleware`：Bearer token（HTTP）/ `?token=` 查询参数（WebSocket）验证；白名单路径：`/api/status`、`/api/pairing/code`、`/api/pairing/active`、`/api/pairing/exchange`、非 `/api/` 前缀（静态资源 / SPA）
  - `PairingEndpoints`：`POST /api/pairing/code`（loopback-only，托盘 UI 调用）、`GET /api/pairing/active`（查询当前码）、`POST /api/pairing/exchange`（客户端换 token，在白名单上）
  - `TokenAdminEndpoints`：`GET /api/tokens`（列出已签发 token，不含 token 值）、`DELETE /api/tokens/{id}`（撤销，自删时响应 `selfDeleted:true`）

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

当前 `capabilities` 中：

- 所有能力均为 `true`：`audioVolume`、`audioMute`、`audioDeviceSwitch`、`fileManagement`、`discoveryBroadcast` 均已实现

### 认证相关接口

| 端点 | 说明 | 认证要求 |
|---|---|---|
| `POST /api/pairing/code` | 创建配对码（loopback-only） | 无（仅限 127.0.0.1/::1） |
| `GET /api/pairing/active` | 查询当前活跃配对码 | 无（白名单） |
| `POST /api/pairing/exchange` | 用配对码换取 token | 无（白名单） |
| `GET /api/tokens` | 列出已签发 token（不含 token 值） | Bearer token |
| `DELETE /api/tokens/{id}` | 撤销指定 token | Bearer token |

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
- `Security/PairingEndpointsTests.cs`：配对码创建（loopback 限制、参数校验）、换取 token（IP 绑定、单次消费、过期）
- `Security/AuthMiddlewareTests.cs`：Bearer token 验证、WebSocket `?token=` 验证、白名单路径放行、401 响应格式
- `Security/TokenAdminEndpointsTests.cs`：token 列表、撤销（含自删 `selfDeleted:true`）
- `Files/FileListingEndpointsTests.cs` / `FileMutationEndpointsTests.cs`：文件操作集成测试






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

1. 实现托盘 UI（阶段 9）：显示配对码、打开 PC Web、控制启停
2. 单文件打包与防火墙放行说明

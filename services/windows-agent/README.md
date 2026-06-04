# Windows Agent

Windows Agent 是运行在目标 Windows 电脑上的本地服务，负责暴露局域网 HTTP API、记录日志，并提供音频控制与文件管理功能。

## 当前已完成

- ASP.NET Core Minimal API 启动骨架
- `GET /api/status` 状态接口（含 `discoveryBroadcast`、`remoteShutdown` 能力标志）
- Serilog 控制台 + 文件日志
- 默认监听 `0.0.0.0:8765`
- `Makaretu.Dns.Multicast` mDNS / DNS-SD 服务广播
- `IFileRootService` / `FileRootService`：从 `appsettings.json` 的 `FileRoots` 段加载根目录配置，支持热重载
- `PathGuard.ResolveSafe`：安全路径解析，防止路径穿越、绝对路径、符号链接逃逸
- `PathSafetyError` 枚举：`InvalidChar`、`Absolute`、`OutsideRoot`、`SymlinkEscape`、`ReparsePointInPath`
- `PathGuardResult`：类型化结果，调用方映射到 HTTP 403/400，不使用异常驱动控制流
- 音频控制 HTTP 接口：`GET /api/audio/state`、`POST /api/audio/volume`、`POST /api/audio/mute`
- 音频设备接口：`GET /api/audio/devices`、`POST /api/audio/default-device`
- 远程关机接口：`GET /api/power/shutdown`、`POST /api/power/shutdown`
- `DeviceChangeNotifier`：监听 Windows Core Audio 设备变更事件，通过 EventHub WebSocket 广播 `audio.device.changed`
## 目录结构

```text
services/windows-agent/
├─ MaimaiHomeAgent.sln
├─ src/
│  └─ MaimaiHomeAgent/
└─ tests/
   └─ MaimaiHomeAgent.Tests/
```

## 开发环境要求

- Windows 10/11
- .NET 9 SDK
- Rider / Visual Studio Code / Visual Studio 任一 IDE

如果 `dotnet` 没有加入 PATH，可以直接使用本机 SDK 路径，例如：

```powershell
& "C:\Users\abbey\scoop\apps\dotnet9-sdk\9.0.314\dotnet.exe" --version
```

## 启动方式

### Rider

- 打开 [MaimaiHomeAgent.sln](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/services/windows-agent/MaimaiHomeAgent.sln)
- 选择 `http` 运行配置
- 直接运行项目

### 命令行

在 [services/windows-agent](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/services/windows-agent) 目录执行：

```powershell
& "C:\Users\abbey\scoop\apps\dotnet9-sdk\9.0.314\dotnet.exe" run --project src\MaimaiHomeAgent\MaimaiHomeAgent.csproj
```

启动后默认监听：

- `http://0.0.0.0:8765`

本机验证：

```powershell
Invoke-WebRequest -Uri "http://127.0.0.1:8765/api/status" -UseBasicParsing
```

## 状态接口

当前可用接口：

- `GET /api/status`
- `GET /api/file-roots`（需配置 `FileRoots` 段，当前返回空列表）
- `GET /api/audio/state`
- `POST /api/audio/volume`
- `POST /api/audio/mute`
- `GET /api/audio/devices`
- `POST /api/audio/default-device`
- `GET /api/power/shutdown`
- `POST /api/power/shutdown`

返回示例：

```json
{
  "machineName": "FRZ-XIAOXIN",
  "version": "1.0.0.0",
  "startedAt": "2026-05-28T06:49:45.7607778+00:00",
  "uptimeSeconds": 5,
  "baseUrl": "http://192.168.31.24:8765",
  "capabilities": {
    "audioVolume": true,
    "audioMute": true,
    "audioDeviceSwitch": true,
    "fileManagement": true,
    "discoveryBroadcast": true,
    "remoteShutdown": false
  }
}
```

`baseUrl` 字段由服务端根据入站请求动态生成，供移动端回传使用。`remoteShutdown` 默认是 `false`，只有 `appsettings.json` 的 `RemoteShutdown.Enabled = true`、`RemoteShutdown.ControlToken` 非空且当前平台支持关机执行器时才会变为 `true`。

## 远程关机

远程关机是危险操作，不复用普通 LAN API 的匿名访问能力。发起关机必须携带 `Authorization: Bearer <RemoteShutdown.ControlToken>`，请求体必须包含 `{ "confirm": true }`。

配置位置：

```json
"RemoteShutdown": {
  "Enabled": false,
  "ControlToken": ""
}
```

接口行为：

- `GET /api/power/shutdown`：读取当前能力、执行中或失败状态。
- `POST /api/power/shutdown`：令牌校验和二次确认通过后立即调用 `shutdown.exe /s /t 0`；正在执行时返回 409。
- 开始执行和失败会通过 `/api/events` 广播 `power.shutdown.executing` / `power.shutdown.failed` 事件。

状态响应固定为：

```json
{
  "available": true,
  "state": "idle",
  "error": null
}
```

`state` 取值为 `idle`、`executing` 或 `failed`；执行事件的时间只出现在 `/api/events` 的远程关机事件 payload 中。

## 日志位置

Serilog 日志默认写到：

```text
%LOCALAPPDATA%\maimai-home-assistant\logs\
```

例如：

```text
C:\Users\abbey\AppData\Local\maimai-home-assistant\logs\agent-20260528.log
```

## 防火墙放行

第一次启动时，Windows 可能会弹出防火墙提示。为了让手机或其他局域网设备访问：

- 勾选“专用网络”
- 允许 `8765/TCP`

如果没有弹窗，可用管理员 PowerShell 手动放行：

```powershell
New-NetFirewallRule -DisplayName "Maimai Home Agent" -Direction Inbound -LocalPort 8765 -Protocol TCP -Action Allow
```

## 局域网访问检查

1. 在电脑上运行：

   ```powershell
   ipconfig
   ```

2. 找到当前局域网 IPv4 地址，例如 `192.168.31.24`
3. 在手机浏览器访问：

   ```text
   http://192.168.31.24:8765/api/status
   ```

如果能返回 JSON，说明阶段 1 的局域网访问链路正常。

## 当前已知限制

- `AudioSwitcher.AudioApi.CoreAudio` 只声明了 .NET Framework target，因此项目里对 `NU1701` 做了显式豁免；当前已验证可构建，但真正音频功能接入后仍需做一次实机回归。
- 音频控制和设备切换 HTTP 接口已实现；`AudioSwitcher.AudioApi.CoreAudio` 只声明了 .NET Framework target，项目对 `NU1701` 做了显式豁免，已验证可构建，建议在实机上做一次音频功能回归。
- `FileRoots` 默认为空数组，需在 `appsettings.json` 或 `config.json` 中手动配置根目录后文件管理才可用。

## 下一步

按 [实现规划](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/dev-docs/features/2026-05-11-maimai-home-assistant/%E5%AE%9E%E7%8E%B0.md) 继续推进：

1. 实现文件管理 HTTP 接口（`/api/files*`）
2. 对音频功能做实机回归测试

## 构建与部署 PC Web

PC Web 是一个 Vite + React 单页应用，构建产物会直接输出到 Agent 的 `src/MaimaiHomeAgent/wwwroot/`，再由 Agent 通过 `UseStaticFiles()` + `MapFallbackToFile("index.html")` 提供。`/api/*` 优先匹配，SPA 回退只兜非 API 路由。

### 一次完整的构建步骤

在仓库根目录执行：

```powershell
# 1. 安装 PC Web 依赖（首次或依赖变更后才需要）
pnpm --dir apps/pc-web install

# 2. 构建 PC Web 到 Agent 的 wwwroot/
pnpm --dir apps/pc-web build

# 3. 构建 / 发布 Agent（此时 wwwroot/index.html 已存在）
& "C:\Users\abbey\scoop\apps\dotnet9-sdk\9.0.314\dotnet.exe" publish services\windows-agent\src\MaimaiHomeAgent\MaimaiHomeAgent.csproj -c Release
```

### 注意事项

- `pnpm --dir apps/pc-web build` 会先跑 `tsc --noEmit`，再跑 `vite build`。如果只想跳过类型检查、纯出包，使用 `pnpm --dir apps/pc-web build:agent`。
- `vite.config.ts` 的 `build.outDir` 通过 `fileURLToPath` 解析为绝对路径，指向 `services/windows-agent/src/MaimaiHomeAgent/wwwroot/`。`emptyOutDir: true` 会清空旧产物，但 `.gitkeep` 在每次 vite build 后会被一起清掉——它只是给 Git 占位用。
- Agent 的 csproj 里有一个 `WarnIfPcWebMissing` MSBuild target：检测到 `wwwroot/index.html` 不存在时只打印 `MSBuild warning`，**不会阻断构建**。换言之，单独构建 Agent（不构建 PC Web）依然成功，但访问 `/audio`、`/files`、`/power` 等 SPA 路由会返回 404。
- 不要在 `dotnet build` 内部链式调用 `pnpm`：跨工具链路径解析容易出错；保持"先 pnpm build，再 dotnet build/publish"两步走。
- `wwwroot/` 下的构建产物属于派生物，不应提交到 git；仓库的 `.gitignore` 应当忽略 `services/windows-agent/src/MaimaiHomeAgent/wwwroot/*`，只保留 `.gitkeep`。

## 发布

Agent 通过 single-file、self-contained 模式发布，最终产物是一个不依赖外部 .NET 运行时的 `MaimaiHomeAgent.exe`，方便在没有装 SDK 的目标机器上直接拷贝运行。

### 一键发布脚本

在仓库根目录或 `services/windows-agent/` 下都可以执行：

```powershell
services\windows-agent\publish.ps1
```

脚本依次完成：

1. `pnpm --dir apps/pc-web install --frozen-lockfile`（按 lockfile 安装 PC Web 依赖）
2. `pnpm --dir apps/pc-web build`（产物输出到 Agent 的 `wwwroot/`）
3. `dotnet publish src\MaimaiHomeAgent\MaimaiHomeAgent.csproj -c Release -r win-x64 --self-contained -p:PublishProfile=win-x64 ...`

其中 `PublishProfile=win-x64` 对应 [`src/MaimaiHomeAgent/Properties/PublishProfiles/win-x64.pubxml`](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/services/windows-agent/src/MaimaiHomeAgent/Properties/PublishProfiles/win-x64.pubxml)：

- `PublishSingleFile=true` + `IncludeNativeLibrariesForSelfExtract=true` + `EnableCompressionInSingleFile=true`：合一可执行文件、内嵌原生 DLL、压缩。
- `SelfContained=true` + `RuntimeIdentifier=win-x64`：自带 .NET 9 运行时，目标机器无需预装 SDK。
- `DebugType=embedded`：调试符号嵌入 exe，方便事后排查崩溃。
- `PublishTrimmed=false`：**不**裁剪。`AudioSwitcher`、`NAudio`、`H.NotifyIcon` 都没有官方验证 trimming 支持声明；强行开启会在运行时随机抛 `MissingMethodException`。等到这些依赖明确支持 trimming 之后再考虑打开。
- `PublishDir=bin\publish\win-x64\`：输出目录，相对 csproj。

如果 `dotnet` 不在 PATH 上，可以传 `-DotnetPath`：

```powershell
services\windows-agent\publish.ps1 -DotnetPath "C:\Users\abbey\scoop\apps\dotnet9-sdk\9.0.314\dotnet.exe"
```

### 输出位置

```text
services/windows-agent/src/MaimaiHomeAgent/bin/publish/win-x64/
├─ MaimaiHomeAgent.exe              # 单文件主程序，约 60 MB
├─ appsettings.json
├─ appsettings.Development.json
├─ web.config
├─ MaimaiHomeAgent.staticwebassets.endpoints.json
└─ wwwroot/                         # PC Web 静态资源（dotnet publish 自动复制）
```

### 启动验证

```powershell
& "services\windows-agent\src\MaimaiHomeAgent\bin\publish\win-x64\MaimaiHomeAgent.exe"
Invoke-WebRequest -Uri "http://127.0.0.1:8765/api/status" -UseBasicParsing
```

正常情况下 1~2 秒内即可返回 200 + JSON。

### 防火墙提示

首次运行 `MaimaiHomeAgent.exe` 时，Windows Defender 防火墙会弹出提示。务必勾选 **专用网络** 并允许 `8765/TCP`，否则手机或局域网内其他设备无法访问。如果错过弹窗，用管理员 PowerShell 手动放行：

```powershell
New-NetFirewallRule -DisplayName "Maimai Home Agent" -Direction Inbound -LocalPort 8765 -Protocol TCP -Action Allow
```

### 注意事项

- **必须先把 PC Web 构建到 `wwwroot/`**：脚本第 1、2 步会做这件事；如果跳过，`/`、`/audio`、`/files`、`/power` 等 SPA 路由会返回 404，但 `/api/*` 仍然正常。
- **`ContentRoot` 已固定到 exe 所在目录**：发布后 `appsettings.json` 紧挨着 exe，`Program.cs` 通过 `WebApplicationOptions.ContentRootPath = AppContext.BaseDirectory` 显式锁定路径，不论从哪个 CWD 启动都能正确加载配置。
- **single-file 下 Serilog 不能自动扫程序集**：`Program.cs` 通过 `ConfigurationReaderOptions(typeof(ConsoleLoggerConfigurationExtensions).Assembly, typeof(FileLoggerConfigurationExtensions).Assembly)` 显式声明 sink 程序集；如果再添加新 sink，需要在这里同步声明，否则会报 `No Serilog:Using configuration section is defined`。
- **不要 trim**：见上面 `PublishTrimmed=false` 的说明。

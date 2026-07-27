---
title: 发布 Windows Agent 单文件包
document_type: how-to
status: current
audience:
  - 维护者
owners:
  - Maimai Dev
created: 2026-05-31
updated: 2026-07-27
source_of_truth:
  - code:services/windows-agent/publish.ps1
  - code:services/windows-agent/src/MaimaiHomeAgent/Properties/PublishProfiles/win-x64.pubxml
---

# Windows Agent 发布说明（single-file self-contained）

## 目的

记录 Windows Agent 走 single-file、self-contained 模式打包成单个 `MaimaiHomeAgent.exe` 的标准流程：发布配置文件、一键脚本、目录结构、验证步骤、踩过的坑。后续维护者按本文档操作即可复现完整发布。

## 对应代码

- [services/windows-agent/publish.ps1](../../services/windows-agent/publish.ps1)
- [services/windows-agent/src/MaimaiHomeAgent/Properties/PublishProfiles/win-x64.pubxml](../../services/windows-agent/src/MaimaiHomeAgent/Properties/PublishProfiles/win-x64.pubxml)
- [services/windows-agent/src/MaimaiHomeAgent/MaimaiHomeAgent.csproj](../../services/windows-agent/src/MaimaiHomeAgent/MaimaiHomeAgent.csproj)
- [services/windows-agent/src/MaimaiHomeAgent/Program.cs](../../services/windows-agent/src/MaimaiHomeAgent/Program.cs)
- [services/windows-agent/README.md「发布」章节](../../services/windows-agent/README.md)

## 发布配置文件 win-x64.pubxml

发布配置位于 `src/MaimaiHomeAgent/Properties/PublishProfiles/win-x64.pubxml`，由 `dotnet publish -p:PublishProfile=win-x64` 自动加载。

```xml
<?xml version="1.0" encoding="utf-8"?>
<Project>
  <PropertyGroup>
    <Configuration>Release</Configuration>
    <Platform>Any CPU</Platform>
    <PublishDir>bin\publish\win-x64\</PublishDir>
    <RuntimeIdentifier>win-x64</RuntimeIdentifier>
    <SelfContained>true</SelfContained>
    <PublishSingleFile>true</PublishSingleFile>
    <IncludeNativeLibrariesForSelfExtract>true</IncludeNativeLibrariesForSelfExtract>
    <EnableCompressionInSingleFile>true</EnableCompressionInSingleFile>
    <DebugType>embedded</DebugType>
    <PublishTrimmed>false</PublishTrimmed>
  </PropertyGroup>
</Project>
```

各项说明：

| 属性 | 取值 | 说明 |
|---|---|---|
| `Configuration` | `Release` | 发布走 Release 优化路径 |
| `RuntimeIdentifier` | `win-x64` | 当前阶段只目标 Windows 64-bit；mDNS、Core Audio、Tray 都强依赖 Windows |
| `SelfContained` | `true` | 自带 .NET 9 运行时，目标机器无需预装 SDK / Hosting Bundle |
| `PublishSingleFile` | `true` | 把所有托管 DLL 打包进 exe |
| `IncludeNativeLibrariesForSelfExtract` | `true` | 把 native DLL（Kestrel、ICU、Hostfxr 等）也塞进 exe |
| `EnableCompressionInSingleFile` | `true` | 启用 LZMA 压缩，60 MB 出包对比未压缩约可省 30%~40% |
| `DebugType` | `embedded` | 调试符号嵌入 exe，崩溃栈带行号；不会再额外生成独立 PDB |
| `PublishTrimmed` | **`false`** | **绝对不能开**：`AudioSwitcher.AudioApi.CoreAudio`（NU1701 net40 库）、`NAudio` COM 互操作、`H.NotifyIcon` 都没有 trimming 支持声明，开启后会在运行时随机抛 `MissingMethodException` |
| `PublishDir` | `bin\publish\win-x64\` | 显式指定输出目录（相对 csproj），避开默认的 `bin/Release/<TFM>/<RID>/publish/` 嵌套层级 |

## 一键发布脚本 publish.ps1

`services/windows-agent/publish.ps1` 是发布唯一入口，脚本只做三件事：

1. `pnpm --dir apps/pc-web install --frozen-lockfile`
2. `pnpm --dir apps/pc-web build`（Vite 构建产物落到 `services/windows-agent/src/MaimaiHomeAgent/wwwroot/`）
3. `dotnet publish src\MaimaiHomeAgent\MaimaiHomeAgent.csproj -c Release -r win-x64 --self-contained -p:PublishProfile=win-x64 -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true -p:DebugType=embedded -p:PublishTrimmed=false -nologo`

任何一步失败 → 立即 `exit 1`。

执行：

```powershell
# 仓库根目录
services\windows-agent\publish.ps1

# 如果 dotnet 不在 PATH
services\windows-agent\publish.ps1 -DotnetPath "C:\Users\abbey\scoop\apps\dotnet9-sdk\9.0.314\dotnet.exe"
```

脚本最后会打印 exe 绝对路径与体积；体积超过 100 MB 会以 PowerShell warning 形式提示，但不阻断脚本退出（已通过的发布不应回滚）。

## 输出位置

```
services/windows-agent/src/MaimaiHomeAgent/bin/publish/win-x64/
├─ MaimaiHomeAgent.exe                            # ~60 MB，单文件主程序
├─ appsettings.json                               # 默认配置（Kestrel / Serilog / Discovery / FileRoots）
├─ appsettings.Development.json                   # Development 环境覆盖
├─ web.config                                     # IIS 部署描述（当前未使用）
├─ MaimaiHomeAgent.staticwebassets.endpoints.json # 静态资源端点清单
└─ wwwroot/                                       # PC Web 构建产物（dotnet publish 自动复制）
   ├─ index.html
   └─ assets/
```

把整个 `bin/publish/win-x64/` 目录拷到目标机器即可运行。**不要**只拷 exe — wwwroot 与 appsettings 必须在 exe 同级。

## 验证步骤

### 体积验证

```powershell
$exe = "services\windows-agent\src\MaimaiHomeAgent\bin\publish\win-x64\MaimaiHomeAgent.exe"
$info = Get-Item -LiteralPath $exe
"{0} ({1:N2} MB)" -f $info.FullName, ($info.Length / 1MB)
```

期望：约 60 MB，必须 ≤ 100 MB。

### 启动 + /api/status 烟雾测试

```powershell
$exe = (Resolve-Path "services\windows-agent\src\MaimaiHomeAgent\bin\publish\win-x64\MaimaiHomeAgent.exe").Path
Start-Process -FilePath $exe -PassThru
Start-Sleep -Seconds 2
Invoke-WebRequest -Uri "http://127.0.0.1:8765/api/status" -UseBasicParsing
```

期望响应：

- `StatusCode` 为 200
- `Content-Type` 为 `application/json; charset=utf-8`
- Body 中 `capabilities.audioVolume / audioMute / audioDeviceSwitch / fileManagement / discoveryBroadcast` 全部为 `true`

T29 实测响应时间约 1.18 秒（冷启动 + LZMA 解压 + Kestrel 初始化），稳定低于 15 秒目标。

证据：[`.omo/evidence/task-29-publish-launch.log`](../../.omo/evidence/task-29-publish-launch.log)、[`.omo/evidence/task-29-exe-size.log`](../../.omo/evidence/task-29-exe-size.log)。

## 已知坑与对策

### 坑 1：单文件下 Serilog 抛 `No Serilog:Using configuration section is defined`

**现象**：`Program.cs` 里 `configuration.ReadFrom.Configuration(context.Configuration)` 在 publish 后启动直接 fatal，错误说找不到 sink 程序集。

**根因**：Serilog `Configuration.AssemblyFinder` 默认通过扫描磁盘 DLL 来发现 `Serilog.Sinks.Console`、`Serilog.Sinks.File`；single-file publish 把 DLL 嵌进了 exe，扫描找不到任何文件。

**对策**：在 `Program.cs` 通过 `ConfigurationReaderOptions` 显式声明 sink 程序集：

```csharp
var serilogOptions = new ConfigurationReaderOptions(
    typeof(Serilog.ConsoleLoggerConfigurationExtensions).Assembly,
    typeof(Serilog.FileLoggerConfigurationExtensions).Assembly);

configuration
    .ReadFrom.Configuration(context.Configuration, serilogOptions)
    .ReadFrom.Services(services)
    .Enrich.FromLogContext();
```

新增 sink 时，在这里追加对应的 `Assembly` 引用。

### 坑 2：launch CWD ≠ exe 目录时 `appsettings.json` 加载失败

**现象**：从仓库根目录或随便一个目录 `Start-Process` exe，启动直接报错，但从 exe 同级目录运行就 OK。

**根因**：`WebApplication.CreateBuilder(args)` 默认用 `Environment.CurrentDirectory` 作为 ContentRoot，所以会去 CWD 找 `appsettings.json`。Tray 启动、用户双击、schtasks 启动都不能保证 CWD 等于 exe 目录。

**对策**：`Program.cs` 显式设置 `ContentRootPath`：

```csharp
var builder = WebApplication.CreateBuilder(new WebApplicationOptions
{
    Args = args,
    ContentRootPath = AppContext.BaseDirectory,
});
```

`AppContext.BaseDirectory` 在 single-file extract 模式下指向运行时解压目录（也是 wwwroot 与 appsettings.json 的实际位置）。

### 坑 3：`PublishDir` 不生效

**现象**：第一次执行 `dotnet publish ... -p:PublishSingleFile=true ...`（不带 `-p:PublishProfile=win-x64`）发现 exe 出现在 `bin/Release/net9.0-windows/win-x64/publish/`，跟 pubxml 里写的 `bin\publish\win-x64\` 不一致。

**根因**：`*.pubxml` 文件在 `dotnet publish` 命令行不会被自动加载，需要显式 `-p:PublishProfile=<profile-name>`（不带 `.pubxml` 后缀）。

**对策**：脚本里强制 `-p:PublishProfile=win-x64`，pubxml 中所有属性才会生效。

### 坑 4：`MaimaiHomeAgent.exe` 进程残留导致后续 `dotnet build` 文件占用

参见 T26 学习笔记。`Get-Process | Where-Object Name -like 'MaimaiHomeAgent*' | Stop-Process -Force` 在每次 publish 前都跑一遍。脚本本身不会自动清理（不知道是不是用户在用），由调用方决定。

### 坑 5：trimming 不可开

`AudioSwitcher.AudioApi.CoreAudio`（NU1701 旁路引入的 .NET Framework 4.0 程序集）、`NAudio.CoreAudioApi`（基于 source-generated COM）、`H.NotifyIcon`（动态加载 Win32 图标）都没有声明 trim 安全。强行 `PublishTrimmed=true` 会出现：

- 启动时 `MissingMethodException: AudioSwitcher.AudioApi.CoreAudio.CoreAudioController..ctor()`
- 切换设备时 `TypeLoadException` on `IMMNotificationClient`
- Tray 加载图标时 `EntryPointNotFoundException` on `LoadImageW`

且这些都是运行时偶发，**单元测试覆盖不到**。当前节流体积约 60 MB 已经在可接受范围（< 100 MB 目标），不值得为了再压几兆冒崩溃风险。等到这些依赖明确声明支持 trimming 后再考虑切换。

## 后续可选优化

- **PublishReadyToRun**：开启后启动时间能再降 30%~50%，代价是 exe 体积涨 ~15 MB。如果未来发现冷启动 > 3s 影响 Tray 体验，可以开。
- **PublishAot**：完全不可行 —— ASP.NET Core 的 minimal API + System.Text.Json source generator 还没有覆盖整个 ResponseWriter 链路；`Microsoft.AspNetCore.OpenApi` 在 AOT 下抛 `RequiresUnreferencedCodeAttribute`。
- **签名（Authenticode）**：发布到外部用户前需要给 exe 签名，否则 SmartScreen 默认拦截。当前仅内网部署，未排期。
- **MSIX / WiX 安装包**：本任务范围只到 single-file exe；如果未来要做安装器，把这个 exe 当作 payload 即可。

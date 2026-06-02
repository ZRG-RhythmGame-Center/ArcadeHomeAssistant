# Android 移动端启动骨架说明

> 创建日期: 2026-05-31
> 最后更新: 2026-06-01（更新路由表与联调说明，反映三 Tab 导航结构与无参数路由；补充 AudioScreenTags 和 AudioScreen 公开接口）
> 作者: Adsicmes
> 状态: 草稿

## 目的

记录 `apps/mobile-android` Android 原生 App 的初始骨架实现现状，包括工具链版本、架构、已实现页面与联调方式。

## 对应代码

- [build.gradle.kts（根）](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/mobile-android/build.gradle.kts)
- [app/build.gradle.kts](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/mobile-android/app/build.gradle.kts)
- [AndroidManifest.xml](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/mobile-android/app/src/main/AndroidManifest.xml)
- [App.kt](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/mobile-android/app/src/main/kotlin/com/maimai/home/App.kt)
- [ServiceLocator.kt](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/mobile-android/app/src/main/kotlin/com/maimai/home/ServiceLocator.kt)
- [data/AgentClient.kt](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/mobile-android/app/src/main/kotlin/com/maimai/home/data/AgentClient.kt)
- [data/AgentPreferences.kt](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/mobile-android/app/src/main/kotlin/com/maimai/home/data/AgentPreferences.kt)
- [data/DiscoveryService.kt](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/mobile-android/app/src/main/kotlin/com/maimai/home/data/DiscoveryService.kt)
- [data/EventStream.kt](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/mobile-android/app/src/main/kotlin/com/maimai/home/data/EventStream.kt)
- [ui/nav/MaimaiNavHost.kt](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/mobile-android/app/src/main/kotlin/com/maimai/home/ui/nav/MaimaiNavHost.kt)

## 工具链版本

| 项目 | 版本 |
|---|---|
| AGP（Android Gradle Plugin） | 8.7.3 |
| Kotlin | 2.3.0 |
| Gradle Wrapper | 9.1.0 |
| `compileSdk` | 36 |
| `minSdk` | 24 |
| `targetSdk` | 35 |
| Java / JVM target | 17 |
| Application ID | `com.maimai.home` |
| Debug suffix | `.debug`（`applicationId` 追加 `.debug`） |

## 主要依赖

| 依赖 | 版本 | 用途 |
|---|---|---|
| `compose-bom` | 2024.12.01 | Compose 版本 BOM |
| `androidx.navigation:navigation-compose` | 2.8.5 | 页面路由 |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.8.7 | ViewModel 集成 |
| `androidx.datastore:datastore-preferences` | 1.1.1 | Agent 地址持久化 |
| `kotlinx-coroutines-android` | 1.10.2 | 协程 |
| `kotlinx-serialization-json` | 1.7.3 | JSON 序列化 |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | HTTP + WebSocket |

### 测试依赖

| 依赖 | 版本 | 用途 |
|---|---|---|
| `org.junit:junit-bom` | 5.14.1 | JUnit 5 BOM（对齐 Jupiter 各模块版本） |
| `org.junit.jupiter:junit-jupiter-api` | BOM 管理 | JUnit 5 测试 API |
| `org.junit.jupiter:junit-jupiter-params` | BOM 管理 | 参数化测试 |
| `org.junit.jupiter:junit-jupiter-engine` | BOM 管理 | JUnit 5 运行引擎（`testRuntimeOnly`） |
| `junit:junit` | 4.13.2 | JUnit 4（供 Robolectric / Vintage 桥接） |
| `org.junit.vintage:junit-vintage-engine` | BOM 管理 | JUnit 4 Vintage 桥接引擎（`testRuntimeOnly`） |
| `com.google.truth:truth` | 1.4.2 | 断言库 |
| `app.cash.turbine:turbine` | 1.2.1 | Flow / StateFlow 测试工具 |
| `io.mockk:mockk` | 1.13.14 | Kotlin-native Mock 框架 |
| `io.mockk:mockk-agent` | 1.13.14 | MockK JVM agent（单元测试） |
| `io.mockk:mockk-android` | 1.13.14 | MockK Android instrumentation 版本 |
| `org.jetbrains.kotlin:kotlin-test` | 2.3.0 | Kotlin 断言工具（兼容 JUnit 5，不引入 kotlin-test-junit 以避免 capability 冲突） |
| `kotlinx-coroutines-test` | 1.10.2 | 协程测试工具 |
| `com.squareup.okhttp3:mockwebserver` | 4.12.0 | HTTP/WebSocket mock 服务器 |
| `org.robolectric:robolectric` | 4.13 | Android 框架 JVM 模拟（NSD、Manifest 等） |
| `androidx.test:core` | 1.6.1 | AndroidX 测试核心工具 |
| `androidx.test.ext:junit` | 1.2.1 | AndroidX JUnit 扩展 |
| `org.mockito:mockito-core` | 5.14.2 | Mockito（供 Wave 1 遗留测试使用；新测试优先用 MockK） |
| `androidx.compose.ui:ui-test-junit4` | BOM 管理 | Compose UI 测试（`createComposeRule()`，Wave 5） |
| `androidx.compose.ui:ui-test-manifest` | BOM 管理 | Compose UI 测试宿主 Activity（Robolectric 下 `setContent()` 所需，Wave 5） |

## 架构

采用 **ServiceLocator + ViewModel + Jetpack Compose** 模式：

- `App.kt`：`Application` 子类，启动时调用 `ServiceLocator.init(this)`
- `ServiceLocator`：单例，懒加载 `Json`、`OkHttpClient`、`AgentPreferences`、`AgentClient`、`DiscoveryService`
- 各页面 ViewModel 从 `ServiceLocator` 取依赖，不使用 DI 框架

### 路由（`MaimaiNavHost`）

底部导航三 Tab，路由均无参数，连接信息通过 `ServiceLocator.connectionHandle` 传递：

| 路由（`AppDestination`） | 页面 | 说明 |
|---|---|---|
| `connection`（`Device`） | `ConnectionScreen` / 设备页 | 连接与设备管理；未连接时为默认启动页 |
| `audio`（`Audio`） | `AudioScreen` / `AudioTabUnconnected` | 音频控制页；已连接时为默认启动页 |
| `files`（`Files`） | `FilesScreen` / `FilesTabUnconnected` | 文件管理页 |

启动默认页规则：`connectionHandle == null` → Device，否则 → Audio。未连接时 Audio/Files Tab 展示空状态页（`AudioTabUnconnected` / `FilesTabUnconnected`），提供"前往设备"入口。

## 数据层

### AgentPreferences

- 使用 `DataStore<Preferences>` 持久化 Agent 地址
- key：`agent_address`
- 默认值：debug 构建为 `192.168.1.100:8765`（`BuildConfig.DEFAULT_AGENT_ADDRESS`），release 构建为空字符串（显示输入框占位提示）

### AgentClient

封装所有 HTTP 调用，基于 OkHttp 同步执行（在协程 IO 调度器中调用）：

- 状态：`fetchStatus(address)` → `GET /api/status`
- 音频：`fetchAudioState`、`fetchAudioDevices`、`setVolume`、`setMute`、`switchDevice`
- 文件：`fetchFileRoots`、`fetchFiles`、`uploadFile`（两个重载：`File` 和 `ContentResolver+Uri`）、`downloadFile`、`deleteFile`、`renameFile`、`moveFile`

地址规范化：无 scheme 时自动补 `http://`；所有地址在发起请求前经 `LanAddressPolicy` 校验，非 RFC1918 / loopback / `.local` / IPv6 link-local 地址会抛出 `IllegalArgumentException`。

### DiscoveryService

使用 Android `NsdManager` 扫描 `_maimai-home._tcp` 服务，默认超时 6 秒，返回 `List<DiscoveredService>`（按名称排序）。

`DiscoveredService` 数据类字段：`name: String`、`host: String`、`port: Int`、`version: String?`（可选，从 mDNS TXT record 的 `version` 键读取；旧版 Agent 不广播此字段时为 `null`，UI 仅在非 `null` 时显示版本行）。

构造函数接受 `MulticastLockFactory` 参数（`com.maimai.home.data.MulticastLockFactory`），用于在发现期间获取和释放 Wi-Fi 组播锁。生产环境传入 `RealMulticastLockFactory(wifiManager)`，测试环境可注入 `FakeLockFactory` 以验证锁的获取/释放行为，无需真实 `WifiManager`。

### EventStream

基于 OkHttp WebSocket，连接 `ws://<address>/api/events`；地址在建立连接前同样经 `LanAddressPolicy` 校验（与 `AgentClient` 共用同一策略）：

- 指数退避重连（初始 1 s，最大 30 s）
- 暴露 `events: SharedFlow<EventEnvelope>` 和 `connectionState: StateFlow<ConnectionState>`
- 重连成功后触发 `onReconnect` 回调（供 ViewModel 刷新状态）

### LanAddressPolicy

应用层 LAN 允许列表，在 `AgentClient` 和 `EventStream` 的地址规范化路径中强制执行：

- 允许：`127.0.0.0/8`（loopback）、`10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`、`169.254.0.0/16`（IPv4 link-local）、`localhost`、`.local` mDNS 主机名、IPv6 loopback（`::1`）、IPv6 link-local（`fe80::`）、IPv6 site-local（`fec0::/10`）、IPv6 unique-local（`fc00::/7`）
- 拒绝：公网 IP 和任意公共主机名，抛出 `IllegalArgumentException`
- 主要方法：`requireLanHost(host)`（校验，不通过则抛出）、`isLanHost(host): Boolean`（纯判断；对主机名执行 DNS 解析，要求所有解析地址均为私有/loopback，防止 DNS rebinding 攻击）、`extractHost(address): String?`（从含 scheme 或裸 host:port 的地址中提取 host）

### LanDns

OkHttp `Dns` 接口的内部实现（`internal class LanDns`），作为连接层第二道 DNS 防护，弥补 `LanAddressPolicy.requireLanHost`（URL 构造时校验）与 OkHttp 实际 DNS 解析之间的 TOCTOU 缺口：

- 对每次 DNS 解析结果逐一检查，若任意地址不属于私有/loopback 范围，则抛出 `UnknownHostException`，阻止 socket 建立
- 部分解析结果为私有、部分为公网时，整批拒绝（防止 DNS rebinding 攻击中的混合结果）
- `localhost` 直接短路，不发起 DNS 查询
- 通过 `ServiceLocator` 注入到 `OkHttpClient`，同时覆盖 HTTP 调用和 WebSocket 升级两条路径

## UI 层关键接口

### ConnectionViewModel

- `uiState: StateFlow<ConnectionUiState>`：包含 `address`、`isTesting`、`isScanning`、`discovered`、`connectedStatus`、`errorMessage`
- `discoveryNavigation: Flow<DiscoveryNavigation>`：一次性导航信号，仅由 `useDiscoveredService()` 在静默验证成功后发出；`testConnection()` 不发出此信号（成功后需用户点击"进入设备"按钮）
- `DiscoveryNavigation(address: String, machineName: String)`：导航事件数据类
- `clearConnectedStatus()`：导航完成后由 Screen 调用，清除 `connectedStatus`

### ConnectionScreen 公开 Composable

以下三个 Composable 定义在 `ConnectionScreen.kt`，可被其他页面（Audio、Files）复用：

| Composable | 说明 |
|---|---|
| `LoadingCard(text, modifier)` | 带旋转指示器的加载卡片 |
| `EmptyCard(text, modifier)` | 空状态卡片 |
| `ErrorCard(text, modifier)` | 错误状态卡片（红色文字） |

### AudioScreen 公开 Composable

`AudioScreen` 的完整签名（Wave 8 重设计）：

```kotlin
fun AudioScreen(
    address: String,
    machineName: String,
    onOpenDevice: () -> Unit,          // 跳转到设备页
    onOpenFiles: (String, String) -> Unit, // 跳转到文件页（address/machineName 由 NavHost 忽略，仅导航）
    viewModel: AudioViewModel = ...,
)
```

未连接时由 `MaimaiNavHost` 渲染 `AudioTabUnconnected(onGoToConnection)`，不进入 `AudioScreen`。

以下三个 Composable 定义在 `ConnectionScreen.kt`，可被其他页面（Audio、Files）复用：

| Composable | 说明 |
|---|---|
| `LoadingCard(text, modifier)` | 带旋转指示器的加载卡片 |
| `EmptyCard(text, modifier)` | 空状态卡片 |
| `ErrorCard(text, modifier)` | 错误状态卡片（红色文字） |

### FilesScreenTags

`FilesScreenTags` 是定义在 `FilesScreen.kt` 的公开 `object`，提供 Compose UI 测试所需的 `testTag` 常量：

| 常量 | 说明 |
|---|---|
| `ROOT_PICKER_BUTTON` | 根目录选择按钮 |
| `ROOT_PICKER_SHEET` | 根目录选择 ModalBottomSheet |
| `ACTION_SHEET` | 文件操作 ModalBottomSheet（长按触发） |
| `RENAME_DIALOG` | 重命名对话框 |
| `MOVE_DIALOG` | 移动对话框 |
| `DELETE_DIALOG` | 删除确认对话框 |
| `DELETE_CONFIRM_BUTTON` | 删除确认按钮（红色） |
| `UPLOAD_FAB` | 上传 FAB |
| `BREADCRUMB_ROW` | 面包屑导航行 |
| `SNACKBAR_HOST` | Snackbar 宿主 |
| `EMPTY_DIRECTORY` | 空目录提示卡片 |

### AudioScreenTags

`AudioScreenTags` 是定义在 `AudioScreen.kt` 的公开 `object`，提供 Compose UI 测试所需的 `testTag` 常量：

| 常量 | 说明 |
|---|---|
| `MUTE_TOGGLE` | 静音切换按钮 |
| `VOLUME_SLIDER` | 音量滑条 |
| `VOLUME_PERCENT` | 音量百分比文字 |
| `REFRESH_BUTTON` | 顶部栏刷新按钮 |
| `SNACKBAR_HOST` | Snackbar 宿主 |
### FilesViewModel

- `start()` / `stop()`：生命周期方法，由 Screen 通过 `DisposableEffect` 调用；`start()` 创建真实 `EventStream` 并订阅文件事件，`stop()` 断开连接并取消协程
- `canMutate: Boolean`（`FilesUiState` 计算属性）：当前根目录已选且非只读时为 `true`，UI 用此门控上传/重命名/移动/删除按钮
- 文件事件订阅：监听 `file.created`、`file.deleted`、`file.renamed`、`file.moved` 四种事件，500 ms 防抖后刷新当前目录列表

## 权限与网络安全配置（AndroidManifest）

```xml
INTERNET
ACCESS_NETWORK_STATE
ACCESS_WIFI_STATE
CHANGE_WIFI_MULTICAST_STATE
NEARBY_WIFI_DEVICES  <!-- Android 13+，mDNS NSD 发现必需；usesPermissionFlags="neverForLocation" -->
```

网络安全配置：

- `android:usesCleartextTraffic="true"`：允许向局域网 Agent 发起 HTTP 明文请求（Android 9+ 默认禁止）
- `android:networkSecurityConfig="@xml/network_security_config"`：通过配置文件显式声明明文流量策略（`<base-config cleartextTrafficPermitted="true">`），并信任系统 CA，为将来支持 HTTPS 预留扩展点

## Manifest 安全验证

项目提供 `scripts/verify-manifest.ps1` 脚本，用于在 CI 或本地快速校验 Manifest 安全配置：

```powershell
# 在 apps/mobile-android 目录下执行
pwsh scripts/verify-manifest.ps1
# 或指定自定义路径
pwsh scripts/verify-manifest.ps1 -ManifestPath path/to/AndroidManifest.xml
```

脚本检查五项：`usesCleartextTraffic`、`networkSecurityConfig`、`NEARBY_WIFI_DEVICES`（含 `neverForLocation` 标志）、`CHANGE_WIFI_MULTICAST_STATE`，以及 `network_security_config.xml` 文件存在且包含 `<base-config cleartextTrafficPermitted='true'>`。全部通过时退出码为 0，任一失败时打印错误并以退出码 1 退出。

对应的 Robolectric 单元测试位于 `ManifestSecurityTest.kt`，覆盖相同五项检查，可通过 `./gradlew test` 运行。

`RobolectricSmokeTest.kt`（Wave 2.10）是一个独立的冒烟测试，验证 Robolectric 运行时可正常启动、`ApplicationProvider.getApplicationContext<App>()` 返回生产 `App` 实例（而非 Robolectric 默认的 `Application`），以及包名符合 debug/release 两种变体之一。该测试以 JUnit 4 编写，通过 `junit-vintage-engine` 桥接在 JUnit Platform 下运行。`testOptions.unitTests.isIncludeAndroidResources = true` 已在 `build.gradle.kts` 中启用，Robolectric 需要此选项才能访问 AndroidManifest 和资源文件。

`app/src/test/resources/robolectric.properties` 设置了全局 `sdk=28`，规避 `androidx.core 1.15.x` 与 Robolectric 4.13 的 insets-dispatch 字段不兼容问题（`NoSuchFieldError`）。旧测试类上的 `@Config(sdk = [28])` 与此保持一致；Wave 5 新增的 Compose UI 测试类（`ConnectionScreenTest`、`AudioScreenTest`、`FilesScreenTest`）使用 `@Config(sdk = [33])`，依赖 `build.gradle.kts` 中的 `afterEvaluate` 块通过 `configurations.matching { it.name.contains("UnitTest") && it.name.contains("RuntimeClasspath") }` 在单元测试运行时 classpath 中强制将 `androidx.core` 和 `androidx.core-ktx` 降至 `1.13.1`，作为双重保险。

### Release 变体测试配置（Wave 5）

Release 单元测试变体（`testRelease`）需要额外配置才能让 Robolectric + `createComposeRule()` 正常工作：

- `app/src/release/AndroidManifest.xml`：release manifest overlay，注册 `androidx.activity.ComponentActivity`（`android:exported="false"`），供 Robolectric 在 release 变体下解析 `setContent()` 宿主 Activity。
- `app/src/testRelease/AndroidManifest.xml`：testRelease source set 的 manifest，同样注册 `ComponentActivity`，作为 debug 变体从 `ui-test-manifest` 自动获取该注册的等价替代。
- `app/src/testRelease/resources/robolectric.properties`：将 Robolectric manifest 指向 `../debug/AndroidManifest.xml`（`manifest=../debug/AndroidManifest.xml`），使 release 单元测试复用 debug manifest 中已有的 `ComponentActivity` 注册。
- `build.gradle.kts` 中新增 `testReleaseImplementation("androidx.compose.ui:ui-test-manifest")`，确保 release 变体测试 classpath 中包含 Compose UI 测试宿主依赖。

### MainDispatcherRule

`MainDispatcherRule` 是一个 JUnit 5 Extension（`BeforeEachCallback` / `AfterEachCallback`），在每个测试方法前将 `Dispatchers.Main` 替换为 `UnconfinedTestDispatcher`（可传入自定义 dispatcher），测试结束后自动还原。ViewModel 单元测试通过 `@RegisterExtension` 注册使用。

## JaCoCo 覆盖率配置

Wave 2.12 在 `app/build.gradle.kts` 中手动定义了两个 Gradle 任务，替代 `android-junit5` 插件自带的 JaCoCo 任务生成（已通过 `junitPlatform { jacocoOptions.taskGenerationEnabled.set(false) }` 禁用）：

| 任务 | 说明 |
|---|---|
| `jacocoTestReport` | 生成 debug 单元测试覆盖率报告（XML + HTML），依赖 `testDebugUnitTest` |
| `jacocoVerification` | 强制 `com/maimai/home/data/**` 和 `com/maimai/home/ui/**` 包的行覆盖率 ≥ 70%，依赖 `jacocoTestReport` |

`jacocoVerification` 已挂入 `check` 任务，执行 `./gradlew check` 时自动触发覆盖率门禁。

```bash
# 生成覆盖率报告
./gradlew jacocoTestReport

# 检查覆盖率门禁（同时运行单元测试）
./gradlew jacocoVerification

# check 任务已包含覆盖率验证
./gradlew check
```

JaCoCo 版本：`0.8.12`。覆盖率统计排除主题文件、`MainActivity`、`App`、导航骨架、Compose 生成类等非业务代码。

## 构建说明

```bash
# 进入 Android 子项目目录
cd apps/mobile-android

# Debug 构建（arm64-v8a only）
./gradlew assembleDebug

# Release 构建（需配置签名，当前使用 debug 签名）
./gradlew assembleRelease
```

ABI 拆分已启用，仅输出 `arm64-v8a`，不生成 universal APK。

## 联调方式

1. 确认 Windows Agent 已运行并监听 `0.0.0.0:8765`
2. 手机与 Windows 电脑连接同一局域网
3. App 启动后进入设备页（`ConnectionScreen`），输入 Windows 机器 IP（如 `192.168.x.x:8765`）或点击"扫描局域网"自动发现
4. 连接成功后自动跳转到音频页（`AudioScreen`），可切换设备、调节音量
5. 通过底部 Tab 切换到文件页（`FilesScreen`）进行文件管理；未连接时两个页面均展示空状态并提供"前往设备"入口

## 注意事项

- Android 模拟器对 mDNS 支持不可靠，**局域网发现功能以真机验收为准**
- `CHANGE_WIFI_MULTICAST_STATE` 权限在部分厂商 ROM 上需要额外申请
- Release 构建当前使用 debug 签名（`signingConfig = signingConfigs.getByName("debug")`），正式发布前需替换
- `compileSdk 36` 需要在 `gradle.properties` 中设置 `android.suppressUnsupportedCompileSdk=36`（已配置）

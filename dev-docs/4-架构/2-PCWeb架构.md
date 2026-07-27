---
title: PC Web 架构
document_type: architecture
status: current
audience:
  - 维护者
owners:
  - Maimai Dev
created: 2026-05-30
updated: 2026-07-27
source_of_truth:
  - code:apps/pc-web
---

# PC Web 阶段 4 启动骨架说明

## 目的

记录 PC Web 已落地的 React + Vite 骨架、路由结构、状态管理与开发工作流，以及音频控制、文件管理、远程关机页面和实时事件流的实现现状。

## 对应代码

- [package.json](../../apps/pc-web/package.json)
- [vite.config.ts](../../apps/pc-web/vite.config.ts)
- [src/main.tsx](../../apps/pc-web/src/main.tsx)
- [src/router.tsx](../../apps/pc-web/src/router.tsx)
- [src/App.tsx](../../apps/pc-web/src/App.tsx)
- [src/AppLayout.tsx](../../apps/pc-web/src/AppLayout.tsx)
- [src/services/agentApi.ts](../../apps/pc-web/src/services/agentApi.ts)
- [src/services/audioApi.ts](../../apps/pc-web/src/services/audioApi.ts)
- [src/services/filesApi.ts](../../apps/pc-web/src/services/filesApi.ts)
- [src/services/powerApi.ts](../../apps/pc-web/src/services/powerApi.ts)
- [src/stores/agentStore.ts](../../apps/pc-web/src/stores/agentStore.ts)
- [src/lib/queryClient.ts](../../apps/pc-web/src/lib/queryClient.ts)
- [src/lib/eventStream.ts](../../apps/pc-web/src/lib/eventStream.ts)
- [src/hooks/useAudio.ts](../../apps/pc-web/src/hooks/useAudio.ts)
- [src/hooks/useFiles.ts](../../apps/pc-web/src/hooks/useFiles.ts)
- [src/hooks/usePower.ts](../../apps/pc-web/src/hooks/usePower.ts)
- [src/hooks/useEventStream.ts](../../apps/pc-web/src/hooks/useEventStream.ts)
- [src/pages/AudioPage.tsx](../../apps/pc-web/src/pages/AudioPage.tsx)
- [src/pages/FilesPage.tsx](../../apps/pc-web/src/pages/FilesPage.tsx)
- [src/pages/PowerPage.tsx](../../apps/pc-web/src/pages/PowerPage.tsx)

## 技术栈

- React 18 + TypeScript
- Vite 5（构建 + 开发服务器）
- react-router-dom v6（客户端路由）
- @tanstack/react-query v5（服务端状态管理）
- Zustand v4（客户端状态：Agent 地址）
- Axios（HTTP 请求，通过 `agentApi` 实例封装）
- Vitest + @testing-library/react（单元测试）

## 路由结构

```text
/           → 重定向到 /audio
/audio      → AudioPage（音频控制）
/files      → FilesPage（文件管理）
/power      → PowerPage（远程关机）
*           → 重定向到 /audio
```

根路由使用 `AppLayout` 作为 shell，子路由通过 `<Outlet />` 渲染。当前 PC Web 是 LAN-only 路由，没有 `RequireAuth`、`PairingPage` 或全局 token gate；`router.tsx` 直接把 URL 映射到页面。

## 状态管理

### Zustand：agentStore

`useAgentStore` 管理当前 Agent 地址：

| 字段 | 类型 | 说明 |
|---|---|---|
| `baseUrl` | string | Agent 地址，默认 `http://127.0.0.1:8765` |
| `setBaseUrl(baseUrl)` | function | 更新 Agent 地址，后续 Axios 请求会在拦截器中读取新值 |

### Axios：agentApi

`agentApi` 是全局 Axios 实例，在请求拦截器中从 `agentStore` 读取 `baseUrl`，无需重建实例即可响应地址变更。当前普通请求不自动加认证头；远程关机的控制令牌只由 `powerApi.ts` 在执行关机请求中单独写入 `Authorization: Bearer <token>`。

### 音频 API：audioApi

`src/services/audioApi.ts` 封装音频相关的所有 HTTP 调用：

| 函数 | 请求 | 返回 |
|---|---|---|
| `getAudioState()` | `GET /api/audio/state` | `AudioState` |
| `getAudioDevices()` | `GET /api/audio/devices` | `AudioDevice[]` |
| `setVolume(level)` | `POST /api/audio/volume` | `void` |
| `setMute(muted)` | `POST /api/audio/mute` | `void` |
| `switchDevice(deviceId)` | `POST /api/audio/default-device` | `void` |

### 文件 API：filesApi

`src/services/filesApi.ts` 封装文件管理相关的所有 HTTP 调用：

| 函数 | 请求 | 返回 |
|---|---|---|
| `getFileRoots()` | `GET /api/file-roots` | `FileRoot[]` |
| `getFileListing(rootId, path, limit?)` | `GET /api/files` | `FileListingResult` |
| `uploadFile(rootId, path, file, overwrite?)` | `POST /api/files/upload` | `UploadResult` |
| `downloadFile(rootId, path)` | `GET /api/files/download` | `Blob` |
| `deleteFile(rootId, path)` | `DELETE /api/files` | `MutationResult` |
| `renameFile(rootId, path, newName)` | `POST /api/files/rename` | `MutationResult` |
| `moveFile(rootId, fromPath, toPath)` | `POST /api/files/move` | `MutationResult` |

### 电源 API：powerApi

`src/services/powerApi.ts` 封装远程关机相关 HTTP 调用，并定义 `AgentCapabilities`、`AgentStatus`、`RemoteShutdownStatus` 类型：

| 函数 | 请求 | 返回 |
|---|---|---|
| `getAgentStatus()` | `GET /api/status` | `AgentStatus`，包含 `capabilities.remoteShutdown` |
| `getRemoteShutdownStatus()` | `GET /api/power/shutdown` | `RemoteShutdownStatus` |
| `executeRemoteShutdown(controlToken)` | `POST /api/power/shutdown`，body 为 `{ confirm: true }`，请求头带 `Authorization: Bearer <controlToken>` | `RemoteShutdownStatus` |

`RemoteShutdownStatus` 只包含当前状态所需字段：

```ts
{
  available: boolean;
  state: 'idle' | 'executing' | 'failed' | string;
  error: string | null;
}
```

### TanStack Query：queryClient

`queryClient` 使用默认配置，通过 `QueryClientProvider` 注入全局。

## 页面行为

### PowerPage

`src/pages/PowerPage.tsx` 是 PC Web 的远程关机页面，路由为 `/power`，由 `AppLayout` 顶部导航的 `Power` Tab 进入。

页面行为：

- 读取 `GET /api/status` 和 `GET /api/power/shutdown`，同时要求 `capabilities.remoteShutdown` 与 `RemoteShutdownStatus.available` 为真才启用危险按钮
- 展示目标机器名和 Agent 地址，地址优先使用状态接口返回的 `baseUrl`，否则使用 `agentStore.baseUrl`
- 控制令牌只保存在组件本地 state，不写入 localStorage
- 发起关机前显示二次确认，确认文案包含机器名、Agent 地址，并明确确认后立即关机
- 用户在二次确认中输入控制令牌并点击确认后，立即发送关机请求
- 执行失败时展示接口错误信息

### Hooks：useAudio / useFiles / usePower / useEventStream

`src/hooks/useAudio.ts` 封装音频功能的所有 TanStack Query 读写操作：

| Hook | 类型 | 说明 |
|---|---|---|
| `useAudioState()` | `useQuery` | 读 `GET /api/audio/state`，staleTime 5s |
| `useAudioDevices()` | `useQuery` | 读 `GET /api/audio/devices`，staleTime 10s |
| `useSetVolume()` | `useMutation` | 写音量，成功后 invalidate `['audio', 'state']` |
| `useSetMute()` | `useMutation` | 写静音，成功后 invalidate `['audio', 'state']` |
| `useSwitchDevice()` | `useMutation` | 切换设备，成功后 invalidate `['audio']` |

`src/hooks/useFiles.ts` 封装文件功能的所有 TanStack Query 读写操作：

| Hook | 类型 | 说明 |
|---|---|---|
| `useFileRoots()` | `useQuery` | 读 `GET /api/file-roots` |
| `useFileListing(rootId, path)` | `useQuery` | 读 `GET /api/files`，rootId 为 null 时禁用 |
| `useUpload()` | `useMutation` | 上传文件，成功后 invalidate 该 root 下所有列表 |
| `useDelete()` | `useMutation` | 删除文件 |
| `useRename()` | `useMutation` | 重命名文件 |
| `useMove()` | `useMutation` | 移动文件 |
| `useDownload()` | `useCallback` | 下载文件（Blob 触发浏览器下载） |

`src/hooks/usePower.ts` 封装远程关机功能的 TanStack Query 读写操作：

| Hook | 类型 | 说明 |
|---|---|---|
| `useAgentStatus()` | `useQuery` | 读 `GET /api/status`，query key 为 `['power', 'agent-status']` |
| `useRemoteShutdownStatus()` | `useQuery` | 读 `GET /api/power/shutdown` |
| `useExecuteRemoteShutdown()` | `useMutation` | 写 `POST /api/power/shutdown`，成功后 invalidate 电源状态和 Agent 状态 |

`src/hooks/useEventStream.ts` 订阅 `/api/events` WebSocket，按事件类型选择性 invalidate 缓存：

| 事件类型 | 操作 |
|---|---|
| `audio.state` | invalidate `['audio', 'state']` |
| `audio.device.changed` | invalidate `['audio']` |
| `file.*` | invalidate `['files', 'listing']` |
| `power.shutdown.*` | invalidate `['power', 'shutdown']` + `['power', 'agent-status']` |
| 断线重连 | invalidate `['audio']` + `['files']` + `['power']` |

`App.tsx` 在根组件层挂载 `useEventStream()`，保证整个应用共用一个 WebSocket 连接。

`queryClient` 使用默认配置，通过 `QueryClientProvider` 注入全局。

## 开发工作流

### 启动开发服务器

```powershell
# 在 apps/pc-web 目录
pnpm dev
```

默认监听 `http://localhost:5173`。

### 构建

```powershell
pnpm build
```

构建产物输出到 `apps/pc-web/dist/`，需复制到 `services/windows-agent/src/MaimaiHomeAgent/wwwroot/` 后由 Agent 通过 `UseStaticFiles` 托管。

### 测试

```powershell
pnpm test
```

### 类型检查

```powershell
pnpm typecheck
```

## 与 Windows Agent 的集成

PC Web 由 Windows Agent 通过 `app.UseStaticFiles` 托管静态文件，`app.MapFallbackToFile("index.html")` 处理 SPA 路由回退。

开发期使用 Vite dev server 直接访问，通过 `agentStore.baseUrl` 指向本机 Agent（默认 `http://127.0.0.1:8765`）。

## 测试现状

已有单元测试：

- `src/lib/queryClient.test.ts`：queryClient 基础配置校验
- `src/stores/agentStore.test.ts`：agentStore 状态读写校验
- `src/lib/eventStream.test.ts`：EventStream 连接/断开/重连/事件分发逻辑
- `src/hooks/useAudio.test.ts` / `useAudio.test.tsx`：音频 hooks 读写测试
- `src/hooks/useFiles.test.tsx`：文件 hooks 读写测试
- `src/hooks/useEventStream.test.ts`：WebSocket 事件路由测试
- `src/pages/AudioPage.test.tsx`：AudioPage 组件测试
- `src/pages/FilesPage.test.tsx`：FilesPage 组件测试
- `src/pages/PowerPage.test.tsx`：PowerPage 能力门控、控制令牌输入、二次确认和立即关机请求测试

## 下一步

1. 远程关机控制令牌当前由用户手动输入，后续可在 Agent 本机配置入口完成令牌生成/展示。
2. 构建后将 `dist/` 输出到 `wwwroot/` 并验证 Agent 托管 `/audio`、`/files`、`/power` 三个 SPA 路由。

---

## 修订记录

| 时间 | 作者 | 变更说明 |
|------|------|----------|
| 2026-06-03 10:05 | Maimai Dev | `RemoteShutdownStatus` 收敛为 `available`、`state`、`error`，同步移除旧状态字段说明。 |
| 2026-06-03 09:50 | Maimai Dev | 远程关机页面改为输入控制令牌并二次确认后立即执行，移除延迟展示、撤销按钮、撤销 API 和排程轮询说明。 |
| 2026-06-02 20:38 | Maimai Dev | 更新为当前 LAN-only 路由和状态管理，新增 `/power`、`powerApi`、`usePower`、远程关机事件刷新和 PowerPage 测试说明；移除旧配对授权描述并归档。 |

# PC Web 阶段 4 启动骨架说明

> 创建日期: 2026-05-30
> 最后更新: 2026-05-31
> 作者: Adsicmes
> 状态: 草稿

## 目的

记录 PC Web 已落地的 React + Vite 骨架、路由结构、状态管理与开发工作流，以及音频控制、文件管理页面、实时事件流和配对授权的实现现状。

## 对应代码

- [package.json](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/pc-web/package.json)
- [vite.config.ts](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/pc-web/vite.config.ts)
- [src/main.tsx](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/pc-web/src/main.tsx)
- [src/router.tsx](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/pc-web/src/router.tsx)
- [src/App.tsx](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/pc-web/src/App.tsx)
- [src/AppLayout.tsx](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/pc-web/src/AppLayout.tsx)
- [src/services/agentApi.ts](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/pc-web/src/services/agentApi.ts)
- [src/services/audioApi.ts](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/pc-web/src/services/audioApi.ts)
- [src/services/filesApi.ts](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/pc-web/src/services/filesApi.ts)
- [src/stores/agentStore.ts](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/pc-web/src/stores/agentStore.ts)
- [src/lib/queryClient.ts](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/pc-web/src/lib/queryClient.ts)
- [src/lib/eventStream.ts](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/pc-web/src/lib/eventStream.ts)
- [src/hooks/useAudio.ts](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/pc-web/src/hooks/useAudio.ts)
- [src/hooks/useFiles.ts](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/pc-web/src/hooks/useFiles.ts)
- [src/hooks/useEventStream.ts](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/pc-web/src/hooks/useEventStream.ts)
- [src/pages/AudioPage.tsx](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/pc-web/src/pages/AudioPage.tsx)
- [src/pages/FilesPage.tsx](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/pc-web/src/pages/FilesPage.tsx)
- [src/pages/PairingPage.tsx](file:///D:/UserFiles/Development/Projects/ZRC/maimai-home-assistant/apps/pc-web/src/pages/PairingPage.tsx)

## 技术栈

- React 18 + TypeScript
- Vite 5（构建 + 开发服务器）
- react-router-dom v6（客户端路由）
- @tanstack/react-query v5（服务端状态管理）
- Zustand v4（客户端状态：Agent 地址和 token）
- Axios（HTTP 请求，通过 `agentApi` 实例封装）
- Vitest + @testing-library/react（单元测试）

## 路由结构

```text
/           → 重定向到 /audio
/audio      → AudioPage（音频控制）〔需要 Bearer token〕
/files      → FilesPage（文件管理）〔需要 Bearer token〕
/pairing    → PairingPage（配对授权）〔无需 token〕
*           → 重定向到 /audio
```

根路由使用 `AppLayout` 作为 shell，子路由通过 `<Outlet />` 渲染。`RequireAuth` 组件包裹需要认证的路由，未配对时跳转到 `/pairing?redirect=<原始路径>`。

## 状态管理

### Zustand：agentStore

`useAgentStore` 管理两个全局状态，并将 token 持久化到 localStorage：

| 字段 | 类型 | 说明 |
|---|---|---|
| `baseUrl` | string | Agent 地址，默认 `http://127.0.0.1:8765` |
| `token` | string \| null | Bearer token，配对前为 null |
| `AGENT_TOKEN_STORAGE_KEY` | `'agent_token'` | localStorage key，导出供测试断言 |
| `setToken(token)` | 写入 localStorage（`agent_token` key）并更新内存状态；`null` 时删除 key |

### Axios：agentApi

`agentApi` 是全局 Axios 实例，在请求拦截器中从 `agentStore` 读取 `baseUrl` 和 `token`，无需重建实例即可响应配对或地址变更。响应拦截器处理 401：清除 token 并跳转到 `/pairing?redirect=<当前路径>`。

`exchangePairingCode(code, deviceLabel)` 函数封装 `POST /api/pairing/exchange`，返回 Bearer token 字符串；401 和网络错误以原始 AxiosError 抛出，由 PairingPage 映射错误文案。

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

### TanStack Query：queryClient

`queryClient` 使用默认配置，通过 `QueryClientProvider` 注入全局。

### Hooks：useAudio / useFiles / useEventStream

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

`src/hooks/useEventStream.ts` 订阅 `/api/events` WebSocket，按事件类型选择性 invalidate 缓存：

| 事件类型 | 操作 |
|---|---|
| `audio.state` | invalidate `['audio', 'state']` |
| `audio.device.changed` | invalidate `['audio']` |
| `file.*` | invalidate `['files', 'listing']` |
| 断线重连 | invalidate `['audio']` + `['files']` |

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
- `src/pages/PairingPage.test.tsx`：PairingPage 组件渲染、输入校验、配对成功跳转、401 错误提示

## 下一步

1. 实现 PairingPage：接入配对码流程
2. 构建后将 `dist/` 复制到 `wwwroot/` 并验证 Agent 托管链路

1. ~~实现 PairingPage：接入配对码流程~~ **已完成**：`PairingPage`、`RequireAuth` 路由守卫、`exchangePairingCode`、401 自动跳转均已实现
2. 构建后将 `dist/` 复制到 `wwwroot/` 并验证 Agent 托管链路

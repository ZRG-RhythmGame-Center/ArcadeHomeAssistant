---
title: PC Web 阶段 4 启动骨架说明移除内容归档
document_type: explanation
status: superseded
audience:
  - 维护者
owners:
  - Maimai Dev
created: 2026-06-02
updated: 2026-07-27
related:
  - ../../4-架构/2-PCWeb架构.md
---

---
title: PC Web 阶段 4 启动骨架说明移除内容归档
document_type: explanation
status: superseded
audience:
  - 维护者
owners:
  - Maimai Dev
created: 2026-06-02
updated: 2026-07-27
related:
  - ../../4-架构/2-PCWeb架构.md
---

# PC Web 阶段 4 启动骨架说明移除内容归档

> 移除时间: 2026-06-02 20:38
> 来源文档: ../../4-架构/2-PCWeb架构.md
> 原章节: 路由结构 / 状态管理 / 测试现状 / 下一步
> 移除原因: 已失效；当前 `apps/pc-web/src` 中没有 `PairingPage`、`RequireAuth`、`AGENT_TOKEN_STORAGE_KEY`、`setToken` 或 `exchangePairingCode`，路由文件明确为 LAN-only。

## 原文

```markdown
/audio      → AudioPage（音频控制）〔需要 Bearer token〕
/files      → FilesPage（文件管理）〔需要 Bearer token〕
/pairing    → PairingPage（配对授权）〔无需 token〕

根路由使用 `AppLayout` 作为 shell，子路由通过 `<Outlet />` 渲染。`RequireAuth` 组件包裹需要认证的路由，未配对时跳转到 `/pairing?redirect=<原始路径>`。

`useAgentStore` 管理两个全局状态，并将 token 持久化到 localStorage：

| 字段 | 类型 | 说明 |
|---|---|---|
| `token` | string \| null | Bearer token，配对前为 null |
| `AGENT_TOKEN_STORAGE_KEY` | `'agent_token'` | localStorage key，导出供测试断言 |
| `setToken(token)` | 写入 localStorage（`agent_token` key）并更新内存状态；`null` 时删除 key |

`agentApi` 是全局 Axios 实例，在请求拦截器中从 `agentStore` 读取 `baseUrl` 和 `token`，无需重建实例即可响应配对或地址变更。响应拦截器处理 401：清除 token 并跳转到 `/pairing?redirect=<当前路径>`。

`exchangePairingCode(code, deviceLabel)` 函数封装 `POST /api/pairing/exchange`，返回 Bearer token 字符串；401 和网络错误以原始 AxiosError 抛出，由 PairingPage 映射错误文案。

- `src/pages/PairingPage.test.tsx`：PairingPage 组件渲染、输入校验、配对成功跳转、401 错误提示

1. 实现 PairingPage：接入配对码流程
2. 构建后将 `dist/` 复制到 `wwwroot/` 并验证 Agent 托管链路

1. ~~实现 PairingPage：接入配对码流程~~ **已完成**：`PairingPage`、`RequireAuth` 路由守卫、`exchangePairingCode`、401 自动跳转均已实现
2. 构建后将 `dist/` 复制到 `wwwroot/` 并验证 Agent 托管链路
```

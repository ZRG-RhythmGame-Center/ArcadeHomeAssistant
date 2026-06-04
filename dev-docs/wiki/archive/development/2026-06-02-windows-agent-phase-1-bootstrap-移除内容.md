# Windows Agent 阶段 1 启动骨架说明移除内容归档

> 移除时间: 2026-06-02 20:38
> 来源文档: ../../development/windows-agent-phase-1-bootstrap.md
> 原章节: 当前代码状态 / 当前公开接口 / 测试现状 / 下一步
> 移除原因: 已失效；当前 `services/windows-agent/src/MaimaiHomeAgent` 没有 `Security/` 目录、配对端点、TokenAdmin 端点或全局认证 middleware，测试目录也没有对应 Security 测试文件，托盘 UI 代码已存在。

## 原文

```markdown
- `Security/` 认证与配对层（Wave 4 / 阶段 8）：
  - `IPairingService` / `PairingService`：创建一次性配对码（绑定来源 IP，默认 TTL 120 秒）、换取长期 token（原子单次消费）、查询当前活跃码
  - `ITokenStore` / `JsonFileTokenStore`：token 持久化到 `%LOCALAPPDATA%\maimai-home-assistant\tokens.json`，支持校验、列表、撤销
  - `AuthMiddleware`：Bearer token（HTTP）/ `?token=` 查询参数（WebSocket）验证；白名单路径：`/api/status`、`/api/pairing/code`、`/api/pairing/active`、`/api/pairing/exchange`、非 `/api/` 前缀（静态资源 / SPA）
  - `PairingEndpoints`：`POST /api/pairing/code`（loopback-only，托盘 UI 调用）、`GET /api/pairing/active`（查询当前码）、`POST /api/pairing/exchange`（客户端换 token，在白名单上）
  - `TokenAdminEndpoints`：`GET /api/tokens`（列出已签发 token，不含 token 值）、`DELETE /api/tokens/{id}`（撤销，自删时响应 `selfDeleted:true`）

### 认证相关接口

| 端点 | 说明 | 认证要求 |
|---|---|---|
| `POST /api/pairing/code` | 创建配对码（loopback-only） | 无（仅限 127.0.0.1/::1） |
| `GET /api/pairing/active` | 查询当前活跃配对码 | 无（白名单） |
| `POST /api/pairing/exchange` | 用配对码换取 token | 无（白名单） |
| `GET /api/tokens` | 列出已签发 token（不含 token 值） | Bearer token |
| `DELETE /api/tokens/{id}` | 撤销指定 token | Bearer token |

- `Security/PairingEndpointsTests.cs`：配对码创建（loopback 限制、参数校验）、换取 token（IP 绑定、单次消费、过期）
- `Security/AuthMiddlewareTests.cs`：Bearer token 验证、WebSocket `?token=` 验证、白名单路径放行、401 响应格式
- `Security/TokenAdminEndpointsTests.cs`：token 列表、撤销（含自删 `selfDeleted:true`）

1. 实现托盘 UI（阶段 9）：显示配对码、打开 PC Web、控制启停
```

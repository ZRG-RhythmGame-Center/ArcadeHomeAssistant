---
title: Agent 实时事件总线移除内容归档
document_type: explanation
status: superseded
audience:
  - 维护者
owners:
  - Maimai Dev
created: 2026-06-02
updated: 2026-07-27
related:
  - ../../9-参考/1-Agent实时事件总线.md
---

# Agent 实时事件总线（/api/events）移除内容归档

> 移除时间: 2026-06-02 20:38
> 来源文档: ../../9-参考/1-Agent实时事件总线.md
> 原章节: 后续 Wave
> 移除原因: 已失效；PC Web 与 Android 的 WebSocket 客户端已落地，`?token=` 当前实现为兼容性 no-op 而非认证验证。

## 原文

```markdown
## 后续 Wave

- T19 / T20 将分别在 Mobile / PC Web 实现 WebSocket 客户端，按本表定义解析 `type`。
- 鉴权（`token` query 参数验证）在 Wave 4 一并完成；当前实现仅记录到会话用于诊断。
```

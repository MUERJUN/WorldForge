---
name: AI Provider Compatibility
about: 报告真实 AI 服务商（DeepSeek / OpenAI / 兼容端点）的兼容性问题
title: '[AI] '
labels: ['ai-provider']
---

## 服务商信息（必填）

| 项目 | 你的值 |
|------|--------|
| 服务商名称 | 例如 DeepSeek / OpenAI / 自建兼容端点 |
| Base URL | 例如 `https://api.deepseek.com`（**不要贴完整 Key**） |
| Model 名称 | 例如 `deepseek-chat` |
| 协议 | OpenAI 兼容 / 其他 |
| WorldForge 版本 | 例如 v0.6.0 |

## 遇到的阶段

- [ ] 连接测试（AI 设置里点"测试连接"）
- [ ] 自然语言提案生成
- [ ] 提案确认/执行
- [ ] 其他：____

## 现象

<!-- 错误码/错误消息（脱敏后）、超时、返回内容无法解析等 -->

## 关键声明

> ⚠️ **绝对不要粘贴 API Key 或 `Authorization` 头**。贴了请立刻去服务商后台吊销并重新生成。
> 如能提供请求/响应的**脱敏**片段（去掉 Key 与个人路径），请附上。

- [ ] 我已确认本 issue 中不含任何 API Key 或认证头

## 备注

> v0.6.0 公测版发布说明中已声明：真实 DeepSeek/OpenAI 服务商的端到端调用尚无独立验收，
> 本类 issue 是补齐该验收的主要渠道。
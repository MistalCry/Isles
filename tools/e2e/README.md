# tools/e2e — 接口审计与冒烟

通过公开 HTTP / STOMP 契约验证功能与权限边界，不依赖浏览器、不直接读写数据库。
每次运行创建带时间戳的新用户与频道，可重复执行（注册限流见下文）。

## 脚本

| 脚本 | 关注点 | 规模 |
| --- | --- | --- |
| `api-e2e.mjs` | 正向旅程：注册 → 频道/发帖/评论/点赞 → 搜索/热门 → 邀请审批 → 好友/私信 → 通知 → 管理端 → 举报 → AI 流式 | 11 步 |
| `permissions-audit.mjs` | 越权与边界：未加入发帖、个人/全员禁言、退出、私密可见性、邀请审批、拉黑、板块范围、重复举报、通配符、媒体、聚合、解散 | 13 步 |
| `feature-audit.mjs` | 内容协作细节：分享计数、精华筛选、用户卡片、楼中楼、板块 CRUD/排序、身份组层级、频道内搜索、通知过滤、私信分享 | 9 步 |
| `realtime-check.mjs` | STOMP 鉴权 + 私信/通知实时推送 | 3 步 |

浏览器级（真实 Chrome + CDP，位于 `tools/dev/`）：

| 脚本 | 关注点 |
| --- | --- |
| `cdp-register-check.mjs` | 打开 `/register?redirect=/ai`、填表提交、断言跳转成功，并打印失败请求与 CORS/代理错误（用于排查「无法连接服务器」） |

## 运行

```bash
# 1. 启动后端（默认 8080）
cd backend && mvn spring-boot:run

# 2. 另开终端
node tools/e2e/api-e2e.mjs
node tools/e2e/permissions-audit.mjs
node tools/e2e/feature-audit.mjs
node tools/e2e/realtime-check.mjs
```

指定地址：

```bash
BASE_URL=http://127.0.0.1:8082/api/v1 node tools/e2e/permissions-audit.mjs
```

## 注意事项

- **注册限流**：`/auth/register` 为 5 次/小时/IP。重复运行请用
  `RATE_LIMIT_ENABLED=false` 启动后端，或更换 IP/等待窗口重置。
- **限流专项**：`api-e2e.mjs` 的限流步骤默认跳过，需要时：
  `E2E_CHECK_RATE_LIMIT=1 BASE_URL=... node tools/e2e/api-e2e.mjs`
- **AI 流式**：`api-e2e.mjs` 仅在 `GET /ai/status` 返回 `configured=true` 时执行。
  可用真实 LM Studio：启动 LM Studio 服务（默认 1234），在平台后台把 Base URL 配成
  `http://127.0.0.1:1234/v1`、模型 ID 填 `/v1/models` 返回的 id（如 `qwen3-vl-8b-instruct`），
  并勾选视觉/推理能力。也可用 `node tools/mock-openai/server.mjs 1234` 做无模型联调。
- 失败时脚本以非 0 退出并打印断言信息，便于 CI 判定。

# tools/e2e — 接口审计与冒烟

通过公开 HTTP / STOMP 契约验证功能与权限边界，不依赖浏览器、不直接读写数据库。
每次运行创建带时间戳的新用户与频道，可重复执行（注册限流见下文）。

## 脚本

| 脚本 | 关注点 | 规模 |
| --- | --- | --- |
| `api-e2e.mjs` | 正向旅程：注册 → 频道/发帖/评论/点赞 → 搜索/热门 → 邀请审批 → 好友/私信 → 通知 → 管理端 → 举报 → AI 流式 | 11 步 |
| `permissions-audit.mjs` | 越权与边界：未加入发帖、个人/全员禁言、退出、私密可见性、邀请审批、拉黑、板块范围、重复举报、通配符、媒体、聚合、解散 | 16 步 |
| `feature-audit.mjs` | 内容协作细节：分享计数、精华筛选、用户卡片、楼中楼、板块 CRUD/排序、身份组层级、频道内搜索、通知过滤、私信分享 | 9 步 |
| `realtime-check.mjs` | STOMP 鉴权 + 私信/通知实时推送 | 3 步 |
| `admin-console-audit.mjs` | 平台后台护栏：配置中心闭环（含 60s 内二次修改仍即时 + no-store）、举报→下架→通知→恢复、MODERATOR 授权边界、公告状态机、置顶首屏 | 20 步 + 1 跳过 |
| `report-regression.mjs` | 举报回归：中英文场景各举报帖子+评论、五种原因代码全覆盖、界面文案当 reason 被 400 | 6 步 |
| `media-regression.mjs` | 上传格式：heic/avif/mov 上传+回读、非法类型拒绝、ffmpeg 缺失降级 | 8 步 |
| `ai-tools-regression.mjs` | AI 工具调用：模型要工具→检索→二轮回答、来源真实命中、不支持 tools 时降级 | 5 步 |
| `assistant-model-regression.mjs` | 助手模型：频道指定模型生效、模型禁用后回落默认 | 4 步 + 1 跳过 |

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
node tools/e2e/admin-console-audit.mjs
node tools/e2e/report-regression.mjs
node tools/e2e/media-regression.mjs

# AI 相关脚本需要 mock-openai（另开终端）
node tools/mock-openai/server.mjs 1235
MOCK_URL=http://127.0.0.1:1235 node tools/e2e/ai-tools-regression.mjs
MOCK_URL=http://127.0.0.1:1235 node tools/e2e/assistant-model-regression.mjs
```

指定地址：

```bash
BASE_URL=http://127.0.0.1:8082/api/v1 node tools/e2e/permissions-audit.mjs
```

## 平台后台脚本的前置条件

`admin-console-audit.mjs` 需要一个平台超管账号（脚本会自己造普通用户/频道，但**不会**造超管）：

```bash
# 默认 admin_demo / Passw0rd123；也可显式指定
ADMIN_USERNAME=admin_demo ADMIN_PASSWORD=Passw0rd123 node tools/e2e/admin-console-audit.mjs
node tools/e2e/report-regression.mjs
node tools/e2e/media-regression.mjs

# AI 相关脚本需要 mock-openai（另开终端）
node tools/mock-openai/server.mjs 1235
MOCK_URL=http://127.0.0.1:1235 node tools/e2e/ai-tools-regression.mjs
MOCK_URL=http://127.0.0.1:1235 node tools/e2e/assistant-model-regression.mjs
```

没有超管时先提升一个账号（角色缓存最多 60 秒）：

```bash
isles.bat admin <用户名>
```

脚本收尾会清理自己造的数据：站点名改完立即重置（若运行前本来就存在覆盖值，收尾会还原原值）、
删除公告、撤销 MODERATOR 授权、取消置顶、解散测试频道。置顶用例依赖后台 P0-6，
接口未就绪时自动跳过并标注；「30 天前旧帖」因创建接口无法回填 `created_at`，
用「置顶帖 + 3 条更新帖 + limit=3 首屏」等价覆盖。

## AI 与媒体脚本的前置条件

- `ai-tools-regression.mjs` / `assistant-model-regression.mjs`：
  - 需要 `node tools/mock-openai/server.mjs 1235`（脚本用它的 `/debug/requests` 断言轮次与实际模型）；
  - 需要平台超管（默认 `admin_demo`，可用 `ADMIN_USERNAME`/`ADMIN_PASSWORD` 覆盖）；
  - 会临时新建模型配置、跑完删除，**不改平台默认模型**；`assistant-model-regression.mjs` 的回落用例
    会真实调用平台默认模型（本地 LM Studio），耗时约 1 分钟。
- `media-regression.mjs`：avif/mov 用本机 ffmpeg 现场生成（无 ffmpeg 时退化为最小 ISO BMFF 头，仍验证格式识别与回读）；
  heic 因 ffmpeg 不支持封装，固定用最小 `ftyp(heic)` 头。降级路径这样跑：

  ```bash
  # 后端加 --app.media.ffmpeg=/nonexistent/ffmpeg 启动，然后
  E2E_FFMPEG_MODE=missing node tools/e2e/media-regression.mjs
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

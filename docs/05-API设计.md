---
id: API-05
title: API 设计
version: 0.4.0
status: approved
owner: lead
last_updated: 2026-11-14
reviewers: []
related: [REQ-03, ARC-02, DB-06, ADR-0005]
---

# API 设计

> 状态：**全量契约已实现**（M1 核心闭环 + M2 管理与运营 + M3 智能与增长）。
> 机器可读契约见 [`backend/src/main/resources/openapi/isles-api.yaml`](../backend/src/main/resources/openapi/isles-api.yaml)，
> 本文是设计说明与约定；两者不一致时以 OpenAPI 文件为准并同步修正本文。
> 接口变更必须先改 OpenAPI 与本文，再改代码（规范 §7）。

## 1. 全局约定

| 项 | 约定 |
| --- | --- |
| Base URL | `/api/v1` |
| 认证 | `Authorization: Bearer <accessToken>`；刷新令牌走 HttpOnly Cookie `refresh_token` |
| 内容类型 | `application/json`；上传 `multipart/form-data`；AI 流式 `text/event-stream` |
| 时间 | ISO 8601 UTC，如 `2026-11-14T01:15:37Z` |
| ID | ULID 字符串（26 位，不可猜测、可排序）；频道对外另有数字 `channelNo` |
| 请求追踪 | 响应头 `X-Request-Id`；错误体 `requestId` |
| 游客 | 标 🔓 的接口允许匿名访问；其余需登录 |
| 分页 | 内容流：游标 `?cursor=&limit=`（默认 20，上限 100），返回 `nextCursor`；管理端列表：`?page=&size=`，返回 `items + total` |
| 限流 | 超限返回 `429` + `Retry-After`（秒），规则见 [docs/07 §7](07-安全与合规.md) |

### 1.1 成功响应

- 单资源：直接返回资源 JSON。
- 游标列表：`{ "items": [...], "nextCursor": "ulid|null" }`。
- 管理端列表：`{ "items": [...], "total": 123 }`（部分含 `page`/`size`）。

### 1.2 错误响应

```json
{
  "error": {
    "code": "CHANNEL_NOT_FOUND",
    "message": "频道不存在或不可见",
    "details": [],
    "requestId": "01HZX..."
  }
}
```

| HTTP | 错误码示例 | 场景 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | 参数校验失败 |
| 401 | `UNAUTHORIZED` / `TOKEN_EXPIRED` / `INVALID_CREDENTIALS` | 未登录、令牌过期、密码错误 |
| 403 | `FORBIDDEN` / `CHANNEL_MUTED` / `NOT_JOINED` / `BLOCKED` | 无权限、禁言、未加入、拉黑 |
| 404 | `CHANNEL_NOT_FOUND` / `POST_NOT_FOUND` / `CONVERSATION_NOT_FOUND` | 不存在或不可见 |
| 409 | `USERNAME_TAKEN` / `ALREADY_JOINED` / `REPORT_ALREADY_EXISTS` | 冲突 |
| 413 | `MEDIA_TOO_LARGE` | 上传超限 |
| 422 | `BUSINESS_RULE_VIOLATION` / `OWNER_CANNOT_LEAVE` | 业务规则不满足 |
| 429 | `RATE_LIMITED` | 触发限流 |
| 500 | `INTERNAL_ERROR` | 服务端异常 |

## 2. 接口清单

### 2.1 认证 / 用户

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | `/auth/register` | 注册（5 次/小时/IP） | 🔓 |
| POST | `/auth/login` | 登录（10 次/分钟/IP），返回 accessToken + Set-Cookie | 🔓 |
| POST | `/auth/refresh` | 刷新 accessToken（读取 Cookie，轮换） | 🔓 |
| POST | `/auth/logout` | 登出并撤销 refreshToken | 登录 |
| GET | `/auth/me` | 当前用户信息 | 登录 |
| GET | `/users/{id}` | 用户公开资料 | 🔓 |
| GET | `/users/{id}/card` | 用户卡片（`?channelId=` 返回加入时间/身份组） | 🔓 |
| PATCH | `/users/me` | 修改头像/昵称/签名/性别/地区 | 登录 |
| POST / DELETE | `/users/{userId}/block` | 拉黑 / 解除拉黑 | 登录 |
| GET | `/users/blocked` | 黑名单列表 | 登录 |

### 2.2 频道 / 板块 / 邀请

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | `/channels` | 创建频道（生成频道号、默认板块、四个系统身份组） | 登录 |
| GET | `/channels/{id}` | 频道详情（按可见性校验） | 🔓 |
| GET | `/channels/mine` | 我加入的频道 | 登录 |
| POST | `/channels/{id}/join` | 加入公开频道 | 登录 |
| POST | `/channels/{id}/leave` | 退出频道（频道主不可退出） | 成员 |
| POST | `/channels/{id}/dissolve` | 解散频道（二次确认） | 频道主 |
| GET | `/channels/{id}/boards` | 板块列表 | 🔓 |
| POST | `/channels/{id}/invites` | 创建邀请链接（审批/有效期/次数） | `channel.invite.create` |
| GET | `/channels/{id}/invites` | 邀请链接列表 | `channel.invite.create` |
| DELETE | `/channels/{id}/invites/{inviteId}` | 撤销邀请链接 | `channel.invite.create` |
| POST | `/invites/{token}/accept` | 使用邀请加入 / 提交申请 | 登录 |
| GET | `/channels/{id}/join-requests` | 加入申请列表（`status=PENDING`） | `channel.invite.approve` |
| POST | `/channels/{id}/join-requests/{requestId}/approve` | 通过申请 | `channel.invite.approve` |
| POST | `/channels/{id}/join-requests/{requestId}/reject` | 拒绝申请 | `channel.invite.approve` |
| GET | `/channels/{id}/settings` | 频道设置面板（管理员/邀请/禁言状态） | 成员 |
| POST / DELETE | `/channels/{id}/mute-all` | 全员禁言 / 解除 | `channel.mute_all` |

### 2.3 帖子 / 评论 / 互动

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/channels/{id}/posts` | 频道帖子流：`tab=latest\|hot\|essence`、`boardId`、游标 | 🔓 |
| POST | `/channels/{id}/posts` | 发帖（10 次/分钟/用户） | 成员（受禁言限制） |
| GET | `/posts/{id}` | 帖子详情（浏览量 +1） | 🔓 |
| DELETE | `/posts/{id}` | 删除帖子（作者或管理员） | 作者 / `post.delete` |
| POST / DELETE | `/posts/{id}/like` | 点赞 / 取消 | 登录 |
| POST | `/posts/{id}/share` | 分享计数 | 登录 |
| GET | `/posts/{postId}/comments` | 评论列表：`sort=latest\|likes` | 🔓 |
| POST | `/posts/{postId}/comments` | 评论 / 楼中楼回复（30 次/分钟/用户） | 成员 |
| POST / DELETE | `/comments/{id}/like` | 评论点赞 / 取消 | 登录 |
| DELETE | `/comments/{id}` | 删除评论（作者或管理员） | 作者 / `comment.delete` |

### 2.4 动态 / 热门 / 推荐

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/feed` | 已加入频道动态：`sort=latest\|hot`、游标 | 登录 |
| GET | `/hot` | 全站热门讨论（浏览 + 点赞×3 + 评论×5，时间衰减） | 🔓 |
| GET | `/hot/recommendations` | 频道推荐（含推荐理由）；`seed` 用于「换一批」，在候选窗口内轮换 | 登录 |

### 2.5 搜索

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/search` | 全站搜索：`q`、`type=all\|channel\|post`、`sort`、游标（60 次/分钟） | 🔓 |
| GET | `/search/suggest` | 联想：已加入频道前缀 + 热词前缀 | 登录 |
| GET | `/search/hot` | 热词榜 | 🔓 |
| GET | `/search/history` | 搜索历史 | 登录 |
| DELETE | `/search/history` | 清空历史 | 登录 |
| DELETE | `/search/history/{id}` | 删除单条 | 登录 |
| GET | `/channels/{id}/search` | 频道内搜索 | 🔓 |

### 2.6 媒体

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | `/media/upload` | 上传图片/视频（`file`、`type=image\|video`，30 次/小时） | 登录 |

### 2.7 通知

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/notifications` | 通知列表：`channelId`、`type`、`unreadOnly`、游标 | 登录 |
| GET | `/notifications/unread-count` | 未读数（含按类型分组） | 登录 |
| POST | `/notifications/{id}/read` | 标记单条已读 | 本人 |
| POST | `/notifications/read-all` | 全部已读 | 本人 |

### 2.8 好友 / 私信

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/friends` | 好友列表 | 登录 |
| GET | `/friends/requests` | 好友申请：`direction=incoming\|outgoing` | 登录 |
| POST | `/friends/requests` | 发起申请（互相申请直接成为好友） | 登录 |
| POST | `/friends/requests/{id}/accept` / `reject` | 同意 / 拒绝 | 被申请人 |
| DELETE | `/friends/{userId}` | 删除好友 | 登录 |
| GET | `/friends/{userId}/status` | 关系状态（好友/待处理/拉黑） | 登录 |
| POST | `/dm/conversations` | 创建/复用会话（任何人可发起） | 登录 |
| GET | `/dm/conversations` | 会话列表（最后消息、未读） | 登录 |
| GET | `/dm/conversations/{id}` | 会话详情 | 参与者 |
| GET | `/dm/conversations/{id}/messages` | 历史消息（时间正序，游标向前翻页） | 参与者 |
| POST | `/dm/conversations/{id}/messages` | 发送 `TEXT` / `IMAGE` / `POST`（分享帖子） | 参与者 |
| POST | `/dm/conversations/{id}/read` | 标记已读 | 参与者 |
| GET | `/dm/unread-count` | 私信未读总数 | 登录 |

> 实时推送：STOMP over WebSocket，端点 `/ws`，用户队列 `/user/queue/dm`、`/user/queue/notifications`，
> 连接需在 `CONNECT` 帧携带 `Authorization: Bearer <accessToken>`（见 ADR-0003）。

### 2.9 频道管理中心

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| PATCH | `/channels/{id}` | 修改频道资料 | `channel.settings` |
| GET | `/channels/{id}/members` | 成员列表（page/size，搜索/身份组过滤） | 成员 |
| POST / DELETE | `/channels/{id}/members/{userId}/mute` | 禁言 / 解除 | `channel.member.mute` |
| DELETE | `/channels/{id}/members/{userId}` | 移除成员 | `channel.member.remove` |
| POST | `/channels/{id}/members/{userId}/role` | 分配身份组 | `channel.role.manage` |
| GET | `/channels/{id}/roles` | 身份组列表 | 成员 |
| POST | `/channels/{id}/roles` | 新建自定义身份组 | `channel.role.manage` |
| PATCH / DELETE | `/channels/{id}/roles/{roleId}` | 编辑 / 删除身份组 | `channel.role.manage` |
| GET | `/channels/{id}/boards/manage` | 板块管理列表 | `channel.board.manage` |
| POST | `/channels/{id}/boards` | 创建板块 | `channel.board.manage` |
| PATCH | `/channels/{id}/boards/{boardId}` | 重命名/改描述 | `channel.board.manage` |
| PATCH | `/channels/{id}/boards/reorder` | 板块排序 | `channel.board.manage` |
| DELETE | `/channels/{id}/boards/{boardId}` | 删除板块（至少保留一个） | `channel.board.manage` |
| GET | `/channels/{id}/posts/manage` | 内容管理列表（page/size，关键词/作者/板块/状态/时间） | `channel.content.manage` |
| POST / DELETE | `/channels/{channelId}/posts/{postId}/pin` | 置顶 / 取消 | `post.pin` |
| POST / DELETE | `/channels/{channelId}/posts/{postId}/essence` | 加精 / 取消 | `post.essence` |
| DELETE | `/channels/{channelId}/posts/{postId}` | 删除帖子 | `post.delete` |
| POST | `/channels/{channelId}/posts/batch` | 批量置顶/精华/删除（≤50） | `channel.content.manage` |
| GET | `/channels/{id}/audit-logs` | 管理日志（page/size，操作人/动作/时间） | `channel.log.view` |
| POST | `/reports` | 提交举报 | 登录 |
| GET | `/reports/mine` | 我的举报 | 登录 |
| GET | `/channels/{id}/reports` | 频道举报列表（page/size） | `channel.content.manage` |
| POST | `/channels/{id}/reports/{reportId}/handle` | 处置举报（`ACCEPTED`/`REJECTED` + 动作） | `channel.content.manage` |

### 2.10 数据中心

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/channels/{id}/stats/overview` | 实时数字 + 昨日指标 + 环比 | `channel.data.view` |
| GET | `/channels/{id}/stats/trends` | 近 N 天趋势（`days`） | `channel.data.view` |
| GET | `/channels/{id}/stats/content` | 板块分布 / 热门帖子 / 互动漏斗 | `channel.data.view` |
| GET | `/channels/{id}/stats/members` | 身份组分布 / 新增成员趋势 | `channel.data.view` |
| POST | `/channels/{id}/stats/aggregate` | 手动补跑日聚合（`date`，默认昨日） | `channel.data.view` |

### 2.11 AI：频道问问 / 频道助手

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/ai/status` | AI 能力状态（是否已配置模型） | 登录 |
| GET | `/ai/models` | 可用模型（仅启用项） | 登录 |
| GET | `/ai/conversations` | 会话列表（`scope=GLOBAL\|CHANNEL`） | 登录 |
| POST | `/ai/conversations` | 新建会话 | 登录 |
| GET / PATCH / DELETE | `/ai/conversations/{id}` | 详情 / 重命名 / 删除 | 本人 |
| GET | `/ai/conversations/{id}/messages` | 会话消息 | 本人 |
| POST | `/ai/conversations/{id}/messages/stream` | 提问（SSE：`meta`/`delta`/`reasoning`/`done`/`error`） | 本人 |
| POST | `/ai/conversations/{id}/regenerate` | 重新生成最后一条回答（SSE） | 本人 |
| POST | `/ai/messages/{id}/edit-stream` | 编辑历史提问并重发（SSE，截断后续） | 本人 |
| POST | `/ai/messages/{id}/stop` | 停止生成 | 本人 |
| GET | `/channels/{id}/ai/assistant` | 频道助手面板信息 | 频道可见 |
| GET / PUT | `/channels/{id}/ai/settings` | 助手设置（账号/任务开关/频率） | `channel.ai.manage` |
| POST | `/channels/{id}/ai/assistant-account` | 创建/修复助手虚拟账号 | `channel.ai.manage` |
| GET | `/channels/{id}/ai/task-logs` | 任务执行日志（page/size） | `channel.ai.manage` |
| POST | `/channels/{id}/ai/tasks/run` | 手动触发任务（幂等 + 频率上限） | `channel.ai.manage` |

### 2.12 平台管理后台

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/admin/me` | 管理员身份校验 | 登录 |
| GET | `/admin/overview` | 平台数据概览 | 平台管理员 |
| GET | `/admin/users` | 用户列表（page/size） | 平台管理员 |
| POST | `/admin/users/{id}/ban` / `unban` | 禁用 / 恢复用户（不能禁用自己） | 平台管理员 |
| GET | `/admin/channels` | 频道列表（page/size） | 平台管理员 |
| POST | `/admin/channels/{id}/ban` / `unban` | 封禁 / 解封频道 | 平台管理员 |
| GET | `/admin/reports` | 全站举报（`available=false` 表示表未就绪） | 平台管理员 |
| POST | `/admin/reports/{id}/handle` | 处置（`DISMISS`/`RESOLVE`） | 平台管理员 |
| GET | `/admin/ai/models` | 模型配置列表 | 平台管理员 |
| POST | `/admin/ai/models` | 新建模型配置（API Key 加密落库） | 平台管理员 |
| PUT / DELETE | `/admin/ai/models/{id}` | 更新 / 删除 | 平台管理员 |
| POST | `/admin/ai/models/{id}/test` | 连通性测试（已保存配置） | 平台管理员 |
| POST | `/admin/ai/test-connection` | 连通性测试（未保存配置） | 平台管理员 |
| GET | `/admin/ai/models/{id}/remote-models` | 拉取远端模型列表 | 平台管理员 |
| GET | `/admin/ai/status` | AI 配置状态 | 平台管理员 |

### 2.13 健康检查

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/actuator/health` | 健康检查（部署探针） |

## 3. 关键请求/响应示例

### 3.1 注册

```http
POST /api/v1/auth/register
Content-Type: application/json

{ "username": "vrfan", "password": "S3cure-pass", "nickname": "VR 爱好者" }
```

```json
201 Created
{ "id": "01HZX...", "username": "vrfan", "nickname": "VR 爱好者", "avatarUrl": null }
```

### 3.2 创建频道

```json
POST /api/v1/channels
{
  "name": "VRChat 中文社区",
  "avatarUrl": "/uploads/2026/11/av.png",
  "coverUrl": "/uploads/2026/11/cover.png",
  "description": "VRChat 玩家交流",
  "type": "PUBLIC",
  "visibility": "PUBLIC_VISIBLE"
}
```

### 3.3 发帖

```json
POST /api/v1/channels/{channelId}/posts
{
  "boardId": "01HZY...",
  "title": "本周 VRChat 活动合集",
  "content": "<p>...</p>",
  "contentFormat": "HTML",
  "media": [{ "type": "IMAGE", "url": "/uploads/...", "thumbUrl": "/uploads/..." }]
}
```

### 3.4 AI 流式回答（SSE）

```
POST /api/v1/ai/conversations/{id}/messages/stream
Accept: text/event-stream

event: meta
data: {"conversationId":"...","messageId":"...","model":"qwen2.5","sources":[...],"deepThinking":false}

event: delta
data: {"content":"根据社区帖子，"}

event: done
data: {"messageId":"...","aborted":false,"status":"COMPLETED","tokenIn":120,"tokenOut":340}
```

### 3.5 限流响应

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 37

{ "error": { "code": "RATE_LIMITED", "message": "操作过于频繁（LOGIN：10 次/60 秒），请 37 秒后重试", "details": [], "requestId": "req_..." } }
```

## 4. 响应模型（实际契约）

> 后端返回**扁平字段**（如 `authorId`/`channelName`/`liked`），前端 `frontend/src/api/adapters.ts` 统一适配为嵌套模型。
> 新增字段只增不改；删除/重命名按 §1.1 兼容规则执行。完整字段定义见 OpenAPI `components.schemas`。

| DTO | 关键字段 |
| --- | --- |
| `LoginResponse` | `accessToken`、`tokenType`、`expiresIn`、`user`；refreshToken 仅走 HttpOnly Cookie |
| `UserResponse` | `id`、`username`、`nickname`、`avatarUrl`、`signature`、`gender`、`region`、`createdAt` |
| `UserCardResponse` | UserResponse 字段 + `channelId`、`joinedAt`、`roleName`、`memberStatus` |
| `ChannelResponse` | `id`、`channelNo`、`name`、`avatarUrl`、`coverUrl`、`description`、`type`、`visibility`、`ownerId`、`memberCount`、`postCount`、`joined`、`myRoleName`、`createdAt` |
| `BoardResponse` | `id`、`channelId`、`name`、`description`、`sort`、`postCount`、`createdAt` |
| `PostResponse` | `id`、`channelId`、`channelName`、`channelAvatarUrl`、`boardId`、`boardName`、`authorId`、`authorNickname`、`authorAvatarUrl`、`title`、`content`、`contentFormat`、`coverUrl`、`media[]`、`viewCount`、`likeCount`、`commentCount`、`shareCount`、`liked`、`pinned`、`essence`、`status`、`createdAt` |
| `CommentResponse` | `id`、`postId`、`parentId`、`authorId`、`authorNickname`、`authorAvatarUrl`、`replyToUserId`、`replyToNickname`、`content`、`media[]`、`emoji[]`、`likeCount`、`liked`、`createdAt`、`replies[]` |
| `SearchItemResponse` | `type`、`id`、`title`、`summary`、`channelId`、`channelName`、`channelAvatarUrl`、`avatarUrl`、`memberCount`、`likeCount`、`commentCount`、作者与板块字段、`createdAt` |
| `MemberResponse` | `userId`、`username`、`nickname`、`avatarUrl`、`roleId`、`roleName`、`status`、`mutedUntil`、`muted`、`joinedAt` |
| `RoleResponse` | `id`、`channelId`、`name`、`description`、`permissions[]`、`scopeBoardId`、`scopeBoardName`、`system`、`sort`、`memberCount` |
| `ReportResponse` | `id`、`reporterId`、`reporterNickname`、`channelId`、`targetType`、`targetId`、`targetPreview`、`reason`、`status`、`handleAction`、`handleNote`、`handledAt`、`createdAt` |
| `AuditLogResponse` | `id`、`channelId`、`actorId`、`actorNickname`、`action`、`targetType`、`targetId`、`detail`、`ip`、`createdAt` |
| `StatsOverviewResponse` | `channelId`、`memberCount`、`postCount`、`yesterday`、`memberDelta`、`postDelta` |
| `AssistantSettingsResponse` | `channelId`、`assistantUserId`、`nickname`、`avatarUrl`、`coverUrl`、`bio`、`persona`、`enabled`、六个任务开关、`dailySummaryHour`、三个每日频率 |
| `TaskLogResponse` | `id`、`channelId`、`taskType`、`frequencyKey`、`status`、`resultText`、`postId`、`commentId`、`errorMessage`、`startedAt`、`finishedAt`、`createdAt` |
| `PageResult<T>` | `items`、`nextCursor` |
| `OffsetPage<T>` | `items`、`total` |

## 5. 待补充

- 契约测试：用 OpenAPI 校验实际响应字段（防止 DTO 漂移）
- 幂等键 `Idempotency-Key`：发帖/评论的重复提交保护（当前依赖 ULID + 唯一约束兜底）

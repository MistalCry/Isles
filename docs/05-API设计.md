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
| 409 | `VOICE_ROOM_CLOSED` / `VOICE_SEAT_OCCUPIED` / `VOICE_ROOM_ALREADY_OPEN` | 语音房已关、抢座失败、同频道重复开房 |
| 403 | `VOICE_NOT_IN_ROOM` | 不在该语音房内（STOMP 侧对应错误码 `NOT_IN_ROOM`） |
| 413 | `MEDIA_TOO_LARGE` | 上传超限 |
| 422 | `BUSINESS_RULE_VIOLATION` / `OWNER_CANNOT_LEAVE` | 业务规则不满足 |
| 422 | `REPORT_TARGET_INVALID` | 举报目标不存在或不可见（帖子/评论已删除、频道不可见、目标类型不匹配） |
| 422 | `SENSITIVE_WORD_BLOCKED` | 内容命中敏感词且平台处理方式为 `BLOCK`（`details` 不返回词库，提示里最多列 3 个命中词） |
| 422 | `VOICE_SEAT_INVALID` / `CHANNEL_APP_LIMIT_EXCEEDED` | 麦位越界、频道应用数量超上限 |
| 422 | `ACTIVITY_NOT_PUBLISHED` / `ACTIVITY_FULL` / `ACTIVITY_ENDED` | 活动未发布、报名已满、活动已开始 |
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
| GET | `/channels/{id}/posts` | 频道帖子流：`tab=latest\|hot\|essence`、`boardId`、游标；`latest` 置顶帖固定在最前 | 🔓 |
| GET | `/channels/{id}/posts/pinned` | 频道置顶帖：`pinned_at DESC`，最多 20 条（`limit` 可调小） | 🔓 |
| POST | `/channels/{id}/posts` | 发帖（10 次/分钟/用户） | 成员（受禁言限制） |
| GET | `/posts/{id}` | 帖子详情（浏览量 +1） | 🔓 |
| DELETE | `/posts/{id}` | 删除帖子（作者或管理员） | 作者 / `post.delete` |
| POST / DELETE | `/posts/{id}/like` | 点赞 / 取消 | 登录 |
| POST | `/posts/{id}/share` | 分享计数 | 登录 |
| GET | `/channels/{id}/mention-candidates` | @提及候选（频道活跃成员，含助手） | 登录 |
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
| POST | `/media/upload` | 上传图片/视频（`file`、`type=image\|video`，30 次/小时）；响应 `{ id, url, thumbUrl, mediaType: IMAGE\|VIDEO, width, height, durationMs }` | 登录 |

**支持格式**（按文件头 magic number 判定，不信任扩展名；单图 ≤10MB，视频 ≤200MB）

| 类别 | 允许的 MIME | 说明 |
| --- | --- | --- |
| 图片 | `image/jpeg`、`image/png`、`image/gif`、`image/webp`、`image/avif`、`image/bmp`、`image/heic`、`image/heif` | 只收浏览器原生可渲染格式，**不做服务端转码** |
| 视频 | `video/mp4`、`video/webm`、`video/quicktime`（mov）、`video/x-m4v` | 视频不转码，仅抽首帧做封面 |

> ISO BMFF 家族（mp4/mov/m4v/avif/heic/heif）按 `ftyp` 的 major brand + 兼容 brand 区分，避免 HEIC 被误判为 mp4。
> 缩略图：ImageIO 可解码的走内置缩放；AVIF/HEIC 等交给 ffmpeg 解码。
> **没有 ffmpeg 时降级为「原图直出 + `thumbUrl=null` + `width/height=null`」，上传仍返回 200**（不报 500），前端按原始宽高比渲染。

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
| POST | `/ai/conversations/{id}/messages/stream` | 提问（SSE：`meta`/`delta`/`reasoning`/`sources`/`done`/`error`；**`sources` 每次回答必发，0 命中为空数组**） | 本人 |
| POST | `/ai/conversations/{id}/regenerate` | 重新生成最后一条回答（SSE） | 本人 |
| POST | `/ai/messages/{id}/edit-stream` | 编辑历史提问并重发（SSE，截断后续） | 本人 |
| POST | `/ai/messages/{id}/stop` | 停止生成 | 本人 |
| GET | `/channels/{id}/ai/assistant` | 频道助手面板信息 | 频道可见 |
| GET | `/channels/{id}/ai/models` | 频道可选模型（仅启用项：`id`/`name`/`model`/`visionEnabled`/`reasoningEnabled`/`isDefault`） | `channel.ai.manage` |
| GET / PUT | `/channels/{id}/ai/settings` | 助手设置（账号/任务开关/频率/`modelConfigId`） | `channel.ai.manage` |
| POST | `/channels/{id}/ai/assistant-account` | 创建/修复助手虚拟账号 | `channel.ai.manage` |
| GET | `/channels/{id}/ai/task-logs` | 任务执行日志（page/size） | `channel.ai.manage` |
| POST | `/channels/{id}/ai/tasks/run` | 手动触发任务（幂等 + 频率上限） | `channel.ai.manage` |

### 2.12 平台管理后台

权限列使用平台权限点（`docs/11 §7.1`）：`CONFIG_READ` / `CONFIG_WRITE` / `ADMIN_MANAGE` / `AUDIT_READ` /
`USER_READ` / `USER_BAN` / `USER_PENALTY` / `CONTENT_READ` / `CONTENT_TAKEDOWN` / `REPORT_READ` /
`REPORT_HANDLE` / `AI_MANAGE` / `ANNOUNCEMENT_MANAGE` / `WORD_MANAGE` / `STATS_READ`。
权限由 `PlatformPermissionInterceptor` 按接口注解统一校验；未标注的后台接口至少要求任意平台角色。

**公开配置**

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/platform/config` | 公开配置子集（站点信息、注册开关等），仅 `publicVisible` 项 | 游客 |

**配置中心**

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/admin/config` | 配置项列表（分组 + 元数据 + 当前值 + 是否覆盖） | `CONFIG_READ` |
| PUT | `/admin/config/{key}` | 单键更新（写历史 + 审计） | `CONFIG_WRITE` |
| PUT | `/admin/config` | 批量更新（先整体校验，单事务） | `CONFIG_WRITE` |
| POST | `/admin/config/{key}/reset` | 恢复默认（删除覆盖行，保留历史） | `CONFIG_WRITE` |
| GET | `/admin/config/history` | 变更历史（`key`/`page`/`size`） | `CONFIG_READ` |

**管理员与审计**

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/admin/admins` | 管理员列表（授权 + 引导超管） | `ADMIN_MANAGE` |
| POST | `/admin/admins` | 授权（`userId`/`role`/`expiresAt`） | `ADMIN_MANAGE` |
| PUT | `/admin/admins/{userId}` | 修改角色 | `ADMIN_MANAGE` |
| DELETE | `/admin/admins/{userId}` | 撤销（不能撤销自己/最后一个超管） | `ADMIN_MANAGE` |
| GET | `/admin/audit-logs` | 平台审计（只读，`actorId`/`action`/`targetType`/`targetId`/`from`/`to`） | `AUDIT_READ` |

**数据与治理**

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/admin/me` | 平台身份 + 权限点列表（任意登录用户，前端据此显示菜单） | 登录 |
| GET | `/admin/overview` | 平台数据概览 | `STATS_READ` |
| GET | `/admin/users` | 用户列表（page/size） | `USER_READ` |
| POST | `/admin/users/{id}/ban` / `unban` | 禁用 / 恢复用户（走处罚记录，不能禁用自己） | `USER_BAN` |
| GET | `/admin/channels` | 频道列表（page/size） | `CONTENT_READ` |
| POST | `/admin/channels/{id}/ban` / `unban` | 封禁 / 解封频道 | `CONTENT_TAKEDOWN` |
| GET | `/admin/posts` | 全站帖子检索（`keyword`/`channelId`/`authorId`/`status`） | `CONTENT_READ` |
| POST | `/admin/posts/{id}/takedown` | 下架（body 必填 `reason`） | `CONTENT_TAKEDOWN` |
| POST | `/admin/posts/{id}/restore` | 恢复展示 | `CONTENT_TAKEDOWN` |
| GET | `/admin/comments` | 全站评论检索 | `CONTENT_READ` |
| POST | `/admin/comments/{id}/takedown` / `restore` | 评论下架 / 恢复 | `CONTENT_TAKEDOWN` |
| GET | `/admin/reports` | 举报列表（`status`/`targetType`/`keyword`） | `REPORT_READ` |
| POST | `/admin/reports/{id}/handle` | 处置：`action=DISMISS|RESOLVE`，可选 `handleAction=NONE|DELETE_CONTENT|MUTE_AUTHOR|WARN` | `REPORT_HANDLE` |
| GET | `/admin/users/{id}/penalties` | 用户处罚历史 | `USER_READ` |
| POST | `/admin/users/{id}/penalties` | 处罚（`BAN`/`MUTE`/`WARN`，`durationHours` 空=永久） | `USER_PENALTY` |
| POST | `/admin/penalties/{id}/revoke` | 撤销处罚 | `USER_PENALTY` |

**运营与数据**

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/platform/announcements` | 生效中的公告（公开，最多 5 条） | 游客 |
| GET | `/admin/announcements` | 公告列表（`status`/`page`/`size`） | `CONFIG_READ` |
| POST | `/admin/announcements` | 新建公告（草稿） | `ANNOUNCEMENT_MANAGE` |
| PUT | `/admin/announcements/{id}` | 编辑公告 | `ANNOUNCEMENT_MANAGE` |
| POST | `/admin/announcements/{id}/publish` | 发布（未排期则立即生效）；`placement=INBOX` 时向全站活跃用户分批写系统通知 | `ANNOUNCEMENT_MANAGE` |
| POST | `/admin/announcements/{id}/archive` | 归档（已发布需先归档才能删除） | `ANNOUNCEMENT_MANAGE` |
| DELETE | `/admin/announcements/{id}` | 删除（仅草稿/归档） | `ANNOUNCEMENT_MANAGE` |
| GET | `/admin/sensitive-words` | 词库列表（`keyword`/`page`/`size`） | `CONFIG_READ` |
| POST | `/admin/sensitive-words` | 新建词条（重复返回 409） | `WORD_MANAGE` |
| PUT / DELETE | `/admin/sensitive-words/{id}` | 编辑 / 删除词条 | `WORD_MANAGE` |
| POST | `/admin/sensitive-words/import` | 批量导入（一行一词，单次 ≤ 500） | `WORD_MANAGE` |
| POST | `/admin/sensitive-words/scan` | 试算命中词（不落库） | `WORD_MANAGE` |
| GET | `/admin/sensitive-words/hits` | 命中记录（`scene`/`word`/`page`/`size`） | `CONFIG_READ` |
| GET | `/admin/stats/trend` | 近 N 天趋势（默认 30，上限 90，缺失日期补零） | `STATS_READ` |
| GET | `/admin/hot-keywords` | 热词列表（按搜索次数倒序） | `STATS_READ` |
| DELETE | `/admin/hot-keywords/{id}` | 下线热词（清理刷量词） | `CONFIG_WRITE` |

> 敏感词处理方式由配置项 `content.sensitiveWord.mode` 决定（默认 `REVIEW`）：
> `REVIEW` 只记录命中、`MASK` 把命中词替换为 `***` 后入库、`BLOCK` 直接 422 拒绝发布。
> 命中记录 `sensitive_word_hits.action` 保存**本次实际处理方式**（便于运营核对），词条自身的 `action` 仅作分类标记。

**AI 配置**

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/admin/ai/models` | 模型配置列表 | `AI_MANAGE` |
| POST | `/admin/ai/models` | 新建模型配置（API Key 加密落库） | `AI_MANAGE` |
| PUT / DELETE | `/admin/ai/models/{id}` | 更新 / 删除 | `AI_MANAGE` |
| POST | `/admin/ai/models/{id}/test` | 连通性测试（已保存配置） | `AI_MANAGE` |
| POST | `/admin/ai/test-connection` | 连通性测试（未保存配置） | `AI_MANAGE` |
| GET | `/admin/ai/models/{id}/remote-models` | 拉取远端模型列表 | `AI_MANAGE` |
| GET | `/admin/ai/status` | AI 配置状态 | `AI_MANAGE` |

> 举报状态已统一为 `PENDING` / `ACCEPTED` / `REJECTED`（与频道侧一致）；`available`/`dependency`
> 字段保留为兼容字段，恒为 `true`/`null`。

### 2.14 频道应用 / 语音房 / 频道活动（V10）

**频道应用**（频道页入口卡片；读：频道可见，写：`channel.settings`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/channels/{id}/apps` | 应用列表（默认只返回启用项；`includeDisabled=true` 需管理权限） |
| POST | `/channels/{id}/apps` | 新建（单频道最多 20 个） |
| PUT | `/channels/{id}/apps/{appId}` | 编辑 |
| DELETE | `/channels/{id}/apps/{appId}` | 删除 |
| PUT | `/channels/{id}/apps/order` | 批量重排（body `{"appIds":[...]}`，按数组顺序写 0..n-1） |

**语音房**（开房/上麦要求频道成员；状态变更广播 `/topic/voice/{roomId}/state`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/channels/{id}/voice-rooms` | 频道内开启中的房间 |
| POST | `/channels/{id}/voice-rooms` | 开房（同一频道一人只能有一个开启中的房间） |
| GET | `/voice-rooms/{roomId}` | 房间详情（含麦位） |
| POST | `/voice-rooms/{roomId}/join` | 进房（登记「在房」，信令鉴权依赖它；听众不落库） |
| DELETE | `/voice-rooms/{roomId}/join` | 退房（清理在房登记） |
| POST | `/voice-rooms/{roomId}/seats` | 上麦（body `{"seatIndex":0}`；抢座冲突 409 `VOICE_SEAT_OCCUPIED`） |
| DELETE | `/voice-rooms/{roomId}/seats/me` | 下麦 |
| POST | `/voice-rooms/{roomId}/seats/{seatIndex}/mute` | 静音/取消（body `{"muted":true}`；房主可操作任何人，其他人只能操作自己） |
| POST | `/voice-rooms/{roomId}/close` | 关房（房主或 `channel.settings`；清空麦位并广播 `ROOM_CLOSED`） |

**STOMP 信令**（连接 `/ws`，CONNECT 帧带 JWT）

> 目的地校验：`/topic/voice/{roomId}/signal|state` 的**订阅**与 `/app/voice/{roomId}/signal` 的**发送**
> 都要求「当前连接的主人在该房间内」（房间 `OPEN` + 在麦位上或调用过 `POST /voice-rooms/{id}/join`）。
> 不满足时回 ERROR 帧，`code` 头为 `NOT_IN_ROOM` 或 `ROOM_CLOSED`（`message` 为中文提示）。
> 听众要在房登记：进房后调用 join 接口，退房可调用 `DELETE /voice-rooms/{id}/join`（清理登记）。


| 方向 | 目的地 | 说明 |
| --- | --- | --- |
| 上行 | `/app/voice/{roomId}/signal` | WebRTC offer/answer/candidate 透传（服务端不解析 SDP） |
| 下行 | `/topic/voice/{roomId}/signal` | `{roomId, from, data}`；仅转发给房间所在频道的成员 |
| 下行 | `/topic/voice/{roomId}/state` | 状态事件：`ROOM_OPENED` / `SEAT_TAKEN` / `SEAT_LEFT` / `SEAT_MUTED` / `SEAT_UNMUTED` / `ROOM_CLOSED` / `SIGNAL_RELAY` |

**频道活动**（浏览：频道可见；管理：`channel.settings`；报名：频道成员）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/channels/{id}/activities` | 活动列表（`status`/`page`/`size`；非管理员强制只看 `PUBLISHED`） |
| POST | `/channels/{id}/activities` | 新建（草稿） |
| GET | `/activities/{activityId}` | 详情（含报名人数与本人是否已报名） |
| PUT | `/activities/{activityId}` | 编辑（已取消不可编辑） |
| DELETE | `/activities/{activityId}` | 删除（已发布需先取消） |
| POST | `/activities/{activityId}/publish` | 发布 |
| POST | `/activities/{activityId}/cancel` | 取消 |
| POST | `/activities/{activityId}/signup` | 报名（幂等；满员 422 `ACTIVITY_FULL`） |
| DELETE | `/activities/{activityId}/signup` | 取消报名 |
| GET | `/activities/{activityId}/signups` | 报名名单（管理端） |

> 活动开始前 **30 分钟**由定时任务（每 10 分钟扫描）给报名者发站内通知；
> 幂等依赖 `channel_activities.reminder_sent_at`（先抢占再发送，多实例不会重复）。

### 2.15 AI 用户代理（V13，平台后台）

权限点 `AGENT_MANAGE`（超管与运营默认拥有）。路径中的 `{id}` 是**代理账号 id**（`users.id`）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/admin/agents` | 代理列表（人设摘要、今日发帖/评论数、最近运行状态） |
| GET | `/admin/agents/switches` | 平台级开关：`enabled`（kill switch）、`dailyPostLimit`、`dailyTokenBudget` |
| GET | `/admin/agents/{id}` | 单个代理详情 |
| POST | `/admin/agents` | 创建代理（同时创建 `is_agent=1` 账号；`username` 可空，自动 `agent_xxxxxxxx`） |
| PUT | `/admin/agents/{id}` | 修改人设（兴趣/语气/活跃时段/日上限/频道/工具） |
| DELETE | `/admin/agents/{id}` | 删除代理（人设删除、账号置 `DISABLED`、内容保留） |
| POST | `/admin/agents/{id}/pause` | 暂停（调度器不再挑选） |
| POST | `/admin/agents/{id}/resume` | 恢复 |
| POST | `/admin/agents/{id}/run-now` | 手动触发一次（测试必需；忽略活跃时段与概率，其余闸门照旧） |
| GET | `/admin/agents/{id}/runs` | 运行日志分页（`page`/`size`，可回放 `planJson` / `toolCallsJson`） |

**请求体（创建/修改共用）**

```json
{
  "username": "agent_blue01",
  "displayName": "岛民小蓝",
  "bio": "喜欢露营与潜水",
  "interests": ["露营", "潜水"],
  "tone": "casual",
  "activeHours": [[20, 24], [0, 1]],
  "postsPerDay": 2,
  "commentsPerDay": 8,
  "channels": ["01J..."],
  "toolsEnabled": ["search_site_posts", "create_post", "comment", "like", "remember", "recall"],
  "status": "ACTIVE"
}
```

**`run-now` 响应**

```json
{ "status": "SUCCESS", "reason": null, "run": { "id": "...", "status": "SUCCESS", "outputType": "POST", "outputId": "...", "tokensIn": 420, "tokensOut": 180 } }
```

> 状态取值：`SUCCESS` / `GUARDED`（限额、白名单、敏感词拦截）/ `FAILED`（模型或工具异常）/ `SKIPPED`
> （kill switch、token 预算用尽、**没有可用模型**）。没有模型时只记 `SKIPPED`，不抛异常。
> 内容标记：代理产出的帖子/评论 `is_ai_generated=1`，账号 `is_agent=1`。

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

event: sources
data: {"sources":[{"id":"...","title":"...","channelId":"...","channelName":"...","excerpt":"...","url":"/posts/..."}],"toolRounds":1}

event: done
data: {"messageId":"...","aborted":false,"status":"COMPLETED","tokenIn":120,"tokenOut":340}
```

### 3.5 限流响应

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 37

{ "error": { "code": "RATE_LIMITED", "message": "操作过于频繁（LOGIN：10 次/60 秒），请 37 秒后重试", "details": [], "requestId": "req_..." } }
```

### 3.6 AI 工具调用契约（function calling）

> 仅当 `ai_model_configs.tool_call_enabled=1`（默认 0）时启用；未开启、模型报错或不支持时**自动降级**为原有
> 关键词检索，对话功能不受影响（降级写日志，任务状态照常落库）。

**工具清单**（只读，沿用检索可见性：公开可见频道的已发布帖子；频道会话可读本频道）

| 工具 | 参数 | 说明 |
| --- | --- | --- |
| `search_channel_posts` | `query`（string，必填）、`limit`（int，1–8，默认 5） | 频道会话专属，检索本频道帖子 |
| `search_global_posts` | `query`、`limit` | 全站公开频道检索 |
| `get_post_detail` | `postId`（string，必填） | 读取帖子正文（≤800 字摘要） |

**请求侧**（服务端 → 模型，OpenAI 兼容）

```json
{
  "model": "qwen2.5-7b",
  "messages": [{"role": "user", "content": "岛上有露营攻略吗"}],
  "tools": [{
    "type": "function",
    "function": {
      "name": "search_channel_posts",
      "description": "在当前频道内按关键词检索已发布的帖子",
      "parameters": {"type": "object", "properties": {"query": {"type": "string"}, "limit": {"type": "integer"}}, "required": ["query"]}
    }
  }],
  "tool_choice": "auto"
}
```

**响应侧**：模型要工具时返回 `tool_calls`（流式下按 `index` 分片下发 `id` / `function.name` / `function.arguments`，
服务端按 index 累积拼完整后才执行）。回填消息格式：

```json
{"role": "assistant", "content": null,
 "tool_calls": [{"id": "call_1", "type": "function", "function": {"name": "search_channel_posts", "arguments": "{\"query\":\"露营\"}"}}]}
{"role": "tool", "tool_call_id": "call_1", "content": "{\"total\":1,\"results\":[{\"postId\":\"...\",\"title\":\"...\"}]}"}
```

**约束**

- 最多 **2 轮**工具调用；第 3 次请求不再下发 `tools`，保证收敛到自然语言回答。
- 工具轮次计入回答总超时（每轮按剩余时间下发 `timeoutSeconds`）；用户点「停止」立即中断，未执行的工具不再执行。
- 工具执行失败/参数非法/帖子不可见都返回结构化 `{"error":...,"message":...}` 给模型，不打断对话。
- **来源引用**由工具实际命中的帖子生成（去重），不再预置上下文；`meta.sources` 先给关键词兜底结果，
  工具跑完后再下发 `event: sources` 覆盖（并落库为最终引用）。未命中时沿用关键词结果，引用不会为空。
- `event: sources` **无条件发送**（工具模式与非工具模式都发；0 命中时 `sources: []`）。
  前端应把它当作「引用可展示」的唯一信号，不要依赖 `meta.sources`。

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
| `PostResponse` | `id`、`channelId`、`channelName`、`channelAvatarUrl`、`boardId`、`boardName`、`authorId`、`authorNickname`、`authorAvatarUrl`、`title`、`content`、`contentFormat`、`coverUrl`、`media[]`、`viewCount`、`likeCount`、`commentCount`、`shareCount`、`liked`、`pinned`、`essence`、`status`、`createdAt`、`topComment` |
| `TopCommentResponse` | `id`、`postId`、`authorId`、`authorNickname`、`authorAvatarUrl`、`content`、`likeCount`、`liked`、`createdAt`；帖子列表每帖返回 1 条最高赞评论（无评论为 `null`），批量查询不产生 N+1 |
| `CommentResponse` | `id`、`postId`、`parentId`、`authorId`、`authorNickname`、`authorAvatarUrl`、`replyToUserId`、`replyToNickname`、`content`、`media[]`、`emoji[]`、`likeCount`、`liked`、`createdAt`、`replies[]` |
| `SearchItemResponse` | `type`、`id`、`title`、`summary`、`channelId`、`channelName`、`channelAvatarUrl`、`avatarUrl`、`memberCount`、`likeCount`、`commentCount`、作者与板块字段、`createdAt`、`coverUrl`、`media[]` |
| ↳ `coverUrl` / `media` | 帖子结果带封面与**首图**（`post_media` 中 `sort` 最小的一张，`media` 最多 1 项）；频道结果 `coverUrl` 为频道封面、`media` 为空。批量查询，不产生 N+1 |
| `MemberResponse` | `userId`、`username`、`nickname`、`avatarUrl`、`roleId`、`roleName`、`status`、`mutedUntil`、`muted`、`joinedAt` |
| `RoleResponse` | `id`、`channelId`、`name`、`description`、`permissions[]`、`scopeBoardId`、`scopeBoardName`、`system`、`sort`、`memberCount` |
| `ReportResponse` | `id`、`reporterId`、`reporterNickname`、`channelId`、`targetType`、`targetId`、`targetPreview`、`reason`、`status`、`handleAction`、`handleNote`、`handledAt`、`createdAt` |
| `AuditLogResponse` | `id`、`channelId`、`actorId`、`actorNickname`、`action`、`targetType`、`targetId`、`detail`、`ip`、`createdAt` |
| `StatsOverviewResponse` | `channelId`、`memberCount`、`postCount`、`yesterday`、`memberDelta`、`postDelta` |
| `AgentView` | `id`、`userId`、`username`、`displayName`、`bio`、`interests[]`、`tone`、`activeHours[][]`、`postsPerDay`、`commentsPerDay`、`channels[]`、`toolsEnabled[]`、`status`、`lastRunAt`、`todayPosts`、`todayComments`、`lastRunStatus` |
| `RunView` | `id`、`startedAt`、`finishedAt`、`outputType`（`POST`/`COMMENT`/`LIKE`/`NONE`）、`outputId`、`status`、`error`、`tokensIn`、`tokensOut`、`planJson`、`toolCallsJson` |
| `AppView` | `id`、`channelId`、`appType`（`VOICE_ROOM`/`LIVE_ROOM`/`CUSTOM`）、`name`、`iconUrl`、`description`、`sortOrder`、`enabled`、`createdAt` |
| `RoomView` | `id`、`channelId`、`appId`、`hostId`、`hostNickname`、`title`、`status`（`OPEN`/`CLOSED`）、`maxSeats`、`seats[]`（`seatIndex`/`userId`/`nickname`/`avatarUrl`/`muted`/`joinedAt`）、`createdAt`、`closedAt` |
| `ActivityView` | `id`、`channelId`、`title`、`description`、`startAt`、`endAt`、`location`、`capacity`、`status`（`DRAFT`/`PUBLISHED`/`CANCELLED`）、`goingCount`、`signedUp`、`createdAt` |
| `TaskLogResponse` | `id`、`channelId`、`taskType`、`frequencyKey`、`status`、`resultText`、`postId`、`commentId`、`errorMessage`、`startedAt`、`finishedAt`、`createdAt`、`modelConfigId`、`model`（后两个为本次任务实际使用的模型；未配置模型时为 `null`，配置被删除后 `modelConfigId` 为 `null` 但 `model` 保留快照） |
| `SelectableModelResponse` | `id`、`name`、`model`、`visionEnabled`、`reasoningEnabled`、`isDefault`（频道可选模型，不含 Base URL/密钥） |
| `AssistantSettingsResponse` | 原有字段 + `modelConfigId`（空=跟随平台默认）；`updatedAt` 之后追加，前端按字段名读取 |
| `AssistantSettingsResponse` | `channelId`、`assistantUserId`、`nickname`、`avatarUrl`、`coverUrl`、`bio`、`persona`、`enabled`、六个任务开关、`dailySummaryHour`、三个每日频率 |
| `AgentView` | `id`、`userId`、`username`、`displayName`、`bio`、`interests[]`、`tone`、`activeHours[][]`、`postsPerDay`、`commentsPerDay`、`channels[]`、`toolsEnabled[]`、`status`、`lastRunAt`、`todayPosts`、`todayComments`、`lastRunStatus` |
| `RunView` | `id`、`startedAt`、`finishedAt`、`outputType`（`POST`/`COMMENT`/`LIKE`/`NONE`）、`outputId`、`status`、`error`、`tokensIn`、`tokensOut`、`planJson`、`toolCallsJson` |
| `AppView` | `id`、`channelId`、`appType`（`VOICE_ROOM`/`LIVE_ROOM`/`CUSTOM`）、`name`、`iconUrl`、`description`、`sortOrder`、`enabled`、`createdAt` |
| `RoomView` | `id`、`channelId`、`appId`、`hostId`、`hostNickname`、`title`、`status`（`OPEN`/`CLOSED`）、`maxSeats`、`seats[]`（`seatIndex`/`userId`/`nickname`/`avatarUrl`/`muted`/`joinedAt`）、`createdAt`、`closedAt` |
| `ActivityView` | `id`、`channelId`、`title`、`description`、`startAt`、`endAt`、`location`、`capacity`、`status`（`DRAFT`/`PUBLISHED`/`CANCELLED`）、`goingCount`、`signedUp`、`createdAt` |
| `TaskLogResponse` | `id`、`channelId`、`taskType`、`frequencyKey`、`status`、`resultText`、`postId`、`commentId`、`errorMessage`、`startedAt`、`finishedAt`、`createdAt` |
| `PageResult<T>` | `items`、`nextCursor` |
| `OffsetPage<T>` | `items`、`total` |

## 5. 待补充

- 契约测试：用 OpenAPI 校验实际响应字段（防止 DTO 漂移）
- 幂等键 `Idempotency-Key`：发帖/评论的重复提交保护（当前依赖 ULID + 唯一约束兜底）

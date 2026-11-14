---
id: API-05
title: API 设计
version: 0.1.0
status: draft
owner: lead
last_updated: 2026-11-14
reviewers: []
related: [REQ-03, ARC-02, DB-06, ADR-0005]
---

# API 设计

> 状态：**M1 契约冻结中**。接口变更必须先改本文与 OpenAPI 契约，再改代码（规范 §7）。
> 实现阶段逐步补充 OpenAPI 文件（`backend/src/main/resources/openapi/`）与示例。

## 1. 全局约定

| 项 | 约定 |
| --- | --- |
| Base URL | `/api/v1` |
| 认证 | `Authorization: Bearer <accessToken>`；刷新令牌走 HttpOnly Cookie `refresh_token` |
| 内容类型 | `application/json`；上传使用 `multipart/form-data` |
| 时间 | ISO 8601 UTC，如 `2026-11-14T01:15:37Z` |
| ID | ULID 字符串（26 位，不可猜测、可排序）；频道对外另有数字 `channelNo` |
| 请求追踪 | 响应头 `X-Request-Id`；错误体 `requestId` |
| 游客 | 标 🔓 的接口允许匿名访问；其余需登录 |
| 分页 | 游标：`?cursor=&limit=`（默认 20，上限 100），返回 `nextCursor`；管理列表用 `?page=&size=` |
| 幂等 | 创建类接口支持 `Idempotency-Key` 头（M2 起强制用于发帖/评论） |

### 1.1 成功响应

- 单资源：直接返回资源 JSON。
- 列表：`{ "items": [...], "nextCursor": "ulid|null" }` 或 `{ "items": [...], "total": 123 }`。

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
| 401 | `UNAUTHORIZED` / `TOKEN_EXPIRED` | 未登录或令牌过期 |
| 403 | `FORBIDDEN` / `CHANNEL_MUTED` | 无权限、被禁言 |
| 404 | `CHANNEL_NOT_FOUND` / `POST_NOT_FOUND` | 不存在或不可见 |
| 409 | `USERNAME_TAKEN` / `ALREADY_JOINED` | 冲突 |
| 422 | `BUSINESS_RULE_VIOLATION` | 业务规则不满足 |
| 429 | `RATE_LIMITED` | 限流 |
| 500 | `INTERNAL_ERROR` | 服务端异常 |

## 2. 接口清单（M1）

### 2.1 认证 / 用户

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | `/auth/register` | 注册：`{username, password, nickname?}` | 🔓 |
| POST | `/auth/login` | 登录：返回 accessToken + Set-Cookie refreshToken | 🔓 |
| POST | `/auth/refresh` | 刷新 accessToken（读取 Cookie） | 🔓 |
| POST | `/auth/logout` | 登出并撤销 refreshToken | 登录 |
| GET | `/auth/me` | 当前用户信息 | 登录 |
| GET | `/users/{id}` | 用户公开资料 | 🔓 |
| GET | `/users/{id}/card` | 用户卡片（可带 `?channelId=` 返回加入时间/身份组） | 🔓 |
| PATCH | `/users/me` | 修改头像/昵称/签名/性别/地区 | 登录 |

### 2.2 频道 / 板块 / 成员

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | `/channels` | 创建频道：`{name, avatarUrl, coverUrl?, description?, type, visibility?}`；自动生成默认板块 | 登录 |
| GET | `/channels/{id}` | 频道详情（按可见性校验） | 🔓 |
| GET | `/channels/mine` | 我加入的频道列表 | 登录 |
| POST | `/channels/{id}/join` | 加入公开频道 | 登录 |
| POST | `/channels/{id}/leave` | 退出频道（M2） | 登录 |
| GET | `/channels/{id}/members` | 成员列表（`keyword`、分页；M2） | 成员 |
| POST | `/channels/{id}/invites` | 创建邀请链接：`{requiresApproval, expiresInSeconds?, maxUses?}` | 管理员 |
| POST | `/invites/{token}/accept` | 使用邀请链接加入 / 提交加入申请 | 登录 |
| GET | `/channels/{id}/boards` | 板块列表 | 🔓 |
| GET | `/channels/{id}/posts` | 频道帖子列表：`tab=latest|hot|essence`、`boardId`、`cursor` | 🔓 |

### 2.3 帖子 / 评论 / 互动

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | `/channels/{id}/posts` | 发帖：`{boardId, title?, content, contentFormat, media[], coverUrl?}` | 成员（受禁言限制） |
| GET | `/posts/{id}` | 帖子详情（浏览量 +1） | 🔓 |
| POST | `/posts/{id}/like` / `DELETE` | 点赞 / 取消 | 登录 |
| POST | `/channels/{id}/posts/{postId}/pin` 等管理操作 | 置顶/精华/删除（M2） | 管理员 |
| GET | `/posts/{id}/comments` | 评论列表 `sort=likes|latest`、`cursor` | 🔓 |
| POST | `/posts/{id}/comments` | 评论：`{content, parentId?, replyToUserId?, media[], emoji?}` | 成员 |
| POST | `/comments/{id}/like` / `DELETE` | 评论点赞 | 登录 |
| POST | `/posts/{id}/share` | 分享计数（复制链接/分享给好友） | 登录 |

### 2.4 发现 / 动态

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/feed` | 频道动态：`sort=latest|hot`、`cursor` | 登录 |
| GET | `/hot` | 全站热门讨论（M2） | 🔓 |
| GET | `/hot/recommendations` | 频道推荐（M3） | 登录 |

### 2.5 搜索

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/search` | 全站搜索：`q`、`type=all|channel|post`、`sort`、分页 | 🔓 |
| GET | `/search/suggest` | 联想：已加入频道 + 热词（M1 返回已加入频道 + 基础匹配；热词 M2） | 登录 |
| GET | `/search/history` | 搜索历史 | 登录 |
| DELETE | `/search/history` | 清空历史 | 登录 |
| DELETE | `/search/history/{id}` | 删除单条 | 登录 |

### 2.6 媒体

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | `/media/upload` | 上传图片/视频（multipart `file`、`type=image|video`），返回 `{url, thumbUrl, mediaType, width, height, durationMs}` | 登录 |
| DELETE | `/media/{id}` | 删除未使用媒体（M2） | 本人 |

### 2.7 通知（M1 基础）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/notifications` | 通知列表：`channelId`、`type`、`unreadOnly`、分页 | 登录 |
| POST | `/notifications/{id}/read` | 标记已读 | 登录 |
| POST | `/notifications/read-all` | 全部已读 | 登录 |

### 2.8 健康检查

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/actuator/health` | 健康检查（部署用） |

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

## 4. 待细化（随实现补充）

- 置顶/精华/删除/内容管理接口（M2）
- 身份组与权限管理接口（M2）
- 私信、好友、通知实时通道（M2，STOMP 主题）
- AI 会话与配置接口（M3）
- 数据中心与统计接口（M2/M3）
- OpenAPI 自动生成与契约测试

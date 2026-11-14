# 群岛（Isles）后端

社交频道平台后端（模块化单体），实现 M1 核心闭环。接口契约见 [`docs/05-API设计.md`](../docs/05-API设计.md)，
表结构见 [`docs/06-数据库设计.md`](../docs/06-数据库设计.md)，安全规则见 [`docs/07-安全与合规.md`](../docs/07-安全与合规.md)。

## 技术栈

| 项 | 选型 |
| --- | --- |
| 语言 / 运行时 | Java 17（`JAVA_HOME` 指向 JDK 17） |
| 框架 | Spring Boot 4.1.2（Web / Security / Validation / WebSocket / Actuator） |
| 数据访问 | MyBatis-Plus 3.5.18（`mybatis-plus-spring-boot4-starter`）+ MySQL 8（`mysql-connector-j`） |
| 迁移 | Flyway 12（`db/migration/V1__init.sql`） |
| 认证 | Spring Security + Nimbus JWT（`spring-security-oauth2-jose`，未引入 jjwt） |
| 富文本净化 | Jsoup（服务端白名单，docs/07 §4） |
| 构建 | Maven 3.9.4，单模块 |

## 目录结构（package-by-feature）

```
src/main/java/com/isles/
├── IslesApplication.java
├── common/        # 统一错误信封、错误码、分页游标、ULID、RequestId、当前用户、JSON/媒体工具
├── config/        # Security（JWT/游客只读/CORS）、静态上传资源
├── auth/          # 注册/登录/刷新/登出/me、JWT 过滤器
├── user/          # 公开资料、用户卡片、资料修改
├── channel/       # 频道、成员、身份组、板块、邀请、ChannelAccessService（权限唯一入口）
├── post/          # 发帖、频道帖子流、详情、互动接口
├── interaction/   # 点赞/浏览/分享（统一计数与通知）
├── comment/       # 评论列表/发表/回复/点赞
├── feed/          # 已加入频道动态
├── search/        # 全站搜索、历史、联想（MySQL LIKE 基础版）
├── notification/  # 通知列表与已读（M1 基础）
└── media/         # 本地上传、类型/大小校验、图片缩略图
```

`src/main/resources/db/migration/V1__init.sql` 为 M1 全部 17 张表（ULID CHAR(26)、utf8mb4、
显式命名外键/唯一约束/索引）。Flyway 随应用启动执行，**不单独手工执行**。

## 构建与测试

```powershell
# 工作目录：backend/
mvn -B test                    # 单元测试（纯 Mockito，不加载 Spring、不依赖 MySQL）
mvn -B -DskipTests package     # 打包 target/isles-backend-0.1.0-SNAPSHOT.jar
```

> 本仓库开发环境验证：`mvn -B test` 33 个用例全绿；`mvn -B -DskipTests package` 成功。
> 本次交付**未启动应用**、**未连接数据库**（本机 MySQL 凭据未就绪），启动联调需先准备数据库。

## 本地运行前置

1. JDK 17：`JAVA_HOME` 指向 JDK 17（Maven 使用 `JAVA_HOME`，与 PATH 中的 java 版本无关）。
2. MySQL 8.0：创建数据库与专用账号：

   ```sql
   CREATE DATABASE my_social_channel CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
   -- 建专用账号并授予该库权限（示例）
   CREATE USER 'isles'@'localhost' IDENTIFIED BY 'change-me';
   GRANT ALL PRIVILEGES ON my_social_channel.* TO 'isles'@'localhost';
   ```

3. 配置环境变量（参考 [`.env.example`](.env.example)），至少需要 `DB_USERNAME`、`DB_PASSWORD`、
   `JWT_SECRET`；本地可直接使用 `application-local.yml` 中的开发默认值。
4. 启动：`mvn spring-boot:run`（默认 `local` profile，端口 8080）。Flyway 自动建表；
   健康检查 `GET /actuator/health`。

## 配置项清单

| 配置键 | 环境变量 | 默认值（local） | 说明 |
| --- | --- | --- | --- |
| `server.port` | `SERVER_PORT` | `8080` | 服务端口 |
| `spring.datasource.url` | `DB_URL` | `jdbc:mysql://localhost:3306/my_social_channel?...` | JDBC URL（UTC） |
| `spring.datasource.username` | `DB_USERNAME` | `root` | 数据库账号 |
| `spring.datasource.password` | `DB_PASSWORD` | 空 | 数据库密码 |
| `app.jwt.secret` | `JWT_SECRET` | 本地开发默认值 | HS256 密钥，≥32 字节，生产必填且每环境不同 |
| `app.jwt.access-token-minutes` | — | `15` | access token 有效期 |
| `app.auth.refresh-token-days` | — | `7` | refresh token 有效期 |
| `app.auth.cookie-secure` | `COOKIE_SECURE` | `false` | 生产必须 `true`（HTTPS） |
| `app.auth.cookie-same-site` | — | `Lax` | refresh cookie SameSite |
| `app.upload-dir` | `UPLOAD_DIR` | `./uploads` | 上传根目录（`/uploads/**` 静态只读） |
| `app.cors.allowed-origins` | `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | CORS 白名单，逗号分隔 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | 同名 | `localhost` / `6379` / 空 | M1 未使用，预留 M2 |

## M1 已实现接口（与 docs/05 对齐）

- **认证**：`POST /api/v1/auth/register|login|refresh|logout`、`GET /api/v1/auth/me`
  （access token 15min Bearer，refresh token 7 天 HttpOnly Cookie，刷新轮换 + 撤销）
- **用户**：`GET /api/v1/users/{id}`、`GET /api/v1/users/{id}/card?channelId=`、`PATCH /api/v1/users/me`
- **频道**：`POST /api/v1/channels`（事务内生成默认板块 + 四个系统身份组 + 频道主成员 + 频道号）、
  `GET /api/v1/channels/{id}`、`GET /api/v1/channels/mine`、`POST /api/v1/channels/{id}/join`、
  `GET /api/v1/channels/{id}/boards`、`POST /api/v1/channels/{id}/invites`、`POST /api/v1/invites/{token}/accept`
- **帖子**：`POST/GET /api/v1/channels/{id}/posts`（`tab=latest|hot|essence`、`boardId`、`cursor`）、
  `GET /api/v1/posts/{id}`（浏览 +1）、`POST|DELETE /api/v1/posts/{id}/like`、`POST /api/v1/posts/{id}/share`
- **评论**：`GET /api/v1/posts/{id}/comments?sort=likes|latest&cursor=`、
  `POST /api/v1/posts/{id}/comments`（顶级/回复）、`POST|DELETE /api/v1/comments/{id}/like`
- **动态**：`GET /api/v1/feed?sort=latest|hot&cursor=`
- **搜索**：`GET /api/v1/search?q=&type=all|channel|post&cursor=`、
  `GET /api/v1/search/suggest`、`GET|DELETE /api/v1/search/history`、`DELETE /api/v1/search/history/{id}`
- **媒体**：`POST /api/v1/media/upload`（multipart `file` + `type=image|video`）
- **通知**：`GET /api/v1/notifications`、`POST /api/v1/notifications/{id}/read`、`POST /api/v1/notifications/read-all`
- **健康**：`GET /actuator/health`
- **公共约定**：错误信封 `{error:{code,message,details,requestId}}`；响应头 `X-Request-Id`；
  列表 `{items,nextCursor}`，`limit` 默认 20 上限 100；时间 ISO 8601 UTC。

权限边界：游客可读公开频道/帖子/评论/搜索结果；写接口需登录；频道内互动由
`ChannelAccessService` 统一校验可见性、成员身份、禁言与权限点（防 IDOR）。

## 测试覆盖（JUnit 5 + Mockito，不依赖 Spring/DB）

- 注册重复用户名、登录失败/成功、refresh token 轮换撤销
- 频道可见性（公开/仅成员可见）、权限点、禁言
- 创建频道初始化数据、加入私密频道被拒、加入公开频道、邀请审批与过期
- 发帖权限/禁言/板块归属、浏览量 +1
- 评论创建/回复校验/禁言、通知生成
- ULID 生成与校验、Token 哈希

## M1 已知限制（延续到 M2 的部分）

- 限流（登录/注册/发帖等）未实现：按 docs/07 §7 规划优先 Redis，M2 接入。
- 邀请链接「需审批」只创建 `join_requests`，审批接口与通过后的成员加入属 M2。
- 置顶/精华/删除、身份组自定义与分配、退出/解散、频道内搜索、举报、私信等属 M2。
- WebSocket 依赖已引入但 M1 无实时通道（M2 随私信/通知上线）。
- 视频不转码、不生成首帧封面与时长（M1 按 docs/03 FR-MEDIA-02 允许）。
- 搜索为 MySQL `LIKE` 基础检索（按 ULID 倒序），相关度/热词排序在 M2+ 升级。
- 通知仅落库，实时推送 M2。

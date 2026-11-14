# 变更记录

本文件格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

变更分类：`Added` 新增 / `Changed` 变更 / `Deprecated` 废弃 / `Removed` 移除 / `Fixed` 修复 / `Security` 安全。

## [Unreleased]

### Changed

- 交付策略调整：取消 M1/M2/M3 分期，改为**全量交付**（2026-11-14，负责人决定）。
  `docs/03` §10 改为 WP1–WP7 工作包，`docs/10` 改为全量交付计划。

### Added

- 建立工程开发规范（`docs/00-工程开发规范.md`）。
- 建立文档体系与维护规范（`docs/01-文档体系与维护规范.md`）。
- 建立 ADR 机制、文档模板与文档索引（`docs/adr/`、`docs/templates/`、`docs/README.md`）。
- 确认技术栈与架构基线：Spring Boot 4.1.2 + Vue 3 + MySQL 8.0 + Redis 7 + STOMP over WebSocket
  （`docs/02-架构设计.md`、ADR-0002～0004）。
- 完成需求分析与模块排布：角色权限、信息架构与路由、功能清单（FR）、通用组件复用、
  数据模型草案、数据中心设计、分期建议（`docs/03-需求规格.md`、`docs/04-领域模型与词汇表.md`）。
- 确认产品名 **群岛（Isles）**；确认三期交付（M1 核心闭环 → M2 管理与运营 → M3 智能与增长）。
- 确认频道类型与邀请机制（公开 + 私密邀请，邀请链接可审批/可设可见性），见 ADR-0005。
- 确认游客可浏览公开内容、双向好友 + 任何人可私信、板块首期支持、媒体本地存储、AI 需视觉 + 深度思考。
- 新增 M1 设计与契约文档：`docs/05-API设计.md`、`docs/06-数据库设计.md`、`docs/07-安全与合规.md`、`docs/10-路线图与里程碑.md`。
- M1 后端实现：Spring Boot 4.1.2 + Java 17 + MyBatis-Plus + Flyway + Nimbus JWT；认证/用户/频道/板块/邀请/帖子/评论/互动/动态/搜索/媒体/通知接口，
  统一错误信封与 requestId，权限校验集中 `ChannelAccessService`；35 个单元测试通过。
- M1 前端实现：Vue 3 + TypeScript + Vite + Element Plus；侧边栏布局、频道页、帖子详情、动态、搜索、登录注册、我的频道、
  帖子卡片/评论/用户卡片/媒体查看器等复用组件；`pnpm build` 通过。
- 新增前后端适配层 `frontend/src/api/adapters.ts`，收敛后端扁平 DTO 与前端嵌套模型的差异；补充 docs/05 响应模型。
- 修复 Flyway 自动配置（Boot 4 需 `spring-boot-starter-flyway`）、Jackson 3 配置项、搜索 DTO 作者/板块字段。
- 新增 Docker Compose 部署方案（`deploy/`：backend/frontend Dockerfile、nginx.conf、compose、env 模板）
  与部署运维文档 `docs/09-部署与运维.md`（待服务器验证）。
- 新增本地 Mock OpenAI 兼容服务 `tools/mock-openai/server.mjs`，用于 LMStudio 未运行时联调 AI 功能。
- 建立项目入口文件与工程基础配置（`README.md`、`.editorconfig`、`.gitignore`、`.gitattributes`）。

## [0.1.0] - 2026-11-14

### Added

- 项目初始化：规范与文档体系基线（详见 Unreleased 内容，本版本为首次提交）。

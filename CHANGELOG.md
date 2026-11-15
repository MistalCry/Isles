# 变更记录

本文件格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

变更分类：`Added` 新增 / `Changed` 变更 / `Deprecated` 废弃 / `Removed` 移除 / `Fixed` 修复 / `Security` 安全。

## [Unreleased]

### Added

- 频道助手「被动接话」：有人回复助手的评论时，助手异步生成回复（同楼中楼、带 AI 标识），
  受助手总开关 / 自动评论开关 / 每日评论限额约束，`ai_task_logs` 以评论 id 幂等防重复；
  频道助手设置页新增「被动接话」开关，执行日志可查看（FR-ASSIST-05 扩展）。
- AI 模型「思考参数下发方式」（V5）：`reasoning_effort` / `chat_template_kwargs` / 两者都发 / 不发送，
  解决不同后端（OpenAI、LM Studio、vLLM）关闭深度思考的方式不一致；本地模型默认「两者都发」。
- 频道推荐「换一批」：`GET /hot/recommendations?seed=` 在高质量候选窗口内轮换，热门页刷新按钮真正换内容。
- 长文富文本编辑器 `RichTextEditor`、可复用裁剪上传 `ImageCropUpload`、帖子编辑 `PATCH /posts/{id}`、
  「我管理的频道」标识、长文封面（详见 docs/03 FR-POST-02/07、FR-MYCH-03）。
- 接口限流（docs/07 §7）：`@RateLimit` + `RateLimitInterceptor` + 固定窗口计数，覆盖登录/注册/发帖/评论/上传/搜索；
  支持内存与 Redis 两种后端（Redis 故障自动降级），超限返回 429 + `Retry-After`；新增 11 个单元测试。
- OpenAPI 契约 `backend/src/main/resources/openapi/isles-api.yaml`：覆盖认证、频道、帖子、评论、动态、搜索、私信、
  好友、通知、频道管理、数据中心、AI、平台后台全部接口与响应模型。
- 接口审计脚本（`tools/e2e/`，可重复执行）：
  - `api-e2e.mjs`：11 步正向旅程（注册登录 → 发帖评论点赞 → 搜索热门 → 邀请审批 → 好友私信 →
    通知 → 数据中心/日志/内容管理 → 举报处置 → AI 流式 → 限流）。
  - `permissions-audit.mjs`：13 步越权与边界（禁言、全员禁言、退出、私密可见性、邀请审批、拉黑、
    板块范围权限、重复举报、通配符转义、媒体校验、数据聚合、解散）。
  - `feature-audit.mjs`：9 步内容协作细节（分享计数、精华筛选、用户卡片、楼中楼、板块 CRUD/排序、
    身份组层级、频道内搜索、通知过滤、私信分享帖子、作者删除）。
  - `realtime-check.mjs`：STOMP CONNECT 鉴权 + 私信/通知实时推送。
- 频道管理中心「频道助手设置」页面：复用 `ChannelAiSettingsPage`，提供助手身份、任务开关、频率限额、
  手动试运行与执行日志（FR-MGR-08 / FR-ASSIST-01～06）。
- 频道页「频道助手」入口：悬浮按钮 + 浮层（`ChannelAssistantPanel`），仅成员可见，内部处理未登录/
  未开启状态，实现频道内基于本频道内容的问答（FR-ASSIST-02）。
- 个人资料编辑弹窗 `ProfileEditDialog`：头像上传、昵称、签名、性别、地区，保存后同步全局用户状态
  （FR-USER-01 / FR-NAV-03）；替换侧边栏「后续版本上线」占位提示。
- 平台管理后台路由接入：`/admin/{overview,ai-models,reports,users,channels}` 使用 `AdminLayout` 子路由，
  替换原占位页；新增管理端 AI 配置、举报处置、用户/频道管理页面入口。
- 管理端偏移分页：成员/内容管理/管理日志/举报列表支持 `page`/`size` 并返回 `total`（`OffsetPage`），
  与前端表格分页契约一致。
- 前端开发代理支持 `VITE_API_TARGET` 覆盖后端地址。

### Fixed

- 修复热门页频道推荐的「加入」按钮永远处于加载态、点不动：后端推荐接口是扁平结构（`channelId`），
  适配层按 `id` 读取导致 `channel.id` 为空；补齐 id 映射后按钮可正常加入。
- 修复频道助手「频率与限额」无法编辑：数字输入框放在 `el-form` 之外且未撑满列宽，只剩加减按钮可见；
  补表单上下文与宽度，并补充限额说明（UTC 自然日、每小时调度）。
- 修复公共头像预览过大：`ImageCropUpload` 增加独立预览尺寸，助手/频道/个人头像预览缩到 72–88px，
  裁剪弹窗仍使用大尺寸取景框。
- 修复「管理」标识点击无效：侧边栏与频道卡片的标识改为按钮，点击直接进入对应频道的管理中心。
- 修复发帖一律报「参数校验失败」（前端 `contentFormat: TEXT` vs 后端 `PLAIN|HTML`）、
  邀请撤销后刷新又出现（列表未过滤 `revoked_at`）、非成员仍看到评论框、深度思考开关无效
  （关闭时未下发关闭参数）、撤销/加入方式保存等上一轮问题（详见 docs/10 验证记录）。
- 修复用 `http://127.0.0.1:5173` 打开页面时所有接口 403、前端提示「无法连接服务器」：
  Vite 代理会把浏览器 `Origin` 原样透传给后端，而后端 CORS 默认只放行 `http://localhost:5173`，
  `127.0.0.1` 被判定为非法来源（`403 Invalid CORS request`）。现在默认同时放行两个主机名与本地任意端口
  （通配符条目改走 `allowedOriginPatterns`），`isles.bat` 也会按实际前端端口注入 `CORS_ALLOWED_ORIGINS`；
  新增 `CorsConfigTest` 回归测试。
- 修复非统一错误信封的 4xx/5xx 被误报为「无法连接服务器」：仅网关级失败（502/503/504、代理错误文本）
  才提示网络错误，`403` 等保留真实状态语义。
- 修复 Vite 开发期反复「发现新依赖 → 重新预构建 → 整页 reload」：Element Plus 按需样式入口在启动时
  全量列入 `optimizeDeps.include`，避免页面刷新中断进行中的请求。
- 修复应用无法启动：`AiProperties` 缺少配置绑定注册（新增 `@ConfigurationPropertiesScan`），
  且 `@MapperScan` 只扫描 M1 包导致新增模块 Mapper 未注册；改为按 `@Mapper` 注解扫描全包。
- 修复 7 个失败单元测试：`AiTaskService` 任务日志响应映射、`AdminService` 自禁用判空、
  `AiModelConfigService` Lambda 条件构造器缺少 TableInfo 缓存。
- 修复 SSE 流式回答被中断：Spring Security 在 ASYNC 派发时重新鉴权导致 `Access Denied`、
  连接被强制关闭（浏览器/客户端表现为 `terminated`）；改为放行 `DispatcherType.ASYNC/ERROR`。
- 修复实时推送完全不可用：STOMP 订阅白名单只允许 `/queue/dm`，而客户端订阅 `/user/queue/dm`，
  所有订阅被拒；改为剥离 `/user` 前缀后校验（仅允许用户级队列）。
- 修复通知从未实时推送：`NotificationService` 只落库未推送；新增提交后推送
  `/user/queue/notifications`（事件信封 `{id,type,ts,version,data}`）。
- 修复解除禁言/取消全员禁言无效：`muted_until`/`mute_all_until` 置空被 MyBatis-Plus 默认
  `NOT_NULL` 策略忽略，导致「已解除仍 403」；两个字段改为 `updateStrategy = ALWAYS`。
- 修复被拉黑方仍可新建私信会话：`openConversation` 未校验拉黑关系，改为新建会话前校验（已存在会话仍可查看）。
- 修复频道页缺少「频道助手」入口：`ChannelAssistantPanel` 已实现但未挂载，现以悬浮按钮 + 浮层接入
  （仅成员可见，内部处理未登录/未开启状态）。
- 修复 AI 配额统计 500：`AiMessageMapper`/`AiContextMapper`/`AiTaskLogMapper` 的纯文本 SQL 误用 XML 实体
  （`&gt;=`），直接拼进 SQL 造成语法错误；补充静态回归测试防止再犯。
- 修复请求体省略可选 `boolean` 字段时 400：Jackson 3 默认 `FAIL_ON_NULL_FOR_PRIMITIVES`，
  在 `application.yml` 关闭（请求 DTO 的布尔字段按缺省 `false` 处理）。
- 错误响应显式声明 `application/json`：SSE 接口出错时返回统一错误信封，而不是 406/空响应。
- 前端管理端接口路径/方法与后端契约对齐：解散频道、分配身份组、数据中心、管理日志、内容管理、举报处置；
  举报状态枚举由 `APPROVED` 更正为 `ACCEPTED`。
- 修正频道概览/趋势/热门帖子适配器对后端嵌套字段（`yesterday`/`statDate`/`visitUv`/`postId`）的解析。

### Changed

- 交付策略调整：取消 M1/M2/M3 分期，改为**全量交付**（2026-11-14，负责人决定）。
  `docs/03` §10 改为 WP1–WP7 工作包，`docs/10` 改为全量交付计划。
- 文档同步至全量实现：`docs/05`（全量接口清单 + 限流/SSE 示例）、`docs/06`（V2–V4 表结构）、
  `docs/07`（限流实现与安全落地）、`docs/08`（测试落地情况）、`docs/10`（WP 状态与验证记录）、`README`。

### Added（历史条目）

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

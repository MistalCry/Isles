# 变更记录

本文件格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

变更分类：`Added` 新增 / `Changed` 变更 / `Deprecated` 废弃 / `Removed` 移除 / `Fixed` 修复 / `Security` 安全。

## [Unreleased]

### Added

- **AI 代理两阶段 tick（研究前置）**：新增 `agent.twoPhaseEnabled`（默认开）与 `agent.researchFirst`（默认开）。
  研究阶段只接受只读动作（`web_search` / `search_site_posts` / `list_channel_posts` / `fetch_page`，最多 2 个），
  「要不要研究」不交给模型——模型只决定研究什么，给不出 query 时用人设兴趣词强制兜底；
  产出阶段把检索结果拼成「今日素材」注入提示，只接受写动作。
- **禁止编造 id**：`comment` / `like` 的目标必须出现在本 tick 的只读结果里，否则 `GUARDED`。
  此前模型曾把 `postId` 拼成「频道ID_日期」并真的尝试提交。
- **代理资料完整**：`SaveAgentRequest` 支持 `avatarUrl`，创建/更新同步写 `users.nickname` 与
  `users.avatar_url`；`AgentView` 返回 `avatarUrl` / `nickname`。人设抽屉支持 1:1 头像上传与裁剪。
- **工具结果结构化**：`tool_calls_json` 里 `web_search` 带 `query`/`count`/`results[{title,url,engine,snippet}]`，
  `fetch_page` 带 `url`/`chars`，站内检索带 `postIds`；前端按「研究 → 产出」顺序渲染，不再显示成一条未知工具。
- **AI 代理联网搜索（P1）**：新增 `com.isles.agent.search` 包——`SearchProvider` 抽象 +
  `SearxngSearchProvider`（超时 8s、失败重试 1 次、同 url 去重、摘要截 300 字、单次 ≤10 条）；
  部分引擎失败只要结果非空即视为成功。
- **`web_search` / `fetch_page` 工具**：搜索结果回灌模型并把命中 url 写入
  `agent_memories(kind=SOURCE)`；抓取页面用 Jsoup 抽正文（≤5000 字）。
  `fetch_page` 做 SSRF 防护：只允许 http/https，拒绝内网/回环/链路本地地址与 DNS 解析到内网的域名，
  **不跟随重定向**，响应体上限 2MB。
- **搜索配置项（5 个）**：`agent.search.provider`（`NONE`/`SEARXNG`，默认 NONE）、`baseUrl`、
  `engines`（逗号分隔，支持带空格的引擎名如 `google cse`）、`dailyQuota`（默认 200，0=不限）、
  `timeoutSeconds`。
- **AI 用户页联网搜索状态**：页头显示「已启用（SearXNG）/ 未配置 / 状态未知」，未配置时提示只能站内检索。
- **运行日志工具调用结构化展示**：`web_search` 显示查询词、命中条数、前 3 条标题（可折叠）；
  `fetch_page` 显示 URL 与抓取字数；原始 JSON 仍可展开。
- **本地 SearXNG 部署**：`deploy/searxng/`（settings.yml 开启 json 输出、引擎收敛、`outgoing.proxies` 代理、
  docker-compose、README 含故障判断）。
- **AI 合规标识透出接口**：`PostResponse` / `CommentResponse` / `SearchItemResponse` 末尾追加
  `isAiGenerated` 与 `authorIsAgent`，`UserResponse` / `UserCardResponse` 追加 `isAgent`；
  取值走已批量加载的作者映射，无额外查询。历史数据一律 `false`，不会误标。
- **频道应用**：频道管理中心「应用管理」（语音房 / 直播间 / 自定义），支持增删改、排序、启停与图标；
  频道详情右侧简介下方渲染应用卡片。
- **语音房**：V10 三张表（`voice_rooms` / `voice_room_seats` / `channel_apps`）+ 开房/进房/上麦/下麦/静音/关房；
  WebRTC P2P mesh（≤6 人）+ STOMP 信令转发，抢麦位靠唯一键兜底返回 409。
- **频道活动**：活动创建/发布/取消/删除、成员报名与取消、人数上限校验；
  开始前 30 分钟站内提醒（`reminder_sent_at` 抢占保证只发一次）。
- **我的帖子**：侧边栏「我的帖子」→ `/my-posts`，按时间倒序、可按频道筛选、可进详情/编辑/删除；
  接口 `GET /users/me/posts`。
- **频道助手多会话**：复用 `ai_conversations.scope`，新增 `CHANNEL_ASSISTANT`，
  按「用户 × 频道」隔离，支持新建/切换/重命名/删除；非频道成员创建返回 403。
- **AI 用户代理设计**（[docs/12](docs/12-AI用户代理设计.md)）：拟人代理的人设/记忆/运行日志三张表、
  工具集与权限边界、节奏与去重策略、审核闸门与 kill switch、P0–P4 分阶段与验收标准。
- **帖子卡片展示最高赞评论**：列表返回的 `topComment` 渲染在卡片底部（作者 + 摘要 + 查看全部 N 条），
  点击直达详情并高亮定位该评论；无评论不占位。
- **上传进度与重试**：发帖/评论/私信/AI 面板四处显示上传百分比，失败可重试；
  超时按文件大小动态计算（`max(120s, size/0.5MB/s)`），200MB 视频不再被固定 120 秒卡死。
- **视频懒播放**：`MediaViewer` 去掉无条件 `autoplay`，改用 IntersectionObserver，进入视口才播、离开暂停。
- **前端 ESLint + Prettier**：flat config + `.prettierrc`，新增 `pnpm lint` / `pnpm format`，
  lint 不阻断构建（当前 0 error / 12 warning）。
- **敏感词处理模式**：新增配置项 `content.sensitiveWord.mode`（`REVIEW` / `MASK` / `BLOCK`，默认 REVIEW）；
  MASK 只替换富文本文本节点，BLOCK 返回 422 `SENSITIVE_WORD_BLOCKED`。
- **浏览量口径**：作者本人查看不计；同一会话 30 分钟内重复访问只计一次（登录按 userId、匿名按 IP+UA 指纹；
  多实例各实例独立计数，已在 docs/11 标注为已知限制）。
- **搜索返回封面与首图**：`SearchItemResponse` 新增 `coverUrl` 与 `media[]`（仅首图），
  并修掉搜索结果的频道/作者/板块 N+1 查询。
- **公告站内通知**：`placement=INBOX` 的公告发布时按 id 游标分批写系统通知（每批 500、单条多行 INSERT），
  BANNER/MODAL 不写站内信。
- **任务日志可追溯**：V9 给 `ai_task_logs` 加 `model_config_id` / `model`，`TaskLogResponse` 透出，
  频道指定模型与回落默认都能核对。
- 测试：`content-regression.mjs`（浏览量/敏感词三模式/搜索封面）、卡片 topComment、视频懒播、上传超时、
  弹窗评论权限、来源事件边界；前端 136 例 / 后端 301 例。
- **平台后台前端补全**：配置中心（元数据驱动，按 `type` 渲染控件、单键/批量保存、恢复默认、变更历史）、
  管理员授权（远程搜索用户选择器）、审计日志、内容治理（帖子/评论下架与恢复）、敏感词库（CRUD/导入/试算/命中）、
  公告管理、概览趋势与热词，后台共 11 个页面；菜单按 `permissions` 显隐，侧边栏新增「进入后台」。
- **前台公告**：顶部横幅（多条轮播、按条关闭并记 localStorage）+ 用户菜单「公告」可重新查看当前生效公告。
- **频道置顶**：新增 `GET /channels/{id}/posts/pinned`（≤20 条，`pinned_at DESC`）；频道流 `latest`/`essence`
  置顶固定最前，游标改为「置顶标记 + id」复合条件，翻页不重复不漏帖。
- 帖子列表与详情返回 `topComment`（每帖 1 条最高赞评论，窗口函数批量查询，无 N+1）。
- 帖子编辑支持封面与媒体（增删、排序、多选上传），发布时能选的都能改。
- **频道助手可选模型**：V8 迁移 `channel_ai_settings.model_config_id` + `GET /channels/{id}/ai/models`；
  任务按频道模型调用，缺失/禁用回落平台默认并记 WARN。
- **AI 工具调用**：`search_channel_posts` / `search_global_posts` / `get_post_detail`，最多 2 轮、计入总超时、可中断；
  来源引用改由实际命中生成，新增 SSE `sources` 事件并落库；`ai_model_configs.tool_call_enabled` 默认关，
  模型不支持或报错时自动降级到关键词检索。
- 上传支持 AVIF / BMP / HEIC / HEIF / MOV / M4V，ISO BMFF 按 brand 区分（HEIC 不再误判为 mp4）；
  解码失败降级为原图直出，不再 500。
- `application-prod.yml`（不含任何默认密钥）+ `app.security.require-secrets`：生产缺 JWT / AI 密钥拒绝启动。
- 测试：举报回归（组件 + e2e）、配置即时生效、媒体格式与降级、AI 工具调用两轮、助手模型回落；
  前端 93 例 / 后端 281 例。
- 前端单元测试（Vitest，`pnpm test`）：适配器字段映射、评论校验、裁剪尺寸计算、提及 token，共 27 个用例。
- **全站 i18n 落地**：1186 条文案迁移到 vue-i18n（zh-CN / en-US），覆盖 82 个 `.vue`/`.ts` 文件；
  源码中已无硬编码中文（品牌名除外），用户菜单可切换语言，Element Plus 组件语言同步切换。
  文案源为 `tools/i18n/catalog.json`，`python tools/i18n/i18n_tool.py gen` 生成语言包。
- **前端组件测试**：引入 `@vue/test-utils` + happy-dom，覆盖 PostCard / CommentItem / MediaViewer / MentionPicker；
  新增「文案守卫」测试（key 对齐、`t()` 引用存在、无硬编码中文、`@`/`|` 转义）。前端共 50 个用例。
- 前端接入平台公开配置 `GET /api/v1/platform/config`：站点名称/Logo/简介、注册开关由后台配置驱动，
  接口不可用时静默回落到内置常量。
- 视频首帧封面与时长：上传视频时用 ffmpeg 抽帧生成 `_poster.jpg` 并写入 `durationMs`，列表/详情不再黑块；
  WebP 通过 ffmpeg 兜底解码，尺寸与缩略图不再缺失；带透明通道的图片缩略图输出 PNG（不再填白）。
- @提及改为 ID 化 token `@[id:昵称]`（展示仍是 `@昵称`），重名可精确命中；手打 `@昵称` 仍兼容，且重名时通知所有同名成员。
- i18n 基础设施：vue-i18n + 中英文文案，侧边栏导航与校验文案已接入，用户菜单可切换语言。
- 局域网模式显式开关：`isles.bat lan` / `ISLES_LAN=1 pnpm dev`，默认仍只监听 127.0.0.1。

- 平台管理后台（后端 P0–P3，docs/11）：
  - **运行时配置中心**：`platform_settings` + 变更历史，21 个配置项（站点信息 / 注册准入 / 内容策略 / 互动 /
    上传 / AI / 运营开关）。默认值由代码 `SettingDefinitions` 定义、数据库只存覆盖值，保存即生效（30s 快照兜底），
    支持按分组读取、单键/批量更新、恢复默认与历史查询；新增 `GET /platform/config` 公开接口（仅 `publicVisible` 项）。
  - **平台角色与权限点**：`SUPER_ADMIN` / `OPERATOR` / `MODERATOR` / `VIEWER` 四档 + 15 个权限点（读写分离，
    只读角色可查看用户/内容/举报但无任何写权限），`@RequirePlatformPermission` + 拦截器统一校验
    （未标注的后台接口至少要求任意平台角色）；
    `platform_admin_grants` 支持授权/改角色/撤销（不能撤销自己、至少保留一个超管）。
  - **平台操作审计**：`platform_audit_logs` 只追加，记录操作人快照、动作、目标、明细、IP/UA；
    `GET /admin/audit-logs` 只读查询，无修改/删除入口。
  - **治理闭环**：帖子/评论全站检索与下架/恢复（`HIDDEN` 软处理 + 通知作者）；用户处罚 `BAN`/`MUTE`/`WARN`
    （期限、原因、撤销、历史），封禁同步 `users.status` 并在登录时返回 `USER_BANNED`，全站禁言拦截发帖/编辑/评论。
  - **配置接入业务**：`auth.register.enabled` 控制注册开关；封禁/禁言在登录与内容生产路径生效。
  - **运营配置（P3，V7）**：公告草稿/发布/归档 + `GET /platform/announcements` 公开接口（生效窗口内、最多 5 条）；
    敏感词库 CRUD、批量导入、试算与命中记录（`REVIEW` 模式只记录不阻断，已接入发帖/评论，HTML 先抽文本）；
    平台趋势（近 30/90 天按日补零）与热词下线。
- 新增《平台管理后台设计》（`docs/11-平台管理后台设计.md`）：盘点现有后台能力与缺口，确立「启动级 / 运行时 / 业务」三层配置模型、
  配置注册表方案、平台角色与权限点矩阵、审计与处罚闭环、V6 迁移草案、API 契约与 P0–P3 实施计划；
  同时记录举报状态值不一致等 4 个现存缺陷及修复方式。
- @提及（帖子与评论）：正文输入 `@昵称` 可提及频道成员，被提及者收到 `MENTION` 通知并可点进帖子定位评论；
  提及频道助手会触发助手自动回复（与「回复我的评论」共用频率限额、独立幂等键）。
  编辑器/评论框新增「@」成员选择器，接口 `GET /channels/{id}/mention-candidates`。
- 评论作者可删除自己的评论（详情页评论操作区「删除」，后端沿用 `DELETE /comments/{id}`，仅作者或具备删除权限者）。
- 后端根路径 `GET /` 增加落地页：提示前端地址、健康检查与接口前缀，避免把 401 误判为「后端挂了」。
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

- 修复**「运行一次」误报连接失败**：该接口单独放宽到 180 秒超时（全局仍是 20 秒），
  运行中提示「最长约 3 分钟」，超时按「已超时，请刷新运行日志」提示而非失败。
- 修复**后台抽屉内容贴边**：`--el-drawer-padding-primary` 被设为 0，且 `AppLayout` 里还有一条
  全局非 scoped 的 `.el-drawer__body { padding: 0 }` 覆盖；恢复 20px 并把移动端导航抽屉改为按 class 单独满宽。
- 修复**代理帖子正文照抄搜索素材**：产出提示词增加硬规则——不许抄素材标题/摘要原文、
  正文不许出现 URL、不许写「我在网上搜到 / 原文在这里」这类暴露检索过程的句子，标题要口语具体。
- 修复**两阶段运行日志形态不一致**：`plan_json` 的 `compose` 由模型原文改为结构化对象，
  与成功路径同构；前端兼容对象与 JSON 字符串两种历史写法。
- 修复**暂停的 AI 代理仍能被「立即运行」执行**：`runNow` 与 `runOne` 都校验 `status == ACTIVE`，
  否则返回 `SKIPPED`「代理已暂停」且不写运行日志（与总开关同语义）。
- 修复**无可用模型时记 FAILED**：`resolveDefault()` 抛的业务异常在 `runOne` 内降级为 `SKIPPED`，
  不再把「没配模型」当成运行失败。
- 修复**用户卡片不显示 AI 代理标识**：`/users/{id}/card` 补 `isAgent`，前端在缺字段时回退到
  `authorIsAgent`，从成员列表打开也能看到标识。
- 修复 **AI 用户运行日志看不出跳过原因**：`SKIPPED` / `FAILED` 均显示 reason，无记录时明确提示。
- 修复**语音房单向音频**：先建连后上麦的一侧音轨无法送达对端；上麦后重建连接并补轨道，
  新增 `renegotiate` 信令（仍只有一方发 offer），另加 3s 状态轮询兜底。
- 修复**评论类举报处置必然 500**：`AdminContentMapper.updateCommentStatus` 写了不存在的
  `comments.updated_at` 列（`BadSqlGrammarException`）。同时新增 Mapper SQL 静态校验测试，
  「SET 列必须真实存在」这类错位以后进不了主干。
- 修复**举报处置通知说不清处置了什么**：处置前取标题/摘要快照，通知改为
  「你举报的帖子《标题》已被删除」「你举报的评论（摘要…）已被删除」「你举报的内容经核查未违规」。
- 修复**置顶帖在列表里重复出现**：频道流 `latest`/`essence` 排除置顶帖（`AND is_pinned = 0`），
  置顶改由 `/channels/{id}/posts/pinned` 单独加载，折叠态固定单行。
- 修复**历史带图消息切到无视觉模型时报错**：按模型能力剥离图片并追加提示「已忽略 N 张图片」。
- 修复 **AI 回答来源在「检索命中为空」时仍显示关键词兜底来源**：`sources` 事件定为唯一信号，
  前端仅在**完全未收到事件**时才回退 `meta.sources`；`meta.sources` 仅作兼容保留。
- 修复**后台 5 个列表页窄屏撑宽**：内容治理/敏感词/审计/用户/频道统一横向滚动容器，操作列固定右侧，
  不再把整页撑到 1100px+ 导致按钮被挤出屏幕。
- 修复**非频道成员可在卡片弹窗写完评论才被 403**：按加入状态直接禁用输入并提示加入。
- 修复**搜索结果永远没有缩略图**：适配层不再硬编码 `media: []` / `coverUrl: null`。
- 修复**举报提交报「参数校验错误」**：举报原因改为提交代码（`SPAM/ABUSE/PORN/ILLEGAL/OTHER`），
  文案仅用于展示；后台与频道管理中心的举报列表不再显示裸枚举，目标类型与处置动作一并中文化。
- 补齐**评论举报入口**（此前只有帖子可举报），支持楼中楼。
- 修复**清空帖子封面/标题不落库**：`updateById` 默认跳过 null，给 `Post.coverUrl`/`title` 加
  `updateStrategy = ALWAYS`，并补单测锁住注解（删掉注解测试即失败）。
- 修复**清空个人头像/签名/地区、频道封面/简介不生效**：后端约定 `null` = 不修改，前端清空时误传 `null`，
  改为传空串。
- 修复**后台保存配置后前台仍显示旧值**：`/platform/config` 由 `max-age=60` 改为 `no-store`，
  保存/批量保存/恢复默认后前端立即重取。
- 修复图片解码失败时上传报 500：降级为原图直出，`thumbUrl/width/height` 为空。
- 修复置顶 30 天前的旧帖在频道首屏不可见（此前既无置顶排序也无置顶查询）。
- 修复 i18n 文案里的 `@` 被 vue-i18n 当作链接语法导致渲染崩溃（`Invalid linked format`）：
  「回复 @昵称」「响应@提及」等 6 条文案在生成时统一转义为 `{'@'}`。
- 修复 `constants/app.ts` 在模块加载时求值 `t()` 导致切换语言不生效：改为 getter / 函数（举报原因、性别、频道类型等）。
- 修复数字缩写只认中文：`formatCount` 改用 `Intl.NumberFormat(notation: 'compact')`，中文 1.2万 / 英文 12.3K。
- 修复页面标题写死「群岛 Isles」：改为读取平台配置的站点名。
- 取消媒体上传的固定窗口限流（30 次/小时）：9 图长文/评论会被误伤，改为依赖登录态 + 单文件大小 + 前端数量上限。
- 帖子相关图片不再强制压缩：裁剪 `maxOutputWidth<=0` 表示不设上限（保持原像素），列表用缩略图、点击查看原图。
- 补充回归：AI 非成员不得对「仅成员可见」频道发起问答（e2e 16/16）。

- 修复平台举报处置的状态机分裂：平台后台此前写入 `DISMISSED`/`RESOLVED`，而表与频道侧枚举是
  `PENDING`/`ACCEPTED`/`REJECTED`，导致处置后频道侧按状态查不到。现统一为 `ACCEPTED`/`REJECTED`，
  并补写 `handler_id` / `handle_action` / `handled_at` / `handle_note`，处置只允许从 `PENDING` 迁移（并发安全）。
- 修复平台举报 Mapper 的静默降级：去掉 `SELECT *` + Map + 表存在性探测，改为强类型 `Report` 映射，
  字段漂移不再被 `available=false` 掩盖；`reports.channel_id` 放宽为可空以承载平台级举报。
- 修复后台鉴权分散易漏：`AdminController` 11 处手工 `requireAdmin()` 改为注解 + 拦截器统一校验。
- 修复富文本里的站内图片/视频「插入后消失」：`HtmlSanitizer` 未给 baseUri 导致 Jsoup 丢弃
  `/uploads/...` 这类相对地址，且白名单没有 `video/source`。现在使用占位 baseUri +
  `preserveRelativeLinks`，并补齐 video 属性；新增 `HtmlSanitizerTest`（7 个用例，含 javascript:/data: 剥离）。
- 修复带图发帖直接失败：上传响应字段是 `mediaType`，前端按 `type` 读取导致类型缺失；
  `uploadMedia` 统一走 `toMediaItem` 适配（兼容两种字段），发帖/私信/AI 面板同步修复。
- 修复帖子封面只写不读：`PostCard` 与 `PostDetailView` 现在展示封面（16:9、懒加载、详情可点开灯箱）。
- 修复裁剪必然降分辨率：导出尺寸改为按裁剪区域原图像素计算（上限 1024/2560/1920），
  新增「使用原图（不裁剪）」，PNG/WebP 保持透明、GIF 提示保留动图需用原图，超限降质并提示。
- 修复评论区不能发图片/视频/emoji：评论输入区补齐媒体（≤9）与表情面板，校验改为「文字/媒体/表情至少一项」，
  `toComment` 补 emoji 映射，评论项渲染表情。
- 修复富文本编辑器插图偶发失效：打开文件框前保存选区、插入前恢复，并校验插入数量，失败给出重试提示；支持多选与视频插入。
- 删除发帖框「将在 M2 补齐」的未完成文案。
- 补充 e2e 回归：上传 → 带媒体/封面发帖 → 回读一致；富文本相对地址图片/视频不被净化器丢弃。
- 修复手机/平板通过局域网或电脑热点访问时注册提示「无权限」：浏览器 Origin 是 `http://192.168.x.x:5173`，
  不在后端 CORS 白名单里被拒（403 Invalid CORS request）。开发默认放行 `192.168.*` / `10.*` / `172.*` 私有网段，
  Vite 代理同时把 Origin 改写为后端地址，双保险；生产仍要求显式配置正式域名。
- 修复频道助手自动任务「永远显示已跳过」：手动试运行此前复用周期幂等键，一旦某次因条件不满足被跳过，
  当天再点都返回那条旧记录；现在手动执行使用独立键（仍受每日限额约束）。
- 修复助手头像「莫名其妙消失」：保存设置时未传头像会把 `avatar_url` 写成 null，且同步虚拟账号时无条件覆盖；
  改为「null = 未修改，空串 = 清除」，同步时仅在有值时覆盖。
- 修复第二台设备看不到头像/图片：Vite 开发服务器只监听 `127.0.0.1`，局域网设备无法访问（页面能开是缓存/代理的假象），
  改为监听所有网卡；`preview` 同样放通。
- 修复通知里好友申请点击无反应：好友类通知没有 postId/channelId，现按标题跳转好友页对应标签（收到的申请/好友/发出的申请）。
- 修复私信图片过大：`MediaViewer` 支持 `max-height`，私信气泡内单图限制 220px。
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

- `AgentView` 的 id 语义写清：路径参数一律是**代理账号 id（`users.id`）**，`AgentView.id` 是人设行 id，
  不能当路径参数回传；前端列表统一用 `AgentView.userId` 作操作主键（docs/05 §2.15）。
- 频道流排序语义调整：置顶帖不再出现在 `latest`/`essence` 列表，只出现在置顶接口；
  `hot` 仍按热度排序，不受影响。
- 语音房只持久化麦位，听众由客户端信令维护（断线即消失），避免僵尸在线与心跳清理任务；
  同一用户在同一频道已有开启中房间时再开房返回 409。
- `database/schema.sql` 重导为 V1–V11（44 表）。
- 敏感词命中记录的 `action` 改为记录**本次实际处理方式**（REVIEW/MASK/BLOCK），不再固定取词条自身配置；
  行为判定以全局 `content.sensitiveWord.mode` 为准，避免词条与全局两套规则打架。
- 搜索接口的 `media` 只返回首图（`post_media` 中 sort 最小的一张），避免搜索卡片响应膨胀；
  帖子详情仍返回完整媒体列表。
- `database/schema.sql` 重导为 V1–V9（39 表，含 `ai_task_logs` 两个模型列）。
- 举报页状态改用 `ACCEPTED`/`REJECTED` 并映射中文，新增目标类型与关键字筛选、处置动作选择；
  平台后台不再依赖 `DISMISSED`/`RESOLVED` 兼容垫片。
- `/platform/config` 缓存策略改为 `no-store, must-revalidate`，后台保存即时生效（原先 60 秒内读旧值）。
- 部署：`docker-compose.yml` 的 `SPRING_PROFILES_ACTIVE` 默认 `prod`，`COOKIE_SECURE` 默认 `false`
  （HTTP 临时方案）；`docs/09` 明确「HTTP 部署不可用于生产」，443/证书留 TODO。
- `database/schema.sql` 重导为 V1–V8（39 表），README / database/README / docs/06 措辞同步。
- `docs/11` 实施状态拆成「后端 / 前端」两列，未交付项不再提前标 ✅。
- 交付策略调整：取消 M1/M2/M3 分期，改为**全量交付**（2026-11-14，负责人决定）。
  `docs/03` §10 改为 WP1–WP7 工作包，`docs/10` 改为全量交付计划。
- 文档同步至全量实现：`docs/05`（全量接口清单 + 限流/SSE 示例）、`docs/06`（V2–V4 表结构）、
  `docs/07`（限流实现与安全落地）、`docs/08`（测试落地情况）、`docs/10`（WP 状态与验证记录）、`README`。

### 测试

- 新增 `agent-research-regression.mjs`：代理资料同步（`avatarUrl` → `users.avatar_url`/`nickname`）、
  `researchFirst` 必搜（`tool_calls_json.research` 含 `web_search`、`count` 与 `results` 一致）、
  素材注入且帖子正文与素材同源、关掉后退回单轮（仅 1 次模型调用）、编造 `postId` 被拒且不写库。
- 新增 `UserCardPopover` 头像测试：有 `avatarUrl` 渲染图片、无头像回落首字、卡片接口头像优先于列表旧值。
- 新增抽屉内边距守卫：扫源码禁止再出现裸 `.el-drawer__body { padding: 0 }`。
- `toolCalls` 补两阶段形态用例：`research` 逐条展开、`compose` 对象与 JSON 字符串都兼容、空 `research` 只渲染 `compose`。
- 新增 `web-search-regression.mjs`：配额（1 次成功后拒绝且不再发真实请求）、中英文查询相关性与
  UTF-8 编码防回归、url 去重与 ≤10 条、`provider=NONE` 与死端口降级、`fetch_page` SSRF
  （127.0.0.1 / 192.168.1.1）、`web_search → create_post` 端到端。
- 新增 `agent-regression.mjs`：代理创建、`run-now` 产出帖子/评论、日发帖上限、同话题 7 天去重、
  暂停不可运行、总开关、无可用模型降级、`agent_runs` 回放；`AGENT_TICK_MODE=1` 验证调度挑选与停止。
- 新增「AI 标识字段」断言：帖子详情/列表、评论列表校验 `isAiGenerated` + `authorIsAgent`，
  作者资料与 `/auth/me` 校验 `isAgent`；**代理内容 true、真人内容 false 两侧都断言**。
- `apps-regression` 语音房信令由跳过改为正式断言，补非成员（`NOT_IN_ROOM`）与关房后（`ROOM_CLOSED`）
  两条安全边界；新增频道解散级联用例（应用/语音房/麦位/活动无孤儿行，直查库校验），现 21/21。
- mock-openai 新增 `/debug/agent-plan`（按队列返回代理计划）与 `ignoredImagesNote`。
- docs/08 补齐 AI 代理 P0、信令安全边界、级联清理与 AI 标识必测项；注明当前用户接口是 `/auth/me`。

### Removed

- 移除 `/admin/reports` 的 `DISMISSED`/`RESOLVED` 状态兼容垫片（前端已迁移到 `ACCEPTED`/`REJECTED`，
  传旧值将查不到数据）。

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

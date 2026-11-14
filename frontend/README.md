# 群岛 Isles · 前端

群岛（Isles）社交频道平台的 Web 前端。M1 交付内容为工程骨架 + 可演示页面，接口契约见 [`docs/05-API设计.md`](../docs/05-API设计.md)。

## 技术栈

| 项 | 选型 |
| --- | --- |
| 框架 | Vue 3.5 + TypeScript + Vite 7 |
| 路由 | Vue Router 4 |
| 状态 | Pinia 3（仅全局共享状态：认证、我的频道/当前频道） |
| UI | Element Plus 2（`unplugin-vue-components` 按需引入）+ `@element-plus/icons-vue` |
| 请求 | Axios（统一错误信封、JWT 拦截器、401 单飞刷新） |
| 样式 | Sass + `styles/tokens.css` Design Token |
| 内容安全 | DOMPurify（帖子 HTML 白名单净化） |

## 开发命令

```bash
pnpm install       # 安装依赖（Node >= 20.19）
pnpm dev           # 开发服务器 http://127.0.0.1:5173
pnpm build         # 类型检查（vue-tsc -b） + 生产构建
pnpm preview       # 预览构建产物
pnpm type-check    # 仅类型检查
```

开发服务器将以下路径代理到本地后端 `http://127.0.0.1:8080`：

- `/api` → 后端 REST API（base `/api/v1`）
- `/uploads` → 本地上传媒体

后端未启动时页面不会白屏：各数据视图均有加载 / 空 / 错误三态，请求层会给出「无法连接服务器」等友好提示。

## 目录结构（对齐 docs/02 §6.1）

```
src/
├── api/          # axios 实例、拦截器、按领域拆分的接口封装
├── components/   # 跨页面通用组件（PostCard / CommentSection / SearchPanel…）
├── composables/  # useCursorList、useInfiniteScroll、useSearchHistory、useRequireLogin
├── constants/    # 应用常量与中文文案常量
├── layouts/      # AppLayout（侧边栏 + 内容区）、AuthLayout（登录/注册）
├── pages/        # 路由页面
├── router/       # 路由表与守卫
├── stores/       # Pinia：auth、channel
├── styles/       # Design Token、Element Plus 主题覆盖、全局样式
├── types/        # 与 API 契约对齐的类型
└── utils/        # 时间/数字格式化、校验、剪贴板、HTML 净化
```

## 页面与路由

| 路由 | 页面 | 说明 |
| --- | --- | --- |
| `/` | 重定向 `/feed` | |
| `/feed` | 频道动态 | 最新/热门、无限滚动；游客显示登录引导 |
| `/hot` | 热门讨论 | 占位（M2/M3） |
| `/ai` | 频道问问 | 占位（M3） |
| `/channels/:id` | 频道页 | 头部渐隐、标签页/板块、置顶、帖子流、简介、发帖条、加入 |
| `/posts/:id` | 帖子详情 | 正文/媒体/互动/评论 |
| `/search` | 搜索 | 历史、综合/频道/帖子 |
| `/my-channels` | 我的频道 | 已加入频道网格（M1 补充路由） |
| `/messages`、`/messages/:id` | 我的私信 | 占位（M2） |
| `/manage/:channelId/*` | 频道管理中心 | 占位（M2） |
| `/login`、`/register` | 登录/注册 | 独立认证布局 |
| `/admin/*` | 平台管理后台 | 占位（M3） |

## M1 未完成 / 已知限制

- 热门讨论、频道问问、私信、管理中心、平台后台按 M1 范围仅提供占位页与路由。
- 长文发帖为纯文本编辑器弹窗；富文本（标题封面、视频排序）在 M2 补齐，界面已标注。
- 频道内搜索、私信、加好友、举报处置、内容管理操作均为占位交互，明确提示 M2 上线。
- 媒体上传统一走 `/media/upload`；后端未启动时上传会给出错误提示。
- 游客访问 `/feed` 不发起请求，直接展示登录引导（feed 接口按契约要求登录）。
- 搜索历史优先读取服务端；接口不可用或游客场景回退到浏览器本地记录，界面会标注「本地搜索记录」。

## 与文档差异

- 路由增加 `/my-channels`（我的频道页），需求文档路由清单未列出该页面，但 M1 要求交付「我的频道」页面。
- 综合搜索在契约 `type=all` 响应结构未明示的情况下，由前端并行请求 `type=channel` 与 `type=post` 后聚合展示，避免猜测混合列表结构。
- 参考 `docs/03 §5.1` 的复用组件清单实现了 M1 所需组件；`AiChat`、`NotificationList`、`ManageShell`、`QuickCommentSheet`（已实现基础版）随对应里程碑补齐。

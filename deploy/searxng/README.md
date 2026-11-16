# 本地 SearXNG（AI 代理联网搜索用）

给 `web_search` 工具提供搜索后端。**只监听 `127.0.0.1:8888`，不对外暴露**。

## 前置

- Docker Desktop（Windows）。装完启动一次，确保托盘图标是运行状态。
- 首次启动后确认：`docker version` 能输出版本号。

## 启动

```bash
cd deploy/searxng
cp .env.example .env     # 首次：按需改代理地址
docker compose up -d     # 改了 .env 或 settings.yml 都要用 up -d 重建，restart 不会重读环境变量
```

## 代理（可选，但强烈建议）

Google / DuckDuckGo / Brave 在国内网络下会被 CAPTCHA 或直接拒绝，配代理后可用，搜索质量明显更好。

`.env` 里一行即可（v2rayN 默认 SOCKS5 端口 10808）：

```bash
SEARXNG_HTTPS_PROXY=socks5h://host.docker.internal:10808
```

- 容器内用 `host.docker.internal` 指向宿主机，Windows/Mac 自带，Linux 由 compose 的 `extra_hosts` 兜底。
- 如果 SOCKS 不通（镜像里的 HTTP 客户端不支持 socks），改用 v2rayN 的 **HTTP 代理端口**（需在 v2rayN 里开启 HTTP 代理，通常 10809）：
  ```bash
  SEARXNG_HTTPS_PROXY=http://host.docker.internal:10809
  ```
- 判断是否生效：不带 `engines` 参数查一次，看 `unresponsive_engines` 里还有没有 google。

## 验证（必须过这一条）

```bash
curl "http://127.0.0.1:8888/search?q=vrchat&format=json" | head -c 200
```

期望：返回 `{"query": "vrchat", "results": [...]}` 这样的 JSON。

- 返回 HTML → `settings.yml` 的 `search.formats` 没生效，检查挂载路径是否为 `/etc/searxng/settings.yml`。
- 返回 `403 Forbidden` → 可能是 `limiter` 打开了，本仓库配置已关闭，确认用的是仓库里的这份 settings.yml。
- `results` 为空且 `unresponsive_engines` 全是 CAPTCHA/denied → 引擎被墙，配代理（见上）或只用 `360search,yahoo`。

## 停止 / 卸载

```bash
docker compose down          # 停止并删除容器
docker compose down -v       # 连数据一起删
```

## 后端接入

平台后台 → 配置中心 → AI 分组，填这几项（**Key 走 `ApiKeyCipher` 加密存储，不写 .env**）：

| 配置项 | 值 |
| --- | --- |
| `agent.search.provider` | `SEARXNG` |
| `agent.search.baseUrl` | `http://127.0.0.1:8888` |
| `agent.search.engines` | 引擎列表，逗号分隔；留空=用实例默认。例：`google,360search,yahoo` |
| `agent.search.dailyQuota` | 每日调用上限（默认 200） |

`agent.search.engines` 会作为 `engines` 参数传给 SearXNG，**后台可改、不用重启容器**——
某个引擎被封了，直接在后台把它从列表里去掉即可。

## 引擎现状（2026-11 实测）

| 引擎 | 直连 | 走代理 | 说明 |
| --- | --- | --- | --- |
| google | access denied | ✅ | 质量最好，优先 |
| duckduckgo | CAPTCHA | ✅ | 备选 |
| brave | too many requests | ✅ | 备选 |
| 360search | ✅ | ✅ | 中文兜底 |
| yahoo | ✅ | ✅ | 英文兜底 |
| sogou | 间歇 crash | — | 不建议依赖 |
| bing | 0 条且无报错 | — | 解析器问题，已关闭 |
| baidu | CAPTCHA | — | 国内站点，代理无意义，已关闭 |

## 注意

- 公共实例（searx.space 上那些）大多**关闭了 JSON 输出**且限流严重，不适合长期当 API 用，只适合临时验证。
- 本实例没配 `limiter`，因为它只对本机后端开放；如果要暴露到局域网，请先打开 `limiter` 并配 Redis。

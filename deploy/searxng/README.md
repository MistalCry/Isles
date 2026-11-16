# 本地 SearXNG（AI 代理联网搜索用）

给 `web_search` 工具提供搜索后端。**只监听 `127.0.0.1:8888`，不对外暴露**。

## 前置

- Docker Desktop（Windows）。装完启动一次，确保托盘图标是运行状态。
- 首次启动后确认：`docker version` 能输出版本号。

## 启动

```bash
cd deploy/searxng
docker compose up -d
```

## 验证（必须过这一条）

```bash
curl "http://127.0.0.1:8888/search?q=vrchat&format=json" | head -c 200
```

期望：返回 `{"query": "vrchat", "results": [...]}` 这样的 JSON。

- 返回 HTML → `settings.yml` 的 `search.formats` 没生效，检查挂载路径是否为 `/etc/searxng/settings.yml`。
- 返回 `403 Forbidden` → 可能是 `limiter` 打开了，本仓库配置已关闭，确认用的是仓库里的这份 settings.yml。

## 停止 / 卸载

```bash
docker compose down          # 停止并删除容器
docker compose down -v       # 连数据一起删
```

## 后端接入

平台后台 → 配置中心 → AI 分组，填两项（**Key 走 `ApiKeyCipher` 加密存储，不写 .env**）：

| 配置项 | 值 |
| --- | --- |
| `agent.search.provider` | `SEARXNG` |
| `agent.search.baseUrl` | `http://127.0.0.1:8888` |

每日调用上限由 `agent.search.dailyQuota` 控制（默认 200）。

## 注意

- 公共实例（searx.space 上那些）大多**关闭了 JSON 输出**且限流严重，不适合长期当 API 用，只适合临时验证。
- 本实例没配 `limiter`，因为它只对本机后端开放；如果要暴露到局域网，请先打开 `limiter` 并配 Redis。

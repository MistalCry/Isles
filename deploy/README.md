# 部署说明（Docker Compose）

> 本目录提供「前端 + 后端 + MySQL + Redis」的单机 Docker Compose 部署方案。
> 本机开发环境未安装 Docker，以下配置需在具备 Docker 的服务器上验证后视为通过（docs/09）。

## 文件

| 文件 | 说明 |
| --- | --- |
| `docker-compose.yml` | 四个服务：nginx（前端+反代）、backend、mysql、redis |
| `backend.Dockerfile` | Maven 构建后端，JRE 17 运行 |
| `frontend.Dockerfile` | pnpm 构建前端，Nginx 托管 |
| `nginx.conf` | SPA 回退、`/api` 反向代理、`/ws` WebSocket 升级、`/uploads` 静态代理 |
| `.env.example` | 环境变量模板（复制为 `.env`） |

## 步骤

```bash
cd deploy
cp .env.example .env
# 修改 .env：数据库/Redis 密码、JWT_SECRET（≥32 字节随机值）、CORS 域名、端口
docker compose up -d --build
docker compose ps           # 等待 mysql/redis/backend healthy
curl http://127.0.0.1/api/v1/actuator/health  # 实际会被 nginx 拦截，直接查看 backend 容器日志/健康检查
```

首次启动：MySQL 初始化数据库 → 后端启动时 Flyway 自动执行迁移（V1/V2/V3/V4）。

## 常用运维

```bash
docker compose logs -f backend       # 后端日志
docker compose logs -f nginx         # 访问日志
docker compose restart backend       # 重启后端（迁移会再次校验）
docker compose down                  # 停止（保留数据卷）
docker compose down -v               # 危险：删除数据卷（会清空数据库与上传文件）
```

## 备份

```bash
# 数据库备份（示例）
docker compose exec -T mysql mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" --single-transaction isles > backup-$(date +%F).sql
# 上传文件备份
docker run --rm -v isles_uploads:/data -v "$PWD":/backup alpine tar czf /backup/uploads-$(date +%F).tar.gz -C /data .
```

## 注意

- `deploy/.env` 已被 `.gitignore` 忽略；不要提交真实密钥。
- 生产必须启用 HTTPS（前置 Caddy/Nginx TLS 或云负载均衡）；`COOKIE_SECURE=true` 要求 HTTPS。
- 本机无 Docker，配置为「待服务器验证」状态；验证后更新 docs/09 与发布记录。

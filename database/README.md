# 数据库初始化

本目录提供**仅结构、不含任何数据**的建库脚本，便于新环境快速起步。

| 文件 | 内容 |
| --- | --- |
| `schema.sql` | 31 张表的完整 DDL（含索引、外键、注释），由 `mysqldump --no-data` 从 V1–V5 迁移全部应用后的库导出 |

> 不含 `flyway_schema_history`：该表由 Flyway 自行维护，手工导入时不需要。

## 方式 A（推荐）：空库 + Flyway 自动迁移

```bash
mysql -u root -p -e "CREATE DATABASE my_social_channel CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
```

配置好 `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` 后启动后端，Flyway 会按
`backend/src/main/resources/db/migration/V1..V5` 顺序建表，无需手工执行任何 SQL。

## 方式 B：手工导入结构文件

```bash
mysql -u root -p -e "CREATE DATABASE my_social_channel CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
mysql -u root -p my_social_channel < database/schema.sql
```

导入后表已存在，**启动后端时必须关闭 Flyway**，否则会重复建表失败：

```bash
# 任选一种
SPRING_FLYWAY_ENABLED=false mvn spring-boot:run
# 或在 .env.local / 环境变量中设置 SPRING_FLYWAY_ENABLED=false
```

## 注意事项

- 脚本开头包含 `DROP TABLE IF EXISTS`，请确认目标库为空，避免误删。
- 字符集固定 `utf8mb4` / `utf8mb4_0900_ai_ci`（MySQL 8.0 默认排序规则）。
- 时间字段统一 `DATETIME(3)`，应用侧按 UTC 存取。
- 升级结构请新增 Flyway 迁移（V6、V7…），不要直接改本文件或历史迁移。

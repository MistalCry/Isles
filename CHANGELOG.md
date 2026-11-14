# 变更记录

本文件格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

变更分类：`Added` 新增 / `Changed` 变更 / `Deprecated` 废弃 / `Removed` 移除 / `Fixed` 修复 / `Security` 安全。

## [Unreleased]

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
- 建立项目入口文件与工程基础配置（`README.md`、`.editorconfig`、`.gitignore`、`.gitattributes`）。

## [0.1.0] - 2026-11-14

### Added

- 项目初始化：规范与文档体系基线（详见 Unreleased 内容，本版本为首次提交）。

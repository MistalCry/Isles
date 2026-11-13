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
- 建立项目入口文件与工程基础配置（`README.md`、`.editorconfig`、`.gitignore`、`.gitattributes`）。

## [0.1.0] - 2026-11-14

### Added

- 项目初始化：规范与文档体系基线（详见 Unreleased 内容，本版本为首次提交）。

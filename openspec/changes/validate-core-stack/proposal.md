# 提案：M0 技术验证 —— 固定核心栈

## Why

DESIGN.md 第 22 节要求以 M0 作为实施起点：在写任何业务功能之前，先在真实环境验证
版本系列、数据库扩展、对象存储签名行为与 Python 处理协议，并提交依赖锁定。
跳过 M0 直接写 M1 会让选型风险（Spring Boot 4 / Java 25 工具链兼容性、MinIO 签名与
Range 行为、Java↔Python 进程协议）延迟到业务代码中暴露。

本变更同时完成仓库骨架（compose、OpenSpec、server 骨架），使后续里程碑有统一的启动方式。

## What Changes

- 新增 `compose.yaml` + `.env.example`：PostgreSQL（pgvector）/ RabbitMQ / MinIO 一键重建。
- 新增 `server/`：Spring Boot 4.x + Java 25 + Maven Wrapper 骨架，API 与 Worker 两种运行角色，
  Spring JDBC 显式 SQL + Flyway。
- 新增迁移 V1：启用 `vector` / `pg_trgm` 扩展；仅建 M0 所需最小表
  （assets、upload_sessions、jobs、job_events、outbox_events）。
- 新增上传协议最小链路：`POST /api/v1/uploads` → 预签名 PUT → `complete` 幂等核对 →
  Worker 流式 SHA-256 核验（真实 S3 SDK + MinIO 实测）。
- 新增 `processing/`：`python -m processing.run --task task.json` 固定入口与示例任务
  （阅读图 / 缩略图派生），stdout 仅 JSONL 进度、产物 result.json。
- 新增任务通知最小闭环：事务写 jobs + outbox_events → publisher confirm 标记派发 →
  Worker 条件更新取租约 → 匹配 lease_token 提交 → consumer ack。

## Capabilities

### New

- `upload-pipeline`：上传会话、对象核验、处理任务协议与任务通知的最小可验证行为。

## Impact

- 影响：新增 server / processing / compose / openspec 四个目录与首次依赖锁定。
- 不影响：MangaWeaver 仓库无任何改动；不实现身份、目录、阅读等 M1 功能。
- 风险：镜像标签与依赖版本需实测后锁定；MinIO 与"完全 S3 兼容"的差异以实测记录为准。

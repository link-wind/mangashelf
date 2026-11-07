# 设计：M0 技术验证

## Context

DESIGN.md 第 6.2/22 节确定了版本系列与 M0 范围，但补丁版本、镜像标签、MinIO 实际行为
均未经本机验证。本变更只做验证与最小实现，不引入 M1 业务表。

## Goals / Non-Goals

**Goals**

- Java 25 + Spring Boot 4.x 在本机真实构建并锁定补丁版本。
- PostgreSQL 扩展（vector / pg_trgm）经 Flyway 迁移在真实库启用。
- MinIO 预签名 PUT / GET、Range、CORS 行为实测并记录（回应 DESIGN 24.1-3）。
- Java Worker ↔ Python 固定任务入口协议（task.json / JSONL / result.json）跑通。
- outbox + 租约 + 幂等提交最小闭环在真实 RabbitMQ 上跑通。

**Non-Goals**

- 用户身份、目录、阅读、动画等 M1+ 功能。
- SSE、前端、完整任务恢复扫描（M0 只验证派发与租约提交路径）。

## Decisions

### D1：依赖版本在真实构建后锁定

Spring Boot 4.x / Spring AI 2.x 取官方当前稳定补丁号，构建通过后在 design.md 追加记录，
并写入 `server/pom.xml`。不照抄设计文档中的"最新"字样。（DESIGN 版本说明 [R1][R2]）

**实测追加（2026-11-07）**：Spring Boot 4.1.2（4.2.0-RC2 存在但非稳定）；
Jackson 3.1.7（Boot 4 默认 tools.jackson，spring-amqp 用 JacksonJsonMessageConverter）；
Flyway 12.4.0（须用 spring-boot-starter-flyway，裸 flyway-core 不触发 Boot 4 自动配置）；
AWS SDK 2.39.6；Spring AI 本阶段未引入，M4 前实测锁定。

### D2：镜像标签拉取实测后锁定

`pgvector/pgvector:pg18` 优先，拉取失败回退 `pg17`；RabbitMQ `4-management`；
MinIO `latest` 并记录实测镜像摘要。锁定结果写入 tasks.md 验证记录。

**实测追加（2026-11-07）**：pgvector/pgvector:pg18（PostgreSQL 18.6）✅；RabbitMQ 4.4.0 ✅；
**MinIO 已撤下 Docker Hub 官方镜像**（pull access denied），quay.io 与国内代理在本机 Docker
环境下不可达（401/403），开发存储改用 **chrislusf/seaweedfs:3.96**（sigv4 预签名、Range 实测通过；
4.50 的独立 IAM 服务使静态 identities 不再经 -s3.config 生效，故锁定 3.96）。
完整过程见 docs/validation/m0.md 第 3 节。

### D3：API 与 Worker 用同一工程 + 运行参数区分

`MangaShelfApplication` 单入口，`--mangashelf.role=api|worker`（默认 api）决定装配：
Worker 启用队列消费者与任务执行器，API 不装配任何任务执行组件。
避免两个 main 类漂移。（DESIGN 6.1）

### D4：M0 表结构按 DESIGN 8.2 收窄

只建 `assets`、`upload_sessions`、`jobs`、`job_events`、`outbox_events` 五表及索引；
字段与 8.2 一致，但不建 users（M0 上传会话 owner 暂用固定演示 UUID，
M1 引入 identity 后回填约束）。收窄理由：先验证协议，不预先设计权限。

### D5：Python 任务用项目内 venv + 固定入口

`processing/` 独立依赖（Pillow），`uv venv` 或 `python -m venv` 创建；Java 通过
`ProcessBuilder` 调 `python -m processing.run --task <abs task.json>`，工作目录为任务目录，
不拼接用户输入。（DESIGN 12.2）

### D6：对象 Key 与签名由服务端生成

M0 Key 规范：`users/<owner>/uploads/<upload-id>/original`；预签名 PUT 有效期默认 600s。
S3 SDK 用 AWS SDK v2（S3 Presigner），对 MinIO 路径风格访问
（endpoint 覆盖 + path-style）。

## Risks / Trade-offs

- MinIO 签名 URL 与真实 S3 存在行为差异 → 以实测记录边界，不写"完全兼容"。
- M0 固定演示 owner 是临时妥协 → M1 identity 落地时必须替换，记入 tasks.md 验收项。
- 并发 brew／docker 拉取受网络影响 → 镜像摘要与版本记录以实际拉取结果为准。

## Migration Plan

无既有数据。迁移 V1 为唯一基线；M1 在其上追加，不修改 V1。

## Open Questions

- ~~pgvector 镜像 pg18 标签在本机网络下是否可用~~ → 已解决：pg18 可拉取（PostgreSQL 18.6）。
- ~~Spring Boot 4.x + Java 25 + Spring AI 2.x 组合的具体补丁号~~ → 已解决：Boot 4.1.2 + Java 25
  构建通过；Spring AI 2.0.2 已在 Central 确认存在，M4 引入时实测。

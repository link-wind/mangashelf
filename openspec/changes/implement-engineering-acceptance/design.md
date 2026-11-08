# 设计：M5 工程验收

## Context

DESIGN 20（部署/可观测性/备份）、21（验证/评估）、22（M5 退出条件）为依据。
M0–M4 的验证记录分散于 docs/validation/m0–m4.md；本变更补齐部署与运维面，
并把中断恢复矩阵汇总成总表。

## Goals / Non-Goals

**Goals**

- `docker compose --profile full up -d` 一键起全栈（db/rabbitmq/storage/api/worker/web）。
- 请求日志关联（request_id + user_id 进 MDC，响应头 X-Request-Id）+ Prometheus 指标端点。
- 备份恢复演练可复现（脚本 + 实测结果）。
- 容量测量：固定数据集上关键端点延迟与索引吞吐，记录数字与规格边界。
- 中断恢复矩阵总表：M0–M4 已测项 + M5 补测项（API 退出后补投、下架后播放）。

**Non-Goals**

- Kubernetes、多副本扩缩、跨地域容灾（DESIGN 20.1 明确不做）。
- 完整可观测性平台（无 Prometheus 服务端部署，仅暴露格式端点）。
- 生产 TLS/域名（部署文档记录为环境配置事项）。
- HLS/转码等媒体能力（DESIGN 14.2 不做）。

## Decisions

### D1：镜像分层与模型外置

- `server/Dockerfile`（多阶段）：maven 构建层（依赖缓存优先）→ 运行层
  eclipse-temurin:25-jre。API 与 Worker 共用一个 jar 镜像（角色由
  MANGASHELF_ROLE 环境变量决定，DESIGN 6.1 两角色同工程）。
- Worker 运行层另建 `server/Dockerfile.worker`：在 API 运行层之上安装
  python3/venv/ffmpeg，装 processing 依赖，并把本机 RapidOCR 模型缓存
  （~/.cache 或 venv 内 rapidocr/models）拷入镜像——运行期零联网。
- e5 模型文件（133MB）以只读卷挂载（`./server/models:/opt/models:ro`），不进镜像。
- `web/Dockerfile`：node 构建 → nginx:alpine 提供静态 + /api、/media 反代。

### D2：compose 全栈用 profile 而非新文件

开发三件套（db/rabbitmq/storage）保持默认；api/worker/web 加 `profiles: ["full"]`。
`--profile full` 一次起全栈；日常开发不受影响。e5 模型挂载对 full 栈必需。

### D3：可观测性最小集（DESIGN 20.2）

Servlet 过滤器：请求进入生成 `request_id`（复用响应头/日志 MDC），认证后补
`user_id`；日志 pattern 注入 `%X{request_id} %X{user_id}`；异常与慢请求日志自带关联。
Actuator 增加 `prometheus` 端点（micrometer-registry-prometheus），暴露 JVM/HTTP 指标；
不部署 Prometheus 服务端（记录为环境事项）。

### D4：备份恢复按"数据库与对象存储一起"（DESIGN 20.3）

backup.sh：pg_dump（custom 格式）+ SeaweedFS 数据卷 tar 快照 + 时间戳目录。
restore.sh：空库 psql 恢复 + 卷内容回放 → 启动 → 以"已备份的作品列表 + 对象
GET 200"验证。演练先 `compose down -v` 证明从零恢复。

### D5：容量测量口径（DESIGN 21.3）

固定数据集：2 作品 × 12 页漫画（已发布）+ 1 动画；测量项与口径：
- 目录列表（GET /works?limit=20）：N=50 次，p50/p95；
- 固定版本页面窗口（GET /chapter-revisions/{id}/pages?limit=5）：N=50；
- 搜索（POST /search，字面+语义混合）：N=30；
- 索引吞吐：12 页 OCR+嵌入 批次端到端秒数与页/秒。
API 耗时与对象存储传输分开计（DESIGN 21.3）。结果如实记录，不外推生产容量。

## Risks / Trade-offs

- Worker 镜像体积较大（ffmpeg + onnxruntime + rapidocr 模型缓存），预计 1–2GB；
  记录体积，不做多阶段极限裁剪。
- 容器内首次 OCR/嵌入受 CPU 限制，吞吐数字与开发机一致记录（不外推）。
- 一键全栈在冷启动时（首次拉镜像/构建）耗时较长，属一次性成本。

## Migration Plan

无数据库结构变更（V1–V6 不动）。

## Open Questions

- eclipse-temurin:25-jre 镜像在本机网络下的拉取（实测，必要时用国内镜像源记录）。

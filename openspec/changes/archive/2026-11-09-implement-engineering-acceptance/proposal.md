# 提案：M5 工程验收 —— 部署、可观测性与集中验证

## Why

M0–M4 完成了全部功能里程碑，但系统只以"本机进程 + 开发 compose"方式运行；
DESIGN 22 要求 M5 集中验证：部署（可重建）、备份恢复、容量测量与中断恢复矩阵
的复现步骤、日志、结果及已知限制。本变更不新增业务功能。

## What Changes

- 部署：API/Worker 容器镜像（多阶段构建，Worker 内置 Python venv + RapidOCR 模型缓存
  + ffmpeg），前端静态构建 + nginx 反向代理（/api、/media 同源），compose 全栈
  profile（`docker compose --profile full up -d` 一键起完整栈）。
- 可观测性：请求日志贯穿 request_id/user_id（MDC），响应回写 X-Request-Id，
  Actuator 暴露 Prometheus 指标（DESIGN 20.2 最小集）。
- 验证：中断恢复矩阵补测（API 退出后 outbox 由 Worker 侧派发补投、下架后播放 404）、
  备份恢复演练（pg_dump + 存储卷快照 → 重建 → 恢复 → 对象可读）、
  固定数据集容量测量（目录/页面/检索延迟与索引吞吐，DESIGN 21.3）。
- 汇总：docs/validation/m5.md 汇总 M0–M4 已验证矩阵与 M5 新测项、日志样本、
  测量数字、已知限制；README 部署一节更新。

## Capabilities

### New

- `operations`：全栈容器部署、请求日志关联与指标端点、备份恢复脚本。

## Impact

- 影响：新增 Dockerfile ×3（api/worker/web）、nginx 配置、compose profile、
  一个 Servlet 过滤器、micrometer-prometheus 依赖、backup/restore/bench 脚本。
- 不影响：业务行为；协议；数据库结构。
- 风险：容器内 e5 模型文件以只读挂载提供（镜像不含 133MB 模型，保持轻量）；
  容量数字仅代表演示规格（非生产容量结论，DESIGN 21.3）。

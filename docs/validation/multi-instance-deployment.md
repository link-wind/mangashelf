# multi-instance-deployment 验证记录

- 日期：2026-11-13
- 基线：`main` `5b484a4` + 本变更工作区改动
- 环境：单机 compose 双实例演练——`--profile full,multi`：api-1(:8080) + api-2(:8081) + worker×2 + migrate 一次性步骤 + db/rabbitmq/redis/storage

## 验收场景与结果（distributed-evolution 第 6 节对照）

| 场景 | 结果 |
| --- | --- |
| migrate 一次性步骤 | 跑 25 个迁移后**优雅退出 code 0**；embedding 关闭（migrate 模式不需要模型）；失败时非零码阻止发布的路径由 Spring Boot 退出语义保证 |
| 双 API 就绪 | api-1(:8080)/api-2(:8081) 同时 Up；`MANGASHELF_PLATFORM_ID` 分别为默认值/`mangashelf-api-2`；两副本 `MANGASHELF_DB_MIGRATE=false` 无迁移竞态 |
| 多 API 交替请求 | 登录（A/B 各 200）、收藏列表一致（三类型各 1）、A 收藏 → B 立即可见 |
| Session 跨实例 | A 实例登录的会话 Cookie 直接访问 B 实例收藏接口 → 200（Spring Session JDBC 共享） |
| 进度跨实例 | A 写 page5(v2) → B 读到 → B 写 page9(v3) → A 读到 page9/v3（版本递增语义跨实例一致） |
| API 重启后的 SSE | 任务事件流从实例 A 断开（游标 id:2），携带 `Last-Event-ID: 2` 到实例 B → **恰好补回 id:3、id:4** |
| Worker 中途退出 | 停全部 Worker → 制造 RUNNING+过期租约 → 恢复扫描置回 QUEUED 并写 dispatch 2（原子）→ 重启双 Worker 后 attempt 2 **SUCCEEDED**（另一实例接手完成） |
| 双 Worker 并发领取 | 新解析任务仅 worker-1 领取执行（claimed 日志 1 次），worker-2 无领取记录；任务 attempt=1 SUCCEEDED，无重复执行 |
| 页图缓存共享卷 | api-1 下载哔哩页图（1.1MB 写入卷）→ api-2 同 URL 请求同卷命中返回同尺寸；两容器挂载同一 `bilibili-http-cache` 卷 |
| 入口 upstream | web 镜像内 nginx 含 `upstream mangashelf_api`（api + 注释的 api-2 行）；经 :5173 入口访问 API 正常转发 |
| 单实例默认行为 | 未启用 multi profile 时服务清单无 api-2/migrate；`MANGASHELF_DB_MIGRATE` 默认 true 保持"API 启动迁移" |
| 测试 | 43/43 通过；编译通过 |

## 实施发现

- **migrate 容器需禁 embedding**：API 镜像的 EmbeddingConfig 在模型文件缺失时启动失败；
  migrate-only 模式加 `--mangashelf.embedding.enabled=false`（无需挂模型卷）。
- **演练暴露的旧镜像问题**：api-1 首次启动使用了构建缓存中的旧 jar（缺收藏端点/version 字段），
  重建后两实例镜像对齐——多实例下"同镜像版本"必须显式保证（第二阶段按 digest 部署）。
- **8080/5173 宿主端口冲突**：本地 dev 实例（PID 50296）与 vite 占用端口，演练前已停；
  跨机部署时端口由各机自行管理，无此问题。

## 已知限制

- 入口仍为单 web 容器（单点）；nginx upstream 已支持多上游，启用 api-2 需取消注释并重建 web。
- 双 Worker 的解析类任务并发由 `--scale worker=N` 控制，未按队列拆分实例角色（部署者可用
  `MANGASHELF_CONSUME_QUEUES` 区分）。
- 数据服务（db/redis/rabbitmq/storage）仍为单点——第二阶段（跨机 + 数据服务高可用）范围。
- 演练后应用副本已停止，数据服务保留（数据无损）。

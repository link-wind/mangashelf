## Why

docs/distributed-evolution.md 第 5 节第一阶段：代码层已具备多实例能力（共享 Session/Redis/共享卷、任务租约、队列路由），但部署层还是单实例假设——Flyway 由 API 启动触发（两个 API 同时启动会竞态跑迁移）、compose 只有单 api/worker 服务、入口无多上游配置。本变更在**单机双实例**上完成第一阶段的部署形态与验收演练，为跨机（第二阶段）提供可复制的服务定义。

## What Changes

- Flyway 迁移与 API 角色解耦：新增 `MANGASHELF_DB_MIGRATE=true|false` 开关（默认 true 保持现有行为）；compose 中 API 实例默认关闭迁移，新增一次性 `migrate` 服务（与 api 同镜像，启动即跑迁移后退出）。
- compose 新增 `api-2` 服务（同镜像同配置，复用 `full` profile；`replicas` 无法用 `docker compose up` 单独管理，采用显式服务定义），Worker 保持单服务但可用 `--scale worker=N` 横向扩。
- 入口层：`deploy/nginx/nginx.conf` 的 API 上游改为 `upstream` 块（api-1/api-2，默认注释掉 api-2 以兼容单实例部署，多实例启用）；Web 反代配置同步。
- `deploy/compose.multi.yaml` 多实例覆盖层：api-2 服务定义 + 卷共享（bilibili-http-cache 共享卷多实例挂载）。
- 双实例演练：交替请求会话/收藏/进度一致；SSE 事件游标跨实例补读；Worker 中途退出后租约恢复；重复队列通知不重复提交。
- 平台实例标识：`MANGASHELF_PLATFORM_ID` 按实例区分（api-1/api-2），用于日志与来源排查。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `upload-pipeline`：数据库迁移必须由单一部署步骤执行（多实例下 API 副本禁用启动迁移）。
- `platform-integration`：多实例部署形态要求（入口 upstream、一次性迁移服务、实例标识）。

## Impact

- 后端：`MangaShelfApplication`/`application.yml`（迁移开关）
- 部署：compose.yaml（migrate 服务、api-2）、deploy/compose.multi.yaml（新）、deploy/nginx/nginx.conf（upstream）
- 无业务代码改动；验证以真实双实例演练为主

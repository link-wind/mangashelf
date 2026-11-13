## Context

动机见 proposal.md，依据 docs/distributed-evolution.md 第 5 节第一阶段与 9.4 落地顺序。当前实现事实（2026-11-13 核验）：

- Flyway：API 角色启动迁移（`application.yml` spring.flyway.enabled=true），Worker 已禁用；两 API 并发启动有 V15 迁移竞态前科（MangaSourceRegistry 注释记录）。
- compose：单 `api`（profile full）+ 单 `worker`；bilibili-http-cache 命名卷已挂 api（多实例共享卷天然支持）；Redis 已接入。
- 入口：`deploy/nginx/nginx.conf` 单上游 `proxy_pass http://api:8080`；`/media/` 反代 storage。
- 代码层多实例能力已就绪（前四个变更）：任务租约、队列路由、Redis 共享缓存、统一进度语义。

## Goals / Non-Goals

**Goals:**

- 单机双 API + 双 Worker 的部署定义与一键演练。
- 迁移与常驻副本解耦（一次性 migrate 服务）。
- 入口 upstream 多上游配置（默认单实例不受影响）。
- 完成第 5 节第一阶段验收清单。

**Non-Goals:**

- 不做跨机部署与真实负载均衡（第二阶段）。
- 不做 Kubernetes/自动扩缩容。
- 不做数据服务（db/redis/rabbitmq）高可用。
- 不引入服务发现；upstream 是静态配置。

## Decisions

1. **迁移开关：`MANGASHELF_DB_MIGRATE` 环境变量映射到 `spring.flyway.enabled`。**
   实现：`application.yml` 改 `enabled: ${MANGASHELF_DB_MIGRATE:true}`——语义直白、不改代码。
   compose 中 api/worker 显式 `MANGASHELF_DB_MIGRATE: false`；新增 `migrate` 一次性服务
   （同 API 镜像、`command: java -jar app.jar --mangashelf.role=api --spring.main.web-application-type=none --mangashelf.jobs.dispatch-interval-ms=999999` 类方式只跑迁移退出）。
   更简单的做法：migrate 服务复用 API 镜像并覆盖 entrypoint 跑 Flyway Maven？——镜像里没有
   Maven。采用：`java -jar app.jar --mangashelf.role=api --server.port=0 --spring.datasource...`
   + 立即退出不优雅。**最终方案**：给应用加 `--mangashelf.migrate-only=true` 开关
   （`MangaShelfApplication` 解析后调用 `SpringApplication.exit`）——启动 → Flyway 跑完 →
   优雅退出（exit code 0）。单点改动、语义明确。
   备选：独立 Flyway CLI 容器——引入新镜像，与同镜像原则冲突。

2. **api-2 用显式服务定义而非 `deploy.replicas`。**
   `docker compose up --scale api=2` 要求服务无静态端口映射（端口冲突），而当前 api 需要
   宿主端口（本地调试）。多实例覆盖层 `deploy/compose.multi.yaml` 定义 `api-2`
   （`<<: *api` anchor 复用 + 端口改 8081、PLATFORM_ID=api-2），`--profile multi` 隔离。
   备选：去掉宿主端口纯 upstream——损失本地直连调试便利。

3. **入口 upstream：nginx.conf 增 `upstream mangashelf_api` 块。**
   默认只含 `server api:8080`；`server api-2:8080` 行以注释提供（部署多实例时取消注释）。
   compose.multi.yaml 挂载对应 nginx.conf 变体？——不引入第二份配置文件，文档说明"多实例时
   取消注释并重建 web 容器"即可（低频操作）。

4. **双 Worker：`--scale worker=2`。**
   worker 无静态端口，scale 天然可用；`consume-queues` 两实例都消费（SKIP LOCKED + 租约
   保证不重复）。浏览器解析类 Worker 数量由部署者按资源决定，compose 不做特殊化。

5. **实例标识：compose.multi.yaml 中 api-2 设 `MANGASHELF_PLATFORM_ID=api-2`。**
   api 保持默认（或部署者自定）；仅用于日志与外部来源记录排查，无业务逻辑依赖。

## Risks / Trade-offs

- [双 API 竞态写 Session 行] → Spring Session JDBC 行级唯一键保证；演练覆盖交替请求。
- [migrate-only 开关误用于常驻] → 该模式跑完迁移即退出，误配置表现为容器退出（显式可见）。
- [upstream 未启用 api-2 时流量全走 api-1] → 默认行为与单实例一致；文档明确启用步骤。
- [--scale worker 与 compose multi 覆盖层叠加] → scale 只作用于 worker 服务，无冲突。

## Migration Plan

1. 代码加 migrate-only 开关（一个类 + application.yml 一行）。
2. compose 加 migrate 服务（profile multi）与 `MANGASHELF_DB_MIGRATE` 默认值。
3. `deploy/compose.multi.yaml` 覆盖层（api-2 定义）。
4. 演练：`--profile full,multi up` 双 API 双 Worker；按第 6 节验收清单逐项验证。
5. 回滚：不启用 multi profile 即回到单实例形态；无数据变更。

## Open Questions

无。

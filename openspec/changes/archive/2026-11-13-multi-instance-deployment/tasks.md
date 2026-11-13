## 1. 迁移解耦

- [x] 1.1 `MangaShelfApplication` 加 `--mangashelf.migrate-only=true` 开关：Flyway 完成后优雅退出（exit 0）
- [x] 1.2 `application.yml` flyway enabled 改 `${MANGASHELF_DB_MIGRATE:true}`；compose 中 api/worker 设 false
- [x] 1.3 compose 新增 `migrate` 一次性服务（profile multi，API 镜像，migrate-only 模式）

## 2. 多实例部署定义

- [x] 2.1 新增 `deploy/compose.multi.yaml`：api-2 服务（复用 api 定义、端口 8081、PLATFORM_ID=api-2）
- [x] 2.2 `deploy/nginx/nginx.conf` 加 `upstream mangashelf_api`（api + 注释掉的 api-2 行）
- [x] 2.3 验证：单实例 `--profile full` 行为不变（无 multi profile 时无 api-2/migrate）

## 3. 双实例演练（第一阶段验收）

- [x] 3.1 `--profile full,multi` 启动：migrate 跑完退出 → api-1/api-2/worker×2 就绪；双 API 交替请求登录/收藏/进度一致
- [x] 3.2 SSE 游标：任务事件经 `/api/v1/jobs/{id}/events` 从实例 A 断开后用 Last-Event-ID 在实例 B 补读
- [x] 3.3 Worker 中途退出：`--scale worker=1` 制造租约过期，恢复扫描重派后另一实例接手完成
- [x] 3.4 重复队列通知不重复执行（沿用 harden 变更验证方法，双 Worker 并发领取）
- [x] 3.5 页图缓存共享卷：api-1 下载的哔哩页图对 api-2 可读（cacheHit 命中）

## 4. 收尾

- [x] 4.1 演练结果与已知限制写入 `docs/validation/multi-instance-deployment.md`
- [x] 4.2 `mvnw test`、`npm run build` 通过；`openspec validate` 通过

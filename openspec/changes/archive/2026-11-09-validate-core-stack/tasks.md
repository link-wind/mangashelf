# 任务：M0 技术验证

## 环境与骨架

- [x] 启动 Docker Desktop，守护进程可用（实测 29.4.1）
- [x] 安装 JDK 25 并确认 `JAVA_HOME` 下 `java -version` 输出 25
- [x] 仓库骨架：README / AGENTS.md / .gitignore / .env.example / docs 迁移，git 首次提交
- [x] `compose.yaml` 通过 `docker compose up -d` 三服务健康，`down -v` 后可重建
- [x] OpenSpec 初始化 + 本变更提案

## server 骨架

- [x] Maven Wrapper + pom：Java 25、Spring Boot 4.x（真实构建后记录具体补丁号）、
      spring-web、spring-jdbc、flyway、postgresql、AWS SDK v2 S3、spring-amqp、
      spring-session-jdbc（M0 仅引依赖不启用）、actuator
- [x] `MangaShelfApplication` + `--mangashelf.role` 装配区分（D3）
- [x] application.yml 从环境变量读取 DB / RabbitMQ / S3 配置（对齐 .env.example）
- [x] API 角色可启动：`/actuator/health` 返回 UP

## 数据库

- [x] 迁移 V1：`CREATE EXTENSION vector`、`CREATE EXTENSION pg_trgm`
- [x] 迁移 V1：assets / upload_sessions / jobs / job_events / outbox_events（DESIGN 8.2 收窄，D4）
- [x] Flyway 迁移在真实 PostgreSQL 上执行成功，`\dx` 可见两扩展

## 上传链路

- [x] `POST /api/v1/uploads`：服务端生成对象 Key + 预签名 PUT（真实 S3 SDK + MinIO）
- [x] 浏览器外工具（curl）实测：预签名 PUT 上传成功；过期签名被拒；Range GET 可用；CORS 记录
- [x] `POST /api/v1/uploads/{id}/complete`：幂等核对；重复调用返回同一结果不重复创建
- [x] Worker 任务 `content.verify`：流式 SHA-256 → assets 置 VERIFIED（M0 演示 owner，D4）

## Python 协议

- [x] `processing/run.py` 固定入口：--task task.json；stdout 仅 JSONL 进度；stderr 日志；result.json
- [x] 示例任务 `image.derive`：输入原图清单 → 输出阅读图 + 缩略图（相对路径、尺寸、SHA-256）
- [x] Java 校验产物路径在任务目录内、类型一致、摘要核对后上传对象存储

## 任务通知闭环

- [x] 事务内写 jobs + outbox_events；派发器 publisher confirm 后标记已派发（DESIGN 11.1）
- [x] Worker 条件更新取租约（lease_token）；重复消息读到 SUCCEEDED 直接确认结束
- [x] 提交匹配 status + lease_token；过期 Worker 不能覆盖新 attempt（DESIGN 11.2）
- [x] 注入实验：同一消息投递两次，任务不重复执行；记录结果

## 验证与收尾

- [x] 全链路演练：创建上传 → PUT → complete → Worker 核验 → 处理任务 → 产物上传，一次通过
- [x] `compose down -v && up -d` 重建演练后全链路复跑
- [x] 锁定版本记录追加至本 design.md；`docs/validation/m0.md` 写实测结果与已知限制
- [x] 演示 owner 收窄项列入 M1 必办（identity 落地时替换）

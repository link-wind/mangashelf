# MangaShelf 实施计划

## Context

mangashelf 是全新独立项目：目录内目前只有 [DESIGN.md](/Users/linkwind/Code/mangashelf/DESIGN.md)（详细设计 v0.1）与架构图，尚无代码。设计文档已确定选型（Java 25 + Spring Boot 4.x 模块化单体，API/Worker 两运行角色；PostgreSQL + pgvector/pg_trgm；RabbitMQ；S3 兼容存储；React 前端；Python 固定任务），并明确要求**按 M0–M5 分阶段实施、每阶段建立 OpenSpec 变更、只建当期所需表和端点**。本计划不改设计决策，只做落地排期。

环境现状（已核实）：Java 未安装、Docker Desktop 已安装但守护进程未启动；Python 3.14.6、FFmpeg 8.1、Node 25.9、brew、openspec CLI 均就绪。目录还不是 git 仓库。

探索确认的复用事实：MangaWeaver（HEAD `e057b32`）的 `src/mangaweaver/preprocessing/` 三组件（`import_manga.py` 图片/CBZ/PDF/MOBI 导入与自然页序、`crop_panels.py` MAGI 分格、`transcript.py` OCR 归属）**无桌面 GUI 耦合、可独立调用**；其 `book.json` 清单已含 sha256/1 起始页序/pageNNNN 稳定 ID/输入签名防混用约定（缺页宽高，Java 版契约补上）；OpenSpec 约定可沿用 MangaWeaver 风格（spec-driven schema、中文 context/rules、proposal/design/tasks/specs delta 结构）。

## 第 0 步：环境准备

1. 启动 Docker Desktop（守护进程当前未运行）。
2. 安装 JDK 25（brew，实施时确认配方名 `openjdk@25` 或 Temurin，备选回退）并配置 JAVA_HOME。
3. `git init` 初始化仓库。

## 第 1 步：仓库骨架（设计第 7 节）

- 建目录：`server/`、`web/`、`processing/`、`openspec/`、`docs/`（将 DESIGN.md 与 architecture.\* 移入 docs/）、`README.md`、`AGENTS.md`（沿用 MangaWeaver AGENTS.md 的工程约定：中文文档、精简主路径、显式报错、少量关键测试）、`.env.example`、`.gitignore`。
- 首次提交：骨架 + 文档迁移。
- `openspec init` + `config.yaml`（沿用 MangaWeaver 风格）。
- `compose.yaml`：PostgreSQL（pgvector 镜像，扩展由 Flyway 迁移管理）+ RabbitMQ + MinIO（开发用 S3）；`.env.example` 按设计第 19 节列占位配置。

## 第 2 步：M0 技术验证（OpenSpec 变更 `validate-core-stack`）

退出条件（设计 22 节）：**可重建环境；一个文件上传与一个处理任务通过**。

1. `server/` Spring Boot 4.x 骨架：Java 25、Spring MVC、Spring JDBC（显式 SQL）、Flyway、Maven Wrapper；`MangaShelfApplication` + api/worker 两种启动角色（共用业务模块）。
2. 迁移 V1：`CREATE EXTENSION vector / pg_trgm` + 仅 M0 所需最小表（`assets`、`upload_sessions`、`jobs`、`job_events`、`outbox_events`）。
3. 上传协议链路（设计 10.2）：`POST /api/v1/uploads` → 服务端生成 Key → MinIO 预签名 PUT（真实 S3 SDK，实测签名/Range/CORS，回应待验证项 24.1-3）→ complete 幂等核对 → Worker 流式计算 SHA-256 → VERIFIED。
4. Python 协议（设计 12.2）：`python -m processing.run --task task.json` 固定入口；示例任务（阅读图 WebP + 缩略图）；stdout 仅 JSONL 进度、stderr 日志、`result.json` 产物；Java 校验路径/类型/摘要后上传再事务提交。
5. 任务通知最小闭环（设计 11.1–11.2）：事务写 jobs+outbox → publisher confirm 后标记派发 → Worker 条件更新取租约 → 心跳 → 匹配 lease_token 提交 → consumer ack；注入"重复消息""派发确认前后中断"实验并记录。
6. 真实构建后锁定 Java/Spring Boot/Spring AI 补丁版本（设计版本说明 [R1][R2]），验证记录写入 `docs/validation/m0.md`；compose down -v 后一键重建演练。

## 第 3 步：M1 漫画阅读（OpenSpec 变更 `implement-manga-reading`，M0 验收后开始）

- `identity/`：users、登录、Spring Security + Spring Session JDBC、CSRF、统一错误结构（16.2）。
- `catalog/`：works/chapters/chapter_revisions/pages 及约束（8.3 组合外键）、状态机（第 9 节）、发布事务（10.4 锁定+切 current_revision_id）。
- `storage/`：assets 绑定校验、签名读 URL、Key 规范（18.1）。
- `ingestion/`：扩展为完整任务模型（attempt 上限、租约恢复扫描、取消标志、任务 SSE + Last-Event-ID 补读）。
- `processing/`：CBZ/多图解析任务（移植 `import_manga.py` 自然排序与解压防护，剥离 events.emit 的 conversation 惰性依赖，固定来源提交并核对许可证；Pillow 派生图+缩略图）；PDF 后置。
- `library/`：收藏、阅读进度（expected_version 409 冲突，14.3）。本阶段不建 `integration/`、`retrieval/`。
- `web/` React+TS+Vite 最小站：书库、漫画详情、阅读器（游标分页+签名图+续读）、我的素材（上传/页序确认/发布/任务进度）、我的书架；视觉打磨后置。
- 验收（21.1 子集）：图片/CBZ 导入、重复完成上传幂等、章节换版不混页、私人素材不可见、Worker 中断恢复；**"上传一章，另一用户按权限阅读，刷新能继续"**。

## 第 4–7 步：M2–M5（每阶段开始时再建 OpenSpec 变更并细化）

- **M2 动画观看**：animations/animation_revisions/animation_sources、ffprobe 媒体验证（13.3）、播放签名 URL + Range/续播实测（14.2）、播放器页、漫画↔动画双向跳转（4.1）。
- **M3 项目闭环（首个完整产品里程碑）**：api_tokens scope（17.1）、`/integrations/mangaweaver/exports|publications`（17.2/17.3）、MangaWeaver 侧独立 OpenSpec 变更；真实导入 + 成片发布回执验收。
- **M4 智能检索**：panels/OCR（复用 crop_panels/transcript，RapidOCR）、search_chunks/index_generations 批次原子激活、pg_trgm 字面检索、Spring AI Embedding（模型届时验证 24.1-5）、RRF 混合召回、证据 ID 引用问答（15.4）、30–50 条评估集（21.2）。
- **M5 工程验收**：中断恢复矩阵、权限矩阵、备份恢复演练（20.3）、固定数据集容量测量（21.3）、Actuator/结构化日志最小可观测性（20.2）。

## 执行原则（来自设计文档，不重新决策）

数据库任务为事实、队列只通知；显式 SQL 无 ORM；发布是用户操作不自动公开；跨模块明确方法调用，不建插件注册中心/DAG 引擎；版本号经真实验证才写入文档；未实现不写"已实现"。

## 验证方式

- M0：compose 全新重建 + "上传→核验→Python 任务→产物上传"全链路一次通过；重复消息与中断注入实验各一次；依赖锁定提交。
- 每阶段按 21.1 对应场景核对，记录写 `docs/validation/`；单元测试只覆盖复杂映射/事务/权限判断，事务与队列行为在真实 PostgreSQL/RabbitMQ 中验证（21.1 末尾）。

## 本次批准后立即执行的范围

第 0–2 步（环境准备 + 仓库骨架 + M0 全部）。M0 退出条件达成并汇报后，再按 OpenSpec 流程开始 M1。
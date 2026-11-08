# 设计：M1 漫画阅读

## Context

DESIGN 3/8/9/10/14/16 节定义了身份、领域模型、状态机、发布事务、进度同步与端点；
M0 已锁定核心栈（Boot 4.1.2 / Jackson 3 / Flyway 12.4 / SeaweedFS 3.96，见 docs/validation/m0.md）。
本变更只做 M1 范围：能读；动画（M2）、接入协议（M3）、检索（M4）不引入。

## Goals / Non-Goals

**Goals**

- 真实用户与权限：注册／登录／会话，所有权与可见性统一判定（DESIGN 3.3）。
- 上传一章（CBZ 或单图）→ 解析为固定版本页面 → 页序确认 → 发布 → 另一用户按权限阅读、续读。
- M0 收窄项闭环：owner 外键、租约恢复扫描、取消／重试、任务 SSE。
- 最小前端覆盖 DESIGN 第 4 节的九个页面中的六个（首页/书库、详情、阅读器、我的素材、书架、登录）。

**Non-Goals**

- PDF／MOBI 导入（DESIGN 10.1 第二批）；"有序多图"先经 CBZ 承载，多会话拼页后置并记录边界。
- 动画任何表与端点；章节级 ACL（DESIGN 3.2 章节继承作品可见性）。
- 前端视觉打磨、组件库选型；检索与 pg_trgm 查询优化（M4）。

## Decisions

### D1：会话用 Spring Session JDBC + Cookie CSRF（DESIGN 6.2/16）

浏览器同源 Cookie（HttpOnly Session + 非	HttpOnly XSRF-TOKEN），SPA 以 `X-XSRF-TOKEN` 头回传；
Session 存 PostgreSQL（spring_session 表由 Spring Session JDBC 自动建表）。
Token（Bearer）属 M3 接入协议，本阶段不实现。登录端点手动认证并保存 SecurityContext
（官方 SPA 模式），CSRF Cookie 仓库 + 每 请求写回。

### D2：M0 收窄回填以 V2 前向迁移完成

V1 已发布的迁移不改写。V2：建 users 并将 M0 演示 owner 落为真实引导管理员（密码来自
`MANGASHELF_BOOTSTRAP_ADMIN_PASSWORD`，启动时若 users 为空则创建——放代码而非 SQL，
避免在迁移中固化哈希）；assets/upload_sessions/jobs 补 owner 外键；
jobs 增加 chapter_revision_id 并按任务种类约束目标（DESIGN 8.2）。

### D3：任务种类按目标收敛（DESIGN 8.2 按任务种类约束）

队列仍只有 `content.process`（DESIGN 11.1）。任务种类：`content.verify`（目标 upload_session，
不再链式派生）、`chapter.parse`（目标 chapter_revision，产出页面清单）。
M0 的 content.process 派生任务退役——其职责由 chapter.parse 以"页面资产"承接，
数据库 CHECK 保证每种任务的目标外键唯一非空。

### D4：章节解析以固定来源提交移植 MangaWeaver（AGENTS 复用与来源）

Python `chapter.parse` 移植 `import_manga.py`（基线 e057b32）中的：
自然排序 key（数字段按整数）、zip 防护（contained 路径检查、单文件 100MB／总量上限、
条目数上限、拒绝目录与嵌套压缩）、EXIF 转正 + RGB 化、缩略图 240x320。
剥离 `events.emit` 与 `..io/..schemas` 依赖，输出走 12.2 协议（task.json / JSONL / result.json）。
清单在 book.json 基础上补充每页宽高（M0 探索确认的缺口）与逐页 SHA-256。
来源记录：仅复用排序与防护的判定逻辑，不复制桌面 IO 结构；许可证核对记录于 tasks.md 验证项。

### D5：页面清单与授权模型（DESIGN 14.1）

`GET /chapter-revisions/{id}/pages` 按固定版本分页（游标 + limit），窗口内直接返回
短期预签名 GET（数据库不存签名地址）；授权判定：版本 PUBLISHED 且（作品 PUBLISHED+PUBLIC
或操作者为 owner/admin）。资产访问端点按 asset → page → revision → work 追溯授权。
"有序多图"首版经 CBZ 承载：单文件 PUT 上传协议与多文件页序确认的体验矛盾先不解决，
记录为已知边界。

### D6：发布事务按 10.4 实现

一个事务内：SELECT … FOR UPDATE 锁章节行 → 校验版本仍 READY 且属于该章节 →
版本置 PUBLISHED → 切换 chapters.current_revision_id（组合外键保证归属正确，
V2 建 UNIQUE(id, chapter_id) + 复合 FK）。阅读 API 先解析当前版本再按固定版本分页，
不拼接两版页面（DESIGN 10.4）。

### D7：进度冲突用 ON CONFLICT 条件更新（DESIGN 14.3）

`INSERT … ON CONFLICT (user_id, chapter_id) DO UPDATE … WHERE reading_progress.version = ?`
RETURNING；无返回行即读取当前快照返回 409 PROGRESS_CONFLICT。允许前后跳页，
不用 max(page)。播放进度 M2 另建表，不共用字段。

### D8：任务 SSE 轮询数据库事件（DESIGN 11.4）

`GET /jobs/{id}/events` 返回 text/event-stream：服务端 SseEmitter 每 ~600ms 读
job_events（sequence > Last-Event-ID / after_sequence），终态推送后完成连接。
事件 ID 即任务内序号；慢连接不阻塞 Worker（读库不改库）。M1 不引入 WebSocket。

### D9：前端同源代理（DESIGN 19）

web/ 开发服务器代理 `/api → :8080`、`/media → :9000`（changeOrigin 保留签名 Host），
生产由同源反向代理承接同一规则。预签名 URL 在前端把 `http://localhost:9000` 前缀替换为
`/media`，签名按目标 Host 校验不受影响。

## Risks / Trade-offs

- Security 7 + Boot 4 组合的 CSRF／Session 行为需实测，构建期可能需微调。
- 恢复扫描与取消在"阶段边界"生效：Python 子进程运行中不可中断，M1 先接受
  （单任务 ≤ 分钟级）；进程组终止放 M2 媒体处理时一并处理（DESIGN 11.4）。
- 页序确认后未重发布即换版阅读的边界：发布后的版本清单不可变（DESIGN 8.2），
  调序创建新版本，M1 不提供"已发布版本调序"。

## Migration Plan

V1 → V2 前向迁移；旧数据（演示 owner 的上传记录）经 V2 种子行保持可读。
无对外 API 兼容负担（M0 端点本就是内部验证面）。

## Open Questions

- Spring Session JDBC 在 Boot 4 的表自动建表与索引行为（实现时核实）。
- SeaweedFS 3.96 的 bucket CORS 能力：若不支持，开发走 D9 代理、生产记录为
  部署项（同源反向代理本就是设计方向）。

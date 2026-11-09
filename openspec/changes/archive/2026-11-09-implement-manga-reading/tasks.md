# 任务：M1 漫画阅读

## 迁移与身份

- [x] 迁移 V2：users / works / chapters / chapter_revisions / pages / library_entries / reading_progress
- [x] V2 回填：M0 三表补 owner 外键；演示 owner 落为引导管理员；jobs 增 chapter_revision_id
      与按任务种类约束（content.verify→upload_session，chapter.parse→revision，content.process→upload_session）
- [x] chapters.current_revision_id 组合外键（UNIQUE(id, chapter_id) + 复合 FK）
- [x] Spring Security + Spring Session JDBC 依赖与配置；session 表自动建立
- [x] 注册 / 登录 / 登出 / GET /me；CSRF Cookie 仓库 + SPA 回传头；引导管理员启动创建
- [x] 演示 owner 代码路径移除：控制器一律从会话解析当前用户

## 目录与发布

- [x] works / chapters / chapter_revisions 仓库与服务（状态机见 DESIGN 9，状态值数据库 CHECK 限定）
- [x] 端点：GET/POST /works、GET/PATCH /works/{id}、POST /works/{id}/publish|withdraw、
      POST /works/{id}/chapters、GET /chapters/{id}、POST /chapters/{id}/revisions
- [x] 列表与详情按可见性过滤（owner 或 PUBLIC+PUBLISHED），游标分页稳定排序
- [x] 页序确认 PATCH /chapter-revisions/{id}/page-order（READY 状态 + 期望 edit_version，冲突 409）
- [x] 发布事务 POST /chapter-revisions/{id}/publish：锁章节行、READY 校验、置 PUBLISHED、
      原子切换 current_revision_id（10.4）

## 解析任务

- [x] VerifyJobHandler 去链式；新 ChapterParseHandler：修订 PROCESSING→READY，
      建 pages 行与页面资产（含逐页宽高）、计算 manifest_sha256、page_count
- [x] Python chapter.parse：CBZ 防护（条目数/总量/单文件上限、路径检查、拒绝嵌套）、
      自然页序、EXIF 转正、reader.webp + thumbnail.webp（240x320）
- [x] 复用来源记录：import_manga.py @ e057b32 的移植范围与许可证核对结论写入本 design.md 追加段
- [x] POST /chapters/{id}/revisions {upload_id}：核验通过的上传 → 新修订 + chapter.parse 任务

## 任务补全

- [x] 租约恢复扫描：过期租约 → attempt 未超限则重派发（dispatch_no 递增），超限置 FAILED
- [x] POST /jobs/{id}/cancel（阶段边界生效）与 POST /jobs/{id}/retry（FAILED 显式重跑）
- [x] GET /jobs/{id}/events SSE：Last-Event-ID / after_sequence 补读，终态后关闭

## 书架与进度

- [x] PUT/DELETE /me/library/{work_id}、GET /me/library（收藏 + 最近阅读 + 最近观看占位）
- [x] PUT /me/reading-progress/{chapter_id}：expected_version 不符返回 409 + 当前快照（14.3）
- [x] 资产访问授权按 asset → page → revision → work 追溯；私人内容其他用户不可读

## 前端 web/

- [x] Vite + React + TS 脚手架；API 封装（CSRF 头、错误结构解析）；/api 与 /media 同源代理
- [x] 登录 / 注册页
- [x] 书库页（公开+自己的作品列表、搜索框按标题过滤）
- [x] 漫画详情页（章节目录、阅读入口、加入书架）
- [x] 阅读器（固定版本页面窗口、翻页、保存进度、续读、退出恢复位置）
- [x] 我的素材（建作品/章节、上传、进度 SSE、页序确认、发布）
- [x] 我的书架（收藏、最近阅读，继续阅读入口）

## 验证与收尾

- [x] smoke-m1.sh：双用户注册登录 → A 建 CBZ 上传发布 → B 书库可见并阅读 →
      B 刷新续读 → A 换版发布 → B 阅读清单不混页 → B 不可读 A 私人草稿 → 越权 403
- [x] smoke-m0.sh 简化为上传+核验（content.verify 不再链式）
- [x] Worker 中断恢复实验：处理中 kill Worker → 恢复扫描重派发 → 完成
- [x] 发布事务并发实验：两请求同时发布同一 READY 版本，版本号只增一次
- [x] docs/validation/m1.md 写实测结果与已知限制；README 里程碑状态更新

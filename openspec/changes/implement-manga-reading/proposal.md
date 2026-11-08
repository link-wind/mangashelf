# 提案：M1 漫画阅读闭环

## Why

M0 验证了核心栈（版本锁定、上传协议、任务通知、Python 协议），但站点还没有"能读"的能力
（DESIGN 2.1 目标 1）。M1 交付最小可用的漫画阅读闭环：真实用户身份、作品／章节／版本、
图片／CBZ 导入解析、页序确认与发布、书架与阅读进度，以及访问这些能力的最小前端。
退出条件（DESIGN 22）：**上传一章，另一用户按权限阅读，刷新能继续。**

同时完成 M0 收窄项（validate-core-stack tasks.md）：identity 替换演示 owner、租约恢复扫描、
任务重试与取消、SeaweedFS CORS／同源代理方案。

## What Changes

- 迁移 V2：users / works / chapters / chapter_revisions / pages / library_entries /
  reading_progress；M0 表回填 owner 外键；jobs 增加章节版本目标并按任务种类约束。
- 新增 `identity/`：Spring Security + Spring Session JDBC；注册、登录、登出、/me；
  SPA Cookie + CSRF；启动时创建引导管理员（密码来自环境配置）。
- 新增 `catalog/`：作品／章节／版本的创建、编辑、可见性与状态机（DESIGN 9）；
  发布事务锁定章节并原子切换 current_revision_id（DESIGN 10.4）；页序确认（乐观锁 edit_version）。
- 上传流程升级：content.verify 不再链式派生；新增 `chapter.parse` 任务（目标为章节版本）——
  从已核验上传解析图片／CBZ 为固定版本页面清单（自然页序 + 阅读派生图 + 缩略图 + manifest 摘要）。
- `ingestion/` 补全：租约恢复扫描（有限重试后标 FAILED）；任务取消（阶段边界生效）与
  显式重试；任务事件 SSE（Last-Event-ID / after_sequence 补读，DESIGN 11.4）。
- 新增 `library/`：收藏（library_entries）与阅读进度（expected_version 冲突返回 409，DESIGN 14.3）。
- `storage/` 资产访问授权改为按真实用户与内容可见性判定（原作 PUBLIC 且作品 PUBLISHED 才可公开读）。
- 新增 `web/`：React + TypeScript + Vite 最小站（登录注册、书库、详情、阅读器、我的素材、书架）；
  开发经 Vite 代理同源访问 API 与媒体（对应 DESIGN 19 同源反向代理方向）。
- `processing/`：新增 chapter.parse 任务，移植 MangaWeaver import_manga.py（基线 e057b32）的
  自然页序与 CBZ 防护逻辑（来源与理由记录于 design.md）。

## Capabilities

### New

- `user-identity`：注册、登录、会话与当前用户信息。
- `manga-catalog`：作品／章节／版本的组织、页序确认与发布。
- `library-progress`：收藏与阅读进度的冲突安全同步。

### Modified

- `upload-pipeline`：验证完成不再自动派生；解析以章节版本为目标，产出固定版本页面清单。

## Impact

- 影响：数据库 V2 迁移、server 六个业务模块、processing 新任务、新增 web/ 前端；
  smoke-m0.sh 简化为上传＋核验（派生链路移入 M1 冒烟）。
- 不影响：RabbitMQ 拓扑（仍只有 content.process 队列）；对象存储 Key 规范不变；
  MangaWeaver 仓库无任何改动（接入协议属 M3）。
- 风险：Spring Security 7 / Boot 4 的会话与 CSRF 配置需实测；CBZ 解析的防护边界
  以真实环境验证为准；前端最小可用，视觉打磨后置（DESIGN 4 开头声明）。

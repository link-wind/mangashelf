## Why

用户反馈漫画索引只有搜索式浏览不够——番剧索引有全量目录（7511 条落库），漫画索引应当同样"有所有的漫画"。调查结论：拷贝漫画可按题材全量遍历；腾讯可补分类模板；哔哩/包子/嬉皮无全量能力，采用渐进式入库（榜单/搜索/阅读中见到的条目沉淀入库）。性能上落库查询（毫秒级）远优于实时打上游（秒级），且上游访问转移到后台同步。

## What Changes

- 新表 `manga_catalog_items`：`(source, source_item_id)` 唯一，含 name/cover_url/remarks/tags text[]/detail_path；tags 加 GIN、name 加 trgm、source+synced_at 加复合索引。
- 新同步任务 `manga.catalog.sync`：按源抓取入库——拷贝漫画遍历题材分页全量；哔哩/腾讯/规则源把 rank/sections 能拿到的条目入库（渐进式）；支持周期增量（按更新时间）。
- 渐进式入库钩子：实时源搜索命中条目时顺手 upsert 入库。
- 漫画索引页改读库：题材 tab 从库内 tags 聚合、状态/搜索/分页走本地查询；保留"实时搜索该源"兜底按钮。
- jobs 表 CHECK 约束扩展新 kind。
- **数据库量级**：预估 2-5 万条（拷贝全量），PG 无压力；同步限速一次性 20-40 分钟，之后周期增量。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `manga-sources`：新增漫画目录同步与索引读库要求（题材聚合、状态筛选、渐进式入库）。

## Impact

- 数据库：V27（新表 + jobs CHECK 扩展）
- 后端：`manga/MangaCatalogItem/MangaCatalogRepository/MangaCatalogSyncHandler/MangaCatalogController`；`MangaSourceController` 搜索入库钩子
- 前端：`MangaCatalog.tsx` 改读库（题材/搜索/分页从本地查询）
- 限制：拷贝/腾讯上游在本机不可达，同步任务真实灌数据待线上验证（单测 mock 覆盖逻辑）

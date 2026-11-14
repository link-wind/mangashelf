## 1. 数据库与目录仓储

- [x] 1.1 V27 迁移：建 `manga_catalog_items`（唯一/GIN/trgm/复合索引）+ jobs CHECK 扩展 `manga.catalog.sync`
- [x] 1.2 `MangaCatalogRepository`：upsert（幂等）、分页查询（source/genre/status/q 过滤）、题材聚合（DISTINCT unnest）
- [x] 1.3 单测：upsert 幂等、题材/状态/搜索过滤正确性

## 2. 同步任务

- [x] 2.1 `MangaCatalogSyncHandler`（kind=manga.catalog.sync，params.sourceId）：copymanga 逐题材分页抓取（限速 2s/题材、500ms/页）；哔哩/腾讯/规则源渐进式（rank/sections 条目 upsert）
- [x] 2.2 单测：mock 上游响应验证解析与 upsert；限速参数不真实 sleep（注入 clock/无 sleep 路径）
- [x] 2.3 真实验证标注：上游本机不可达，线上跑 copymanga 全量灌数据

## 3. 端点与渐进式入库

- [x] 3.1 `GET /api/v1/manga-catalog`（source/genre/status/q/cursor 分页）+ `GET /api/v1/manga-catalog/genres?source=`
- [x] 3.2 `MangaSourceController.search` 返回前 upsert 命中条目（渐进式入库，失败不影响搜索）
- [x] 3.3 真实验证：DB 直插样例数据后索引页读库渲染、题材聚合正确

## 4. 前端索引页改造

- [x] 4.1 `MangaCatalog.tsx` 改读库：题材 tab 从聚合端点、搜索/状态/分页走 manga-catalog 端点；每源显示「库内 N 部」
- [x] 4.2 保留「实时搜索」兜底按钮（调 per-source search，结果自动入库）
- [x] 4.3 浏览器验证：手机视口渲染、题材/搜索/分页交互、空源降级提示

## 5. 收尾

- [x] 5.1 `mvnw test`、`npm run build` 通过；`openspec validate` 通过
- [x] 5.2 验证结果写入 `docs/validation/manga-catalog-sync.md`

# 任务：M8 资源站目录同步与点播物化

## 迁移

- [x] V9：external_catalog_items + jobs.sync_source_id + kind CHECK 扩展

## 后端

- [x] ExternalCatalogItem / ExternalCatalogRepository（upsert/分页/物化）
- [x] CatalogSyncHandler（full/incremental，页间限速）
- [x] 端点：POST/GET /admin/catalog-sync、GET /catalog-items、POST materialize
- [x] 物化事务：建 Work + 解析线路绑集 + 回链

## 前端

- [x] 番剧索引切目录层（封面/备注/搜索/筛选）
- [x] 加入书库 / 打开作品交互

## 验证

- [x] 全量同步跑通（5 分类，数千条）
- [x] 增量同步（h=24）
- [x] 物化 → 播放 E2E（真实番剧站内播放）
- [x] docs/validation/m8.md + 提交归档

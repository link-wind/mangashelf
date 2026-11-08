# 任务：M8 资源站目录同步与点播物化

## 迁移

- [ ] V9：external_catalog_items + jobs.sync_source_id + kind CHECK 扩展

## 后端

- [ ] ExternalCatalogItem / ExternalCatalogRepository（upsert/分页/物化）
- [ ] CatalogSyncHandler（full/incremental，页间限速）
- [ ] 端点：POST/GET /admin/catalog-sync、GET /catalog-items、POST materialize
- [ ] 物化事务：建 Work + 解析线路绑集 + 回链

## 前端

- [ ] 番剧索引切目录层（封面/备注/搜索/筛选）
- [ ] 加入书库 / 打开作品交互

## 验证

- [ ] 全量同步跑通（5 分类，数千条）
- [ ] 增量同步（h=24）
- [ ] 物化 → 播放 E2E（真实番剧站内播放）
- [ ] docs/validation/m8.md + 提交归档

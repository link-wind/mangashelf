-- 读路径性能优化索引（benchmark-db-and-hotspot-cache）：
-- 经 Stage 1 并发压测与 EXPLAIN (ANALYZE, BUFFERS) 画像定位的 4 类核心热点查询索引。

-- 1) 番剧目录默认更新时间游标索引（消除 7500+ 行 Seq Scan + top-N heapsort）
CREATE INDEX idx_catalog_items_updated_cursor
    ON external_catalog_items ((COALESCE(resource_updated_at, synced_at)) DESC, id DESC);

-- 2) 番剧目录分类 + 更新时间复合索引（加速按分类筛选翻页）
CREATE INDEX idx_catalog_items_cat_updated
    ON external_catalog_items (category_id, (COALESCE(resource_updated_at, synced_at)) DESC, id DESC);

-- 3) 番剧目录集数降序索引（消除 sort=episodes 深分页全表排序）
CREATE INDEX idx_catalog_items_episodes_desc
    ON external_catalog_items (episode_count DESC, id DESC);

-- 4) Bangumi 题材数组 GIN 索引（配合 tags @> ARRAY[?] 替代 unnest 子查询）
CREATE INDEX idx_bangumi_subjects_tags_gin
    ON bangumi_subjects USING gin (tags);

-- 5) 站内公开已发布作品游标部分索引（加速 /api/v1/works 公开读路径）
CREATE INDEX idx_works_public_published_cursor
    ON works (created_at DESC, id DESC)
    WHERE visibility = 'PUBLIC' AND status = 'PUBLISHED';

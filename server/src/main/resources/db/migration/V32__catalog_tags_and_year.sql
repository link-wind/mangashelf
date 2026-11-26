-- V32: 番剧目录原生题材标签与上映年份（来自资源站 vod_class / vod_tag / vod_year）
ALTER TABLE external_catalog_items
    ADD COLUMN IF NOT EXISTS tags text[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS release_year integer;

CREATE INDEX IF NOT EXISTS idx_external_catalog_items_tags_gin
    ON external_catalog_items USING GIN (tags);

CREATE INDEX IF NOT EXISTS idx_external_catalog_items_release_year
    ON external_catalog_items (release_year)
    WHERE release_year IS NOT NULL;

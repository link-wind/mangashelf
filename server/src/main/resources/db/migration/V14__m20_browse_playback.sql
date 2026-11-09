-- M20：浏览态播放。外部集可挂在「目录条目」上（不创建作品），
-- 用户点「加入书库」时再把这些剧集收编到作品下。

ALTER TABLE external_episodes ALTER COLUMN work_id DROP NOT NULL;
ALTER TABLE external_episodes
    ADD COLUMN catalog_item_id uuid REFERENCES external_catalog_items(id) ON DELETE CASCADE;

-- 原唯一键基于 work_id，改为两个部分唯一索引
ALTER TABLE external_episodes DROP CONSTRAINT external_episodes_work_line_ordinal_key;
CREATE UNIQUE INDEX uq_ext_ep_work
    ON external_episodes (work_id, source_id, line_name, ordinal) WHERE work_id IS NOT NULL;
CREATE UNIQUE INDEX uq_ext_ep_catalog
    ON external_episodes (catalog_item_id, source_id, line_name, ordinal) WHERE catalog_item_id IS NOT NULL;

-- 归属必须二选一
ALTER TABLE external_episodes ADD CONSTRAINT ext_ep_owner_check
    CHECK ((work_id IS NOT NULL)::int + (catalog_item_id IS NOT NULL)::int = 1);

CREATE INDEX idx_ext_ep_catalog ON external_episodes (catalog_item_id, line_rank, ordinal);

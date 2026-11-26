-- V35: 动画高清横幅（AniList 1900x400 宽幅 Banner）与目录条目高清封面回填字段
ALTER TABLE bangumi_subjects
    ADD COLUMN IF NOT EXISTS banner_url text NOT NULL DEFAULT '';

ALTER TABLE external_catalog_items
    ADD COLUMN IF NOT EXISTS hd_cover_url text NOT NULL DEFAULT '';

-- 题材分类（mobile-nav-genre-remove-mangadex）。
-- 1) Bangumi 日历同步保留题材标签（热血/机甲/恋爱等），番剧索引按关联标签筛选。
ALTER TABLE bangumi_subjects ADD COLUMN tags text[] NOT NULL DEFAULT '{}';

-- 2) 移除 MangaDex 源：清理其阅读进度与收藏数据（源本身无库表，进度/收藏按 source 标识）。
DELETE FROM external_manga_progress WHERE source = 'mangadex';
DELETE FROM favorites WHERE manga_source = 'mangadex';

-- 清理测试数据（保留：admin、量子资源源、指定真实作品）
-- 幂等：可重复执行；全部在事务内完成，失败自动回滚。
BEGIN;

CREATE TEMP TABLE keep_work AS
SELECT DISTINCT ON (title) id
FROM works
WHERE title IN ('尼古喵喵', '森林来信')
ORDER BY title, created_at DESC;

CREATE TEMP TABLE drop_work AS
SELECT id FROM works WHERE id NOT IN (SELECT id FROM keep_work);

CREATE TEMP TABLE keep_source AS
SELECT id FROM external_sources WHERE name = '量子资源';

CREATE TEMP TABLE drop_source AS
SELECT id FROM external_sources WHERE id NOT IN (SELECT id FROM keep_source);

-- 1) 依赖用户/作品的行为数据
DELETE FROM external_manga_progress
 WHERE user_id NOT IN (SELECT id FROM users WHERE role = 'ADMIN');

DELETE FROM external_watch_progress
 WHERE work_id IN (SELECT id FROM drop_work) OR user_id NOT IN (SELECT id FROM users WHERE role = 'ADMIN');

DELETE FROM reading_progress
 WHERE user_id NOT IN (SELECT id FROM users WHERE role = 'ADMIN')
    OR chapter_id IN (SELECT c.id FROM chapters c WHERE c.work_id IN (SELECT id FROM drop_work));

DELETE FROM library_entries
 WHERE work_id IN (SELECT id FROM drop_work) OR user_id NOT IN (SELECT id FROM users WHERE role = 'ADMIN');

DELETE FROM danmaku
 WHERE work_id IN (SELECT id FROM drop_work) OR user_id NOT IN (SELECT id FROM users WHERE role = 'ADMIN');

DELETE FROM watch_progress;

-- 1.5) 任务历史（开发环境整体清空；jobs 引用 generations，必须先于索引删除）
DELETE FROM job_events;
DELETE FROM outbox_events;
DELETE FROM jobs;

-- 2) 动画：开发环境无自托管动画需保留，整体清空（watch_progress 已在上一步清空）
UPDATE animations SET current_revision_id = NULL;
DELETE FROM animation_sources;
DELETE FROM animation_revisions;
DELETE FROM animations;

-- 3) 章节与页面（先解开 chapters.current_revision_id 的复合外键依赖）
UPDATE chapters SET current_revision_id = NULL WHERE work_id IN (SELECT id FROM drop_work);

DELETE FROM search_chunks WHERE page_id IN (
    SELECT p.id FROM pages p
    JOIN chapter_revisions r ON r.id = p.chapter_revision_id
    JOIN chapters c ON c.id = r.chapter_id
    WHERE c.work_id IN (SELECT id FROM drop_work)
);

DELETE FROM index_generations WHERE chapter_revision_id IN (
    SELECT r.id FROM chapter_revisions r
    JOIN chapters c ON c.id = r.chapter_id
    WHERE c.work_id IN (SELECT id FROM drop_work)
);

DELETE FROM pages WHERE chapter_revision_id IN (
    SELECT r.id FROM chapter_revisions r
    JOIN chapters c ON c.id = r.chapter_id
    WHERE c.work_id IN (SELECT id FROM drop_work)
);

DELETE FROM chapter_revisions WHERE chapter_id IN (
    SELECT c.id FROM chapters c WHERE c.work_id IN (SELECT id FROM drop_work)
);
DELETE FROM chapters WHERE work_id IN (SELECT id FROM drop_work);

-- 4) 外部集与作品
DELETE FROM external_episodes
 WHERE work_id IN (SELECT id FROM drop_work)
    OR source_id IN (SELECT id FROM drop_source);

UPDATE external_catalog_items SET work_id = NULL WHERE work_id IN (SELECT id FROM drop_work);
DELETE FROM works WHERE id IN (SELECT id FROM drop_work);

-- 5) 规则源（保留量子资源）
DELETE FROM external_catalog_items WHERE source_id IN (SELECT id FROM drop_source);
DELETE FROM external_sources WHERE id IN (SELECT id FROM drop_source);

-- 6) 任务、上传、资产、令牌（非 admin 用户）
DELETE FROM api_tokens WHERE user_id NOT IN (SELECT id FROM users WHERE role = 'ADMIN');

DELETE FROM upload_sessions WHERE owner_id NOT IN (SELECT id FROM users WHERE role = 'ADMIN');

-- 仅删除不再被任何内容引用的资产（被保留内容引用的资产不删，避免外键冲突）
DELETE FROM assets WHERE id NOT IN (
    SELECT source_asset_id FROM chapter_revisions WHERE source_asset_id IS NOT NULL
    UNION SELECT image_asset_id FROM pages
    UNION SELECT thumbnail_asset_id FROM pages WHERE thumbnail_asset_id IS NOT NULL
    UNION SELECT video_asset_id FROM animation_revisions WHERE video_asset_id IS NOT NULL
    UNION SELECT poster_asset_id FROM animation_revisions WHERE poster_asset_id IS NOT NULL
    UNION SELECT subtitle_asset_id FROM animation_revisions WHERE subtitle_asset_id IS NOT NULL
);

-- 7) 保留内容归属转为 admin，再删除其余用户
UPDATE works SET owner_id = (SELECT id FROM users WHERE role = 'ADMIN' LIMIT 1)
 WHERE owner_id NOT IN (SELECT id FROM users WHERE role = 'ADMIN');
UPDATE assets SET owner_id = (SELECT id FROM users WHERE role = 'ADMIN' LIMIT 1)
 WHERE owner_id NOT IN (SELECT id FROM users WHERE role = 'ADMIN');

DELETE FROM users WHERE role <> 'ADMIN';

COMMIT;

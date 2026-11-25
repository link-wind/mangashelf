-- 清理开发/演示实例中的测试残留（一次性，2026-11-25）
-- 目标：删除冒烟/验证脚本产生的测试用户（123 / retire_smoke / fav_smoke）及其名下测试草稿作品，
--       保留 admin 与真实同步目录数据。物化的目录条目只解除 work_id 关联（视为可浏览的目录内容），不删条目。
-- 幂等：可重复执行；全部在事务内完成，任何失败自动回滚。
-- 仅删除以下明确命名的实体，不触碰真实作品/目录/番剧数据。
BEGIN;

-- 测试用户（非 admin 的冒烟账号）
CREATE TEMP TABLE tmp_test_users AS
SELECT id FROM users WHERE login IN ('123', 'retire_smoke', 'fav_smoke');

-- 测试草稿作品：由测试用户拥有、且为 DRAFT/PRIVATE 的草稿（含“进度测试作品”“欢迎光临”等冒烟产物）
CREATE TEMP TABLE tmp_test_works AS
SELECT id FROM works WHERE owner_id IN (SELECT id FROM tmp_test_users);

-- 1) 依赖测试用户的行为数据
DELETE FROM favorites               WHERE user_id IN (SELECT id FROM tmp_test_users);
DELETE FROM reading_progress        WHERE user_id IN (SELECT id FROM tmp_test_users);
DELETE FROM watch_progress          WHERE user_id IN (SELECT id FROM tmp_test_users);
DELETE FROM external_manga_progress WHERE user_id IN (SELECT id FROM tmp_test_users);
DELETE FROM external_watch_progress WHERE user_id IN (SELECT id FROM tmp_test_users);
DELETE FROM catalog_watch_progress  WHERE user_id IN (SELECT id FROM tmp_test_users);
DELETE FROM danmaku                 WHERE user_id IN (SELECT id FROM tmp_test_users);
DELETE FROM job_events              WHERE job_id IN (SELECT id FROM jobs WHERE owner_id IN (SELECT id FROM tmp_test_users));
DELETE FROM job_event_sequences     WHERE job_id IN (SELECT id FROM jobs WHERE owner_id IN (SELECT id FROM tmp_test_users));
DELETE FROM outbox_events           WHERE job_id IN (SELECT id FROM jobs WHERE owner_id IN (SELECT id FROM tmp_test_users));
DELETE FROM jobs                    WHERE owner_id IN (SELECT id FROM tmp_test_users);
DELETE FROM upload_sessions         WHERE owner_id IN (SELECT id FROM tmp_test_users);

-- 2) 依赖测试作品的行为数据
DELETE FROM favorites             WHERE work_id IN (SELECT id FROM tmp_test_works);
DELETE FROM reading_progress      WHERE chapter_id IN (
    SELECT id FROM chapters WHERE work_id IN (SELECT id FROM tmp_test_works));
DELETE FROM external_episodes     WHERE work_id IN (SELECT id FROM tmp_test_works);

-- 2.5) 物化的目录条目只解除关联，条目本身属真实目录数据，保留可浏览
UPDATE external_catalog_items SET work_id = NULL WHERE work_id IN (SELECT id FROM tmp_test_works);

-- 3) 测试作品本体（无 chapters 依赖）
DELETE FROM works WHERE id IN (SELECT id FROM tmp_test_works);

-- 4) 测试用户本体
DELETE FROM users WHERE id IN (SELECT id FROM tmp_test_users);

-- 5) 测试用户持有的资产（原图/缩略图）
DELETE FROM assets WHERE owner_id IN (SELECT id FROM tmp_test_users);

COMMIT;

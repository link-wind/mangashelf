-- V34: 漫画目录跨源去重归一化标题键（繁体转简体 + 去标点括号季期后缀）
ALTER TABLE manga_catalog_items
    ADD COLUMN IF NOT EXISTS title_key text NOT NULL DEFAULT '';

-- 对已有存量条目做基础标题键回填（同步时会由 Java 繁简归一化器写入最终值）
UPDATE manga_catalog_items
   SET title_key = lower(regexp_replace(
           regexp_replace(name, '（[^）]*）|\([^)]*\)|\[[^]]*\]|【[^】]*】', '', 'g'),
           '[\s[:punct:]·・：；，。！？、—\-]+', '', 'g'))
 WHERE title_key = '';

CREATE INDEX IF NOT EXISTS idx_manga_catalog_title_key
    ON manga_catalog_items (title_key)
    WHERE title_key <> '';

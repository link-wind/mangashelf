-- V33: 补全 V27 遗漏的 jobs_kind_check 约束（允许 manga.catalog.sync 入队）
ALTER TABLE jobs DROP CONSTRAINT IF EXISTS jobs_kind_check;
ALTER TABLE jobs ADD CONSTRAINT jobs_kind_check
    CHECK (kind IN ('content.verify', 'content.process', 'chapter.parse',
                    'animation.verify', 'index.build', 'external.resolve',
                    'catalog.sync', 'bangumi.sync', 'manga.catalog.sync'));

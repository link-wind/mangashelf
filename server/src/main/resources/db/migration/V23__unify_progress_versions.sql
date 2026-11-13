-- 外部漫画与外部观看进度增加版本号：与本地阅读/观看进度统一冲突语义。
-- 存量行按版本 1 处理；客户端从列表读到版本后即可携带 expectedVersion 写入。
ALTER TABLE external_manga_progress ADD COLUMN version int NOT NULL DEFAULT 1;
ALTER TABLE external_watch_progress ADD COLUMN version int NOT NULL DEFAULT 1;

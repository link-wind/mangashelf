-- V31: 新番时刻表播映元数据（精确放送时间、制作公司、播映形式）
ALTER TABLE bangumi_subjects
    ADD COLUMN IF NOT EXISTS air_time varchar(16),
    ADD COLUMN IF NOT EXISTS studio varchar(120) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS media_type varchar(24) NOT NULL DEFAULT '';

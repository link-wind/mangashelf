-- 任务派发边界加固（harden-job-lease-and-routing）。
-- 1) outbox 领取租约：领取时写入令牌与期限，确认按令牌更新，超时可回收重派。
ALTER TABLE outbox_events
    ADD COLUMN lease_token uuid,
    ADD COLUMN lease_until timestamptz;

-- 2) 任务事件序号数据库串行分配：替代 MAX(sequence)+1，多实例并发追加同一任务
--    不再撞 (job_id, sequence) 主键。新任务首次追加时插入初始行。
CREATE TABLE job_event_sequences (
    job_id   uuid PRIMARY KEY REFERENCES jobs(id) ON DELETE CASCADE,
    next_seq bigint NOT NULL DEFAULT 1
);

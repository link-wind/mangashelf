package com.mangashelf.ingestion;

import java.time.Instant;
import java.util.UUID;

/** 任务事件（DESIGN 11.4）：任务内序号唯一，M0 以 JSON 列表返回，M1 增加 SSE。 */
public record JobEvent(UUID jobId, long sequence, String type, String stage, String message, Instant createdAt) {
}

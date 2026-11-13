package com.mangashelf.ingestion;

import java.util.UUID;

/** 消息只携带最小字段；执行参数一律从数据库读取（DESIGN 11.2 规则 1）。kind 供按类型路由。 */
public record JobMessage(int schemaVersion, UUID jobId, int dispatchNo, String kind) {

    public static final int SCHEMA_VERSION = 1;
}

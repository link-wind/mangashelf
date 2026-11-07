package com.mangashelf.config;

import java.time.Instant;
import java.time.ZoneOffset;

/** JDBC 参数辅助：PostgreSQL 驱动不能推断 Instant 的 SQL 类型，写入统一转 OffsetDateTime。 */
public final class JdbcTime {

    private JdbcTime() {
    }

    public static java.time.OffsetDateTime t(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}

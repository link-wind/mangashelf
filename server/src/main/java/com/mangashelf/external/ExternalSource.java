package com.mangashelf.external;

import java.time.Instant;
import java.util.UUID;

/** 规则源（管理员导入，CSS 选择器 / JSONPath，兼容 Kazumi 结构）。 */
public record ExternalSource(
        UUID id,
        String name,
        String baseUrl,
        String rules,
        boolean enabled,
        String cookie,
        Instant createdAt,
        Instant updatedAt) {
}

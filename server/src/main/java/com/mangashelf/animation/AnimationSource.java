package com.mangashelf.animation;

import java.util.UUID;

/** 来源映射（DESIGN 13.2）：固定章节版本 + 自然页码范围 + 在改编来源中的顺序。 */
public record AnimationSource(
        UUID id,
        UUID animationRevisionId,
        UUID chapterRevisionId,
        int pageStart,
        int pageEnd,
        int ordinal) {
}

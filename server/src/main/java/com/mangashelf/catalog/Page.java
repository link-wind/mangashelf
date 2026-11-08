package com.mangashelf.catalog;

import java.util.UUID;

/** 页面（DESIGN 8.2 pages）：同版本页序唯一；图片必需，缩略图可待补。 */
public record Page(
        UUID id,
        UUID chapterRevisionId,
        int ordinal,
        UUID imageAssetId,
        UUID thumbnailAssetId,
        int width,
        int height) {
}

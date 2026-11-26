package com.mangashelf.external;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagNormalizerTest {

    @Test
    void parsesMacCmsVodClassAndFiltersNoise() {
        List<String> tags = TagNormalizer.parseDelimited("剧情,喜剧,动画,奇幻,日本,2026", "日韩动漫/爱情/漫改");
        assertEquals(List.of("剧情", "搞笑", "奇幻", "恋爱"), tags);
    }

    @Test
    void stripsMangaSuffixAndDeduplicates() {
        List<String> tags = TagNormalizer.normalize(List.of("恋爱漫画", "爱情", "玄幻", "独家", "签约", "更新至120话"));
        assertEquals(List.of("恋爱", "玄幻"), tags);
    }

    @Test
    void buildsSynonymAliasesForQueryingLegacyData() {
        List<String> aliases = Arrays.asList(TagNormalizer.aliasesFor("搞笑"));
        assertTrue(aliases.contains("搞笑"));
        assertTrue(aliases.contains("喜剧"));
        assertTrue(aliases.contains("爆笑"));
    }

    @Test
    void parsesValidReleaseYearsOnly() {
        assertEquals(2025, TagNormalizer.parseYear("2025"));
        assertEquals(1999, TagNormalizer.parseYear("1999-04-01"));
        assertNull(TagNormalizer.parseYear("0"));
        assertNull(TagNormalizer.parseYear("未知"));
    }
}

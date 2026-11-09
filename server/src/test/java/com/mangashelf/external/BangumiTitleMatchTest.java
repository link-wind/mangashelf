package com.mangashelf.external;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标题匹配的纯逻辑测试（M9/M10）：归一化与相似度是跨源匹配的正确性关键，
 * 用真实踩过的样例固定行为（不依赖数据库）。
 */
class BangumiTitleMatchTest {

    @Test
    void normalizeStripsSeasonSuffixAndPunctuation() {
        // 中文季期后缀
        assertEquals("转生贵族凭鉴定技能扭转人生",
                BangumiService.normalizeTitle("转生贵族凭鉴定技能扭转人生第三季"));
        // 全角/半角标点与空格
        assertEquals("战斗之魂re绝界之空",
                BangumiService.normalizeTitle("战斗之魂［Re］ 绝界之空"));
        // 括号注释（含全角括号）
        assertEquals("葬送的芙莉莲",
                BangumiService.normalizeTitle("葬送的芙莉莲（第二季）"));
        // 英文季期
        assertEquals("mushoku", BangumiService.normalizeTitle("Mushoku Tensei Season 2".replace("Tensei ", "")));
    }

    @Test
    void identicalTitlesMatchExactly() {
        assertEquals(1.0, BangumiService.bigramSimilarity("斗罗大陆", "斗罗大陆"), 1e-9);
    }

    @Test
    void similarTitlesScoreAboveThresholdButDifferentOnesDoNot() {
        // 资源站与 Bangumi 的常见差异（标点/季期）归一化后应接近
        double similar = BangumiService.bigramSimilarity(
                BangumiService.normalizeTitle("药屋少女的呢喃第三季"),
                BangumiService.normalizeTitle("药屋少女的呢喃"));
        assertTrue(similar >= 0.62, "同系列季期间应达到阈值：" + similar);

        // 不同作品不应误配
        double different = BangumiService.bigramSimilarity(
                BangumiService.normalizeTitle("进击的巨人"),
                BangumiService.normalizeTitle("天气之子"));
        assertTrue(different < 0.3, "不同作品不应接近：" + different);
    }

    @Test
    void emptyTitlesAreSafe() {
        assertEquals("", BangumiService.normalizeTitle(null));
        assertEquals(0.0, BangumiService.bigramSimilarity("", "斗罗大陆"), 1e-9);
    }
}

package com.mangashelf.external;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 轻量推荐打分、贝叶斯平滑、题材打散与换一批切片单元测试。 */
class RecommendationServiceTest {

    @Test
    void bayesianRatingSmoothsExtremeAndNullValues() {
        double high = RecommendationService.bayesianRating(9.5);
        double mid = RecommendationService.bayesianRating(7.2);
        double missing = RecommendationService.bayesianRating(null);
        assertTrue(high > mid && mid > missing);
        assertTrue(high < 0.95, "贝叶斯平滑应压制满分极端值");
    }

    @Test
    void animeScoringBoostsMatchedTagsAndProducesReason() {
        Instant now = Instant.now();
        var matched = new RecommendationService.AnimeCandidate(
                UUID.randomUUID(), null, 100L, "悬疑神作", "", "更新至8集",
                30, "日韩动漫", 8, 8.1, List.of("悬疑", "奇幻"), true, now);
        var unrelated = new RecommendationService.AnimeCandidate(
                UUID.randomUUID(), null, 101L, "普通日常", "", "更新至8集",
                29, "国产动漫", 8, 8.3, List.of("校园"), true, now);

        Map<String, Double> tagWeights = Map.of("悬疑", 1.0, "奇幻", 0.8);
        Map<Integer, Double> catWeights = Map.of(30, 1.0);

        var s1 = RecommendationService.scoreAnimeCandidate(matched, tagWeights, catWeights, now);
        var s2 = RecommendationService.scoreAnimeCandidate(unrelated, tagWeights, catWeights, now);

        assertTrue(s1.score() > s2.score());
        assertTrue(s1.reason().contains("悬疑"));
        assertEquals(List.of("悬疑", "奇幻"), s1.matchedTags());
    }

    @Test
    void diversifyAnimeCapsConsecutiveSamePrimaryTagToTwo() {
        List<RecommendationService.AnimeRecItem> items = List.of(
                rec("A1", "热血"), rec("A2", "热血"), rec("A3", "热血"), rec("B1", "治愈")
        );
        var out = RecommendationService.diversifyAnimeByPrimaryTag(items, 4);
        assertEquals(List.of("A1", "A2", "B1", "A3"),
                out.stream().map(RecommendationService.AnimeRecItem::title).toList());
    }

    @Test
    void mangaScoringPrioritizesReadableAndMatchedTags() {
        Instant now = Instant.now();
        var readableMatched = new RecommendationService.MangaCandidate(
                "tencent", "腾讯动漫", "1", "热血漫画", null, "更新至20话",
                true, 2, List.of("腾讯动漫", "包子漫画"), List.of("热血", "冒险"), 0.8, now);
        var externalOnly = new RecommendationService.MangaCandidate(
                "bilibili", "哔哩漫画", "2", "外链漫画", null, "",
                false, 1, List.of("哔哩漫画"), List.of("校园"), 0.85, now);

        var r1 = RecommendationService.scoreMangaCandidate(readableMatched, Map.of("热血", 1.0), now);
        var r2 = RecommendationService.scoreMangaCandidate(externalOnly, Map.of("热血", 1.0), now);

        assertTrue(r1.score() > r2.score());
        assertTrue(r1.reason().contains("热血"));
    }

    @Test
    void sliceWithRotationWrapsAroundCleanly() {
        List<Integer> src = List.of(1, 2, 3, 4, 5);
        assertEquals(List.of(1, 2, 3), RecommendationService.sliceWithRotation(src, 3, 0));
        assertEquals(List.of(4, 5, 1), RecommendationService.sliceWithRotation(src, 3, 3));
        assertEquals(List.of(2, 3, 4), RecommendationService.sliceWithRotation(src, 3, 6));
    }

    private static RecommendationService.AnimeRecItem rec(String title, String tag) {
        return new RecommendationService.AnimeRecItem(
                UUID.randomUUID(), null, null, title, "", "", "日韩动漫", 12, 8.0,
                List.of(tag), "常看 " + tag, 0.8);
    }
}

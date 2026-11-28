package com.mangashelf.external;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 外部规则源季期感知匹配与签名直链有效期判定单元测试。 */
class RuleLineMatcherTest {

    @Test
    void extractsSeasonAndVariantAccurately() {
        assertNull(RuleLineMatcher.extractSeason("葬送的芙莉莲"));
        assertEquals(2, RuleLineMatcher.extractSeason("葬送的芙莉莲第二季"));
        assertEquals(2, RuleLineMatcher.extractSeason("葬送的芙莉蓮 Season 2"));
        assertEquals(3, RuleLineMatcher.extractSeason("药屋少女的呢喃 第3季"));
        assertEquals("MOVIE", RuleLineMatcher.extractVariant("名侦探柯南 剧场版"));
        assertEquals("OVA", RuleLineMatcher.extractVariant("进击的巨人 OVA"));
        assertEquals("TV", RuleLineMatcher.extractVariant("葬送的芙莉莲第二季"));
    }

    @Test
    void distinguishesSeasonOneFromSeasonTwo() {
        var candidates = List.of(
                new RuleEngine.SearchResultItem("葬送的芙莉莲第二季", "https://www.7sefun.top/voddetail/26976.html"),
                new RuleEngine.SearchResultItem("葬送的芙莉莲", "https://www.7sefun.top/voddetail/32644.html")
        );

        var s2 = RuleLineMatcher.findBestMatch(List.of("葬送的芙莉莲 第二季"), candidates);
        assertTrue(s2.isPresent());
        assertEquals("https://www.7sefun.top/voddetail/26976.html", s2.get().url());

        var s1 = RuleLineMatcher.findBestMatch(List.of("葬送的芙莉莲"), candidates);
        assertTrue(s1.isPresent());
        assertEquals("https://www.7sefun.top/voddetail/32644.html", s1.get().url());

        var s3 = RuleLineMatcher.findBestMatch(List.of("葬送的芙莉莲 第三季"), candidates);
        assertTrue(s3.isEmpty(), "无对应第三季时必须拒绝错配到第一/二季");
    }

    @Test
    void rejectsMovieWhenMatchingTvSeries() {
        var candidates = List.of(
                new RuleEngine.SearchResultItem("鬼灭之刃 剧场版 无限列车篇", "https://www.7sefun.top/voddetail/1.html")
        );
        assertTrue(RuleLineMatcher.findBestMatch(List.of("鬼灭之刃 无限列车篇"), candidates).isEmpty());
    }

    @Test
    void detectsSignedUrlExpiryMargin() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        assertTrue(ExternalService.isUrlNotNearExpiry("https://cdn.example.com/a.m3u8", now));
        assertTrue(ExternalService.isUrlNotNearExpiry("https://cdn.example.com/a.mp4?Expires=1700003600&Signature=x", now));
        assertFalse(ExternalService.isUrlNotNearExpiry("https://cdn.example.com/a.mp4?Expires=1700000060&Signature=x", now));
        assertFalse(ExternalService.isUrlNotNearExpiry("https://cdn.example.com/a.mp4?Expires=1699999000&Signature=x", now));
    }
}

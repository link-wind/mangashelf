package com.mangashelf.external;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogRankingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void regionRankStrictlyFollowsJapanChinaKoreaOverseasOrder() {
        assertEquals(1, CatalogRanking.regionRank("JP"));
        assertEquals(2, CatalogRanking.regionRank("CN"));
        assertEquals(3, CatalogRanking.regionRank("KR"));
        assertEquals(4, CatalogRanking.regionRank("OVERSEAS"));
        assertEquals(5, CatalogRanking.regionRank("UNKNOWN"));
    }

    @Test
    void vodAreaAndCategoryFallbackDistinguishJapanAndKorea() {
        var kr = CatalogRanking.fromVodArea("韩国", 30, "我独自升级", List.of());
        assertEquals("KR", kr.region());
        assertEquals(3, kr.rank());
        assertEquals(CatalogRanking.CONF_SOURCE_FIELD, kr.confidence());

        var jp = CatalogRanking.fromVodArea("日本", 30, "葬送的芙莉莲", List.of());
        assertEquals("JP", jp.region());
        assertEquals(1, jp.rank());

        var cn = CatalogRanking.fromVodArea("", 29, "凡人修仙传", List.of());
        assertEquals("CN", cn.region());
        assertEquals(2, cn.rank());
        assertEquals(CatalogRanking.CONF_PRIOR, cn.confidence());
    }

    @Test
    void aniListCountryAndBilibiliAreaMapAccurately() {
        assertEquals("JP", CatalogRanking.fromAniListCountry("JP").region());
        assertEquals("CN", CatalogRanking.fromAniListCountry("TW").region());
        assertEquals("KR", CatalogRanking.fromAniListCountry("KR").region());
        assertEquals("OVERSEAS", CatalogRanking.fromAniListCountry("US").region());

        assertEquals("JP", CatalogRanking.fromBilibiliAreaId(2).region());
        assertEquals("CN", CatalogRanking.fromBilibiliAreaId(1).region());
        assertEquals("KR", CatalogRanking.fromBilibiliAreaId(6).region());
        assertEquals("OVERSEAS", CatalogRanking.fromBilibiliAreaId(5).region());
    }

    @Test
    void bangumiScriptAndTagsDetectRegion() {
        assertEquals("JP", CatalogRanking.fromBangumiSubject("葬送のフリーレン", List.of("奇幻")).region());
        assertEquals("KR", CatalogRanking.fromBangumiSubject("나 혼자만 레벨업", List.of()).region());
        assertEquals("CN", CatalogRanking.fromBangumiSubject("雾山五行", List.of("国产", "热血")).region());
    }

    @Test
    void vodScoreFiltersZeroAndFullTenAnomalies() throws Exception {
        assertNull(CatalogRanking.parseVodScore(mapper.readTree("{\"vod_douban_score\":\"0.0\",\"vod_score\":\"10.0\"}")));
        assertEquals(8.4, CatalogRanking.parseVodScore(mapper.readTree("{\"vod_douban_score\":\"8.4\",\"vod_score\":\"6.0\"}")));
        assertEquals(7.6, CatalogRanking.parseVodScore(mapper.readTree("{\"vod_douban_score\":\"0\",\"vod_score\":\"7.6\"}")));
    }

    @Test
    void bayesianSmoothingPreventsTinySampleDomination() {
        Double threeVotes = CatalogRanking.bayesianRating(9.8, 3);
        Double popularHigh = CatalogRanking.bayesianRating(8.5, 1000);
        assertNotNull(threeVotes);
        assertNotNull(popularHigh);
        assertTrue(popularHigh > threeVotes, "1000 人 8.5 分应高于 3 人 9.8 分");
    }

    @Test
    void fuseRatingsWeightsBangumiAndAniListSmoothly() {
        var fused = CatalogRanking.fuseRatings(8.6, 500, 82, 50000);
        assertNotNull(fused);
        assertTrue(fused.rawScore() >= 8.2 && fused.rawScore() <= 8.6);
        assertEquals(500 + 2000, fused.votes());
        assertTrue(fused.bayesianRating() > 8.1);
    }
}

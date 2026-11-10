package com.mangashelf.external.manga;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 规则引擎：章节 ID 模板拆分、HTML 详情/榜单解析、能力推导（夹具为 2026-11-09 实测结构）。 */
class MangaRuleEngineTest {

    private static final String DETAIL_HTML = """
            <html><head>
              <meta property="og:title" content="航海王">
              <meta property="og:description" content="每周一更新。 [简介]有一个梦想成为海盗的少年叫路飞。">
              <meta property="og:image" content="https://manhua.acimg.cn/horizontal/0/x.jpg/0">
            </head><body>
              <div class="head-info-author"><span class="author">作者：</span>
                <ul class="author-list"><li class="author-wr">尾田荣一郎</li></ul></div>
              <a class="chapter-link" data-cid="2" data-seq="2" href="/chapter/index/id/505430/cid/2">
                <p class="chapter-title">2 - 第2话 出现</p></a>
              <a class="chapter-link" data-cid="1" data-seq="1" href="/chapter/index/id/505430/cid/1">
                <p class="chapter-title">1 - 第1话 ROMANCE DAWN 冒险的序幕</p></a>
              <a class="chapter-link" data-cid="1" data-seq="1" href="/chapter/index/id/505430/cid/1">
                <p class="chapter-title">1 - 第1话 ROMANCE DAWN 冒险的序幕</p></a>
            </body></html>
            """;

    private static final String DETAIL_RULES = """
            {
              "url": "https://m.ac.qq.com/comic/index/id/{id}",
              "mode": "html",
              "meta": {
                "title":       { "select": "meta[property=og:title]", "attr": "content" },
                "description": { "select": "meta[property=og:description]", "attr": "content" },
                "cover":       { "select": "meta[property=og:image]", "attr": "content" }
              },
              "descriptionMarker": "[简介]",
              "authors": { "select": "ul.author-list", "delimiter": " " },
              "chapters": {
                "list": "a.chapter-link",
                "id":    { "attr": "data-cid", "template": "{mangaId}-{v}" },
                "label": { "attr": "data-seq" },
                "title": { "select": "p.chapter-title" }
              },
              "chapterSort": "numeric-asc",
              "chapterPages": -1
            }
            """;

    private final ObjectMapper om = new ObjectMapper();
    private final MangaRuleEngine engine = new MangaRuleEngine(om, List.of());

    @Test
    void parseChapterIdSplitsByTemplate() {
        assertEquals(Map.of("comicId", "505430", "cid", "1"),
                MangaRuleEngine.parseChapterId("{comicId}-{cid}", "505430-1"));
        assertEquals(Map.of("a", "x", "b", "yz"),
                MangaRuleEngine.parseChapterId("{a}.{b}", "x.yz"));
    }

    @Test
    void parseChapterIdRejectsMismatch() {
        assertThrows(IllegalArgumentException.class,
                () -> MangaRuleEngine.parseChapterId("{comicId}-{cid}", "505430"));
        assertThrows(IllegalArgumentException.class,
                () -> MangaRuleEngine.parseChapterId("{comicId}-{cid}", "-1"));
    }

    @Test
    void detailExtraction() throws Exception {
        var rule = om.readTree(DETAIL_RULES);
        var detail = engine.parseDetail(Jsoup.parse(DETAIL_HTML), rule, "tencent", "505430");
        assertEquals("航海王", detail.item().title());
        assertEquals("有一个梦想成为海盗的少年叫路飞。", detail.description());
        assertEquals("尾田荣一郎", detail.item().altTitle());
        assertEquals("https://manhua.acimg.cn/horizontal/0/x.jpg/0", detail.item().coverUrl());
        // 同 cid 去重 + numeric-asc 排序 + 复合 ID
        assertEquals(2, detail.chapters().size());
        assertEquals("505430-1", detail.chapters().getFirst().id());
        assertEquals("1", detail.chapters().getFirst().label());
        assertEquals("1 - 第1话 ROMANCE DAWN 冒险的序幕", detail.chapters().getFirst().title());
        assertEquals(-1, detail.chapters().getFirst().pages());
    }

    @Test
    void rankExtractionAndCapabilityDerivation() throws Exception {
        var rankRules = om.readTree("""
                {
                  "list": "div.rank-container a.comic-link",
                  "mode": "html",
                  "fields": {
                    "id":    { "attr": "data-id" },
                    "title": { "select": "strong.comic-title" },
                    "cover": { "select": "img", "attr": "src" }
                  }
                }
                """);
        var items = engine.parseRank(Jsoup.parse("""
                <div class="rank-container">
                  <a class="comic-link" data-id="531040" data-pos="1">
                    <img class="cover-image" src="https://manhua.acimg.cn/operation/0/x.jpg/0"/>
                    <strong class="comic-title">斗破苍穹</strong>
                  </a>
                  <a class="comic-link" data-id="523194" data-pos="2">
                    <strong class="comic-title">灵契</strong>
                  </a>
                </div>
                """), rankRules, "tencent", 10);
        assertEquals(2, items.size());
        assertEquals("531040", items.getFirst().id());
        assertEquals("斗破苍穹", items.getFirst().title());
        assertEquals("https://manhua.acimg.cn/operation/0/x.jpg/0", items.getFirst().coverUrl());
        assertTrue(items.getFirst().readable());

        var source = new RuleMangaSource("tencent", "腾讯动漫", "",
                "{\"rank\":{},\"search\":{},\"detail\":{},\"pages\":{}}", engine, om);
        assertEquals(4, source.capabilities().size());
        var rankOnly = new RuleMangaSource("x", "x", "", "{\"rank\":{}}", engine, om);
        assertEquals(1, rankOnly.capabilities().size());
        assertTrue(rankOnly.capabilities().contains(MangaSource.Capability.RANK));
    }

    @Test
    void hrefRegexExtractsIdWhenDataAttrMissing() throws Exception {
        var fields = om.readTree("""
                {
                  "id":    { "attr": "href", "regex": "/id/(\\\\d+)" },
                  "title": { "select": "strong.comic-title" }
                }
                """);
        var items = engine.parseRank(Jsoup.parse("""
                <a class="comic-link" href="/comic/index/id/624570">
                  <strong class="comic-title">传武</strong></a>
                """), "a.comic-link", fields, "tencent", 10);
        assertEquals(1, items.size());
        assertEquals("624570", items.getFirst().id());
    }

    @Test
    void sectionsSplitByBlockAndDedupe() throws Exception {
        var rules = om.readTree("""
                {
                  "url": "https://m.ac.qq.com/",
                  "mode": "html",
                  "fields": {
                    "id":    { "attr": "data-id" },
                    "title": { "select": "strong.comic-title" },
                    "cover": { "select": "img", "attr": "src" }
                  },
                  "sections": [
                    { "id": "recommend", "name": "编辑推荐", "list": "ul.recommend-list a.comic-link" },
                    { "id": "male", "name": "男生榜", "list": "[class*=\\"rank-type-男生榜\\"] a.comic-link" },
                    { "id": "bestseller", "name": "畅销榜", "list": "[class*=\\"rank-type-畅销榜\\"] a.comic-link" }
                  ]
                }
                """);
        var doc = Jsoup.parse("""
                <html><body>
                  <ul class="comic-list col-2 recommend-list">
                    <li><a class="comic-link" data-id="17114"><img src="https://img/17114.jpg"/>
                      <strong class="comic-title">尸兄</strong></a></li>
                    <li><a class="comic-link" data-id="533395">
                      <strong class="comic-title">妖神记</strong></a></li>
                  </ul>
                  <div class="swiper-slide rank-type rank-type-男生榜 rank-1-1">
                    <a class="comic-link" data-id="624570"><strong class="comic-title">传武</strong></a>
                    <a class="comic-link" data-id="523194"><strong class="comic-title">灵契</strong></a>
                  </div>
                  <div class="swiper-slide rank-type rank-type-男生榜 rank-1-2">
                    <a class="comic-link" data-id="523194"><strong class="comic-title">灵契</strong></a>
                    <a class="comic-link" data-id="637624"><strong class="comic-title">蛊真人</strong></a>
                  </div>
                  <div class="swiper-slide rank-type rank-type-畅销榜 rank-3-2">
                    <a class="comic-link" data-id="531490"><strong class="comic-title">一人之下</strong></a>
                    <a class="comic-link" data-id="9"><span>帮助</span></a>
                  </div>
                </body></html>
                """);
        var sections = engine.parseRankSections(doc, rules, "tencent", 10);
        assertEquals(3, sections.size());
        assertEquals("recommend", sections.get(0).id());
        assertEquals(2, sections.get(0).items().size());
        // 两页榜单合并 + 同 id 去重
        assertEquals(3, sections.get(1).items().size());
        assertEquals("传武", sections.get(1).items().getFirst().title());
        // 页脚无标题链接被过滤
        assertEquals(1, sections.get(2).items().size());
        assertEquals("一人之下", sections.get(2).items().getFirst().title());
    }
}

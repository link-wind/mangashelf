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
}

package com.mangashelf.external.manga;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MangaCatalogSyncHandlerTest {

    @Test
    void parsesTencentThemeListingHtmlWithMultiTags() {
        String html = """
                <ul class="ret-search-list clearfix">
                  <li class="ret-search-item clearfix">
                    <div class="ret-works-cover">
                      <a href="/Comic/comicInfo/id/531490" title="一人之下">
                        <img class="lazy" data-original="https://manhua.acimg.cn/vertical/0/cover1.jpg/420" />
                        <span class="mod-cover-list-text">更新至700话</span>
                      </a>
                    </div>
                    <div class="ret-works-info">
                      <h3 class="ret-works-title clearfix">
                        <a href="/Comic/comicInfo/id/531490" title="一人之下">一人之下</a>
                      </h3>
                      <p class="ret-works-author" title="米二">米二</p>
                      <p class="ret-works-tags">
                        <span>玄幻</span>
                        <span>格斗</span>
                        <span>独家</span>
                        <span>人气：99.9亿</span>
                      </p>
                    </div>
                  </li>
                  <li class="ret-search-item clearfix">
                    <div class="ret-works-cover">
                      <a href="/Comic/comicInfo/id/654321" title="A &amp; B">
                        <img data-original="https://manhua.acimg.cn/vertical/0/cover2.jpg/420" />
                        <span class="mod-cover-list-text">已完结</span>
                      </a>
                    </div>
                    <div class="ret-works-info">
                      <h3 class="ret-works-title">
                        <a href="/Comic/comicInfo/id/654321" title="A &amp; B">A &amp; B</a>
                      </h3>
                      <p class="ret-works-author" title="作者甲">作者甲</p>
                      <p class="ret-works-tags">
                        <span>校园漫画</span>
                        <span>喜剧</span>
                      </p>
                    </div>
                  </li>
                </ul>
                """;

        List<MangaCatalogSyncHandler.ParsedTencentItem> items =
                MangaCatalogSyncHandler.parseTencentListPage(html, "异能");

        assertEquals(2, items.size());
        var first = items.get(0);
        assertEquals("531490", first.id());
        assertEquals("一人之下", first.title());
        assertEquals("米二", first.author());
        assertEquals("https://manhua.acimg.cn/vertical/0/cover1.jpg/420", first.coverUrl());
        assertEquals("更新至700话", first.remarks());
        assertEquals(List.of("异能", "玄幻", "动作"), first.tags());

        var second = items.get(1);
        assertEquals("654321", second.id());
        assertEquals("A & B", second.title());
        assertEquals(List.of("异能", "校园", "搞笑"), second.tags());
    }

    @Test
    void normalizesTraditionalAndBracketedTitlesToSameTitleKey() {
        assertEquals("鬼灭之刃", MangaMatcher.titleKey("鬼滅之刃", "copymanga", "gmzr"));
        assertEquals("鬼灭之刃", MangaMatcher.titleKey("鬼灭之刃（全彩版）", "bilibili", "25717"));
        assertEquals("胆大党", MangaMatcher.titleKey("膽大黨", "copymanga", "ddd"));
        assertEquals("胆大党", MangaMatcher.titleKey("胆大党（当哒当）", "tencent", "655879"));
        assertEquals("咒术回战", MangaMatcher.titleKey("咒術迴戰", "copymanga", "zshz"));
        assertEquals("copymanga:123", MangaMatcher.titleKey("（！？）", "copymanga", "123"));
    }

    @Test
    void buildsCopyClientWithAndWithoutProxy() {
        assertNotNull(MangaCatalogSyncHandler.createCopyClient(""));
        assertNotNull(MangaCatalogSyncHandler.createCopyClient("http://127.0.0.1:7890"));
    }
}

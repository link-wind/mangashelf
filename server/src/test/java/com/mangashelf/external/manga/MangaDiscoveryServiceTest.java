package com.mangashelf.external.manga;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 聚合推荐：RRF 累加、作品聚簇（跨源合并/版本区分）、中文过滤、非中文源排除。 */
class MangaDiscoveryServiceTest {

    private static MangaSource.RankItem item(String source, String title) {
        return new MangaSource.RankItem(source, title, title, List.of(), null,
                false, "", true, null, source, title);
    }

    @Test
    void fuseAccumulatesAcrossListsAndDedupes() {
        var fused = MangaDiscoveryService.fuse(List.of(
                List.of(item("tencent", "航海王"), item("tencent", "斗破苍穹"), item("tencent", "一人之下")),
                List.of(item("bilibili", "一人之下"), item("bilibili", "航海王"))
        ), 10, Map.of("tencent", "腾讯动漫", "bilibili", "哔哩漫画"));
        assertEquals(3, fused.size());
        assertEquals("航海王", fused.get(0).title());   // 1/61 + 1/62
        assertEquals("一人之下", fused.get(1).title()); // 1/63 + 1/61
        assertEquals("斗破苍穹", fused.get(2).title()); // 1/62
        // 跨源同作合并为一条，sources 汇总
        assertEquals(2, fused.get(0).sourceCount());
        assertEquals(List.of("腾讯动漫", "哔哩漫画"), fused.get(0).sources());
        assertEquals(1, fused.get(2).sourceCount());
    }

    @Test
    void editionSuffixStaysSeparate() {
        var fused = MangaDiscoveryService.fuse(List.of(
                List.of(item("tencent", "航海王"), item("tencent", "航海王（全彩版）"))
        ), 10, Map.of());
        assertEquals(2, fused.size());
    }

    @Test
    void nearIdenticalTitlesMerge() {
        // 仅空格/标点差异 → 同作
        var fused = MangaDiscoveryService.fuse(List.of(
                List.of(item("tencent", "斗破苍穹"), item("bilibili", "斗破苍穹!"))
        ), 10, Map.of("tencent", "腾讯动漫", "bilibili", "哔哩漫画"));
        assertEquals(1, fused.size());
        assertEquals(2, fused.getFirst().sourceCount());
    }

    @Test
    void readableVersionWinsAsRepresentative() {
        var readable = new MangaSource.RankItem("tencent", "505430", "航海王", List.of(), null,
                false, "更新到701话", true, null, "tencent", "505430");
        var notReadable = new MangaSource.RankItem("bilibili", "24742", "航海王", List.of(), null,
                false, "更新至 1198", false, "https://manga.bilibili.com/detail/mc24742", null, null);
        var fused = MangaDiscoveryService.fuse(List.of(
                List.of(notReadable), List.of(readable)
        ), 10, Map.of("tencent", "腾讯动漫"));
        assertEquals(1, fused.size());
        assertTrue(fused.getFirst().readable());
        assertEquals("更新到701话", fused.getFirst().latestLabel());
        assertEquals(2, fused.getFirst().sourceCount());
    }

    @Test
    void fuseFiltersNonChineseTitles() {
        var fused = MangaDiscoveryService.fuse(List.of(
                List.of(item("tencent", "One Piece"), item("tencent", "航海王"))
        ), 10, Map.of());
        assertEquals(1, fused.size());
        assertEquals("航海王", fused.getFirst().title());
    }

    @Test
    void normalizeKeepsEditionSuffixInsideParentheses() {
        assertEquals(MangaDiscoveryService.normalizeTitle("航海王"),
                MangaDiscoveryService.normalizeTitle(" 航海王 "));
        assertFalse(MangaDiscoveryService.normalizeTitle("航海王（全彩版）")
                .equals(MangaDiscoveryService.normalizeTitle("航海王")));
    }

    @Test
    void sanitizeDowngradesForeignReadSourceToExternal() {
        var bridged = new MangaSource.RankItem("bilibili", "1", "航海王", List.of(), null,
                false, "", true, "https://manga.bilibili.com/detail/mc1", "mangadex", "uuid-1");
        var zhRead = new MangaSource.RankItem("bilibili", "2", "斗破苍穹", List.of(), null,
                false, "", true, null, "tencent", "505430-1");
        var out = MangaDiscoveryService.sanitize(List.of(bridged, zhRead), java.util.Set.of("tencent"));
        assertFalse(out.get(0).readable());
        assertEquals(null, out.get(0).readId());
        assertEquals("https://manga.bilibili.com/detail/mc1", out.get(0).externalUrl());
        assertTrue(out.get(1).readable());
        assertEquals("tencent", out.get(1).readSource());
    }

    @Test
    void discoverExcludesNonChineseSources() throws Exception {
        var zhCalls = new AtomicInteger();
        var multiCalls = new AtomicInteger();
        MangaSource zh = fake("tencent", "腾讯动漫", "zh", zhCalls,
                List.of(new MangaSource.Section("hot", "热门",
                        List.of(item("tencent", "航海王")))));
        MangaSource multi = fake("mangadex", "MangaDex", "multi", multiCalls,
                List.of(new MangaSource.Section("hot", "热门",
                        List.of(item("mangadex", "One Piece")))));
        var registry = new MangaSourceRegistry(List.of(zh, multi), null);
        var service = new MangaDiscoveryService(registry, new MangaSectionsService(),
                new org.springframework.beans.factory.ObjectProvider<>() {
                    public com.mangashelf.config.SharedCacheHelper getIfAvailable() { return null; }
                    public com.mangashelf.config.SharedCacheHelper getObject() { return null; }
                });

        var result = service.discover(10);
        assertTrue(result.errors().isEmpty());
        assertEquals("all", result.rows().getFirst().id());
        assertEquals(1, result.rows().getFirst().items().size());
        assertTrue(result.rows().stream().noneMatch(r -> "mangadex".equals(r.sourceId())));
        assertEquals(1, zhCalls.get());
        assertEquals(0, multiCalls.get());
        // 缓存：第二次不再请求上游
        service.discover(10);
        assertEquals(1, zhCalls.get());
    }

    @Test
    void fuseDiversifiesConsecutiveSameAuthor() {
        var a1 = new MangaSource.RankItem("tencent", "1", "作品甲", List.of("作者A"), null,
                false, "", true, null, "tencent", "1");
        var a2 = new MangaSource.RankItem("tencent", "2", "作品乙", List.of("作者A"), null,
                false, "", true, null, "tencent", "2");
        var b1 = new MangaSource.RankItem("tencent", "3", "作品丙", List.of("作者B"), null,
                false, "", true, null, "tencent", "3");
        var fused = MangaDiscoveryService.fuse(List.of(List.of(a1, a2, b1)), 10, Map.of("tencent", "腾讯动漫"));
        assertEquals(3, fused.size());
        assertEquals("作品甲", fused.get(0).title());
        assertEquals("作品丙", fused.get(1).title());
        assertEquals("作品乙", fused.get(2).title());
    }

    @Test
    void searchAllStreamsMergesAcrossSourcesAndIsolatesFailures() {
        var badCalls = new AtomicInteger();
        MangaSource tencent = searchable("tencent", "腾讯动漫", true,
                List.of(new MangaSource.SearchItem("tencent", "t1", "一人之下", "异人", "https://c/1", "2015")));
        MangaSource baozi = searchable("baozimh", "包子漫画", true,
                List.of(new MangaSource.SearchItem("baozimh", "b1", "一人之下!", "", "https://c/2", ""),
                        new MangaSource.SearchItem("baozimh", "b2", "鬼灭之刃", "", "https://c/3", "")));
        MangaSource broken = new MangaSource() {
            @Override public String id() { return "broken"; }
            @Override public String name() { return "故障源"; }
            @Override public java.util.Set<Capability> capabilities() { return java.util.Set.of(Capability.SEARCH); }
            @Override public List<SearchItem> search(String q, String lang, int limit) {
                badCalls.incrementAndGet();
                throw new RuntimeException("connection reset");
            }
        };
        var registry = new MangaSourceRegistry(List.of(tencent, baozi, broken), null);
        var service = new MangaDiscoveryService(registry, new MangaSectionsService(), null);

        var res1 = service.searchAllStreams("一人之下", "all", 20, null);
        assertEquals(2, res1.items().size());
        assertEquals("一人之下", res1.items().getFirst().title());
        assertEquals(2, res1.items().getFirst().sourceCount());
        assertEquals(2, res1.items().getFirst().streams().size());
        assertTrue(res1.sourceStatus().stream().anyMatch(s -> "broken".equals(s.source()) && !s.ok()));

        // 第二次失败触发熔断，第三次搜索直接冷却跳过不再调上游
        service.searchAllStreams("一人之下", "all", 20, null);
        assertEquals(2, badCalls.get());
        var res3 = service.searchAllStreams("一人之下", "all", 20, null);
        assertEquals(2, badCalls.get());
        assertTrue(res3.sourceStatus().stream()
                .anyMatch(s -> "broken".equals(s.source()) && !s.ok() && s.error().contains("冷却")));
    }

    private static MangaSource searchable(String id, String name, boolean readable,
                                          List<MangaSource.SearchItem> items) {
        return new MangaSource() {
            @Override public String id() { return id; }
            @Override public String name() { return name; }
            @Override public java.util.Set<Capability> capabilities() {
                return readable
                        ? java.util.Set.of(Capability.SEARCH, Capability.READ)
                        : java.util.Set.of(Capability.SEARCH);
            }
            @Override public List<SearchItem> search(String q, String lang, int limit) {
                return items;
            }
        };
    }

    private static MangaSource fake(String id, String name, String lang,
                                    AtomicInteger calls, List<MangaSource.Section> sections) {
        return new MangaSource() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public String language() {
                return lang;
            }

            @Override
            public java.util.Set<Capability> capabilities() {
                return java.util.Set.of(Capability.RANK);
            }

            @Override
            public List<Section> sections(int limit) {
                calls.incrementAndGet();
                return sections;
            }
        };
    }
}

package com.mangashelf.external.manga;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 聚合推荐：RRF 累加/去重、中文过滤、非中文源排除。 */
class MangaDiscoveryServiceTest {

    private static MangaSource.RankItem item(String source, String title) {
        return new MangaSource.RankItem(source, title, title, List.of(), null,
                false, "", true, null, source, title);
    }

    @Test
    void fuseAccumulatesAcrossListsAndDedupes() {
        var a = item("tencent", "航海王");
        var b = item("tencent", "斗破苍穹");
        var c = item("tencent", "一人之下");
        var fused = MangaDiscoveryService.fuse(List.of(
                List.of(a, b, c),
                List.of(c, a)
        ), 10);
        assertEquals(3, fused.size());
        assertEquals("航海王", fused.get(0).title());   // 1/61 + 1/62
        assertEquals("一人之下", fused.get(1).title()); // 1/63 + 1/61
        assertEquals("斗破苍穹", fused.get(2).title()); // 1/62
        assertEquals(1, fused.stream().filter(x -> x.title().equals("航海王")).count());
    }

    @Test
    void fuseFiltersNonChineseTitles() {
        var fused = MangaDiscoveryService.fuse(List.of(
                List.of(item("tencent", "One Piece"), item("tencent", "航海王"))
        ), 10);
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
        var service = new MangaDiscoveryService(registry);

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

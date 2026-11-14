package com.mangashelf.external.manga;

import java.util.List;
import java.util.Set;

/**
 * 漫画数据源（M17）：元数据（榜单/搜索/详情）与阅读（章节页）按能力声明。
 * 新增源只需实现本接口并在 {@link MangaSourceRegistry} 注册；不支持的能力
 * 由控制器显式返回 409，不做静默回退。
 */
public interface MangaSource {

    enum Capability { RANK, SEARCH, DETAIL, READ }

    String id();

    String name();

    /** 内容语言（M25）：zh=中文源；multi=多语言（不参与中文聚合推荐）。 */
    default String language() {
        return "zh";
    }

    Set<Capability> capabilities();

    /** 说明（来源与限制，展示给用户）。 */
    default String note() {
        return "";
    }

    record RankItem(String source, String id, String title, List<String> authors,
                    String coverUrl, boolean finished, String latestLabel,
                    boolean readable, String externalUrl,
                    String readSource, String readId) {}

    record SearchItem(String source, String id, String title, String altTitle,
                      String coverUrl, String year) {}

    record ChapterItem(String id, String label, String title, String lang,
                       int pages, boolean external) {}

    record Detail(SearchItem item, String description, String status, String year,
                  List<String> tags, List<ChapterItem> chapters) {}

    record PageSize(int width, int height) {}

    record Pages(List<String> pages, List<String> dataSaver, boolean external, List<PageSize> dimensions) {
        public Pages(List<String> pages, List<String> dataSaver, boolean external) {
            this(pages, dataSaver, external, List.of());
        }
    }

    /** 首页发现流分区（M24）：编辑推荐/男生榜/女生榜/畅销榜等。 */
    record Section(String id, String name, List<RankItem> items) {}

    /** 热门榜（RANK 能力）。 */
    default List<RankItem> rank(int limit) throws Exception {
        throw new UnsupportedOperationException("rank");
    }

    /** 首页发现流分区（RANK 能力）；默认把热门榜当作单分区。 */
    default List<Section> sections(int limit) throws Exception {
        return List.of(new Section("hot", "热门", rank(limit)));
    }

    /** 搜索（SEARCH 能力）。 */
    default List<SearchItem> search(String query, String lang, int limit) throws Exception {
        throw new UnsupportedOperationException("search");
    }

    /** 详情与章节（DETAIL 能力）。 */
    default Detail detail(String mangaId, String lang) throws Exception {
        throw new UnsupportedOperationException("detail");
    }

    /** 章节页图（READ 能力）。 */
    default Pages pages(String chapterId) throws Exception {
        throw new UnsupportedOperationException("pages");
    }

    // ── 题材浏览（mobile-nav-genre-remove-mangadex，可选能力）──

    /** 题材清单（如热血/机甲）；空列表表示源不支持题材浏览。 */
    default List<String> genres() {
        return List.of();
    }

    /** 按题材搜索（GENRE 声明时实现）；与 search 相同返回形态。 */
    default List<SearchItem> searchByGenre(String genre, String lang, int limit) throws Exception {
        throw new UnsupportedOperationException("searchByGenre");
    }
}

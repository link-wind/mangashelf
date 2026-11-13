package com.mangashelf.retrieval;

import com.mangashelf.animation.AnimationRepository;
import com.mangashelf.catalog.CatalogRepository;
import com.mangashelf.catalog.Work;
import com.mangashelf.config.ApiException;
import com.mangashelf.identity.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 搜索模式与"有动画"筛选（unify-progress-conflict-and-search）。 */
class SearchServiceTest {

    private static final CurrentUser VIEWER = new CurrentUser(
            UUID.fromString("00000000-0000-0000-0000-000000000001"), "tester", "USER");

    private static Work work(String title) {
        Instant now = Instant.parse("2026-11-13T00:00:00Z");
        return new Work(UUID.randomUUID(), VIEWER.id(), title, "", Work.VISIBILITY_PUBLIC,
                Work.STATUS_PUBLISHED, "rtl", now, now);
    }

    /** 标题模式不得触碰 Embedding：模型一旦被调用即抛错。 */
    @Test
    void titleModeNeverCallsEmbeddingModel() {
        RetrievalRepository retrievals = mock(RetrievalRepository.class);
        CatalogRepository catalog = mock(CatalogRepository.class);
        AnimationRepository animations = mock(AnimationRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(provider.getIfAvailable()).thenReturn(model);
        when(model.embed(anyString())).thenThrow(new IllegalStateException("标题模式不应调用模型"));
        when(retrievals.searchChunks(VIEWER.id(), "海", 10)).thenReturn(List.of());
        when(catalog.searchWorksByTitle(VIEWER.id(), "海", 10)).thenReturn(List.of(work("海贼王")));

        var result = new SearchService(retrievals, catalog, animations, provider)
                .search(VIEWER, "海", SearchService.Mode.TITLE, false, 10);

        assertEquals("title", result.mode());
        assertEquals(1, result.hits().size());
        verify(model, never()).embed(anyString());
    }

    @Test
    void semanticModeWithoutModelFailsExplicitly() {
        RetrievalRepository retrievals = mock(RetrievalRepository.class);
        CatalogRepository catalog = mock(CatalogRepository.class);
        AnimationRepository animations = mock(AnimationRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        var ex = assertThrows(ApiException.class, () -> new SearchService(retrievals, catalog, animations, provider)
                .search(VIEWER, "海", SearchService.Mode.SEMANTIC, false, 10));
        assertEquals(503, ex.getStatus());
        assertEquals("SEMANTIC_UNAVAILABLE", ex.getCode());
    }

    /** 有动画筛选按页级关联实时过滤作品命中。 */
    @Test
    void hasAnimationFiltersWorkHits() {
        RetrievalRepository retrievals = mock(RetrievalRepository.class);
        CatalogRepository catalog = mock(CatalogRepository.class);
        AnimationRepository animations = mock(AnimationRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        Work animated = work("有动画的作品");
        Work plain = work("没有动画的作品");
        when(retrievals.searchChunks(VIEWER.id(), "作品", 10)).thenReturn(List.of());
        when(catalog.searchWorksByTitle(VIEWER.id(), "作品", 10)).thenReturn(List.of(animated, plain));
        when(animations.countPublishedForWork(animated.id())).thenReturn(1);
        when(animations.countPublishedForWork(plain.id())).thenReturn(0);

        var service = new SearchService(retrievals, catalog, animations, provider);
        var filtered = service.search(VIEWER, "作品", SearchService.Mode.TITLE, true, 10);
        assertEquals(1, filtered.hits().size());
        assertTrue(filtered.hits().get(0).hasAnimation());

        var unfiltered = service.search(VIEWER, "作品", SearchService.Mode.TITLE, false, 10);
        assertEquals(2, unfiltered.hits().size());
    }
}

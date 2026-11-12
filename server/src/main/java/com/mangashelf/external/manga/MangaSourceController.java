package com.mangashelf.external.manga;

import com.mangashelf.config.ApiException;
import com.mangashelf.identity.AuthUser;
import com.mangashelf.identity.CurrentUser;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 漫画数据源统一入口（M17）：元数据（榜单/搜索/详情）与阅读按源能力分发。
 * 源不支持的能力返回 409 + 明确原因，不做静默降级。
 */
@RestController
@Profile("api")
@RequestMapping("/api/v1/manga-sources")
public class MangaSourceController {

    private static final Logger log = LoggerFactory.getLogger(MangaSourceController.class);

    private final MangaSourceRegistry registry;
    private final MangaSectionsService sectionsService;
    private final BilibiliReaderService bilibili;

    public MangaSourceController(MangaSourceRegistry registry, MangaSectionsService sectionsService,
                                 BilibiliReaderService bilibili) {
        this.registry = registry;
        this.sectionsService = sectionsService;
        this.bilibili = bilibili;
    }

    @GetMapping
    public List<MangaSourceRegistry.SourceInfo> list() {
        return registry.describe();
    }

    @GetMapping("/{source}/rank")
    public List<MangaSource.RankItem> rank(@PathVariable String source,
                                           @RequestParam(defaultValue = "24") int limit) {
        MangaSource s = require(source, MangaSource.Capability.RANK);
        try {
            return s.rank(limit);
        } catch (Exception e) {
            throw new ApiException(502, "SOURCE_UNAVAILABLE", s.name() + " 请求失败：" + e.getMessage());
        }
    }

    /** 首页发现流分区（M24）：推荐/男生榜/女生榜/畅销榜等。 */
    @GetMapping("/{source}/sections")
    public List<MangaSource.Section> sections(@PathVariable String source,
                                              @RequestParam(defaultValue = "12") int limit) {
        MangaSource s = require(source, MangaSource.Capability.RANK);
        try {
            return sectionsService.get(s, limit);
        } catch (Exception e) {
            throw new ApiException(502, "SOURCE_UNAVAILABLE", s.name() + " 请求失败：" + e.getMessage());
        }
    }

    @GetMapping("/{source}/search")
    public List<MangaSource.SearchItem> search(@PathVariable String source,
                                               @RequestParam(required = false) String q,
                                               @RequestParam(defaultValue = "zh") String lang,
                                               @RequestParam(defaultValue = "24") int limit) {
        MangaSource s = require(source, MangaSource.Capability.SEARCH);
        try {
            return s.search(q, "all".equals(lang) ? null : lang, limit);
        } catch (Exception e) {
            throw new ApiException(502, "SOURCE_UNAVAILABLE", s.name() + " 请求失败：" + e.getMessage());
        }
    }

    @GetMapping("/{source}/manga/{mangaId}")
    public MangaSource.Detail detail(@PathVariable String source, @PathVariable String mangaId,
                                     @RequestParam(defaultValue = "zh") String lang) {
        MangaSource s = require(source, MangaSource.Capability.DETAIL);
        try {
            return s.detail(mangaId, "all".equals(lang) ? null : lang);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "SOURCE_UNAVAILABLE", s.name() + " 请求失败：" + e.getMessage());
        }
    }

    @GetMapping("/{source}/chapter/{chapterId}/pages")
    public MangaSource.Pages pages(@PathVariable String source, @PathVariable String chapterId) {
        MangaSource s = require(source, MangaSource.Capability.READ);
        try {
            return s.pages(chapterId);
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(502, "SOURCE_UNAVAILABLE", s.name() + " 请求已中断");
        } catch (Exception e) {
            log.warn("{} 章节 {} 请求失败", s.name(), chapterId, e);
            throw new ApiException(502, "SOURCE_UNAVAILABLE", s.name() + " 请求失败："
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    @GetMapping("/capture/{sessionId}")
    public MangaSource.Pages capture(@PathVariable String sessionId) {
        throw new ApiException(410, "CAPTURE_REPLACED", "章节已改为按需加载，请重新打开章节");
    }

    @GetMapping("/bilibili/chapter/{chapterId}/image/{ordinal}")
    public ResponseEntity<byte[]> bilibiliImage(@AuthUser CurrentUser user,
            @PathVariable String chapterId, @PathVariable int ordinal, @RequestParam("v") String version,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        BilibiliReaderService.PageImage image = bilibili.image(chapterId, ordinal, version);
        return ResponseEntity.ok().header("Content-Type", image.contentType())
                .header("Cache-Control", "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("X-MangaShelf-Cache", image.cacheHit() ? "HIT" : "MISS")
                .body(image.bytes());
    }

    private MangaSource require(String sourceId, MangaSource.Capability cap) {
        MangaSource s = registry.find(sourceId).orElseThrow(
                () -> new ApiException(404, "SOURCE_NOT_FOUND", "漫画源不存在：" + sourceId));
        if (!s.capabilities().contains(cap)) {
            throw new ApiException(409, "SOURCE_CAPABILITY_UNAVAILABLE",
                    s.name() + " 不支持该能力（" + cap + "）。");
        }
        return s;
    }
}

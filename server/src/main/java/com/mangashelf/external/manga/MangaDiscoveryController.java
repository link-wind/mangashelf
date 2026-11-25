package com.mangashelf.external.manga;

import com.mangashelf.config.ApiException;
import com.mangashelf.identity.CurrentUser;
import com.mangashelf.identity.OptionalAuthUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 多源聚合推荐与全流漫画搜索（M25 + revamp-discovery-search-index-and-nav）。 */
@RestController
@RequestMapping("/api/v1/manga-discovery")
public class MangaDiscoveryController {

    private final MangaDiscoveryService service;

    public MangaDiscoveryController(MangaDiscoveryService service) {
        this.service = service;
    }

    @GetMapping
    public MangaDiscoveryService.Result discover(@RequestParam(defaultValue = "12") int limit) {
        try {
            return service.discover(limit);
        } catch (Exception e) {
            throw new ApiException(502, "DISCOVERY_UNAVAILABLE", "聚合推荐请求失败：" + e.getMessage());
        }
    }

    @GetMapping("/search")
    public MangaDiscoveryService.UnifiedSearchResult search(
            @OptionalAuthUser CurrentUser user,
            @RequestParam String q,
            @RequestParam(defaultValue = "all") String source,
            @RequestParam(defaultValue = "30") int limit) {
        return service.searchAllStreams(q, source, limit, user);
    }
}

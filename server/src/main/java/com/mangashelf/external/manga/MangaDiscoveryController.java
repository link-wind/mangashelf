package com.mangashelf.external.manga;

import com.mangashelf.config.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 多源聚合推荐（M25）：中文源综合热榜 + 各源分区。 */
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
}

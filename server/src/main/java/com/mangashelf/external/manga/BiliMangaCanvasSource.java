package com.mangashelf.external.manga;

import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.ObjectMapper;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 哔哩漫画 canvas 截图源（M35）：页图渲染在 <canvas>（WASM 解密 + 防抓取），
 * Playwright 逐页截图 → 上传对象存储 → 阅读器读站内 URL。
 * 与既有 BilibiliMangaSource（RANK）合并： READ + RANK 同一 id="bilibili"，
 * 来源 chip 只显示一个「哔哩漫画」。
 */
public class BiliMangaCanvasSource implements MangaSource {

    private static final String READ_BASE = "https://manga.bilibili.com/";

    private final MangaBrowserResolveService browser;
    private final ObjectMapper objectMapper;
    private final String cookie;
    private final String storageState;
    private final ConcurrentHashMap<String, CacheEntry> pagesCache = new ConcurrentHashMap<>();

    private record CacheEntry(List<String> urls, long expiresAt) {}

    public BiliMangaCanvasSource(MangaBrowserResolveService browser, ObjectMapper objectMapper,
                                 String cookie, String storageState) {
        this.browser = browser;
        this.objectMapper = objectMapper;
        this.cookie = cookie == null ? "" : cookie.trim();
        this.storageState = storageState == null ? "" : storageState.trim();
    }

    @Override
    public String id() {
        return "bilibili";
    }

    @Override
    public String name() {
        return "哔哩漫画";
    }

    @Override
    public String language() {
        return "zh";
    }

    @Override
    public Set<Capability> capabilities() {
        return EnumSet.of(Capability.READ);
    }

    @Override
    public String note() {
        return "canvas 截图源（免费章节站内阅读）；解析需数秒";
    }

    @Override
    public Pages pages(String chapterId) throws Exception {
        // 章节 ID：mc{comicId}/{epId}
        if (chapterId == null || !chapterId.matches("mc\\d+-\\d+")) {
            throw new IllegalArgumentException("章节 ID 应为 mc{comicId}/{epId}：" + chapterId);
        }
        long now = System.currentTimeMillis();
        CacheEntry cached = pagesCache.get(chapterId);
        if (cached != null && cached.expiresAt() > now) {
            return new Pages(cached.urls(), List.of(), false);
        }
        String[] parts = chapterId.split("-", 2);
        List<String> urls = browser.resolveChapterCanvas(
                READ_BASE + parts[0] + "/" + parts[1], cookie, storageState);
        // objectKey → 站内完整 URL
        List<String> full = urls.stream()
                .map(k -> "/api/v1/external-manga/" + k).toList();
        pagesCache.put(chapterId, new CacheEntry(full, now + 30 * 60 * 1000L));
        return new Pages(full, List.of(), false);
    }
}

package com.mangashelf.external.manga;

import com.mangashelf.config.ApiException;
import com.mangashelf.identity.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 外部漫画（M16，MangaDex）：搜索 / 详情 / 章节页图 / 图片代理。
 * 页图默认返回 CDN 绝对地址由浏览器直连；图片代理带域名白名单与磁盘缓存，
 * 供 CDN 不可达时回退。
 */
@RestController
@RequestMapping("/api/v1/external-manga")
public class ExternalMangaController {

    private final MangaDexClient client;

    /** 上游限流保护：MangaDex 约 5 req/s，这里限制并发并在 429 时退避重试。 */
    private static final java.util.concurrent.Semaphore GATE =
            new java.util.concurrent.Semaphore(3, true);

    public ExternalMangaController(MangaDexClient client) {
        this.client = client;
    }

    @GetMapping("/search")
    public List<MangaDexClient.Manga> search(@RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "zh") String lang,
                                             @RequestParam(defaultValue = "24") int limit) {
        try {
            return client.search(q, "all".equals(lang) ? null : lang, limit);
        } catch (Exception e) {
            throw new ApiException(502, "MANGADEX_UNAVAILABLE", "MangaDex 请求失败：" + e.getMessage());
        }
    }

    public record Detail(MangaDexClient.Manga manga, List<MangaDexClient.Chapter> chapters) {}

    @GetMapping("/{mangaId}")
    public Detail detail(@PathVariable String mangaId,
                         @RequestParam(defaultValue = "zh") String lang) {
        try {
            MangaDexClient.Manga m = client.manga(mangaId);
            List<MangaDexClient.Chapter> chapters =
                    client.chapters(mangaId, "all".equals(lang) ? null : lang, 500);
            return new Detail(m, chapters);
        } catch (Exception e) {
            throw new ApiException(502, "MANGADEX_UNAVAILABLE", "MangaDex 请求失败：" + e.getMessage());
        }
    }

    public record ChapterPageView(String chapterId, String baseUrl, String hash,
                                  List<String> pages, List<String> dataSaver, boolean external) {}

    @GetMapping("/chapter/{chapterId}/pages")
    public ChapterPageView pages(@PathVariable String chapterId) {
        try {
            MangaDexClient.ChapterPages p = client.pages(chapterId);
            if (p.hash().isBlank() || p.pages().isEmpty()) {
                return new ChapterPageView(chapterId, p.baseUrl(), p.hash(), List.of(), List.of(), true);
            }
            List<String> data = absolute(p.baseUrl(), "data", p.hash(), p.pages());
            List<String> saver = absolute(p.baseUrl(), "data-saver", p.hash(), p.dataSaver());
            return new ChapterPageView(chapterId, p.baseUrl(), p.hash(), data, saver, false);
        } catch (Exception e) {
            throw new ApiException(502, "MANGADEX_UNAVAILABLE", "MangaDex 请求失败：" + e.getMessage());
        }
    }

    private static List<String> absolute(String base, String kind, String hash, List<String> files) {
        List<String> out = new ArrayList<>(files.size());
        for (String f : files) {
            out.add(base + "/" + kind + "/" + hash + "/" + f);
        }
        return out;
    }

    /** 图片代理：仅 MangaDex 图片域名；按 URL 哈希磁盘缓存。 */
    @GetMapping("/image")
    public ResponseEntity<byte[]> image(@RequestParam("u") String url) throws Exception {
        if (!MangaDexClient.isAllowedImageHost(url)) {
            return ResponseEntity.badRequest().build();
        }
        Path cacheDir = Path.of(System.getenv().getOrDefault("MANGASHELF_WORK_ROOT",
                "/tmp/mangashelf-work").toString()).getParent().resolve("manga-cache");
        String key = sha256(url) + ".bin";
        Path cached = cacheDir.resolve(key);
        if (Files.isRegularFile(cached)) {
            return ResponseEntity.ok()
                    .header("Content-Type", "image/jpeg")
                    .header("Cache-Control", "public, max-age=604800")
                    .body(Files.readAllBytes(cached));
        }
        MangaDexClient.Image img;
        try {
            GATE.acquire();
            try {
                img = client.fetchImage(url);
            } finally {
                GATE.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(503).build();
        }
        if (img == null) return ResponseEntity.notFound().build();
        Files.createDirectories(cacheDir);
        Files.write(cached, img.bytes());
        return ResponseEntity.ok()
                .header("Content-Type", img.contentType())
                .header("Cache-Control", "public, max-age=604800")
                .body(img.bytes());
    }

    private static String sha256(String s) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.substring(0, 32);
        } catch (Exception e) {
            return UUID.nameUUIDFromBytes(s.getBytes()).toString();
        }
    }
}

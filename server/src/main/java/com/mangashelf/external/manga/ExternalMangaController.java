package com.mangashelf.external.manga;

import com.mangashelf.config.ApiException;
import com.mangashelf.identity.CurrentUser;
import com.mangashelf.identity.AuthUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * 外部漫画（M16，MangaDex）：图片代理与历史截图链接 410。
 * 搜索/详情/章节页图已统一到 /api/v1/manga-sources/mangadex（retire-legacy-implementations）；
 * 图片代理带域名白名单与磁盘缓存，供 CDN 不可达时回退。
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

    @org.springframework.web.bind.annotation.GetMapping("/captured/{hash}/{ordinal}.png")
    public org.springframework.http.ResponseEntity<byte[]> captured(
            @AuthUser CurrentUser user,
            @org.springframework.web.bind.annotation.PathVariable String hash,
            @org.springframework.web.bind.annotation.PathVariable int ordinal) throws Exception {
        throw new ApiException(410, "CAPTURE_REPLACED", "截图阅读已替换，请重新打开章节");
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

package com.mangashelf.external.manga;

import com.mangashelf.config.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BilibiliReaderServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    Path root;

    @Test
    void manifestDoesNotDownloadAndDiskCacheSurvivesRestart() throws Exception {
        var upstream = new SampleClient();
        var reader = reader(upstream);
        var pages = reader.pages("mc1-2");
        assertEquals(2, pages.pages().size());
        assertEquals(0, upstream.imageCalls.get());
        assertEquals(128, pages.dimensions().getFirst().width());
        assertEquals(403, assertThrows(ApiException.class, () -> reader.pages("mc1-3")).getStatus());
        assertEquals(404, assertThrows(ApiException.class, () -> reader.pages("mc1-99")).getStatus());
        String version = version(pages);
        assertFalse(reader.image("mc1-2", 2, version).cacheHit());
        assertTrue(reader.image("mc1-2", 2, version).cacheHit());
        assertEquals(1, upstream.imageCalls.get());
        var freshUpstream = new SampleClient();
        var restarted = reader(freshUpstream);
        assertTrue(restarted.image("mc1-2", 2, version).cacheHit());
        assertEquals(0, freshUpstream.detailCalls.get());
        assertEquals(0, freshUpstream.indexCalls.get());
        assertEquals(0, freshUpstream.imageCalls.get());
        try (var files = Files.list(root.resolve("metadata"))) {
            for (Path file : files.toList()) {
                String json = Files.readString(file);
                assertFalse(json.contains("Cookie"));
                assertFalse(json.contains("token"));
                assertFalse(json.contains("complete_url"));
            }
        }
    }

    @Test
    void concurrentReadsDownloadOnlyOnceAndRejectOldVersion() throws Exception {
        var upstream = new SampleClient();
        var reader = reader(upstream);
        String version = version(reader.pages("mc1-2"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Callable<BilibiliReaderService.PageImage>>();
            for (int index = 0; index < 8; index++) {
                tasks.add(() -> reader.image("mc1-2", 1, version));
            }
            for (var future : executor.invokeAll(tasks)) assertTrue(future.get().bytes().length > 0);
        }
        assertEquals(1, upstream.imageCalls.get());
        assertEquals(409, assertThrows(ApiException.class,
                () -> reader.image("mc1-2", 1, "0".repeat(64))).getStatus());
        assertEquals(400, assertThrows(ApiException.class,
                () -> reader.image("mc1-2", 0, version)).getStatus());
    }

    @Test
    void invalidDownloadsAreNotCachedAndExpiredAuthorizationIsNotReused() throws Exception {
        var upstream = new SampleClient();
        var reader = reader(upstream);
        String version = version(reader.pages("mc1-2"));
        upstream.invalidImage = true;
        assertEquals("BILIBILI_IMAGE_INVALID", assertThrows(ApiException.class,
                () -> reader.image("mc1-2", 1, version)).getCode());
        try (var files = Files.list(root.resolve("pages"))) {
            assertEquals(0, files.count());
        }
        upstream.invalidImage = false;
        assertFalse(reader.image("mc1-2", 1, version).cacheHit());
        Path detail = root.resolve("metadata/detail-1.json");
        var stored = mapper.readValue(detail.toFile(), BilibiliReaderService.StoredDetail.class);
        mapper.writeValue(detail.toFile(), new BilibiliReaderService.StoredDetail(0, stored.detail()));
        upstream.failDetail = true;
        assertEquals("SAMPLE_UPSTREAM_FAILURE", assertThrows(ApiException.class,
                () -> reader.image("mc1-2", 1, version)).getCode());
    }

    @Test
    void pruneRemovesExpiredAndOldestFilesWithinQuota() throws Exception {
        var reader = reader(new SampleClient());
        Path oldest = root.resolve("pages/old.image");
        Path newest = root.resolve("pages/new.image");
        Files.write(oldest, new byte[8 * 1024 * 1024]);
        Files.write(newest, new byte[8 * 1024 * 1024]);
        Files.setLastModifiedTime(oldest, FileTime.fromMillis(System.currentTimeMillis() - 60000));
        reader.prune();
        assertFalse(Files.exists(oldest));
        assertTrue(Files.exists(newest));
        Files.setLastModifiedTime(newest, FileTime.fromMillis(
                System.currentTimeMillis() - Duration.ofDays(8).toMillis()));
        reader.prune();
        assertFalse(Files.exists(newest));
    }

    @Test
    void truncatedImagesAndIncompleteCatalogFail() throws Exception {
        byte[] jpeg = jpeg();
        assertEquals("image/jpeg", BilibiliReaderService.validateImage(jpeg, 128, 192));
        assertThrows(ApiException.class,
                () -> BilibiliReaderService.validateImage(Arrays.copyOf(jpeg, jpeg.length - 2), 128, 192));
        assertThrows(ApiException.class, () -> BilibiliReaderService.validateImage(jpeg, 127, 192));
        assertThrows(ApiException.class, () -> BilibiliReaderService.normalizeDetail("1",
                mapper.readTree("{\"id\":1,\"title\":\"样本\",\"total\":2,\"ep_list\":[]}")));
        assertFalse(BilibiliHttpClient.isAllowedImageUrl(URI.create("http://manga.hdslb.com/image")));
        assertFalse(BilibiliHttpClient.isAllowedImageUrl(URI.create("https://manga.hdslb.com.evil.test/image")));
        assertFalse(BilibiliHttpClient.isAllowedImageUrl(URI.create("https://user@a.hdslb.com/image")));
        assertFalse(BilibiliHttpClient.isAllowedImageUrl(URI.create("https://a.hdslb.com:8443/image")));
        assertTrue(BilibiliHttpClient.isAllowedImageUrl(URI.create("https://manga.hdslb.com/image")));
    }

    private BilibiliReaderService reader(SampleClient client) throws Exception {
        return new BilibiliReaderService(client, mapper, root, 1800, 600, 604800,
                BilibiliHttpClient.MAX_IMAGE_BYTES);
    }

    private static String version(MangaSource.Pages pages) {
        return pages.pages().getFirst().split("\\?v=")[1];
    }

    private static byte[] jpeg() throws Exception {
        var image = new BufferedImage(128, 192, BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "JPEG", output);
        return output.toByteArray();
    }

    private final class SampleClient extends BilibiliHttpClient {
        private final AtomicInteger detailCalls = new AtomicInteger();
        private final AtomicInteger indexCalls = new AtomicInteger();
        private final AtomicInteger imageCalls = new AtomicInteger();
        private boolean invalidImage;
        private boolean failDetail;

        private SampleClient() {
            super(BilibiliReaderServiceTest.this.mapper, HttpClient.newHttpClient(), URI.create("https://manga.bilibili.com"),
                    URI.create("https://api.bilibili.com/x/frontend/finger/spi"));
        }

        @Override
        public JsonNode detail(String mangaId) {
            detailCalls.incrementAndGet();
            if (failDetail) throw new ApiException(502, "SAMPLE_UPSTREAM_FAILURE", "受控上游失败");
            return mapper.readTree("""
                    {"id":%s,"title":"样本","total":2,"ep_list":[
                    {"id":2,"ord":1,"short_title":"1","is_locked":false,"pay_mode":0,"image_count":2},
                    {"id":3,"ord":2,"short_title":"2","is_locked":true,"pay_mode":1,"image_count":2}]}
                    """.formatted(mangaId));
        }

        @Override
        public JsonNode index(String episodeId) {
            indexCalls.incrementAndGet();
            return mapper.readTree("""
                    {"last_modified":"sample-v1","images":[
                    {"path":"/sample/1.jpg","x":128,"y":192},
                    {"path":"/sample/2.jpg","x":128,"y":192}]}
                    """);
        }

        @Override
        public byte[] image(String path) {
            imageCalls.incrementAndGet();
            if (invalidImage) return new byte[100];
            try {
                return jpeg();
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        }
    }
}

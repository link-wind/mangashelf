package com.mangashelf.external.manga;

import com.mangashelf.config.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
@Profile("api")
public class BilibiliReaderService {

    private static final Logger log = LoggerFactory.getLogger(BilibiliReaderService.class);
    private final BilibiliHttpClient upstream;
    private final ObjectMapper mapper;
    private final Path metadataRoot;
    private final Path pagesRoot;
    private final long detailTtlMillis;
    private final long indexTtlMillis;
    private final long imageTtlMillis;
    private final long maxBytes;
    private final Object[] locks = new Object[64];
    private final Semaphore downloads = new Semaphore(4, true);
    private final Object maintenance = new Object();

    public record ImagePage(String path, int width, int height) {}
    public record StoredDetail(long expiresAt, MangaSource.Detail detail) {}
    public record StoredIndex(long expiresAt, String version, List<ImagePage> images) {}
    public record PageImage(byte[] bytes, String contentType, boolean cacheHit) {}
    private record ChapterRef(String mangaId, String episodeId) {}

    @Autowired
    public BilibiliReaderService(BilibiliHttpClient upstream, ObjectMapper mapper,
            @Value("${mangashelf.bilibili.cache-root}") Path cacheRoot,
            @Value("${mangashelf.bilibili.detail-ttl-seconds:1800}") long detailTtlSeconds,
            @Value("${mangashelf.bilibili.index-ttl-seconds:600}") long indexTtlSeconds,
            @Value("${mangashelf.bilibili.image-ttl-seconds:604800}") long imageTtlSeconds,
            @Value("${mangashelf.bilibili.cache-max-bytes:536870912}") long maxBytes) throws IOException {
        if (detailTtlSeconds < 1 || indexTtlSeconds < 1 || imageTtlSeconds < 1
                || maxBytes < BilibiliHttpClient.MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("哔哩缓存期限须为正数，容量不得小于单页下载上限");
        }
        this.upstream = upstream;
        this.mapper = mapper;
        this.metadataRoot = cacheRoot.toAbsolutePath().normalize().resolve("metadata");
        this.pagesRoot = cacheRoot.toAbsolutePath().normalize().resolve("pages");
        this.detailTtlMillis = Duration.ofSeconds(detailTtlSeconds).toMillis();
        this.indexTtlMillis = Duration.ofSeconds(indexTtlSeconds).toMillis();
        this.imageTtlMillis = Duration.ofSeconds(imageTtlSeconds).toMillis();
        this.maxBytes = maxBytes;
        for (int index = 0; index < locks.length; index++) locks[index] = new Object();
        Files.createDirectories(metadataRoot);
        Files.createDirectories(pagesRoot);
        prune();
    }

    public MangaSource.Detail detail(String mangaId) {
        requireMangaId(mangaId);
        Path path = metadataRoot.resolve("detail-" + mangaId + ".json");
        synchronized (lock("detail|" + mangaId)) {
            StoredDetail cached = read(path, StoredDetail.class);
            if (cached != null && cached.expiresAt() > now()) {
                if (cached.detail() == null || cached.detail().chapters() == null) throw invalidCache();
                return cached.detail();
            }
            MangaSource.Detail detail = normalizeDetail(mangaId, upstream.detail(mangaId));
            write(path, new StoredDetail(now() + detailTtlMillis, detail));
            return detail;
        }
    }

    public MangaSource.Pages pages(String chapterId) {
        StoredIndex index = index(chapterId);
        List<String> urls = new ArrayList<>();
        List<MangaSource.PageSize> dimensions = new ArrayList<>();
        for (int offset = 0; offset < index.images().size(); offset++) {
            ImagePage image = index.images().get(offset);
            urls.add("/api/v1/manga-sources/bilibili/chapter/" + chapterId
                    + "/image/" + (offset + 1) + "?v=" + index.version());
            dimensions.add(new MangaSource.PageSize(image.width(), image.height()));
        }
        return new MangaSource.Pages(urls, List.of(), false, dimensions);
    }

    public PageImage image(String chapterId, int ordinal, String version) {
        if (ordinal < 1 || ordinal > 300 || version == null || !version.matches("[a-f0-9]{64}")) {
            throw new ApiException(400, "BILIBILI_PAGE_INVALID", "哔哩页码或清单版本无效");
        }
        StoredIndex index = index(chapterId);
        if (!index.version().equals(version)) {
            throw new ApiException(409, "BILIBILI_INDEX_CHANGED", "章节清单已更新，请重新打开章节");
        }
        if (ordinal > index.images().size()) {
            throw new ApiException(404, "BILIBILI_PAGE_NOT_FOUND", "该章节没有此页");
        }
        ImagePage image = index.images().get(ordinal - 1);
        String key = hash(chapterId + "|" + version + "|" + ordinal);
        Path file = pagesRoot.resolve(key + ".image");
        synchronized (lock("page|" + key)) {
            try {
                synchronized (maintenance) {
                    if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                            && Files.getLastModifiedTime(file).toMillis() + imageTtlMillis > now()) {
                        byte[] bytes = readImage(file);
                        try {
                            String contentType = validateImage(bytes, image.width(), image.height());
                            Files.setLastModifiedTime(file, FileTime.fromMillis(now()));
                            return new PageImage(bytes, contentType, true);
                        } catch (ApiException error) {
                            Files.deleteIfExists(file);
                            log.warn("清理损坏的哔哩页图缓存 {}", key);
                        }
                    }
                }
                if (!downloads.tryAcquire(10, TimeUnit.SECONDS)) {
                    throw new ApiException(503, "BILIBILI_DOWNLOAD_BUSY", "页图加载繁忙，请稍后重试");
                }
                byte[] bytes;
                try {
                    bytes = upstream.image(image.path());
                } finally {
                    downloads.release();
                }
                String contentType = validateImage(bytes, image.width(), image.height());
                synchronized (maintenance) {
                    writeBytes(file, bytes);
                    prune();
                }
                return new PageImage(bytes, contentType, false);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new ApiException(502, "BILIBILI_REQUEST_INTERRUPTED", "页图加载已中断");
            } catch (IOException error) {
                throw cacheError();
            }
        }
    }

    private StoredIndex index(String chapterId) {
        ChapterRef chapter = parseChapter(chapterId);
        MangaSource.ChapterItem item = detail(chapter.mangaId()).chapters().stream()
                .filter(entry -> entry.id().equals(chapterId)).findFirst()
                .orElseThrow(() -> new ApiException(404, "BILIBILI_CHAPTER_NOT_FOUND", "作品中没有此章节"));
        if (item.external()) {
            throw new ApiException(403, "BILIBILI_CHAPTER_LOCKED", "该章节需要登录、购买或解锁，请前往哔哩原站");
        }
        Path path = metadataRoot.resolve("index-" + chapterId + ".json");
        synchronized (lock("index|" + chapterId)) {
            StoredIndex cached = read(path, StoredIndex.class);
            if (cached != null && cached.expiresAt() > now()) {
                if (cached.version() == null || !cached.version().matches("[a-f0-9]{64}")
                        || cached.images() == null || cached.images().isEmpty()) throw invalidCache();
                return cached;
            }
            JsonNode data = upstream.index(chapter.episodeId());
            JsonNode images = data.path("images");
            if (!images.isArray() || images.isEmpty() || images.size() > 300
                    || (item.pages() > 0 && item.pages() != images.size())) {
                throw new ApiException(502, "BILIBILI_INDEX_INCOMPLETE", "哔哩章节页数未能完整确认");
            }
            List<ImagePage> pages = new ArrayList<>();
            HashSet<String> paths = new HashSet<>();
            for (JsonNode image : images) {
                String imagePath = image.path("path").asText("");
                int width = image.path("x").asInt();
                int height = image.path("y").asInt();
                if (!imagePath.startsWith("/") || imagePath.startsWith("//")
                        || imagePath.contains("?") || imagePath.contains("#")
                        || imagePath.contains("\\") || !paths.add(imagePath)
                        || !validDimensions(width, height)) {
                    throw new ApiException(502, "BILIBILI_INDEX_INVALID", "哔哩章节页图索引无效");
                }
                pages.add(new ImagePage(imagePath, width, height));
            }
            String version = hash(chapterId + "|" + data.path("last_modified").asText("")
                    + "|" + mapper.writeValueAsString(pages));
            StoredIndex result = new StoredIndex(now() + indexTtlMillis, version, List.copyOf(pages));
            write(path, result);
            return result;
        }
    }

    static MangaSource.Detail normalizeDetail(String mangaId, JsonNode data) {
        JsonNode episodes = data.path("ep_list");
        int total = data.path("total").asInt(-1);
        if (!mangaId.equals(data.path("id").asText()) || data.path("title").asText("").isBlank()
                || !episodes.isArray() || total < 1 || total > 10000 || episodes.size() != total) {
            throw new ApiException(502, "BILIBILI_DETAIL_INCOMPLETE", "暂时无法确认哔哩的完整章节列表，请前往原站");
        }
        List<JsonNode> ordered = new ArrayList<>();
        episodes.forEach(ordered::add);
        ordered.sort(Comparator.comparingDouble(episode -> episode.path("ord").asDouble()));
        HashSet<String> ids = new HashSet<>();
        List<MangaSource.ChapterItem> chapters = new ArrayList<>();
        for (JsonNode episode : ordered) {
            String id = episode.path("id").asText("");
            if (!id.matches("[1-9][0-9]{0,11}") || !ids.add(id)) {
                throw new ApiException(502, "BILIBILI_DETAIL_INCOMPLETE", "哔哩章节列表存在无效或重复章节");
            }
            boolean readable = episode.path("is_locked").isBoolean()
                    && !episode.path("is_locked").asBoolean(true)
                    && episode.path("pay_mode").asInt(-1) == 0;
            int count = episode.path("image_count").asInt(-1);
            chapters.add(new MangaSource.ChapterItem("mc" + mangaId + "-" + id,
                    episode.path("short_title").asText(episode.path("ord").asText()),
                    episode.path("title").asText(""), "zh",
                    readable ? (count > 0 ? count : -1) : 0, !readable));
        }
        var item = new MangaSource.SearchItem("bilibili", mangaId, data.path("title").asText(),
                "", data.path("vertical_cover").asText(""), null);
        return new MangaSource.Detail(item, data.path("evaluate").asText(""),
                data.path("is_finish").asInt() == 1 ? "已完结" : "连载中",
                null, List.of(), List.copyOf(chapters));
    }

    static String validateImage(byte[] bytes, int expectedWidth, int expectedHeight) {
        if (bytes.length < 16 || bytes.length > BilibiliHttpClient.MAX_IMAGE_BYTES) {
            throw invalidImage();
        }
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalidImage();
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName();
                String contentType;
                if ("JPEG".equalsIgnoreCase(format)) {
                    if ((bytes[bytes.length - 2] & 0xff) != 0xff
                            || (bytes[bytes.length - 1] & 0xff) != 0xd9) throw invalidImage();
                    contentType = "image/jpeg";
                } else if ("PNG".equalsIgnoreCase(format)) {
                    if (bytes.length < 12 || bytes[bytes.length - 8] != 'I'
                            || bytes[bytes.length - 7] != 'E'
                            || bytes[bytes.length - 6] != 'N'
                            || bytes[bytes.length - 5] != 'D') throw invalidImage();
                    contentType = "image/png";
                }
                else throw invalidImage();
                reader.addIIOReadWarningListener((source, warning) -> { throw invalidImage(); });
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (!validDimensions(width, height)
                        || width != expectedWidth || height != expectedHeight) throw invalidImage();
                if (reader.read(0) == null) throw invalidImage();
                return contentType;
            } finally {
                reader.dispose();
            }
        } catch (IOException error) {
            throw invalidImage();
        }
    }

    private static boolean validDimensions(int width, int height) {
        return width >= 100 && height >= 100 && width <= 20000 && height <= 50000
                && (long) width * height <= 40_000_000;
    }

    private <T> T read(Path path, Class<T> type) {
        synchronized (maintenance) {
            try {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null;
                if (Files.size(path) > 8 * 1024 * 1024) throw new IOException();
                return mapper.readValue(path.toFile(), type);
            } catch (RuntimeException | IOException error) {
                throw invalidCache();
            }
        }
    }

    private void write(Path path, Object value) {
        synchronized (maintenance) {
            try {
                writeBytes(path, mapper.writeValueAsBytes(value));
                prune();
            } catch (IOException error) {
                throw cacheError();
            }
        }
    }

    private static void writeBytes(Path path, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), "writing-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] readImage(Path path) throws IOException {
        try (var stream = Files.newInputStream(path)) {
            return stream.readNBytes(BilibiliHttpClient.MAX_IMAGE_BYTES + 1);
        }
    }

    @Scheduled(fixedDelay = 60000)
    public void prune() {
        synchronized (maintenance) {
            try {
                List<Path> retained = new ArrayList<>();
                long size = 0;
                for (Path directory : List.of(metadataRoot, pagesRoot)) {
                    try (var entries = Files.list(directory)) {
                        for (Path path : entries.toList()) {
                            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                            long modified = Files.getLastModifiedTime(path).toMillis();
                            String name = path.getFileName().toString();
                            long ttl = directory.equals(pagesRoot) ? imageTtlMillis
                                    : name.startsWith("detail-") ? detailTtlMillis : indexTtlMillis;
                            if (name.endsWith(".tmp")) {
                                if (modified + Duration.ofHours(1).toMillis() < now()) Files.deleteIfExists(path);
                            } else if (modified + ttl <= now()) {
                                Files.deleteIfExists(path);
                            } else {
                                retained.add(path);
                                size += Files.size(path);
                            }
                        }
                    }
                }
                retained.sort(Comparator.comparingLong(BilibiliReaderService::modifiedAt));
                for (Path path : retained) {
                    if (size <= maxBytes) break;
                    long bytes = Files.size(path);
                    if (Files.deleteIfExists(path)) size -= bytes;
                }
            } catch (IOException error) {
                throw cacheError();
            }
        }
    }

    private static long modifiedAt(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException error) {
            return 0;
        }
    }

    private Object lock(String key) {
        return locks[Math.floorMod(key.hashCode(), locks.length)];
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void requireMangaId(String mangaId) {
        if (mangaId == null || !mangaId.matches("[1-9][0-9]{0,11}")) {
            throw new ApiException(400, "BAD_MANGA_ID", "哔哩作品 ID 格式不正确");
        }
    }

    private static ChapterRef parseChapter(String chapterId) {
        if (chapterId == null || !chapterId.matches("mc[1-9][0-9]{0,11}-[1-9][0-9]{0,11}")) {
            throw new ApiException(400, "BAD_CHAPTER_ID", "哔哩章节 ID 格式不正确");
        }
        int separator = chapterId.indexOf('-');
        return new ChapterRef(chapterId.substring(2, separator), chapterId.substring(separator + 1));
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static ApiException invalidImage() {
        return new ApiException(502, "BILIBILI_IMAGE_INVALID", "页图不完整、格式不支持或尺寸与章节索引不符");
    }

    private static ApiException cacheError() {
        return new ApiException(503, "BILIBILI_CACHE_UNAVAILABLE", "哔哩缓存目录不可用，请检查磁盘空间与权限");
    }

    private static ApiException invalidCache() {
        return new ApiException(502, "BILIBILI_CACHE_INVALID", "哔哩目录缓存损坏，请检查缓存目录");
    }
}

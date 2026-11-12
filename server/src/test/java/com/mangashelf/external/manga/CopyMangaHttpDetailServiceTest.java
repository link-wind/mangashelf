package com.mangashelf.external.manga;

import com.mangashelf.config.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CopyMangaHttpDetailServiceTest {

    private static final String MANGA = "sample";
    private static final String FIRST = "00000000-0000-0000-0000-000000000001";
    private static final String SECOND = "00000000-0000-0000-0000-000000000002";
    private static final String EXTRA = "00000000-0000-0000-0000-000000000003";
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> unexpectedRequest = new AtomicReference<>();
    private final MutableClock clock = new MutableClock();
    private final Map<String, String> pages = new ConcurrentHashMap<>();
    private HttpServer server;
    private CopyMangaHttpDetailService service;
    private volatile int status;
    private volatile String detailBody;

    @BeforeEach
    void setUp() throws Exception {
        status = 200;
        configure(defaultGroups(2), Map.of(
                "default:0", page(2, List.of(chapter(FIRST), chapter(SECOND))),
                "extra:0", page(1, List.of(chapter(EXTRA)))));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            var headers = exchange.getRequestHeaders();
            if (headers.containsKey("Cookie") || headers.containsKey("Authorization")) {
                unexpectedRequest.set("credential received");
            }
            if (!"3".equals(headers.getFirst("platform"))
                    || !"COPY/3.0.9".equals(headers.getFirst("User-Agent"))) {
                unexpectedRequest.set("protocol headers missing");
            }
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery() == null
                    ? "" : exchange.getRequestURI().getRawQuery();
            String body;
            if (path.equals("/api/v3/comic2/" + MANGA)) {
                if (!query.contains("platform=3") || !query.contains("request_id=")) {
                    unexpectedRequest.set("detail query missing");
                }
                body = detailBody;
            } else if (path.matches("/api/v3/comic/" + MANGA + "/group/[a-zA-Z0-9_-]+/chapters")) {
                String[] parts = path.split("/");
                String group = parts[6];
                String offset = queryParam(query, "offset");
                if (!query.contains("platform=3") || !"100".equals(queryParam(query, "limit"))
                        || offset == null) {
                    unexpectedRequest.set("chapter query missing");
                }
                body = pages.get(group + ":" + offset);
                if (body == null) body = "{\"code\":500}";
            } else {
                body = "";
                status = 404;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        service = new CopyMangaHttpDetailService(mapper,
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3"), clock);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void loadsAllGroupsAndSharesConcurrentCacheWithoutBrowserOrCredentials() throws Exception {
        MangaBrowserResolveService browser = mock(MangaBrowserResolveService.class);
        CopyMangaHttpReaderService reader = mock(CopyMangaHttpReaderService.class);
        CopyMangaBrowserSource source = new CopyMangaBrowserSource(browser, service, reader, mapper,
                "test-cookie=private", "/not/read/storage-state.json");
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = executor.invokeAll(List.of(
                    () -> source.detail(MANGA, "zh"), () -> source.detail(MANGA, "zh"),
                    () -> source.detail(MANGA, "zh"), () -> source.detail(MANGA, "zh")));
            MangaSource.Detail first = (MangaSource.Detail) futures.getFirst().get();
            for (var future : futures) assertSame(first, future.get());
            assertSame(first, service.detail(MANGA));
        }
        MangaSource.Detail detail = service.detail(MANGA);
        assertEquals("测试漫画", detail.item().title());
        assertEquals("测试作者 / 第二作者", detail.item().altTitle());
        assertEquals("https://cdn.example/cover.jpg", detail.item().coverUrl());
        assertEquals("测试简介", detail.description());
        assertEquals("連載中", detail.status());
        assertEquals(List.of("冒险", "测试"), detail.tags());
        assertEquals(List.of(MANGA + "~" + FIRST, MANGA + "~" + SECOND, MANGA + "~" + EXTRA),
                detail.chapters().stream().map(MangaSource.ChapterItem::id).toList());
        assertEquals(3, requests.get());
        assertNull(unexpectedRequest.get());
        verifyNoInteractions(browser);
        assertThrows(UnsupportedOperationException.class, () -> detail.chapters().clear());
    }

    @Test
    void loadsPaginatedChaptersInOrder() throws Exception {
        List<Map<String, Object>> firstPage = new ArrayList<>();
        List<Map<String, Object>> secondPage = new ArrayList<>();
        List<Map<String, Object>> thirdPage = new ArrayList<>();
        for (int index = 1; index <= 250; index++) {
            (index <= 100 ? firstPage : index <= 200 ? secondPage : thirdPage)
                    .add(chapter(uuid(index)));
        }
        configure(Map.of("default", group("default", 250)), Map.of(
                "default:0", page(250, firstPage),
                "default:100", page(250, secondPage),
                "default:200", page(250, thirdPage)));

        MangaSource.Detail detail = service.detail(MANGA);

        assertEquals(250, detail.chapters().size());
        assertEquals(MANGA + "~" + uuid(1), detail.chapters().getFirst().id());
        assertEquals(MANGA + "~" + uuid(250), detail.chapters().getLast().id());
        assertEquals(4, requests.get());
        assertNull(unexpectedRequest.get());
    }

    @Test
    void rejectsPartialDuplicateEmptyAndWrongMangaDirectoriesWithoutCachingFailure() throws Exception {
        for (InvalidCase invalid : List.of(
                new InvalidCase(detail("other", defaultGroups(1)), Map.of()),
                new InvalidCase(detail(MANGA, Map.of("default", group("default", -1))), Map.of()),
                new InvalidCase(detail(MANGA, Map.of("default", Map.of("path_word", "default",
                        "count", "2", "name", "默认"))), Map.of()),
                new InvalidCase(detail(MANGA, Map.of("default", group("default", 2))),
                        Map.of("default:0", page(2, List.of(chapter(FIRST))),
                                "default:1", page(2, List.of()))),
                new InvalidCase(detail(MANGA, Map.of("default", group("default", 2))),
                        Map.of("default:0", page(2, List.of(chapter(FIRST), chapter(FIRST))))),
                new InvalidCase(detail(MANGA, Map.of("default", group("default", 2))),
                        Map.of("default:0", page(3, List.of(chapter(FIRST), chapter(SECOND))))),
                new InvalidCase(detail(MANGA, Map.of("default", group("default", 1))),
                        Map.of("default:0", page(1, List.of(chapter("bad-id"))))))) {
            detailBody = invalid.body();
            pages.clear();
            pages.putAll(invalid.pages());
            ApiException error = assertThrows(ApiException.class, () -> service.detail(MANGA));
            assertEquals(502, error.getStatus());
            assertEquals("COPYMANGA_DIRECTORY_INVALID", error.getCode());
        }

        configure(Map.of("default", group("default", 0)), Map.of());
        int beforeEmpty = requests.get();
        ApiException empty = assertThrows(ApiException.class, () -> service.detail(MANGA));
        assertEquals(502, empty.getStatus());
        assertEquals("COPYMANGA_DIRECTORY_UNAVAILABLE", empty.getCode());
        assertEquals(beforeEmpty + 2, requests.get());

        configure(Map.of("default", group("default", 1)),
                Map.of("default:0", page(1, List.of(chapter(FIRST)))));
        assertEquals(1, service.detail(MANGA).chapters().size());
    }

    @Test
    void expiredCacheDoesNotHideUpstreamErrorsAndCanRefresh() {
        MangaSource.Detail first = service.detail(MANGA);
        clock.advance(Duration.ofMinutes(31));
        status = 500;
        ApiException error = assertThrows(ApiException.class, () -> service.detail(MANGA));
        assertEquals(502, error.getStatus());
        assertEquals(4, requests.get());
        status = 200;
        MangaSource.Detail refreshed = service.detail(MANGA);
        assertNotSame(first, refreshed);
        assertEquals(first, refreshed);
        assertEquals(7, requests.get());
    }

    @Test
    void invalidIdsHttpErrorsAndMalformedBodiesFailExplicitly() {
        assertThrows(IllegalArgumentException.class,
                () -> new CopyMangaHttpDetailService(mapper, "http://user:pw@localhost:7892"));
        assertThrows(IllegalArgumentException.class,
                () -> new CopyMangaHttpDetailService(mapper, "https://localhost:7892"));
        for (String id : List.of("../sample", "https://other.invalid", "sample?token=x", "")) {
            assertEquals(400, assertThrows(ApiException.class, () -> service.detail(id)).getStatus());
        }
        assertEquals(0, requests.get());

        status = 404;
        assertEquals(404, assertThrows(ApiException.class, () -> service.detail(MANGA)).getStatus());
        status = 302;
        assertEquals(502, assertThrows(ApiException.class, () -> service.detail(MANGA)).getStatus());
        status = 200;
        for (String body : List.of("", "[]", "{\"code\":500}", "{\"code\":200,\"results\":{}}",
                "{\"code\":200,\"results\":{\"comic\":{},\"groups\":{}}}")) {
            detailBody = body;
            assertEquals(502, assertThrows(ApiException.class, () -> service.detail(MANGA)).getStatus());
        }
        detailBody = "{\"code\":210}";
        assertEquals(404, assertThrows(ApiException.class, () -> service.detail(MANGA)).getStatus());
    }

    private void configure(Map<String, Object> groups, Map<String, String> pageBodies) throws Exception {
        detailBody = detail(MANGA, groups);
        pages.clear();
        pages.putAll(pageBodies);
    }

    private Map<String, Object> defaultGroups(int count) {
        var groups = new LinkedHashMap<String, Object>();
        groups.put("default", group("default", count));
        groups.put("extra", group("extra", 1));
        return groups;
    }

    private String detail(String pathWord, Map<String, Object> groups) throws Exception {
        var comic = new LinkedHashMap<String, Object>();
        comic.put("uuid", "43b94362-23ce-11f0-bed3-fa163e02432f");
        comic.put("name", "测试漫画");
        comic.put("path_word", pathWord);
        comic.put("cover", "https://cdn.example/cover.jpg.328x422.jpg");
        comic.put("author", List.of(Map.of("name", "测试作者"), Map.of("name", "第二作者")));
        comic.put("theme", List.of(Map.of("name", "冒险"), Map.of("name", "测试")));
        comic.put("status", Map.of("display", "連載中"));
        comic.put("brief", "测试简介");
        return mapper.writeValueAsString(Map.of("code", 200,
                "results", Map.of("comic", comic, "groups", groups)));
    }

    private Map<String, Object> group(String pathWord, int count) {
        return Map.of("path_word", pathWord, "count", count, "name", "默认");
    }

    private String page(int total, List<Map<String, Object>> rows) throws Exception {
        return mapper.writeValueAsString(Map.of("code", 200,
                "results", Map.of("total", total, "list", rows)));
    }

    private Map<String, Object> chapter(String uuid) {
        String tail = uuid.substring(uuid.length() - 1);
        return Map.of("uuid", uuid, "name", "第" + tail + "话");
    }

    private static String uuid(int index) {
        return String.format("00000000-0000-0000-0000-%012d", index);
    }

    private record InvalidCase(String body, Map<String, String> pages) {}

    private static String queryParam(String query, String name) {
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equals(name)) return parts[1];
        }
        return null;
    }

    private static class MutableClock extends Clock {
        private Instant current = Instant.parse("2026-11-12T00:00:00Z");

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}

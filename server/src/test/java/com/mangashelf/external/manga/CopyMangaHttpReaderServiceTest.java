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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CopyMangaHttpReaderServiceTest {

    private static final String MANGA = "sample";
    private static final String CHAPTER = "00000000-0000-0000-0000-000000000001";
    private static final String CHAPTER_ID = MANGA + "~" + CHAPTER;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> unexpectedRequest = new AtomicReference<>();
    private final MutableClock clock = new MutableClock();
    private HttpServer server;
    private CopyMangaHttpReaderService service;
    private volatile int status;
    private volatile String body;

    @BeforeEach
    void setUp() throws Exception {
        status = 200;
        body = chapter(10, 10, false);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            var headers = exchange.getRequestHeaders();
            if (headers.containsKey("Cookie") || headers.containsKey("Authorization")) {
                unexpectedRequest.set("credential received");
            }
            if (!"3".equals(headers.getFirst("platform"))
                    || !"COPY/3.0.9".equals(headers.getFirst("User-Agent"))
                    || !"3.0.9".equals(headers.getFirst("version"))
                    || !"83".equals(headers.getFirst("versionCode"))) {
                unexpectedRequest.set("protocol headers missing");
            }
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            if (!path.equals("/api/v3/comic/" + MANGA + "/chapter/" + CHAPTER)
                    || !"platform=3".equals(query)) {
                unexpectedRequest.set("unexpected path " + path + "?" + query);
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        service = new CopyMangaHttpReaderService(mapper,
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3"), clock);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void loadsEveryDeclaredPageInOrderAndCachesWithoutCredentials() {
        MangaSource.Pages first = service.pages(CHAPTER_ID);

        assertEquals(10, first.pages().size());
        assertEquals("https://sb.mangafunb.fun/b/sample/1/page-1.jpg.h1500x.webp",
                first.pages().getFirst());
        assertEquals("https://sb.mangafunb.fun/b/sample/1/page-10.jpg.h1500x.webp",
                first.pages().getLast());
        assertFalse(first.external());
        assertTrue(first.dimensions().isEmpty());

        assertSame(first, service.pages(CHAPTER_ID));
        assertEquals(1, requests.get());
        assertNull(unexpectedRequest.get());

        clock.advance(Duration.ofMinutes(11));
        assertEquals(first, service.pages(CHAPTER_ID));
        assertEquals(2, requests.get());
    }

    @Test
    void rejectsPartialOrMalformedChaptersInsteadOfReturningOnePage() {
        for (int contents : List.of(1, 9)) {
            body = chapter(10, contents, false);
            ApiException error = assertThrows(ApiException.class, () -> service.pages(CHAPTER_ID));
            assertEquals(502, error.getStatus());
            assertEquals("COPYMANGA_READER_INVALID", error.getCode());
        }
        body = chapter(10, 10, false).replace("https://sb.mangafunb.fun",
                "http://sb.mangafunb.fun");
        ApiException insecure = assertThrows(ApiException.class, () -> service.pages(CHAPTER_ID));
        assertEquals("COPYMANGA_READER_INVALID", insecure.getCode());
    }

    @Test
    void mapsUpstreamRateLimitAndLoginRequiredExplicitly() {
        body = "{\"code\":210,\"message\":\"请更新客户端\"}";
        ApiException limited = assertThrows(ApiException.class, () -> service.pages(CHAPTER_ID));
        assertEquals(502, limited.getStatus());
        assertEquals("COPYMANGA_READER_RATE_LIMITED", limited.getCode());

        body = chapter(10, 0, true);
        ApiException login = assertThrows(ApiException.class, () -> service.pages(CHAPTER_ID));
        assertEquals(403, login.getStatus());
        assertEquals("COPYMANGA_LOGIN_REQUIRED", login.getCode());
    }

    @Test
    void rejectsInvalidIdsAndHttpFailures() {
        for (String id : List.of("", "sample", "sample~bad-id", "../sample~" + CHAPTER,
                MANGA + "~" + CHAPTER + "x")) {
            assertEquals(400, assertThrows(ApiException.class, () -> service.pages(id)).getStatus());
        }
        assertEquals(0, requests.get());

        status = 500;
        ApiException unavailable = assertThrows(ApiException.class, () -> service.pages(CHAPTER_ID));
        assertEquals(502, unavailable.getStatus());
        assertEquals("COPYMANGA_READER_UNAVAILABLE", unavailable.getCode());
    }

    private String chapter(int declaredSize, int contents, boolean locked) {
        List<Map<String, Object>> urls = new ArrayList<>();
        for (int index = 1; index <= contents; index++) {
            urls.add(Map.of("url", "https://sb.mangafunb.fun/b/sample/1/page-" + index
                    + ".jpg.h1500x.webp"));
        }
        var chapter = new LinkedHashMap<String, Object>();
        chapter.put("comic_path_word", MANGA);
        chapter.put("uuid", CHAPTER);
        chapter.put("name", "第10话");
        chapter.put("size", declaredSize);
        chapter.put("contents", urls);
        chapter.put("is_long", true);
        return mapper.writeValueAsString(Map.of("code", 200,
                "results", Map.of("is_lock", locked, "chapter", chapter)));
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

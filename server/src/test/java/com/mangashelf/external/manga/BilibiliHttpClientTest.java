package com.mangashelf.external.manga;

import com.mangashelf.config.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BilibiliHttpClientTest {

    @Test
    void code99RefreshesAnonymousFingerprintOnlyOnce() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var fingerprints = new AtomicInteger();
        var indexes = new AtomicInteger();
        server.createContext("/finger", exchange -> {
            fingerprints.incrementAndGet();
            byte[] body = "{\"code\":0,\"data\":{\"b_3\":\"anonymous-one\",\"b_4\":\"anonymous/two==\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/twirp/comic.v1.Comic/GetImageIndex", exchange -> {
            int attempt = indexes.incrementAndGet();
            boolean valid = exchange.getRequestURI().getQuery().contains("device=android")
                    && exchange.getRequestHeaders().getFirst("Cookie") != null
                    && new ObjectMapper().readTree(exchange.getRequestBody()).path("epId").asInt() == 2;
            byte[] body = (attempt == 1 ? "{\"code\":99}" : valid
                    ? "{\"code\":0,\"data\":{\"images\":[]}}" : "{\"code\":1}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            URI root = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var client = new BilibiliHttpClient(new ObjectMapper(), HttpClient.newHttpClient(),
                    root, root.resolve("/finger"));
            assertTrue(client.index("2").path("images").isArray());
            assertEquals(2, fingerprints.get());
            assertEquals(2, indexes.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void otherBusinessErrorsAreNotRetried() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var indexes = new AtomicInteger();
        server.createContext("/finger", exchange -> {
            byte[] body = "{\"code\":0,\"data\":{\"b_3\":\"anonymous-one\",\"b_4\":\"anonymous-two\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/twirp/comic.v1.Comic/GetImageIndex", exchange -> {
            indexes.incrementAndGet();
            byte[] body = "{\"code\":1}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            URI root = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var client = new BilibiliHttpClient(new ObjectMapper(), HttpClient.newHttpClient(),
                    root, root.resolve("/finger"));
            assertEquals("BILIBILI_API_UNAVAILABLE",
                    assertThrows(ApiException.class, () -> client.index("2")).getCode());
            assertEquals(1, indexes.get());
        } finally {
            server.stop(0);
        }
    }
}

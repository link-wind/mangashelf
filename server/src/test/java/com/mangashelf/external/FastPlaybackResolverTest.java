package com.mangashelf.external;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** API 层直链识别、MacCMS player_aaaa 同步秒解与签名缓存新鲜窗口单测。 */
class FastPlaybackResolverTest {

    @Test
    void detectsDirectVideoUrlsAccurately() {
        assertEquals("hls", FastPlaybackResolver.directVideoKind("https://v.lzcdn.com/2026/index.m3u8"));
        assertEquals("mp4", FastPlaybackResolver.directVideoKind("https://cdn.example.com/v/ep1.mp4?Expires=1999999999"));
        assertNull(FastPlaybackResolver.directVideoKind("https://www.7sefun.top/vodplay/26976-1-1.html"));
        assertNull(FastPlaybackResolver.directVideoKind("javascript:alert(1)"));
    }

    @Test
    void decodesMacCmsPlayerAaaaEncryptModes() {
        String mp4 = "https://mao6.example.top/d/file/第01集.mp4";
        String enc2 = Base64.getEncoder().encodeToString(
                URLEncoder.encode(mp4, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8));
        String html = "<script>var player_aaaa={\"encrypt\":2,\"url\":\"" + enc2 + "\"}</script>";
        assertEquals(mp4, FastPlaybackResolver.extractMacCmsPlayerUrl(html));
    }

    @Test
    void resolvesMacCmsPageAndSingleRedirectOverLocalHttp() throws Exception {
        String finalCdn = "https://cdn.example.com/FAMILYCLOUD/ep1.mp4?Expires=1999999999";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/vodplay/1-1-1.html", ex -> {
            String target = "http://127.0.0.1:" + port + "/d/ep1.mp4";
            String enc = Base64.getEncoder().encodeToString(
                    URLEncoder.encode(target, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8));
            byte[] body = ("<script>var player_aaaa={\"encrypt\":2,\"url\":\"" + enc + "\"}</script>")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/d/ep1.mp4", ex -> {
            ex.getResponseHeaders().add("Location", finalCdn);
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();
        try {
            var resolved = FastPlaybackResolver.tryResolveMacCms(
                    "http://127.0.0.1:" + port + "/vodplay/1-1-1.html", null, null);
            assertTrue(resolved.isPresent());
            assertEquals("mp4", resolved.get().playKind());
            assertEquals(finalCdn, resolved.get().playUrl());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void appliesTieredFreshnessWindowForSignedVsUnsignedUrls() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        String signed = "https://cdn.example.com/a.mp4?Expires=1700007200&Signature=x";
        String unsigned = "https://cdn.example.com/a.m3u8?t=short";

        // 30 分钟前解析的带 Expires 签名直链（距过期还有 2 小时）仍应新鲜复用
        assertTrue(ExternalService.isResolvedFresh(signed, now.minusSeconds(1800), now));
        // 超过 60 分钟上限则强制刷新
        assertFalse(ExternalService.isResolvedFresh(signed, now.minusSeconds(3700), now));
        // 无 Expires 参数的短效直链维持保守 4 分钟窗口
        assertTrue(ExternalService.isResolvedFresh(unsigned, now.minusSeconds(180), now));
        assertFalse(ExternalService.isResolvedFresh(unsigned, now.minusSeconds(300), now));
    }
}

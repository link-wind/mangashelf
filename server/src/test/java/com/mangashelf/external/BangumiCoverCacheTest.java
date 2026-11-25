package com.mangashelf.external;

import com.mangashelf.config.MangaShelfProperties;
import com.mangashelf.identity.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 验证 /api/v1/bangumi-covers/{subjectId} 返回强 ETag 与 304 Not Modified。 */
class BangumiCoverCacheTest {

    @Test
    void returnsEtagOnFirstFetchAnd304WhenIfNoneMatchMatches() throws Exception {
        byte[] payload = "fake-jpeg-bytes-for-etag-check".getBytes(StandardCharsets.UTF_8);
        MangaShelfProperties props = new MangaShelfProperties(
                UUID.randomUUID(), "test", "secret", null, null, null, null,
                new MangaShelfProperties.Bangumi(true, ""), null);
        BangumiService stub = new BangumiService(props, null, null, null, null, null) {
            @Override
            public Cover fetchCover(long subjectId, String size) {
                return subjectId == 42L ? new Cover(payload, "image/jpeg") : null;
            }
        };

        ExternalController controller = new ExternalController(null, null, stub);
        CurrentUser user = new CurrentUser(UUID.randomUUID(), "admin", "ADMIN");

        ResponseEntity<byte[]> first = controller.bangumiCover(user, 42L, "medium", null);
        assertEquals(200, first.getStatusCode().value());
        String etag = first.getHeaders().getFirst("ETag");
        assertNotNull(etag);
        assertEquals("public, max-age=604800", first.getHeaders().getFirst("Cache-Control"));
        assertEquals(payload.length, first.getBody().length);

        ResponseEntity<byte[]> second = controller.bangumiCover(user, 42L, "medium", etag);
        assertEquals(304, second.getStatusCode().value());
        assertEquals(etag, second.getHeaders().getFirst("ETag"));
        assertNull(second.getBody());

        ResponseEntity<byte[]> weak = controller.bangumiCover(user, 42L, "medium", "W/" + etag);
        assertEquals(304, weak.getStatusCode().value());
        assertNull(weak.getBody());

        ResponseEntity<byte[]> mismatch = controller.bangumiCover(user, 42L, "medium", "\"different\"");
        assertEquals(200, mismatch.getStatusCode().value());
        assertEquals(payload.length, mismatch.getBody().length);
    }
}

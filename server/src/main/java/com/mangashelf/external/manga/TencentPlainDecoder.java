package com.mangashelf.external.manga;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 腾讯动漫章节解码钩子（tencent-plain，M23）。
 * GET style=plain → 两行文本 → nonce 求值 + 区间删除 + base64 → JSONPath 取页图。
 * 付费/不可读章节显式报错，不破解。
 */
@Component
public class TencentPlainDecoder implements MangaPageDecoder {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12)).build();

    @Override
    public String name() {
        return "tencent-plain";
    }

    @Override
    public List<String> decode(JsonNode pagesRule, Map<String, String> vars) throws Exception {
        String url = pagesRule.path("url").asText();
        for (Map.Entry<String, String> e : vars.entrySet()) {
            url = url.replace("{" + e.getKey() + "}", e.getValue());
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", pagesRule.path("headers").path("User-Agent").asText(
                        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
                                + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"))
                .header("Referer", "https://m.ac.qq.com/")
                .GET().build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("腾讯动漫章节 HTTP " + resp.statusCode());
        }
        String[] lines = resp.body().strip().split("\n");
        if (lines.length < 2) {
            throw new IllegalStateException("章节响应缺少 nonce 行");
        }
        String json = TencentNonceDecoder.decode(lines[0], TencentNonceDecoder.evalNonceLine(lines[1]));
        List<String> urls;
        try {
            urls = JsonPath.read(json, pagesRule.path("list").asText("$.picture[*].url"));
        } catch (Exception e) {
            urls = List.of();
        }
        if (urls.isEmpty()) {
            boolean canRead;
            try {
                canRead = JsonPath.<Boolean>read(json,
                        pagesRule.path("canReadPath").asText("$.chapter.canRead"));
            } catch (Exception e) {
                canRead = true;
            }
            throw new IllegalStateException(canRead ? "该章节暂无页图" : "该章节为付费章节，站内不可读");
        }
        return new ArrayList<>(urls);
    }
}

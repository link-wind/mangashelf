package com.mangashelf.external;

import com.jayway.jsonpath.JsonPath;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 声明式规则引擎（方案 A，Kazumi 兼容 CSS 选择器子集）。
 * HTML 模式：jsoup CSS 选择器解析搜索列表/标题/链接。
 * API 模式：JSONPath 解析 JSON 响应。
 * 不执行 JS，不加载 WebView——只解析 HTML/JSON 结构。
 */
@Service
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record SearchResultItem(String name, String url) {
    }

    public record EpisodeItem(String title, String url) {
    }

    /** 搜索：根据规则模式（html/api）请求外部站点并解析结果。 */
    public List<SearchResultItem> search(String rules, String baseUrl, String keyword) {
        try {
            JsonNode rulesJson = objectMapper.readTree(rules);
            String mode = rulesJson.path("searchMode").asText("html");
            return "api".equals(mode) ? searchApi(rulesJson, baseUrl, keyword)
                                       : searchHtml(rulesJson, baseUrl, keyword);
        } catch (Exception e) {
            log.warn("rule search failed: {}", e.getMessage());
            throw new RuleEngineException("规则搜索失败：" + e.getMessage(), e);
        }
    }

    /** 解析分集列表：根据规则章节选择器提取 {title, url}。 */
    public List<EpisodeItem> parseEpisodes(String rules, String baseUrl, String episodePageUrl) throws Exception {
        try {
            JsonNode rulesJson = objectMapper.readTree(rules);
            String listSelector = rulesJson.path("episodeListSelector").asText("");
            if (listSelector.isEmpty()) {
                throw new RuleEngineException("规则缺少 episodeListSelector");
            }
            Document doc = fetchHtml(episodePageUrl, rulesJson);
            String absBase = URI.create(episodePageUrl).toString();
            List<EpisodeItem> episodes = new ArrayList<>();
            for (Element el : doc.select(listSelector)) {
                String title = selectText(el, rulesJson, "episodeNameSelector");
                String href = selectHref(el, rulesJson, "episodeUrlSelector");
                if (title != null && href != null) {
                    String abs = resolveUrl(baseUrl, absBase, href);
                    episodes.add(new EpisodeItem(title, abs));
                }
            }
            return episodes;
        } catch (RuleEngineException e) {
            throw e;
        } catch (Exception e) {
            log.warn("rule episode parse failed: {}", e.getMessage());
            throw new RuleEngineException("分集解析失败：" + e.getMessage(), e);
        }
    }

    // ── HTML 模式 ──

    private List<SearchResultItem> searchHtml(JsonNode rules, String baseUrl, String keyword) throws Exception {
        String searchUrl = rules.path("searchUrl").asText().replace("{keyword}", urlEncode(keyword));
        String listSelector = rules.path("searchList").asText("");
        if (listSelector.isEmpty()) {
            throw new RuleEngineException("规则缺少 searchList");
        }
        Document doc = fetchHtml(searchUrl, rules);
        List<SearchResultItem> results = new ArrayList<>();
        for (Element el : doc.select(listSelector)) {
            String name = selectText(el, rules, "searchName");
            String href = selectHref(el, rules, "searchUrlSelector");
            if (name != null && href != null) {
                results.add(new SearchResultItem(name, resolveUrl(baseUrl, searchUrl, href)));
            }
        }
        return results;
    }

    private Document fetchHtml(String url, JsonNode rules) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("User-Agent", rulesJsonUserAgent(rules))
                .GET();
        String referer = rules.path("referer").asText("");
        if (!referer.isEmpty()) builder.header("Referer", referer);
        HttpRequest req = builder.build();
        IOException lastIo = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    throw new RuleEngineException("外部站点返回 HTTP " + resp.statusCode());
                }
                return Jsoup.parse(resp.body(), url);
            } catch (IOException e) {
                lastIo = e;
                if (attempt < 2) {
                    log.debug("rule fetchHtml retry {}/2 for {}: {}", attempt, url, e.getMessage());
                    Thread.sleep(400L);
                }
            }
        }
        throw lastIo;
    }

    private String selectText(Element parent, JsonNode rules, String selectorField) {
        String sel = rules.path(selectorField).asText("");
        if (sel.isEmpty()) return parent.text();
        Element el = parent.selectFirst(sel);
        return el != null ? el.text().trim() : null;
    }

    private String selectHref(Element parent, JsonNode rules, String selectorField) {
        String sel = rules.path(selectorField).asText("");
        Element el = sel.isEmpty() ? parent : parent.selectFirst(sel);
        if (el == null) return null;
        String href = el.hasAttr("href") ? el.attr("abs:href") : el.attr("href");
        return href != null && !href.isEmpty() ? href : null;
    }

    // ── API 模式 ──

    private List<SearchResultItem> searchApi(JsonNode rules, String baseUrl, String keyword) throws Exception {
        JsonNode apiConfig = rules.path("apiRequest");
        if (apiConfig.isMissingNode()) {
            throw new RuleEngineException("API 模式缺少 apiRequest 配置");
        }
        String apiUrl = apiConfig.path("url").asText().replace("{keyword}", urlEncode(keyword));
        String method = apiConfig.path("method").asText("GET");
        var builder = HttpRequest.newBuilder(URI.create(apiUrl))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json");
        String ua = rulesJsonUserAgent(rules);
        if (!ua.isEmpty()) builder.header("User-Agent", ua);
        HttpResponse<String> resp = httpClient.send(
                "POST".equalsIgnoreCase(method)
                        ? builder.POST(HttpRequest.BodyPublishers.ofString(
                                apiConfig.path("body").asText("")))
                              .build()
                        : builder.GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuleEngineException("API 站点返回 HTTP " + resp.statusCode());
        }
        String listPath = rules.path("apiListPath").asText("$.list[*]");
        String namePath = rules.path("apiNamePath").asText("$.name");
        String urlPath = rules.path("apiUrlPath").asText("$.url");
        List<SearchResultItem> results = new ArrayList<>();
        List<Map<String, Object>> items = JsonPath.read(resp.body(), listPath);
        for (Map<String, Object> item : items) {
            // 显式 Object 接收：String.valueOf(JsonPath.read(...)) 会被 javac 推断为 valueOf(char[])
            // （重载更具体），运行时对 String 做 checkcast [C 抛 ClassCastException
            Object nameValue = JsonPath.read(item, namePath);
            Object urlValue = JsonPath.read(item, urlPath);
            String name = String.valueOf(nameValue);
            String url = String.valueOf(urlValue);
            results.add(new SearchResultItem(name, resolveUrl(baseUrl, apiUrl, url)));
        }
        return results;
    }

    // ── 辅助 ──

    private String rulesJsonUserAgent(JsonNode rules) {
        return rules.path("userAgent").asText("");
    }

    private String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String resolveUrl(String baseUrl, String currentPageUrl, String href) {
        try {
            URI uri = URI.create(href);
            if (uri.isAbsolute()) return href;
            // 先尝试相对当前页
            URI page = URI.create(currentPageUrl);
            if (page.isAbsolute()) {
                URI resolved = page.resolve(href);
                if (resolved.isAbsolute()) return resolved.toString();
            }
            // 回退到 baseUrl
            return URI.create(baseUrl).resolve(href).toString();
        } catch (Exception e) {
            return href;
        }
    }

    public static class RuleEngineException extends RuntimeException {
        public RuleEngineException(String message) { super(message); }
        public RuleEngineException(String message, Throwable cause) { super(message, cause); }
    }
}

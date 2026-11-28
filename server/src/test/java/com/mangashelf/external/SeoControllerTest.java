package com.mangashelf.external;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeoControllerTest {

    @Test
    void robotsUsesForwardedHttpsHostAndDisallowsPrivateRoutes() {
        SeoController controller = new SeoController(mock(JdbcTemplate.class));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/robots.txt");
        req.addHeader("X-Forwarded-Proto", "https, http");
        req.addHeader("X-Forwarded-Host", "gemineko.top");

        var resp = controller.robots(req);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody())
                .contains("Sitemap: https://gemineko.top/sitemap.xml")
                .contains("Disallow: /api/")
                .contains("Disallow: /me")
                .contains("Disallow: /settings");
    }

    @Test
    void resolveBaseUrlRejectsUnsafeForwardedHost() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/sitemap.xml");
        req.setScheme("https");
        req.setServerName("gemineko.top");
        req.setServerPort(443);
        req.addHeader("X-Forwarded-Host", "evil.com/path?x=1");

        assertThat(SeoController.resolveBaseUrl(req)).isEqualTo("https://gemineko.top");
    }

    @Test
    void sitemapBuildsValidXmlWithCoreRoutes() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
        SeoController controller = new SeoController(jdbc);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/sitemap.xml");
        req.addHeader("X-Forwarded-Proto", "https");
        req.addHeader("Host", "gemineko.top");

        String xml = controller.sitemap(req).getBody();
        assertThat(xml)
                .startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .contains("<loc>https://gemineko.top/</loc>")
                .contains("<loc>https://gemineko.top/schedule</loc>")
                .contains("<loc>https://gemineko.top/index?kind=manga</loc>")
                .endsWith("</urlset>\n");
    }

    @Test
    void crawlerRenderMarksPrivateRoutesNoIndexAndSetsVaryUserAgent() {
        SeoController controller = new SeoController(mock(JdbcTemplate.class));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/seo/render");
        req.addHeader("X-Forwarded-Proto", "https");
        req.addHeader("Host", "gemineko.top");

        var resp = controller.renderForCrawler(req, "/me", null, null);
        assertThat(resp.getHeaders().getFirst("Vary")).isEqualTo("User-Agent");
        assertThat(resp.getBody())
                .contains("<meta name=\"robots\" content=\"noindex, nofollow\" />")
                .contains("<link rel=\"canonical\" href=\"https://gemineko.top/me\" />");
    }

    @Test
    void crawlerRenderOutputsRecommendedSeoTitlesForCoreRoutes() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        SeoController controller = new SeoController(jdbc);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/seo/render");
        req.addHeader("X-Forwarded-Proto", "https");
        req.addHeader("Host", "gemineko.top");

        assertThat(controller.renderForCrawler(req, "/", null, null).getBody())
                .contains("<title>GemiNeko（杰米猫）· 番剧与漫画空间 - 每周新番时刻表与高分索引</title>");
        assertThat(controller.renderForCrawler(req, "/schedule", null, null).getBody())
                .contains("<title>每周新番时刻表与放送日历 · GemiNeko</title>");
        assertThat(controller.renderForCrawler(req, "/index", "manga", null).getBody())
                .contains("<title>高分漫画索引与评分排行 · GemiNeko</title>")
                .contains("<link rel=\"canonical\" href=\"https://gemineko.top/index?kind=manga\" />");
    }

    @Test
    void jsonAndHtmlEscapingPreventScriptBreakout() {
        assertThat(SeoController.escapeJson("</script><img src=\"x\">&"))
                .doesNotContain("</script>")
                .contains("\\u003c/script\\u003e");
        assertThat(SeoController.escapeXml("a&b<c>\"'"))
                .isEqualTo("a&amp;b&lt;c&gt;&quot;&apos;");
    }
}

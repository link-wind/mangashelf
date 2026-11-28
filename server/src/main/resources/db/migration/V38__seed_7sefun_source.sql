-- V38：固化七色番 (7sefun) 高清备用规则源（幂等，若已存在则跳过）。
-- 仅解析七色B线（-1-，支持 player_aaaa encrypt=2 纯 HTTP 秒解 MP4 直链），
-- 避开七色A线（-2-，lmm85 反自动化嵌套 iframe）。
INSERT INTO external_sources (id, name, base_url, rules, enabled, cookie)
VALUES (
    '75ef0000-75ef-4000-8000-000000000001',
    '七色番 (7sefun)',
    'https://www.7sefun.top',
    '{
      "searchMode": "html",
      "searchUrl": "https://www.7sefun.top/vodsearch/-------------.html?wd={keyword}",
      "searchList": ".videos .video",
      "searchName": ".video-by",
      "searchUrlSelector": "a.video-wrapper",
      "episodeListSelector": ".vod-play-list-container a[href*=''-1-'']",
      "episodeNameSelector": "",
      "episodeUrlSelector": "",
      "userAgent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36",
      "referer": "https://www.7sefun.top/"
    }'::jsonb,
    true,
    ''
)
ON CONFLICT (name) DO NOTHING;

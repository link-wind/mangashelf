-- M28：接入拷贝漫画（RANK 能力）。
-- 官方 APK（3.1.0）内置当前主力 API 域名 api.copy202602.com；ranks/update/newest/recs
-- 匿名可用（2026-11-10 实测），search 维护中、comic2 详情被"破解版"IP 封禁——
-- 因此仅接入榜单（RANK），条目站外阅读（跳官方 www.copymanga.tv）。
-- 请求需版本自举头：先 GET /api/v3/system/appVersion/last 换最新 version。

INSERT INTO manga_source_rules (id, name, note, rules) VALUES (
    'copymanga', '拷贝漫画',
    '正版繁体中文源；详情接口暂受限，榜单条目站外阅读',
    '{
      "language": "zh",
      "externalUrl": "https://www.copymanga.tv/comic/",
      "bootstrapVersion": {
        "url": "https://api.copy202602.com/api/v3/system/appVersion/last?platform=3",
        "path": "$.results.android.version",
        "header": "version",
        "also": ["appVersion"]
      },
      "headers": {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.36",
        "Accept": "application/json",
        "region": "1",
        "webp": "1"
      },
      "rank": {
        "mode": "api",
        "url": "https://api.copy202602.com/api/v3/ranks",
        "list": "$.results.list[*].comic",
        "fields": {
          "id":       "$.path_word",
          "title":    "$.name",
          "altTitle": "$.author[0].name",
          "cover":    "$.cover"
        }
      }
    }'::jsonb
);

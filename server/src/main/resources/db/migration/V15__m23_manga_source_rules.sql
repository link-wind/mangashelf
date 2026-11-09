-- M23：规则驱动漫画源。源 = 规则数据（与动画侧 external_sources.rules 同模式），
-- 宿主是一个解释器（MangaRuleEngine）；新增中文源不再需要改后端代码。
-- schema 说明见 openspec/changes/implement-manga-rule-sources/design.md。

CREATE TABLE manga_source_rules (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    note        TEXT NOT NULL DEFAULT '',
    rules       JSONB NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 种子：腾讯动漫（全能力中文源，2026-11-09 实测）
INSERT INTO manga_source_rules (id, name, note, rules) VALUES (
    'tencent', '腾讯动漫',
    '正版中文源；付费章节站内不可读',
    '{
      "headers": {
        "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
        "Referer": "https://m.ac.qq.com/"
      },
      "rank": {
        "url": "https://m.ac.qq.com/",
        "mode": "html",
        "list": "div.rank-container a.comic-link",
        "fields": {
          "id":    { "attr": "data-id" },
          "title": { "select": "strong.comic-title" },
          "cover": { "select": "img", "attr": "src" }
        }
      },
      "search": {
        "url": "https://m.ac.qq.com/search/smart?word={query}",
        "mode": "api",
        "list": "$.data[*]",
        "fields": {
          "id":       "$.id",
          "title":    "$.title",
          "altTitle": "$.artist_name"
        }
      },
      "detail": {
        "url": "https://m.ac.qq.com/comic/index/id/{id}",
        "mode": "html",
        "meta": {
          "title":       { "select": "meta[property=og:title]", "attr": "content" },
          "description": { "select": "meta[property=og:description]", "attr": "content" },
          "cover":       { "select": "meta[property=og:image]", "attr": "content" }
        },
        "descriptionMarker": "[简介]",
        "authors": { "select": "ul.author-list", "delimiter": " " },
        "chapters": {
          "list": "a.chapter-link",
          "id":    { "attr": "data-cid", "template": "{mangaId}-{v}" },
          "label": { "attr": "data-seq" },
          "title": { "select": "p.chapter-title" }
        },
        "chapterSort": "numeric-asc",
        "chapterPages": -1
      },
      "pages": {
        "mode": "decode",
        "decode": "tencent-plain",
        "url": "https://m.ac.qq.com/chapter/index/id/{comicId}/cid/{cid}?style=plain&qimei=mangashelf-3a7f2c91d4b8e506",
        "chapterIdTemplate": "{comicId}-{cid}",
        "list": "$.picture[*].url",
        "canReadPath": "$.chapter.canRead"
      }
    }'::jsonb
);

-- M33：嬉皮漫画（声明式浏览器源）。详情章节走公开分页 API，阅读页图全部在
-- HTML 的 img[data-src] 中（无需滚动解密）。
INSERT INTO manga_source_rules (id, name, note, rules) VALUES (
    'hipmh', '嬉皮漫画',
    '声明式浏览器源（搜索/详情/站内阅读）',
    $json$
{
  "language": "zh",
  "browserPlan": {
    "search": {
      "url": "https://m.hipmh.com/search?q={query}",
      "wait_ms": 2500,
      "list": "a[href^='/works/']",
      "fields": {
        "id":    { "attr": "href", "regex": "^/works/([^/?#]+)" },
        "title": { "text": true },
        "cover": { "select": "img", "attrs": ["data-src", "src"] }
      }
    },
    "detail": {
      "url": "https://m.hipmh.com/works/{id}",
      "wait_ms": 2500,
      "single": {
        "title":       { "select": "h1" },
        "cover":       { "select": "img[alt='cover image']", "attr": "src" },
        "description": { "select": "meta[name=description]", "attr": "content" }
      },
      "chaptersApi": {
        "url": "https://hipapi1.s3file.top/v1/manga/chapters?mid={mid}&page={page}&per_page=50&order=asc",
        "idRegex": "^([^-]+)",
        "idVar": "mid",
        "list": "data.items",
        "totalPages": "data.total_pages",
        "fields": { "id": "hid", "title": "title" }
      }
    },
    "pages": {
      "url": "https://reader.hipmh.top/chapter/{chapterId}",
      "wait_ms": 3500,
      "images": {
        "select": "img[data-src*='hip-tx']",
        "attrs": ["data-src"],
        "exclude": ["cover.s3imgs.top"]
      }
    }
  }
}
$json$::jsonb
);

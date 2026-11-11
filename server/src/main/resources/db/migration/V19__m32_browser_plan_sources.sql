-- M32：声明式浏览器源。规则行的 browserPlan 描述 URL 模板与选择器，
-- 由 Playwright 任务（manga.plan）执行后映射到站内统一详情/阅读器。
-- 首个源：包子漫画（web 端 Playwright 可达；搜索/详情/阅读页均为服务端渲染或 JS 可执行）。

INSERT INTO manga_source_rules (id, name, note, rules) VALUES (
    'baozimh', '包子漫画',
    '声明式浏览器源（搜索/详情/站内阅读）',
    $json$
{
      "language": "zh",
      "browserPlan": {
        "search": {
          "url": "https://www.baozimh.com/search?q={query}",
          "wait_ms": 2500,
          "list": "a[href^='/comic/']",
          "fields": {
            "id":    { "attr": "href", "regex": "^/comic/([^/?#]+)" },
            "title": { "text": true },
            "cover": { "select": "img", "attrs": ["data-src", "src"] }
          }
        },
        "detail": {
          "url": "https://www.baozimh.com/comic/{id}",
          "wait_ms": 3000,
          "single": {
            "title":       { "select": "h1", "last": true },
            "cover":       { "select": "img[src*='static-tw.baozimh.com/cover']", "attr": "src" },
            "description": { "select": "meta[name=description]", "attr": "content" }
          },
          "slots": {
            "lastRegex": "chapter_slot=(\\d+)",
            "labelTemplate": "第{slot}话"
          }
        },
        "pages": {
          "url": "https://www.baozimh.com/user/page_direct?comic_id={comicId}&section_slot=0&chapter_slot={slot}",
          "wait_ms": 5000,
          "scroll": 10,
          "images": {
            "regex": "https://[a-z0-9.-]+/scomic/[^\\s\"'<>]+",
            "exclude": ["static-tw.baozimh.com/cover", "static.ttkan.co", "logo"]
          }
        }
      }
    }
$json$::jsonb
);

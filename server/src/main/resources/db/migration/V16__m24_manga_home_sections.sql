-- M24：漫画首页发现流。腾讯动漫的「飙升/畅销/新作/真香」四榜由
-- /rank/index?style=items 返回 HTML 片段（2026-11-10 实测，稳定于首页 A/B 布局）；
-- 编辑推荐取移动端首页 recommend-list。只替换 rank 节点，detail/search/pages 不动。

UPDATE manga_source_rules
SET rules = jsonb_set(rules, '{rank}', '{
  "url": "https://m.ac.qq.com/",
  "mode": "html",
  "fields": {
    "id":     { "attr": "href", "regex": "/id/(\\d+)" },
    "title":  { "select": "strong.comic-title" },
    "cover":  { "select": "img", "attr": "src" },
    "latest": { "select": "small.comic-update" }
  },
  "sections": [
    { "id": "recommend", "name": "编辑推荐", "url": "https://m.ac.qq.com/",
      "list": "ul.recommend-list a.comic-link" },
    { "id": "rise", "name": "飙升榜",
      "url": "https://m.ac.qq.com/rank/index?type=rise&page=1&pageSize=12&style=items",
      "list": "a.comic-link" },
    { "id": "pay", "name": "畅销榜",
      "url": "https://m.ac.qq.com/rank/index?type=pay&page=1&pageSize=12&style=items",
      "list": "a.comic-link" },
    { "id": "new", "name": "新作榜",
      "url": "https://m.ac.qq.com/rank/index?type=new&page=1&pageSize=12&style=items",
      "list": "a.comic-link" },
    { "id": "hot", "name": "真香榜",
      "url": "https://m.ac.qq.com/rank/index?type=hot&page=1&pageSize=12&style=items",
      "list": "a.comic-link" }
  ]
}'::jsonb)
WHERE id = 'tencent';

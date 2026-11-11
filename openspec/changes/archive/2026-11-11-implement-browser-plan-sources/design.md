# 设计：声明式浏览器源

## 规则 schema（节选）

```jsonc
"browserPlan": {
  "search": {
    "url": "https://www.baozimh.com/search?q={query}",
    "list": "a[href^='/comic/']",
    "fields": {
      "id":    { "attr": "href", "regex": "^/comic/([^/?#]+)" },
      "title": { "text": true },
      "cover": { "select": "img", "attrs": ["data-src","src"] }
    }
  },
  "detail": {
    "url": "https://www.baozimh.com/comic/{id}",
    "single": { "title": {"select":"h1","last":true}, "cover": {...}, "description": {...} },
    "slots": { "lastRegex": "chapter_slot=(\\d+)", "labelTemplate": "第{slot}话" }
  },
  "pages": {
    "url": ".../user/page_direct?comic_id={comicId}&section_slot=0&chapter_slot={slot}",
    "images": { "regex": "https://[a-z0-9.-]+/scomic/[^\\s\"'<>]+", "exclude": ["..."] }
  }
}
```

- 字段支持 select/attr/attrs（多属性回退）/regex/text/last。
- 图片支持两种模式：DOM `img`（data-src 优先）或 HTML 正则全量提取；
  多 CDN 域名按路径去重。
- URL 模板变量由 Java 填充（query 编码），Python 不接收用户任意 URL。

## 数据流

```
MangaSourceController
  → BrowserPlanMangaSource（规则驱动，零站点代码）
      → MangaBrowserResolveService.resolvePlan
          → python -m processing.run（task=manga.plan）
              → Chromium 执行站点 JS + 选择器提取
  → 统一 Detail/Pages 模型 → 站内详情页/阅读器
```

## 实测（2026-11-11）

- 包子漫画搜索「海賊王」4 条；详情 1187 话，5s；
- 章节页 50 页（原 150 条含 3 个 CDN 域名，按路径去重），14s；
- 前端详情 1187 话；阅读器 25 组双页、页图正常加载；
- 站点偶发连接重置（包子/漫蛙），失败时显式报错，不影响其他源。

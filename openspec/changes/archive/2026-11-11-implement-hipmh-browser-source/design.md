# 设计：嬉皮漫画 + 分页章节 API

## chaptersApi 规则

```jsonc
"chaptersApi": {
  "url": "https://hipapi1.s3file.top/v1/manga/chapters?mid={mid}&page={page}&per_page=50&order=asc",
  "idRegex": "^([^-]+)",   // 从作品 slug 提取 mid
  "idVar": "mid",
  "list": "data.items",
  "totalPages": "data.total_pages",
  "fields": { "id": "hid", "title": "title" }
}
```

Python 在浏览器上下文用 `page.request.get` 请求（携带站点 Cookie），按
`total_pages` 自动翻页；返回统一 `items`。

## 章节 ID 规范

- 包子：`{mangaId}|{slot}`（slot 数字）；
- 嬉皮：`hid` 原样（无作品前缀）；
- pages() 同时提供 `{chapterId}` 与拆分后的 `{comicId}`/`{slot}`，规则按需引用。

## 实测

- 搜索「海贼王」4 条（含封面）；
- 详情「海風盤旋時」50 话（该作品总数），6s；
- 章节页图 41 页，5.5s；前端 21 组双页加载正常；
- 详情页「切换数据源」出现 拷贝/腾讯/包子。

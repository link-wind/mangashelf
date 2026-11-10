# 设计：漫画首页发现流

## 数据

腾讯动漫移动端首页单页包含四个可提取分区（2026-11-10 实测）：

| 分区 | 选择器 | 条目 |
|---|---|---|
| 编辑推荐 | `ul.recommend-list a.comic-link` | 约 10+ |
| 男生榜 | `[class*="rank-type-男生榜"] a.comic-link` | 8（两页各 4） |
| 女生榜 | `[class*="rank-type-女生榜"] a.comic-link` | 8 |
| 畅销榜 | `[class*="rank-type-畅销榜"] a.comic-link` | 8（末页混入页脚链接，需去重+标题非空过滤） |

字段沿用 rank 的 `fields`（data-id / strong.comic-title / img[src]）。
同一作品在两个滑动页内不重复；页脚链接无标题，靠「标题非空」过滤。

## 规则 schema 扩展

```jsonc
"rank": {
  "url": "…", "mode": "html",
  "fields": { … },
  "sections": [
    { "id": "recommend", "name": "编辑推荐", "list": "ul.recommend-list a.comic-link" },
    { "id": "male", "name": "男生榜", "list": "[class*=\"rank-type-男生榜\"] a.comic-link" }
  ]
}
```

- 无 `sections` 时等价于单分区，`rank()` 行为不变（向后兼容）。
- 同一 `url` 只抓一次；每区按 `limit` 截断、按 id 去重。
- 全部区为空 → 显式报错（上游改版可立刻发现）。

## 接口

`GET /api/v1/manga-sources/{source}/sections?limit=12` →
`[{id,name,items:[RankItem]}]`；需要 RANK 能力，错误统一 502 SOURCE_UNAVAILABLE。
MangaSource 增加 `Section` record 与默认 `sections()`（单分区「热门」→ rank()）。

## 前端

- 顶部源切换 chips（腾讯动漫/哔哩漫画/MangaDex），选择存 localStorage。
- Hero：取第一个分区的头部 5 条，6s 自动轮播；背景为该作品封面模糊放大 +
  渐变遮罩，左侧竖封面，右侧标题/作者/chips/「开始阅读」。
- 分区行：标题 + 横向滚动卡片（封面 120×160，前三名角标）；点击进详情
  （带 source 参数）。
- 本地漫画：搜索 + 网格，保留原功能，放在发现流下方。
- 空/错状态：分区加载失败显示错误条，本地漫画仍可用。

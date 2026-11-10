# 设计：多源聚合推荐（M25）

## 语言与过滤

- `MangaSource.language()` 默认 `zh`；MangaDex 覆盖为 `multi`；
  规则源可从规则根 `language` 读取（默认 zh）。
- 聚合只取 `capabilities` 含 RANK 且 `language=zh` 的源。
- 内容过滤：标题必须含汉字（`\u4e00-\u9fff`），过滤纯英文/日文假名标题；
  中文源内部的标题基本都是汉字，过滤只作为兜底。
- MangaDex 代码保留：哔哩榜单的跨源可读桥接（readSource=mangadex）与旧链接
  仍可用，但不再作为推荐数据源出现。

## RRF 融合

对每个源分区按名次计分：

```
score(item) = Σ_sections 1 / (60 + rank_in_section)
```

- 同一作品在多个榜/多个源出现会累加，天然提升“多榜同时在榜”的作品。
- 去重键：NFKC 归一化 + 小写 + 去空白与标点（保留括号内文字，
  “航海王（全彩版）”与“航海王”视为不同作品）。
- 保留得分最高的一条；同分时优先 `readable=true`。
- 综合榜取前 `limit` 条。

## 并行与缓存

- Java 25 虚拟线程执行器并行抓取各源 `sections(limit)`；
  单源 `Future.get(8s)` 超时即放弃并记录错误。
- 整个 discovery 结果缓存 10 分钟（key=limit）；规则/源变化需重启或等过期。
- 单源失败/超时不阻断其余源；全部失败时返回空综合榜并带 errors。

## 接口

`GET /api/v1/manga-discovery?limit=12`：

```jsonc
{
  "rows": [
    { "id": "all", "name": "综合热榜", "sourceId": null, "sourceName": null, "items": [...] },
    { "id": "tencent:recommend", "name": "编辑推荐", "sourceId": "tencent", "sourceName": "腾讯动漫", "items": [...] }
  ],
  "errors": ["哔哩漫画 请求超时"]
}
```

## 前端

- 源 chips：`综合` + 仅中文源；默认综合，选择持久化。
- 综合模式：hero 取综合榜前 5；分区行第一行「综合热榜」（带源徽标），
  其后各源分区（标题旁显示源名）。
- 具体源模式：保持现有 `/{source}/sections` 行为。
- 外部搜索页：仅中文源 chips，移除 MangaDex；旧链接与详情/阅读路由不变。

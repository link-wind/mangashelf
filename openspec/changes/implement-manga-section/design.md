# 设计：漫画专区（M15）

## D1. 封面来源

`pages.thumbnail_asset_id` 已由 M1 的 image.derive 生成，直接取
「首章（ordinal 升序）× 首页」的缩略图对象 Key，缺失回退 `image_asset_id`。
好处：零额外上传流程；封面与内容天然一致。

## D2. 索引查询

`works` 左连两个 LATERAL：
- 章节聚合（count / 最大话号 / 末话标题）；
- 封面（首章首页缩略图 Key）。

过滤 `(owner = viewer OR (PUBLIC AND PUBLISHED)) AND chapter_count > 0`，
排序 `updated_at DESC`，游标 `(updated_at, id)`。

## D3. 续读

`reading_progress` 中该用户该作品的最近一条 → (chapter, page)；
无进度取首章首页。

## D4. 前端

- `/manga`：封面卡（话数 / 更新至第 N 话）、搜索、加载更多。
- `/manga/:id`：封面 + 状态 + 继续阅读（带话号页号）+ 章节行（读到第 N 页 → 继续）。
- 首页：继续阅读（进度角标）+ 漫画更新 12 部。

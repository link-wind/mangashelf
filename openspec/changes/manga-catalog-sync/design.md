## Context

用户确认采用落库 + 同步方案（此前对比过实时聚合与渐进式）。各源能力差异（2026-11-14 核验）：

- 拷贝漫画：filter API 按题材分页（theme + offset/limit），可全量遍历；detail 含 theme 标签。
- 腾讯：规则 search 模板抓搜索结果页；rank/sections 可见条目可入库；分类频道页模板待补。
- 哔哩：无搜索、无全量列表，仅 rank（几百条）。
- 包子/嬉皮：规则仅 browserPlan（pages/detail），无列表能力——渐进式唯一来源是用户交互。

## Goals / Non-Goals

**Goals:**

- 目录表 + 同步任务框架（按源 fetcher）+ 拷贝全量实现。
- 索引页读库（题材聚合、状态、搜索、分页），实时搜索降级为兜底。
- 搜索命中顺手入库（渐进式积累）。

**Non-Goals:**

- 不做腾讯/包子/嬉皮的全量模板逆向（规则模板扩展留待后续变更）。
- 不做目录条目与本地作品/收藏的联动变更。
- 不引入通用抓取框架；每源一个 fetcher 方法。

## Decisions

1. **表结构：单表 + (source, source_item_id) 唯一。**
   ```sql
   CREATE TABLE manga_catalog_items (
     id uuid PRIMARY KEY, source text NOT NULL, source_item_id text NOT NULL,
     name text NOT NULL, alt_title text NOT NULL DEFAULT '',
     cover_url text NOT NULL DEFAULT '', remarks text NOT NULL DEFAULT '',
     tags text[] NOT NULL DEFAULT '{}', episode_count int,
     synced_at timestamptz NOT NULL DEFAULT now(), first_seen_at timestamptz NOT NULL DEFAULT now()
   );
   ```
   `source_item_id` 保存能回到源详情/阅读的标识（copymanga=path_word、bilibili=mc id、
   规则源=规则 id 字段）。索引：UNIQUE(source, source_item_id)、GIN(tags)、
   trgm(name)（pg_trgm 扩展已启用）、(source, synced_at DESC)。
   备选：按源分表——查询跨源聚合麻烦；单表 + source 列即可。

2. **同步任务：`manga.catalog.sync` 一个 kind，params.sourceId 可选指定单源。**
   每个 sync 任务只跑一个源（params 指定），便于失败隔离与重试；ScheduledSyncService
   周期任务按源逐个创建。fetcher 按源 switch：copymanga 全量遍历；其余源把
   rank/sections 可见条目 upsert（渐进式）。jobs CHECK 约束扩展该 kind（全目标 NULL）。
   备选：一个任务跑所有源——单源失败会拖累整体，逐源任务可独立重试。

3. **限速：题材间 sleep 2s、分页间 sleep 500ms。**
   上游限流保护（filter API 实测敏感）；全量约 15 题材 × 平均 10 页 ≈ 150 请求 + 间隔
   ≈ 20-40 分钟。进度事件按题材发射。

4. **索引页读库 + 题材聚合端点。**
   `GET /api/v1/manga-catalog?source=&genre=&status=&q=&cursor=`（keyset by synced_at）
   与 `GET /api/v1/manga-catalog/genres?source=`（`SELECT DISTINCT unnest(tags)` 按 source）。
   前端题材 tab 改从聚合端点渲染；"实时搜索"按钮保留调原 per-source search。

5. **渐进式入库钩子：MangaSourceController.search 返回前 upsert。**
   搜索结果本就是 SearchItem 形态，直接映射入库；失败不影响搜索响应（catch 忽略）。

## Risks / Trade-offs

- [上游 API 变更导致同步失败] → 任务显式 FAILED 可重试；目录存量不受影响。
- [标题相似度误合并不存在于此方案] → 目录按 (source, source_item_id) 原样存，不跨源合并；
  跨源聚簇只在前端展示层（后续可选）。
- [全量同步时间长] → 限速 + 逐源任务；一次全量后增量按更新时间抓首页数页。
- [tags 数据初期为空] → 需跑一次同步；本机上游不可达，标注线上验证（与用户已确认）。

## Migration Plan

1. V27：建表 + jobs CHECK 扩展 + 所需扩展确认（pg_trgm 已启用）。
2. 部署后管理员触发 manga.catalog.sync（copymanga）灌全量。
3. 回滚：代码回滚后新表无害保留。

## Open Questions

无。跨源聚簇展示明确不做（按源原样存）。

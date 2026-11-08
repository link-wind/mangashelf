# 设计：资源站目录同步与点播物化（M8）

## D1. 数据模型（V9）

```sql
CREATE TABLE external_catalog_items (
    id            uuid PRIMARY KEY,
    source_id     uuid NOT NULL REFERENCES external_sources(id),
    vod_id        bigint NOT NULL,            -- 资源站条目 ID
    name          text NOT NULL,
    cover_url     text NOT NULL DEFAULT '',
    remarks       text NOT NULL DEFAULT '',   -- 连载状态/集数备注
    category_id   int NOT NULL,
    category_name text NOT NULL DEFAULT '',
    play_from     text NOT NULL DEFAULT '',   -- 线路名组（$$$ 分隔）
    play_groups   jsonb NOT NULL,             -- vod_play_url 原文按 $$$ 分组
    episode_count int NOT NULL DEFAULT 0,
    work_id       uuid REFERENCES works(id),  -- 物化回链
    resource_updated_at timestamptz,          -- 资源站 vod_time
    synced_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source_id, vod_id)
);
CREATE INDEX idx_catalog_items_name_trgm ON external_catalog_items USING gin (name gin_trgm_ops);
CREATE INDEX idx_catalog_items_category ON external_catalog_items (category_id, name);
ALTER TABLE jobs ADD COLUMN sync_source_id uuid REFERENCES external_sources(id);
-- jobs_kind_check / jobs_target_by_kind 扩展 'catalog.sync'（互斥模式不变）
```

## D2. 同步任务（catalog.sync）

- Java Handler（HttpClient，无 Python）：参数 `sync_source_id` + job params
  `{categories:[29,30,31,32,33], mode:"full"|"incremental", hours:24}`。
- 全量：逐分类逐页 `ac=videolist&t=<c>&pg=<n>`，页间 300ms；增量：
  `ac=videolist&h=<hours>`（MacCMS 更新窗口，跨分类）。
- 每条 upsert：episode_count = 当前线路组 `#` 段数；线路组原文存 play_groups
  （物化时才解析 `名称$url#…`，目录层保持原始数据）。
- 幂等：ON CONFLICT (source_id, vod_id) DO UPDATE；重复执行无副作用。

## D3. 物化（catalog item → work）

- `POST /catalog-items/{id}/materialize`：已有 work_id 直接返回；否则建
  Work（owner=调用者，title/remarks）+ 选线路（优先名称含 m3u8 的组，否则第一组）
  解析 `名称$url` 为 external_episodes（ordinal=序号，source_id=目录条目所属源）；
  回链 work_id，事务内完成。并发物化竞态由 UNIQUE(source_id,vod_id) 行锁 +
  work_id 二次检查兜底。
- 物化后播放/解析/进度全部复用 M7 链路（直链快路径或嗅探）。

## D4. 端点与权限

| 端点 | 权限 | 说明 |
| --- | --- | --- |
| POST /admin/catalog-sync | 管理员 | 触发同步任务（30s 幂等窗口） |
| GET /admin/catalog-sync | 管理员 | 最近同步任务状态 |
| GET /catalog-items | 登录 | 目录浏览（cursor/q/categoryId） |
| POST /catalog-items/{id}/materialize | 登录 | 物化为个人书库作品 |

## D5. 前端

- 番剧索引：数据源切 `/catalog-items`，卡片用资源站封面（img onError 回退首字
  占位），显示备注（连载中/已完结/更新至 N 集）；已在书库 → "打开"跳作品页；
  未物化 → "加入书库"（物化后跳转）。搜索走目录 name ILIKE。
- 首页不变（书库层）。

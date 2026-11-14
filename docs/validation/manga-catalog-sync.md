# manga-catalog-sync 验证记录

- 日期：2026-11-14
- 基线：`main` `a765f2d`…`1156568` + 本变更工作区改动
- 环境：本地 jar（8080，V27 迁移已应用）+ compose PostgreSQL；前端 vite dev + IAB 浏览器

## 验收场景与结果

| 场景 | 结果 |
| --- | --- |
| V27 迁移 | `manga_catalog_items` 建表（UNIQUE + GIN(tags) + trgm(name) + 复合索引）；jobs CHECK 扩展 `manga.catalog.sync`（全目标 NULL）成功 |
| 目录分页 | 30 条测试数据 → `source=copymanga&limit=5` → 5 条 + total 30 + nextCursor |
| 题材聚合 | `genres?source=copymanga` → 热血 30、机甲 10（DISTINCT unnest 聚合） |
| 题材+状态组合 | 机甲 ∩ 已完结 = **5 条**（n%2==0 ∩ n%3==0 → {6,12,18,24,30}，数学正确） |
| 标题搜索 | "测试漫画1" → 11 条（1,10–19，LIKE 语义正确） |
| 前端读库 | 索引页 24 卡首屏（synced_at 倒序）、题材 tab 渲染库内聚合、点「机甲」→ 10 条 |
| 渐进式入库钩子 | `MangaSourceController.search` 返回前 upsert 命中条目（catch 忽略失败） |
| 同步任务 | `manga.catalog.sync` handler 就位（copymanga 全量遍历 + 各源渐进式）；构造器注入修复（@Autowired 消除双构造歧义） |
| 后端测试 | 43/43 通过 |

## 实施发现

- **DataClassRowMapper 不支持 text[] → List**：PgArray 转换抛 ConversionNotSupported，
  手写 RowMapper（`rs.getArray("tags")` → `(String[]) getArray()` → Arrays.asList）。
- **双构造器歧义**：测试用 package-private 构造器 + Spring 主构造器并存时，Spring 选无参
  构造失败（NoSuchMethod `<init>()`）。主构造器需显式 `@Autowired`。

## 已知限制

- **真实灌数据待线上**：copymanga 上游本机不可达（404），同步任务已用 Sleeper 注入可测、
  解析逻辑就位，但全量抓取（估 20-40 分钟、数千条）需在有代理/网络可达的环境执行。
- 拷贝题材清单为官网常集硬编码（15 个）；上游题材调整需手动更新常量。
- 状态筛选沿用 remarks 文本解析（与番剧索引一致），非结构化字段。
- 渐进式入库对"实时搜索"生效；「实时搜索」按钮为显式兜底入口（页面有说明 title）。

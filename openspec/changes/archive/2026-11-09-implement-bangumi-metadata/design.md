# 设计：Bangumi 元数据层与本季新番（M9）

## D1. 数据模型（V10）

```sql
CREATE TABLE bangumi_subjects (
    subject_id   bigint PRIMARY KEY,
    name, name_cn, cover_url, summary text,
    rating       numeric(4,1),
    air_date     date,
    air_weekday  int CHECK (1..7),
    in_calendar  boolean,     -- 本次日历同步命中
    synced_at    timestamptz
);
ALTER TABLE external_catalog_items ADD COLUMN bangumi_subject_id bigint REFERENCES bangumi_subjects;
-- jobs.kind 扩展 'bangumi.sync'（无目标列，参数走 params）
```

## D2. 同步与匹配

- `BangumiService.syncCalendar()`：GET `https://api.bgm.tv/calendar`（按星期分组），
  upsert 全部条目并标记 in_calendar=true。
- `matchCalendarToCatalog()`：用 subject 的中文名/原名做**本地**匹配：
  先等值 `name = ?`，再前缀 `name ILIKE ?%`；只更新 `bangumi_subject_id IS NULL`
  的条目，重复执行幂等。
- 合规：固定 UA（含用途说明）、单次请求、不抓详情页。

## D3. 封面代理

`GET /api/v1/bangumi-covers/{subjectId}`：
- 输入是 subjectId（库内主键），服务端查 cover_url 后取图——**不接受任意 URL**，
  避免 SSRF（AGENTS.md 安全边界）。
- http→https 归一化 + HttpClient 跟随重定向；返回 image/* + `Cache-Control: 86400`。
- HttpClient 统一受 `mangashelf.bangumi.proxy` 控制（受限网络配置）。

## D4. 前端

- `SeasonSection`：本季新番卡片（封面、★评分、放送星期、「可看」徽标）；
  已覆盖未物化 → 「播放」直接物化并跳作品页；已物化 → 「打开」。
- 番剧索引顶部嵌入分区；首页横滚（limit 8）；设置页「同步本季新番」按钮。
- 日历接口按 subject 去重（DISTINCT ON），避免一条目录多条匹配造成扇出。

## D5. 目录只收动漫

`syncIncremental` 读取 params.categories 过滤 type_id；M8 遗留的非动漫条目
（category_id ∉ {29,30,31,32,33}）清理。

## D6. 2026-11-09 补记：匹配增强与时刻表

- 匹配算法重写：标题归一化（小写/去括号注释/去标点空白/去第N季·Season N 后缀）
  + 字符二元组 Jaccard 相似度；精确相等或相似度 ≥ 0.62 才关联，每次同步先重置
  旧关联重算（避免初版前缀匹配过宽）。
- 反查补漏：本地未命中的 subject 用**资源站搜索 API**（ac=videolist&wd=）反查，
  名称相似度 ≥ 0.5 或互相包含（长度 ≥4）则写入目录并关联；单请求 8s 超时、
  总预算 120s、间隔 250ms，超预算下一轮继续。
- 反查源选择：取最近一次 SUCCEEDED 的 catalog.sync 的 sync_source_id（按名称
  排序会命中历史 mock 源，实测踩坑）。
- Bangumi 日历请求加一次重试（代理链路偶发 connect timeout）。
- 实测：本地匹配 55 部 + 反查 14 部 = **68/114 覆盖（60%）**，此前 29/114。
- 新增「新番时刻表」周视图页（/schedule）：按放送星期分栏 + 放送日未定分区，
  覆盖条目可一键播放/打开。

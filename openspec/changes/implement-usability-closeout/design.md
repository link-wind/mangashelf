# 设计：可用性闭环与内容厚度（M10/M11/M12）

## D1. 数据模型（V11）

```sql
ALTER TABLE external_episodes ADD COLUMN line_name text NOT NULL DEFAULT '';
ALTER TABLE external_episodes ADD COLUMN line_rank int NOT NULL DEFAULT 0;
ALTER TABLE external_episodes ADD COLUMN episode_type text CHECK (IN ('MAIN','EXTRA'));
-- 唯一键：(work_id, source_id, line_name, ordinal)
CREATE TABLE external_watch_progress (
    user_id, work_id, ordinal, episode_id, position_ms, duration_ms, updated_at,
    PRIMARY KEY (user_id, work_id, ordinal)
);
ALTER TABLE external_sources ADD COLUMN cookie text NOT NULL DEFAULT '';
```

进度主键不含 episode：切线路时同集号共享进度（design 决策）。

## D2. 多线路物化

`play_groups` 中每组是一条线路（名称含 m3u8 的排前）；组内 `名称$url#…` 逐条写入，
保留标题用于 PV/特典识别（正则 `PV|预告|特典|花絮|OP|ED|…`）。同一集号在多条线路
各存一行，`line_rank` 决定默认线路与展示顺序。

## D3. 周期同步

`ScheduledSyncService` 仅在 worker profile：每 10 分钟检查一次，目录距上次任务
超过 30 分钟则创建 `catalog.sync`（incremental, h=24, 动漫五分类），Bangumi 超过
6 小时则创建 `bangumi.sync`。幂等由 (kind, dedup_key 时间窗口) 收敛；任务记录仍是
事实来源，服务重启不丢。

## D4. 进度上报与续播

前端每 10 秒与卸载时 `PUT /external-progress`（workId, ordinal, episodeId,
positionMs, durationMs）。进入观看页时读取 `/me/continue-watching` 中本作品记录，
若集号匹配且位置在片长内（>5s 且 <片长-5s）则续播；localStorage 保留为兜底。

## D5. 目录检索与详情

搜索页并行请求 `/catalog-items?q=`（trgm 索引）与 `/search`（站内语义），目录结果
优先展示。`GET /catalog-items/{id}` 返回条目 + Bangumi（名称/评分/简介/星期）+
线路概览。作品侧 `GET /works/{id}/external-meta` 供详情页展示封面与简介。

## D6. 安全

- Cookie 仅存 `external_sources.cookie`，仅 Worker 读取并写入任务 params；
  前端与日志均不出现。
- 解析限流（每用户 20/分钟）在 API 进程内滑动窗口实现；多实例部署时需换集中计数
  （已在限制中记录）。

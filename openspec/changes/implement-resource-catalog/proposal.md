# 提案：资源站目录同步与点播物化（M8）

## 背景

M6/M7 打通了规则源搜索与站内播放，但目录仍靠手动绑定。资源站 API（MacCMS
开放接口）提供全库分页目录与 `h=24` 增量更新——gugu 类站点的海量番剧与新番
速度即来源于此。将"目录发现"与"个人书库"分层：

- **资源目录层**（新 `external_catalog_items`）：定时同步资源站全库动漫分类
  （数千条），可浏览搜索，不入书库；
- **书库层**（现有 works）：用户点播/加入书库时**按需物化**（建 Work + 绑集），
  之后解析/播放/进度全走既有链路。

## 变更内容

1. V9：`external_catalog_items`（UNIQUE(source_id, vod_id)，含线路原文
   play_groups JSONB、物化回链 work_id）；jobs 扩展 `catalog.sync`。
2. `catalog.sync` Worker 任务（纯 Java，HttpClient 拉取）：全量（按分类逐页）/
   增量（h=24）两种模式，页间 300ms 限速，upsert 入目录层。
3. 端点：`POST /admin/catalog-sync`（触发同步）、`GET /catalog-items`
   （目录浏览分页）、`POST /catalog-items/{id}/materialize`（物化为作品）。
4. 番剧索引页切目录层：真实封面（资源站 vod_pic）+ 已在书库徽标 + 一键加入
   书库/直接播放。

## 不做的事

- 不做定时调度框架——同步以任务记录为事实，触发方式为管理端点（后续可加
  调度器定期调用）；不自动物化（书库只进用户主动要的）。
- 不做 Bangumi 富化与新番时刻表（M9）。

## 风险

- 资源站限流/失联：任务 FAILED 显式记录，页间限速 300ms；目录数据滞留无害。
- 全量首拉约 5 类 ×1.1k 页，预计 6~10 分钟（一次性）。

# external-animations Specification Delta

## ADDED Requirements

### Requirement: 番剧目录多维分类、年份评分排序与页码分页

`GET /api/v1/catalog-items` MUST 支持 1-based `page` 页码分页并返回 `total` 与 `totalPages`，同时支持 `year`（年份）筛选、`sort=rating`（评分最高）排序及关键字 `q` 下的准确总数统计；系统 MUST 提供 `GET /api/v1/catalog-items/genres` 聚合库内热门题材。

#### Scenario: 组合筛选与关键词下的准确分页
- **WHEN** 客户端按分区、题材、状态、年份、排序与关键字请求指定页码
- **THEN** 返回该页条目与匹配的 `total`/`totalPages`，与无分页全量筛选总数一致

### Requirement: 动画追更提醒与轻量个性化推荐

系统 MUST 提供 `GET /api/v1/recommendations/anime`，对登录用户检测已收藏或已观看连载番的新更集数生成追更提醒，并基于收藏与观看历史题材/分区画像结合贝叶斯评分、更新活跃度与题材打散生成带解释理由的推荐列表；访客访问时自动返回精选公开推荐且不读取私有数据。

#### Scenario: 已追连载番出现新集时生成追更提醒
- **WHEN** 用户曾观看至第 5 集，且目录同步后该番 `episode_count` 更新至 8
- **THEN** 追更提醒列表包含该番并标明最新集数与新增 3 集

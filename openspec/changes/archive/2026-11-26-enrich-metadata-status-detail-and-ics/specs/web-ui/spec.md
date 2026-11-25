# web-ui Specification Delta

## ADDED Requirements

### Requirement: 番剧资料详情页分段浏览与时刻表日历订阅

番剧详情页（`/catalog/:id`）MUST 提供面包屑导航、高清头图与评分卡、可点击题材胶囊、关键指标条、吸顶分区锚点、内嵌线路选集网格及相似推荐；时刻表侧栏 MUST 提供 `.ics` 日历下载与订阅链接复制入口。

#### Scenario: 从详情页点击题材并直接选集播放
- **WHEN** 用户在 `/catalog/:id` 点击任一题材标签或点击内嵌选集中的某集
- **THEN** 题材标签跳转至对应筛选的 `/anime?genre=...`，点击集数直接进入该集播放页

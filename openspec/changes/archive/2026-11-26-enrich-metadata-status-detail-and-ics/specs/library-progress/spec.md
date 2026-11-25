# library-progress Specification Delta

## ADDED Requirements

### Requirement: 收藏支持「在追 / 已看完（已读完）」状态

`favorites` 表 MUST 以数据库约束支持 `status`（`WATCHING` / `COMPLETED`）及 `completed_at`，并提供统一状态切换端点；标记为 `COMPLETED` 的作品 MUST 移出新集追更提醒，并在个性化推荐画像中获得高于普通收藏的题材权重。

#### Scenario: 标记番剧已看完并联动追更提醒
- **WHEN** 已登录用户在番剧详情页或「我的」点击「标记已看完」
- **THEN** 收藏状态更新为 `COMPLETED`，在「我的 → 收藏」可按「已看完」筛选，且该番不再出现在追更提醒中

## ADDED Requirements

### Requirement: 点进动画作品直达播放

仅有外部集的动画作品 MUST 在打开作品时直接进入播放页。

#### Scenario: 首次打开

- **WHEN** 用户从列表点进一个只有外部集的动画作品
- **THEN** 浏览器直接落在该作品首集的播放页

#### Scenario: 已有观看进度

- **WHEN** 用户此前看过该作品第 N 集
- **THEN** 打开作品时直接进入第 N 集（优先上次线路）

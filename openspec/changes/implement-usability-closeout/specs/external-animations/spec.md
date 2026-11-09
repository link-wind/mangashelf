## ADDED Requirements

### Requirement: 番剧目录可检索

站内搜索 MUST 同时覆盖番剧目录与站内内容。

#### Scenario: 搜索命中目录

- **WHEN** 用户输入关键词
- **THEN** 页面展示目录匹配结果（封面卡，可进入条目详情）与站内语义/字面结果

### Requirement: 外部集播放进度由服务端保存

外部集的观看进度 MUST 保存在服务端并跨设备续播；切换线路 MUST NOT 丢失进度。

#### Scenario: 跨设备续播

- **WHEN** 用户在某设备观看到 position，之后在另一设备打开同一作品同一集
- **THEN** 播放器从该 position 续播

#### Scenario: 切换线路保持进度

- **WHEN** 用户在某线路看到 position 后切换到另一线路的同集号
- **THEN** 播放器仍从该 position 续播

### Requirement: 多线路物化与切换

物化 MUST 写入目录条目全部线路；观看页 MUST 支持线路切换与正片/PV 分组。

#### Scenario: 切换线路

- **WHEN** 用户在选择器点击另一线路
- **THEN** 跳转到该线路的同一集号，选集列表随线路更新

### Requirement: 周期同步

Worker MUST 周期性同步资源目录与 Bangumi 日历，事实记录于 jobs 表。

#### Scenario: 目录自动增量同步

- **WHEN** 距上次目录同步超过 30 分钟
- **THEN** Worker 创建 catalog.sync（incremental, h=24, 动漫分类）任务

### Requirement: 解析限流

解析端点 MUST 限制单用户频率，防止被用作公开解析服务。

#### Scenario: 超频被拒绝

- **WHEN** 单用户一分钟内发起超过 20 次解析请求
- **THEN** 返回 429 RESOLVE_RATE_LIMITED

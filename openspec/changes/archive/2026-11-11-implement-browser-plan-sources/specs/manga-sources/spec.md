# manga-sources 增量：声明式浏览器源

## ADDED Requirements

### Requirement: 浏览器源声明式接入

平台 MUST 支持通过规则（URL 模板 + 选择器）声明浏览器漫画源，新增站点不改
Java 代码。

#### Scenario: 新增包子漫画源

- **WHEN** 启用包含 browserPlan 的规则行
- **THEN** 该源自动注册，支持搜索、详情与站内阅读

#### Scenario: 统一展开展示

- **WHEN** 用户打开任意浏览器源的作品
- **THEN** 详情与阅读器使用站内统一 UI，仅数据提取按规则执行

#### Scenario: 页图多 CDN 去重

- **WHEN** 同一页图出现在多个 CDN 域名
- **THEN** 按 URL 路径去重，阅读器不重复显示

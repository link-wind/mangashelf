# 提案：M6 外部动画接入 —— 声明式规则源（方案 A）

## Why

MangaShelf 当前只展示 MangaWeaver 创作回传的动画。用户希望也能观看来自外部
视频源的动画内容（第三方番剧站等）。调研 Kazumi/Animeko/AniBaka 等 6 个项目
后（docs/research/external-animation-integration.md），选定方案 A：Kazumi 式
声明式规则源——JSON 规则文件（XPath/JSONPath），无任意代码执行，安全边界可控。

## What Changes

- 迁移 V7：external_sources（规则源注册表：名称/baseURL/规则 JSON/启停）、
  external_episodes（外部集绑定：作品/来源/标题/播放地址/序号）。
- 新增 `external/` 模块：
  - 规则源 CRUD（管理员导入/启停 Kazumi 兼容 JSON 规则）
  - `POST /api/v1/works/{id}/external-search`：用规则源搜索匹配动画
  - `POST /api/v1/works/{id}/external-episodes`：所有者绑定搜索结果为外部集
  - `GET /api/v1/works/{id}/external-episodes`：列出已绑定外部集
  - `DELETE /api/v1/external-episodes/{id}`：解绑
- 规则引擎（Java 内实现，无 WebView）：XPath（搜索列表/标题/链接提取）
  + JSONPath 模式（API 站点）+ 播放地址直链/m3u8 识别。
- 前端：作品详情页外部集列表 + 播放页跳转；管理员规则源管理。

## Capabilities

### New

- `external-animations`：声明式规则源接入与外部集展示。

## Impact

- 影响：迁移 V7、external 模块、前端两处增量。
- 不影响：M0–M5 已验证功能；接入协议。
- 风险：第三方站点改版导致规则失效（规则生命周期管理缓解）；播放地址解析
  仅覆盖直链/m3u8 类（WebView 嗅探后置）。

## 验收

管理员导入规则 → 用户搜索 → 绑定集 → 作品详情页展示外部集 → 播放页可跳转。

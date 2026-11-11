# 提案：声明式浏览器漫画源（M32）

## Why

用户指出：各站 DOM 不同需要适配器，但站内展示是统一的。适配器不应是每家
一份 Java 代码。把「浏览器执行 + 选择器」做成声明式规则后，新增源只加规则行。

## What Changes

1. 新增 Python 固定任务 `manga.plan`：按规则里的 browserPlan 打开页面、
   执行 JS、点击展开、滚动，提取统一结构（single 字段 / list 列表 / slots
   数字章节 / pages 图片）。
2. 新增 `BrowserPlanMangaSource`：由 `manga_source_rules.rules.browserPlan`
   实例化，能力按 search/detail/pages 节推导；加载器自动识别。
3. 包子漫画作为首个规则源（SEARCH/DETAIL/READ）：
   - 搜索 `/search?q=`；
   - 详情解析标题/封面/简介，章节由 `chapter_slot` 最大值生成；
   - 阅读页从 HTML 正则提取全部页图（同一页多 CDN 域名按路径去重）。
4. 图片代理白名单加 `*.bzcdn.net`、`static-tw.baozimh.com`。

## 不做

- 章节真实标题（包子详情页不直出章节列表，先按序号生成，阅读页标题仍显示原名）；
- 登录/验证码自动化；付费章节破解。

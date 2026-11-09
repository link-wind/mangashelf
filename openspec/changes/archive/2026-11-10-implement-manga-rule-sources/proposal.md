# 提案：规则驱动的漫画源引擎 + 腾讯动漫首个规则源（M23）

## Why

用户要求扩充中文漫画源，并明确指出架构问题：「不能每新增一个数据源就重新写
后端」。动画侧已有声明式规则引擎（RuleEngine，Kazumi 兼容 CSS/JSONPath 子集，
规则存 external_sources.rules），漫画源应沿用同一模式。参考 Tachiyomi 扩展生态
（用户提供的 copymanga-copy20 仓库）：源以数据/插件形式存在，宿主只实现统一
解释器。

2026-11-09/10 探测实测：腾讯动漫全链路匿名可用（榜单 SSR / 搜索 JSON /
章节 plain 密文）；拷贝漫画版本头已破解（appVersion/last 自举），但详情端点
在本机网络被「破解版」IP 封禁（1h 自动解除）、搜索维护中——其纯 JSON API
形态是规则引擎的理想接入对象，待网络限制解除后以纯配置接入。

## What Changes

1. 新增漫画规则源引擎：`manga_source_rules` 表（V15）+ `MangaRuleEngine`
   （HTML jsoup / API JSONPath 双模式，复用动画侧设计）+ `RuleMangaSource`
   （把规则行实例化为 MangaSource）。注册表启动时合并代码源与规则源。
2. 规则可引用**命名解码钩子**（闭合集合，Java 实现）：
   `tencent-plain`（章节 plain 两行密文 → nonce 求值 + 区间删除 + base64）。
   求值器字符白名单，远端内容不可执行。
3. 腾讯动漫以种子规则行接入（RANK/SEARCH/DETAIL/READ 全能力），
   删除一次性 TencentComicsSource；TencentNonceDecoder 保留为钩子实现。
4. 图片代理白名单新增 `manhua.acimg.cn`。
5. 前端外部漫画按源路由：搜索页源切换（MangaDex / 腾讯动漫），详情与阅读器
   按 `?source=` 分发端点并归一化响应形状；阅读进度按源保存。

## 不做

- 哔哩漫画登录态（沿袭既有决定）；快看漫画（aegis 挑战须真实浏览器）；
- 拷贝漫画接入（本机网络临时封禁，端点形态已探明，解除后补规则行即可）；
- 源规则的管理 UI（本期以迁移种子 + SQL 管理，UI 后续）；规则内执行任意 JS；
- 付费章节阅读；跨源去重。

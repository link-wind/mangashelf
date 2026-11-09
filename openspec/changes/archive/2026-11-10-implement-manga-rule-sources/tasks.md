# 任务：漫画规则源引擎（M23）

## 服务端

- [x] T1 V15 迁移：manga_source_rules 表 + 腾讯动漫种子规则
- [x] T2 MangaRuleEngine：HTML（jsoup）/ API（JSONPath）双模式 + 字段提取 +
      命名解码钩子（tencent-plain）
- [x] T3 RuleMangaSource + 注册表启动加载规则行；删除 TencentComicsSource
- [x] T4 图片代理白名单加 manhua.acimg.cn
- [x] T5 关键测试：nonce 解码（已有）、规则引擎 HTML/API 解析（夹具）

## 前端

- [x] T6 搜索页源切换（MangaDex / 腾讯动漫）
- [x] T7 详情页/阅读器按 ?source= 分发端点并归一化；进度按源保存
- [x] T8 热门榜腾讯条目站内打开

## 验证

- [x] T9 端到端：搜索→详情→免费话阅读→进度续读；付费话显式报错
- [x] T10 docs/validation/m23.md + 提交归档

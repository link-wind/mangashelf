# 任务：漫画首页发现流

- [x] T1 V16：腾讯规则升级为四分区（jsonb_set 只改 rank 节点）
- [x] T2 引擎：rank.sections 单次抓页多区解析 + 去重 + 空分区容错
- [x] T3 MangaSource.Section + 默认 sections() + RuleMangaSource 覆盖
- [x] T4 控制器 /sections 端点（RANK 能力，502 包装）
- [x] T5 测试：多 slide 分区解析、去重、无 sections 向后兼容
- [x] T6 前端重写 /manga：源切换 + hero 轮播 + 分区行 + 本地区块 + 样式
- [x] T7 端到端验证（curl + 浏览器截图）+ 文档 + 提交归档

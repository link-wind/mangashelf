## 1. 手机端适配

- [x] 1.1 styles.css 补响应式断点：导航/搜索、hero、卡片网格、详情页、阅读器控件、索引筛选栏
- [x] 1.2 IAB 390px 视口逐页核验（首页/番剧/漫画/收藏/历史/详情/阅读/播放）并修坏点

## 2. 番剧索引分类扩展

- [x] 2.1 服务端 `listItems` 加 `status`（completed/ongoing/movie，按 remarks/category_name 过滤）与 `sort`（updated/episodes）参数
- [x] 2.2 `AnimeIndex.tsx` 加状态与排序筛选（咕咕番式筛选栏），与类型/搜索组合；真实数据验证

## 3. 阅读器目录与导航

- [x] 3.1 `ExternalMangaReader` 章节目录抽屉（当前章高亮、跳转、正/倒序）
- [x] 3.2 `ExternalMangaReader` 键盘导航：滚动模式 ↑/↓ 逐页；双页 ←/→ 翻页（遵循 RTL）；双页滚轮翻页（节流）
- [x] 3.3 本地 `Reader.tsx` 同样接入目录与键盘/滚轮导航
- [x] 3.4 浏览器验证：条漫/双页/RTL 三种模式键位正确，Esc/F 不受影响

## 4. 漫画索引页

- [x] 4.1 新增 `/manga-index` 页面（源 tab + 搜索 + 封面网格 + 分区入口）与导航入口
- [x] 4.2 浏览器验证：按源搜索、进入详情阅读链路、手机视口可用

## 5. 收尾

- [x] 5.1 `npm run build`、`mvnw test` 通过；`openspec validate` 通过
- [x] 5.2 验证结果写入 `docs/validation/mobile-ui-index-and-reader.md`

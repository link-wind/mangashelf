## 1. 手机悬浮导航

- [x] 1.1 Nav 组件加悬浮按钮与全屏导航菜单（≤720px 显示；FAB 与遮罩置于 header 外避免 backdrop-filter 包含块）
- [x] 1.2 styles.css 加 FAB/遮罩/菜单样式；390px 视口验证展开、跳转收起、桌面无 FAB

## 2. 题材分类

- [x] 2.1 V26：bangumi_subjects 加 tags 列；syncCalendar/searchAnime 解析并保存 tags
- [x] 2.2 番剧索引题材筛选：catalog-items 加 genre 参数（经 bangumi_subjects.tags 过滤）+ UI 下拉；真实数据验证 3 条命中
- [x] 2.3 MangaSource 加可选题材能力（genres + searchByGenre）；拷贝漫画实现（筛选 API）；SourceInfo/MangaSourceController 暴露题材清单与端点
- [x] 2.4 MangaCatalog 按源显示题材 tab（无题材源隐藏）；API 验证题材清单与 tab 渲染

## 3. 移除 MangaDex

- [x] 3.1 删除 MangaDexSource/MangaDexClient；ExternalMangaController 收敛为 410 桩；前端代理回退/多语言 UI/缺省源清理
- [x] 3.2 V26 清理 mangadex 进度与收藏数据；源清单确认无 mangadex
- [x] 3.3 external-manga 规格同步（MODIFIED 搜索阅读 + REMOVED 图片代理）

## 4. 收尾

- [x] 4.1 `mvnw test` 43/43、`npm run build` 通过；`openspec validate` 通过
- [x] 4.2 验证结果写入 `docs/validation/mobile-nav-genre-remove-mangadex.md`

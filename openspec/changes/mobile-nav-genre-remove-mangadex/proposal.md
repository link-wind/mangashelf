## Why

三个用户反馈：一、手机端顶部导航收纳后仍看不全漫画/漫画索引等入口，需要悬浮按钮式菜单；二、两个索引页的分类不是用户期望的题材维度（热血/机甲等），需要接入真实的题材数据并在两个索引页提供筛选；三、MangaDex 源在中文阅读场景下价值低（日漫中文章节缺失、外链多），且引入图片代理与多语言复杂度，决定整体移除。

## What Changes

- 手机端导航：≤720px 隐藏顶部导航链接与搜索框，右下角悬浮按钮展开全屏导航菜单（全部入口 + 设置 + 退出）。
- 题材分类（热血/机甲等）：
  - 番剧：Bangumi 日历同步补存 `tags`（V26 加列），番剧索引新增题材筛选（按条目关联的 Bangumi 标签服务端过滤）。
  - 漫画：`MangaSource` 增加可选题材能力（`genres()` + 按题材搜索），先在拷贝漫画源实现（题材筛选 API）；无题材能力的源隐藏题材 tab。
- 移除 MangaDex 源：删除 `MangaDexSource`/`MangaDexClient`/图片代理端点与前端代理回退、多语言筛选；V26 清理 `source='mangadex'` 的进度与收藏数据；相关规格 delta 同步移除。
- **BREAKING**：`/api/v1/external-manga/image` 图片代理端点删除；`source=mangadex` 的历史进度/收藏数据删除。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `manga-sources`：新增题材可选能力要求；移除 MangaDex 相关描述。
- `external-manga`：移除图片代理与 MangaDex 多语言/外链章节要求；搜索与阅读保持按源统一。
- `platform-integration`：本季新番元数据同步增加题材标签。
- `external-animations`：番剧索引题材筛选（挂在本季新番/目录检索要求上）。

## Impact

- 数据库：V26（bangumi_subjects 加 tags 列；清理 mangadex 进度/收藏数据）
- 后端：删除 MangaDexSource/MangaDexClient；ExternalMangaController 收敛为 410 桩；BangumiService/Handler 补 tags；CatalogController/Repository 加 genre 过滤；拷贝漫画源加题材筛选
- 前端：App.tsx（悬浮菜单）、AnimeIndex/MangaCatalog（题材 tab）、ExternalMangaDetail/Reader（去 MangaDex 分支）、externalManga.ts（去代理回退）
- 规格归档时 external-manga 能力范围缩小

# mobile-nav-genre-remove-mangadex 验证记录

- 日期：2026-11-14
- 基线：`main` `a765f2d` + 本变更工作区改动
- 环境：vite dev（代理到 18081 API，V26 迁移已应用）+ IAB 浏览器 390×844 手机视口；真实数据（资源目录 7511 条、68 条已关联 Bangumi）

## 验收场景与结果

| 场景 | 结果 |
| --- | --- |
| V26 迁移 | bangumi_subjects 加 tags 列；mangadex 进度（8 条）与收藏数据清理成功（v26 applied） |
| 源清单 | `/api/v1/manga-sources` 无 mangadex；copymanga 返回 15 个题材（热血/机甲/恋爱…），其余源 genres=[] |
| 悬浮导航菜单 | 390px 视口：顶部导航链接与搜索隐藏、右下角 ☰ 按钮（fixed，不受 header backdrop-filter 包含块影响）；点击展开全部入口+设置+退出+主题；选入口跳转并自动收起；桌面无 FAB |
| 番剧题材筛选 | 选「热血」→ 索引网格 3 条（测试写入的 3 个带 tags subject），API `genre=热血` 3 条 vs 无筛选 7511 条 |
| Bangumi tags 同步代码 | syncCalendar/searchAnime 均解析 `tags[]` 并随 upsert 写入（真实写入待线上网络跑 bangumi.sync，本次以测试数据验证过滤链路） |
| 拷贝漫画题材浏览 | 端点与解析就位；本机上游不可达 → 显式报错「拷贝漫画题材请求 HTTP 404」（部署配代理后可用） |
| MangaDex 前端清理 | proxyImage/onImgError/capturedImage 全部移除（0 残留），封面直连；多语言筛选与「外链章节」语义删除；缺省源改 copymanga |
| 无回归 | 43/43 测试通过；首页/番剧/播放页手机视口无横向滚动 |

## 实施发现

- **List 形式 @Bean 不受生命周期管理**：尝试用 `@Bean List<SimpleMessageListenerContainer>`
  动态注册消费容器——元素不是 Spring 管理的 Bean，容器创建后从不启动（0 消费者）。
  回归为三个显式 `@RabbitListener`，consume-queues 配置一并移除。
- **backdrop-filter 创建包含块**：header 上的 backdrop-filter 会让内部 `position:fixed`
  的 FAB 相对 header 定位——悬浮按钮与遮罩必须放在 header 外。
- **React select 测试触发**：用 prototype setter + dispatchEvent(change) 可靠触发 onChange；
  页面存在多个 `.card-grid`（本季新番区块 + 索引网格）时需按位置区分检查。

## 已知限制

- 拷贝漫画题材浏览依赖上游 API，本机无代理不可达（显式报错）；部署配 `MANGASHELF_BROWSER_PROXY`
  或网络可达后可用。题材清单为官网常集硬编码。
- 番剧题材筛选仅覆盖已关联 Bangumi 的 68/7511 条（真实 tags 数据需跑一次 bangumi.sync）。
- 测试 tags 数据（3 个 subject）留在开发库。

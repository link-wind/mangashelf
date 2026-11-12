# retire-legacy-implementations 验证记录

- 日期：2026-11-13
- 基线：`main` `cb5b627` + 本变更工作区改动（未提交）
- 验证方式：本地打包 jar（`server/target/mangashelf-server-0.1.0-SNAPSHOT.jar`，`--mangashelf.role=api --server.port=18081`）连 compose 数据服务（PostgreSQL/RabbitMQ/SeaweedFS），curl API 验证 + IAB 浏览器经 vite dev（`/api` 代理到 18081）GUI 验证。测试套件：`mvnw test`（40/40）与 `python3 -m unittest discover -s processing/tests`（1/1）。

## 验证场景与结果

| 场景 | 结果 |
| --- | --- |
| 历史任务统计（任务 2.1） | `jobs` 表 `KIND_PROCESS` 任务数为 **0**（仅 bangumi.sync 1 条、catalog.sync 6 条 QUEUED 同步任务），无需取消处置，设计决策 1 的取消 SQL 未触发 |
| Python canvas 退役（1.1/1.2） | `_resolve_chapter_canvas`、`chapter-canvas` 模式、`CANVAS_SPREAD_WAIT_SECONDS`、`image_derive.py`、`test_bilibili_capture.py` 删除；全仓 grep 无残留引用；processing 测试通过 |
| Java 旧实现退役（2.2/2.3） | `DeriveJobHandler`、`Job.KIND_PROCESS` 常量、`MangaMatcher.findReadable()` 及其缓存/MangaDexClient 依赖删除；`similarity()`/`normalize()` 保留；编译通过、40/40 测试通过（含 `MangaDiscoveryServiceTest` 8 项） |
| 哔哩 HTTP 阅读不受影响（1.3） | `GET /api/v1/manga-sources/bilibili/rank?limit=1` 返回真实榜单数据（碧蓝之海等），路径不经 Python |
| 410 桩（specs：历史截图链接显式失效） | `/api/v1/external-manga/captured/abc123/1.png` → **410** `CAPTURE_REPLACED`；`/api/v1/manga-sources/capture/x` → **410** `CAPTURE_REPLACED` |
| 旧端点退役（5.1） | `/api/v1/external-manga/search`、`/{mangaId}`、`/chapter/{id}/pages` → **404** `NOT_FOUND`（删除后由新增的 `NoResourceFoundException` 处理器返回，见 design.md 2026-11-13 追记） |
| 图片代理保留 | `/api/v1/external-manga/image?u=<非白名单域名>` → **400**，未发起上游请求 |
| MangaDex 统一接口（3.2） | search `q=one piece&lang=all` → 200 返回 SearchItem；detail → 500 章（450 可读）；pages → 53 页 + 53 dataSaver + external=false。`lang=zh` 返回空列表与旧端点行为一致（同映射，非回归） |
| 浏览器 MangaDex 详情（4.2） | `/manga-ext/{id}?source=mangadex` 渲染标题/封面/标签/源名chip；语言切换「全部」加载 450 个章节格；页脚无「旧版书库」入口 |
| 浏览器 MangaDex 阅读（4.2） | 阅读器加载 53 页清单 + 章节名「第 1 话 Romance dawn」（统一详情）；首图 757×1200 经 MangaDex CDN data-saver 加载完成（省流默认档生效）；其余页按滚动懒加载 |
| 进度保存（4.2） | 阅读后 `GET /api/v1/me/manga-progress` 返回 `{source:"mangadex", mangaId, chapterId, page:1, totalPages:53, title:"One Piece"}`，数据标识与统一前一致 |
| 旧链接兼容（specs） | `/manga-ext/{id}`（无 source 参数）→ 按 MangaDex 处理，详情正常渲染 |
| 旧版书库移除（4.3） | `/library` 路由与导航链接删除；`web` 构建（tsc + vite）通过；`/api/v1/me/library` 收藏 API 不受影响 |
| 综合榜回归（2.3） | `GET /api/v1/manga-discovery?limit=5` → 8 行，首行「综合热榜」多源聚合条目，errors 为空 |

## 已知限制

- 腾讯动漫源在无 Clash 代理的裸实例上返回 `SOURCE_UNAVAILABLE`（上游解码依赖代理环境）；该源代码本变更未触碰，属环境限制。
- 包子漫画为浏览器型源，搜索依赖 Python 解析任务，裸实例上部分请求失败；同理与本章变更无关。
- 浏览器端合成点击（Playwright locator click）在该 IAB 环境对部分 React 按钮未触发处理器，改用坐标/DOM evaluate 点击完成验证；应用代码问题可能性低（页面内 fetch、导航、渲染均正常），未深究。
- Docker 镜像（api/worker/web）重建仍在后台进行，验证以本地 jar + vite dev 完成；部署前需确认镜像构建成功。
- 本机 8080 端口有一个 4 天前构建的 dev 实例（PID 50296）仍在运行，其对 `POST /api/v1/auth/register|login` 返回 500 `INTERNAL_ERROR`（与 compose 旧镜像同接口行为不一致），疑似陈旧构建与当前库结构不兼容，建议重启或停用。

# 验证记录：Web UI 液态玻璃改版 + 访客浏览 + 运行期修复

> 日期：2026-11-25
> 关联变更：`openspec/changes/polish-web-ui-liquid-glass/`
> 涉及 DESIGN：第 3 节（用户/权限/可见性）、第 16 节（HTTP API 安全与错误）

本文件记录三部分实测结果：
1. 液态玻璃 UI 改版的多端视觉验收（原 tasks 5.2 / 5.3）；
2. 新增的「访客浏览公开内容」能力（DESIGN 3.1/3.2）的端到端验证；
3. 本轮一并修复的运行期小问题（readiness 探针、封面 404 兜底、测试数据清理）。

---

## 1. 构建与测试

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 前端类型检查 + 构建 | `cd web && npm run build`（`tsc -b && vite build`） | ✅ 通过（78 modules，CSS 51.8 kB，JS 1.01 MB / gzip 312 kB） |
| 后端构建 | `cd server && ./mvnw package` | ✅ 通过（Java 25.0.5） |
| 后端测试 | `cd server && ./mvnw test` | ✅ 18 个测试类全过（含新增 `OptionalAuthUserResolverTest` 2 例）；0 失败/错误/跳过 |
| API 容器 | `docker compose build api && up -d api` | ✅ 启动成功，`/actuator/health` UP |

> 已知：前端单块 JS 1.01 MB（gzip 312 kB）超过 Vite 500 kB 告警阈值，暂未做代码分割；
> 作为内网/自托管渲染网站可接受，后续可 `manualChunks` 拆分。

---

## 2. 多端视觉验收（原 tasks 5.2）

以无头 Chromium 按真实尺寸渲染：桌面 1440×900、移动 390×844，明/暗双主题；
登录态（admin）与访客态各测一轮。页面：首页、番剧索引、漫画专区、漫画索引、搜索、收藏、设置。

| 结论 | 说明 |
| --- | --- |
| 暗色刷新无闪烁 | ✅ 首帧主题脚本按 `localStorage` / `prefers-color-scheme` 预置，刷新无白闪 |
| 玻璃浮层可读 | ✅ 顶栏、用户菜单、手机悬浮菜单、卡片均为半透明毛玻璃，暗色下对比正常 |
| 手机导航 | ✅ 顶栏收敛为品牌 + 搜索图标 + 悬浮球；浮层菜单补齐「搜索」入口，登录态含素材/设置/退出，访客态显示「登录 / 注册」 |
| 封面破图兜底 | ✅ `CoverImage` 统一封装：缺失/404 显示渐变首字（见下节封面 404） |
| 首页骨架屏 / Hero 轮播 | ✅ 首屏骨架屏与 Hero 切换正常 |
| 登录 / 收藏 / 搜索页 | ✅ 玻璃登录卡、`3:4` 卡片、来源分类 Tab 均正常 |

### 2.1 设计自检（`check_design` 无头量测）

对本轮改动最敏感的首页反复量测并修复，最终 **桌面 1440×900 与移动 390×844 均无问题**：

- 修复前：34 项（对比度、字号偏小、点击区偏小、Hero 缩略图标题溢出等）。
- 逐项修复后：**0 项**（每屏每尺寸均通过，跳转 10 个目标全部可打开）。

修复要点（均落到设计变量，未硬编码散落颜色）：

| 问题 | 修复 |
| --- | --- |
| 白字配 `--accent #00aeec` 仅 2.54:1 | 新增 `--accent-btn #007bb0`（对白 4.71:1）专用于**填充按钮底色**；`--accent` 仍作装饰/链接强调 |
| `--muted #7f8b96` 配浅底 3.27:1 | 浅色主题 `--muted` 调深为 `#5f6b75`（5.13:1） |
| 星级 `--warn #ff9500` 配白 2.2:1 | 新增 `--warn-text #a35f00`（5.01:1）用于浅底文字，`--warn` 保留装饰 |
| 「可看」标签绿字 2.64:1 | 新增 `--pub-text`（浅色 `#006f49` / 暗色 `#7bdcb5`）用于浅底文字 |
| 排行名次蓝字 2.54:1 | `.rank-no.top` 改用 `--accent-btn` |
| Hero 缩略图标题 10px 偏小 | 提到 12px（含漫画专区 `manga-hero-thumb-title`） |
| 搜索输入框高 19px | 加 `min-height: 24px` / `padding`，满足最小点击区 |
| 页脚链接点击区 18px | 链接 `padding: 4px 0`，达到 24px 高 |

> 仍存在但**判定为可接受/有意为之**、未强改的项：工作卡标题 `.clamp` 两行截断（设计上的行数限制）、
> 时刻表/「查看全部」等文字型链接视觉高 18–20px（`section-head` 内联小链接，非主要按钮）。

---

## 3. 访客浏览公开内容（DESIGN 3.1/3.2）

### 3.1 设计

原实现每条路由、每个接口一律要求登录（`anyRequest().authenticated()` + 前端 `me ? Page : Navigate(/login)`），
与 DESIGN 3.1「访客可浏览、阅读、观看公开内容」不符。本轮补齐：

- **数据层无需改动**：全部查询已是 `(owner_id = :viewerId OR (visibility='PUBLIC' AND status='PUBLISHED'))`
  前置过滤。访客以 `viewerId = 全零 UUID` 代入，`owner_id = 全零` 恒为假，天然只召回 PUBLIC+PUBLISHED。
- **后端**：新增 `@OptionalAuthUser` 注解与 `OptionalAuthUserResolver`——未登录注入
  `CurrentUser.GUEST`（`id=全零`、`role=GUEST`、`isAdmin=false`）而非抛 401；`SecurityConfig`
  显式放行「公开内容只读端点」，其余仍 `authenticated()`。
- **安全错误语义**：新增认证入口 → **401 JSON**（`UNAUTHENTICATED`）、授权拒绝 → **403 JSON**
  （`FORBIDDEN`），前端据 401 在写操作时引导登录，403 作为真实权限错误呈现（不重定向）。
- **前端**：公开路由始终可进；私有路由（收藏/历史/素材/设置）由 `RequireAuth` 守卫跳
  登录后回跳。访客顶栏显示「登录」按钮；私有 `/me/*` GET 由页面按 `me` 门控跳过；
  播放器进度保存、阅读进度保存对访客不提交。
- **访客进入私有页不跳转**：`/shelf`、`/settings`、`/history` 等私有路由在未登录时**原地显示「需要登录」提示卡**
  （含「去登录 / 返回首页」按钮），URL 保持不变，不硬跳 `/login`。
- **访客可直接播放公开剧集**：外部集解析是公共操作，访客触发解析时后端以系统所有者（admin）承载解析任务
  （满足 `jobs.owner_id` 外键），解析成功即返回直链，访客可直接观看；仅观看进度不保存。

### 3.2 放行清单（GET，访客可读）

目录浏览：`/manga-catalog`、`/manga-discovery`、`/manga-sources/**`、`/catalog-items/**`、
`/catalog-covers/**`、`/bangumi-covers/**`、`/calendar`、`/danmaku`、`/external-sources`；
站内作品/章节：`/manga`、`/manga/**`、`/works`、`/works/*`、`/chapters/*`、`/chapter-revisions/*`、
`/chapter-revisions/*/pages`、`/chapter-revisions/*/animations`；动画：`/animations`、`/animations/*`、
`/animations/*/playback`；外来集状态与解析：`/external-episodes/*/resolve`（GET 查状态，POST 触发解析，访客均可）。
站内检索：`POST /search`、`POST /search/documents`（只读语义，访客只召回公开内容）。

### 3.3 实测：端点矩阵（匿名，无 Cookie）

| 端点（GET） | 访客结果 |
| --- | --- |
| `/api/v1/calendar` | **200** |
| `/api/v1/manga-discovery` | **200** |
| `/api/v1/manga-catalog?source=bilibili` | **200** |
| `/api/v1/manga-sources` | **200** |
| `/api/v1/catalog-items` | **200** |
| `/api/v1/works` | **200** |
| `/api/v1/manga` | **200** |
| `/api/v1/external-episodes/{id}/resolve`（GET / POST） | **200**（访客可查状态、可触发解析；解析任务以 admin 承载） |
| `/api/v1/me/continue` | **401** `UNAUTHENTICATED` |
| `/api/v1/me/favorites` | **401** |
| `/api/v1/me/reading-progress/{id}` | **401** |
| `/api/v1/uploads` | **401** |
| `/api/v1/admin/catalog-sync` | **401** |
| `/actuator/health`、`/readiness`、`/liveness` | **200** |

### 3.4 实测：前端行为（无头浏览器，匿名）

| 场景 | 结果 |
| --- | --- |
| 访客打开首页 | ✅ 完整渲染（Hero 航海王、时刻表、更新），右上角为蓝色「登录」按钮，导航仅含公开项 |
| 访客打开番剧索引 / 漫画专区 / 漫画索引 / 搜索 | ✅ 均正常出图、可检索（仅召回公开内容） |
| 访客打开 `/shelf`、`/settings`、`/history` | ✅ 原地显示「需要登录」提示卡，URL 不变（不跳登录页） |
| 访客打开目录详情 `/catalog/:id` | ✅ 正常渲染，无脚本错误（私有收藏/进度探测静默跳过） |
| 访客打开浏览态播放 `/browse/:item/:ep` | ✅ 正常渲染并可直接播放：解析状态「已就绪」，`<video>` 加载直链，无登录提示 |
| 访客触发未解析剧集解析 | ✅ `POST resolve` 200；生成 `external.resolve` 任务 owner=admin 并 SUCCEEDED |
| 登录后回跳 | ✅ 从 `/shelf` 被拦→登录→回到 `/shelf`（`next` 仅接受站内相对路径，防开放重定向） |

---

## 4. 运行期修复

| 问题 | 根因 | 处理 | 验证 |
| --- | --- | --- | --- |
| `/actuator/health/readiness` 返回 403/空 | 仅放行 `/actuator/health`，子路径被拦 | 放行 `/actuator/health/**` | readiness/liveness 均 200 |
| 首页个别封面 404（如 bangumi-covers/498559、catalog-covers/…） | 上游取图失败/该条目无 cover_url | `CoverImage` 已统一兜底渐变首字；重建镜像后 catalog-covers 代理恢复 200 | 首页无破图、无脚本错误 |
| 库中残留测试用户/草稿 | 冒烟脚本产物 | 新增 `scripts/cleanup-dev-test-residue.sql`（事务、幂等），删除 `123`/`retire_smoke`/`fav_smoke` 及其 DRAFT 草稿；物化目录条目仅解除 `work_id` 关联、保留可浏览 | 仅剩 `admin`，0 测试作品，7516 目录条目完整 |

> 说明：封面 404 的根因是上游 Bangumi 代理/网络（`host.docker.internal:7892`）与个别条目无封面，
> 非代码缺陷；兜底与代理均已确认工作。真实源覆盖边界另见 `m7.md`。

---

## 5. 已知限制

1. 前端单块 JS 1.01 MB（gzip 312 kB）超 Vite 提示阈值，未做代码分割。
2. 访客触发解析以系统所有者（admin）承载任务（`jobs.owner_id` 需真实用户外键）；解析为异步无头浏览器任务，
   受外部源可用性影响，超时/失败会显式提示可重试。解析按剧集去重并缓存直链，已解析的不再重复触发。
3. 基于作品（work）的外来剧集端点（`/works/{id}/external-*`）仍要求登录；访客浏览主要走目录条目
   （catalog-items / manga-sources）路径。
4. 部分时间表/页脚文字链接视觉点击高 18–20px，未强制放大（保持排版紧凑）。

## 6. 复现命令

```bash
cd web && npm run build
cd ../server && ./mvnw test
docker compose build api && docker compose up -d api
# 匿名端点矩阵（本机）：
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/v1/calendar        # 200
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/v1/me/continue      # 401
# 清理测试残留（可选，开发/演示实例）：
docker exec -i mangashelf-db-1 psql -U mangashelf -d mangashelf -v ON_ERROR_STOP=1 \
  < scripts/cleanup-dev-test-residue.sql
```

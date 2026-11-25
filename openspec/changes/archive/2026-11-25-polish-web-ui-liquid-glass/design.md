# Design: polish-web-ui-liquid-glass

> 对应 `docs/DESIGN.md` 第 4 节（页面与主要交互）与 `openspec/specs/web-ui/spec.md`。本设计主要记录前端视觉与交互增量决策；2026-11-25 追加「访客浏览」增量，涉及后端身份/安全层的只读放行与 401/403 语义（不改数据表，见文末）。

## 1. 液态玻璃（Liquid Glass）Design Tokens

保持现有 B 站蓝系（`#00aeec` / `#00658b`）作为唯一主操作色，在亮/暗双主题下新增一组玻璃材质 CSS 变量，避免在各组件中手写零散 `rgba`：

| Token | 亮色模式（默认） | 暗色模式（`[data-theme="dark"]`） | 用途 |
| --- | --- | --- | --- |
| `--glass-bg` | `linear-gradient(135deg, rgba(255,255,255,.72), rgba(248,250,252,.52))` | `linear-gradient(135deg, rgba(28,33,40,.72), rgba(18,21,26,.56))` | 顶栏、下拉菜单、Toast、移动端菜单 |
| `--glass-dark-bg` | `linear-gradient(135deg, rgba(18,22,28,.62), rgba(12,15,20,.46))` | 同左（更深透） | Hero 海报浮层、阅读器/播放器浮动条 |
| `--glass-border` | `rgba(255, 255, 255, .62)` | `rgba(255, 255, 255, .14)` | 外轮廓折光边 |
| `--glass-highlight` | `inset 0 1px 0 rgba(255,255,255,.85), inset 0 -1px 0 rgba(15,23,42,.05)` | `inset 0 1px 0 rgba(255,255,255,.16), inset 0 -1px 0 rgba(0,0,0,.35)` | 顶/底镜面内高光 |
| `--glass-blur` | `blur(20px) saturate(185%)` | `blur(22px) saturate(180%)` | 背景模糊与增饱和 |
| `--glass-shadow` | `0 10px 30px rgba(15, 23, 42, .08)` | `0 12px 34px rgba(0, 0, 0, .42)` | 浮层投影 |
| `--ease-spring` | `cubic-bezier(0.22, 1, 0.36, 1)` | 同左 | 玻璃浮层弹出与卡片微抬升曲线 |

降级与可访问性策略：
- `@supports not (backdrop-filter: blur(1px))` 或 `@media (prefers-reduced-transparency: reduce)` 下回退到高不透明度实体背景（`var(--card)` / `#12151a`）。
- `@media (prefers-reduced-motion: reduce)` 关闭 Hero 过渡、骨架屏扫光与弹性位移动画。
- 严格禁止在 `.work-card`、`.chapter-cell`、表格行等高密度重复节点上使用 `backdrop-filter`，避免合成层爆炸导致滚动掉帧。

## 2. 6 个核心液态玻璃落点

1. **悬浮玻璃顶栏（`.nav`）**：保持 `sticky top: 0`，应用 `--glass-bg` + `--glass-blur` + `--glass-highlight`；胶囊搜索框获得焦点时叠加柔和蓝晕光环；右侧用户操作收进玻璃下拉头像菜单（展示用户名/角色、我的素材、设置与令牌、退出登录），使 1280px 宽下顶栏舒展不挤压。
2. **Hero Banner 悬浮控件**：`.hero-thumbs` 缩略图条收进玻璃托盘，次按钮（`.hero-sub-btn` / `.hero-info`）与状态徽标应用 `--glass-dark-bg` + 高光边；增加轮播平滑淡入过渡与缩略图细进度条；桌面端高度按视口自适应（`clamp(360px, 52vh, 480px)`），移动端收紧至 `340px` 防止遮占整屏。
3. **阅读器 & 播放器浮动条**：`.rf-top` / `.rf-bottom` 升级为悬浮圆角暗色玻璃胶囊（保留全屏点击显隐逻辑），`.rf-sheet` 目录抽屉与 `.mp-bigplay` 播放按钮同步套用暗色玻璃材质。
4. **手机端悬浮球 & 菜单**：`.nav-fab` 与 `.nav-mobile-menu` 应用液态玻璃 + `--ease-spring` 展开动画；菜单内补齐「搜索」入口；≤720px 媒体查询同步隐藏顶栏右侧的设置/退出/主题按钮，消除重复。
5. **全局 Toast 与下拉浮层**：新建轻量 `ToastContext` + `<ToastViewport>`（右下角玻璃胶囊，自动消失，支持成功/错误态）。
6. **登录页（`Login.tsx`）**：全屏柔和径向渐变光斑背景（品牌蓝 + 柔青，暗色下压暗为深海微光）+ 居中的液态玻璃登录卡片，增加密码显隐切换与提交 loading 防连点。

## 3. 共享组件与样式组织（不引入重型 UI 框架）

保持极简零冗余依赖，仅在 `web/src/components/` 下新增 3 个小组件：
- `Icons.tsx`：少量统一 1.75px 描边的内联线性 SVG 图标（搜索、太阳/月亮、菜单、关闭、用户、设置、退出、书签、播放、箭头、眼睛等），替换零散 Unicode 字符。
- `CoverImage.tsx`：统一封装封面 `<img>`，内置 `failed` 状态切换到渐变色首字兜底（不再用 `display: none` 留下空灰块）与图片 `onLoad` 淡入。
- `Toast.tsx`：全局轻量提示上下文与右下角玻璃渲染容器。

CSS 拆分策略：
- 将当前 1022 行单文件 `web/src/styles.css` 清理并拆分为：
  - `src/styles/tokens.css`（亮/暗变量、玻璃变量、滚动条、通用动画与骨架屏）
  - `src/styles/layout.css`（顶栏、下拉菜单、页脚、移动端悬浮导航、响应式断点）
  - `src/styles/components.css`（按钮、输入框、封面卡片、Hero、播放器、阅读器、Toast）
  - `src/styles/pages.css`（登录页、首页高分榜/时刻表、漫画详情、搜索、收藏等页面专属样式）
- 删除已被 M14 HeroBanner 替代的废弃 `.banner`（原 389–421 行）与重复选择器定义；`src/main.tsx` 按顺序引入这 4 个 CSS 文件。

## 4. 关键页面一致性与细节决策

- **暗色防白屏闪烁**：在 `web/index.html` `<head>` 内联一段极短同步脚本，优先读 `localStorage.getItem('ms-theme')`，未设置时读 `window.matchMedia('(prefers-color-scheme: dark)').matches`，在 React 挂载前写入 `document.documentElement.dataset.theme`。
- **Favicon & 动态标题**：`web/public/favicon.svg` 放置与顶栏一致的品牌蓝播放/书页融合图标；在 `App.tsx` 中用 `useLocation` 映射主要路由的 `document.title`。
- **首页（`Home.tsx`）**：增加加载中骨架屏（Hero 骨架 + 横向卡片骨架）；「继续观看」与「继续阅读」卡片底部增加 `3px` 细进度条；「继续阅读」接入 `coverUrl`（由 `/api/v1/me/continue` 已返回的 `coverUrl` 透传，无图走 `CoverImage` 兜底）。
- **收藏页（`Shelf.tsx`）**：改为统一 `.card-grid` + `.work-card` 视觉结构，取消收藏改为卡片右上角悬浮微按钮（点击触发 Toast 反馈）。
- **搜索页（`Search.tsx`）**：并行请求 `/api/v1/search`、`/api/v1/catalog-items` 与 `/api/v1/manga-catalog`（外部漫画目录），移除 `.catch(() => setCatalog([]))` 的静默吞错（改为显式分段报错），提供来源切换 Tab（全部 / 番剧 / 漫画 / 站内对白），仅在全部类别均无结果且无错误时展示空态。


## 6. 访客浏览增量决策（2026-11-25）

- **主体模型**：未登录以 `CurrentUser.GUEST`（全零 UUID、role=GUEST、isAdmin=false）参与既有可见性判定；
  不新增数据表、不改 SQL——`owner_id = 全零` 恒假，访客只得到 `PUBLIC` 且 `PUBLISHED`。
- **放行粒度**：只对「公开内容只读端点」放行（目录、站内作品/章节/页、动画播放、检索、外来集状态）；
  写操作与 `/me/**`、`/uploads/**`、`/admin/**`、`/jobs/**`、`/integrations/**` 仍要求登录。
- **错误语义**：认证入口 401 `UNAUTHENTICATED`；授权拒绝 403 `FORBIDDEN`；前端据此区分「去登录」与「真无权」。
- **前端守卫**：公开路由始终可进；私有路由 `RequireAuth` 跳 `/login?next=<站内相对路径>`（防开放重定向）；
  私有 `/me/*` GET 按登录态门控跳过；播放/阅读进度对访客不提交；未解析剧集提示「登录后播放」。

与 DESIGN 对应：第 3.1/3.2 节（访客可浏览公开内容）、第 16.2 节（错误结构）。

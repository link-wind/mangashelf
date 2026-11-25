# Tasks: polish-web-ui-liquid-glass

## 1. 样式系统拆分与液态玻璃基础

- [x] 1.1 在 `web/index.html` `<head>` 添加首帧主题初始化脚本（支持 `localStorage` 与系统 `prefers-color-scheme`），并新增 `web/public/favicon.svg`
- [x] 1.2 将 `web/src/styles.css` 清理废弃样式并拆分为 `web/src/styles/tokens.css`、`layout.css`、`components.css`、`pages.css`，定义明暗双主题液态玻璃变量、降级规则（`@supports` / `prefers-reduced-transparency` / `prefers-reduced-motion`）、骨架屏与暗色滚动条样式
- [x] 1.3 更新 `web/src/main.tsx` 引入拆分后的样式文件，验证 `npm run build` 无报错

## 2. 通用 UI 基础组件

- [x] 2.1 新建 `web/src/components/Icons.tsx`，提供统一风格的内联线性 SVG 图标集
- [x] 2.2 新建 `web/src/components/CoverImage.tsx`，封装封面加载淡入与缺失/404 时的渐变首字兜底
- [x] 2.3 新建 `web/src/components/Toast.tsx`，提供全局 `useToast()` 与右下角液态玻璃轻提示容器，并挂载到 `App.tsx`

## 3. 顶栏、移动端导航与浮层玻璃化

- [x] 3.1 改造 `web/src/App.tsx`：顶栏应用液态玻璃材质、线性 SVG 图标、右侧用户头像下拉菜单（收拢用户名/我的素材/设置/退出）以及路由动态 `document.title`
- [x] 3.2 修复手机端（≤720px）导航：悬浮球与弹出菜单套用液态玻璃材质，补齐「搜索」入口，并隐藏顶栏上重复的设置/退出/主题按钮
- [x] 3.3 升级阅读器浮动条（`Reader.tsx` / `ExternalMangaReader.tsx` 对应样式 `.rf-top` / `.rf-bottom` / `.rf-sheet`）与播放器浮动控件（`Player.tsx` 对应样式）为暗色液态玻璃胶囊

## 4. 核心页面美化与体验统一

- [x] 4.1 升级 `web/src/pages/Home.tsx`：增加首屏骨架屏、Hero 淡入淡出切换与缩略图玻璃托盘（含响应式高度）、卡片 hover 微抬升与封面缩放、「继续观看/继续阅读」底部细进度条及封面透传
- [x] 4.2 美化 `web/src/pages/Login.tsx`：柔和渐变光斑背景 + 液态玻璃登录卡、品牌标识、密码显隐开关与提交中禁用态
- [x] 4.3 统一 `web/src/pages/Shelf.tsx` 为全站 `3:4` `.work-card` 风格 + 右上角悬浮取消收藏按钮，接入 Toast 反馈
- [x] 4.4 升级 `web/src/pages/Search.tsx`：接入外部漫画目录检索（`/api/v1/manga-catalog`）、增加来源分类 Tab、移除静默 `.catch` 改为显式报错、修正空态判断逻辑

## 5. 构建与多端视觉验证

- [x] 5.1 运行 `cd web && npm run build` 确认 TypeScript 类型检查与 Vite 生产构建通过
- [x] 5.2 在真实浏览器（桌面 1440px / 1280px 与手机 390px，明暗双主题）逐项验收：暗色刷新无闪烁、玻璃浮层对比度、手机悬浮菜单含搜索且无重复按钮、封面破图兜底、首页骨架屏与 Hero 轮播、登录/收藏/搜索页表现
- [x] 5.3 将实测结果与截图/已知限制写入 `docs/validation/polish-web-ui-liquid-glass.md`

## 6. 对比度/点击区修正与访客浏览

- [x] 6.1 新增 `--accent-btn` / `--muted` 调深 / `--warn-text` / `--pub-text`，修正填充按钮与浅底文字对比度至 ≥4.5:1；Hero 缩略图标题提至 12px；搜索框与页脚链接补足 24px 点击区（`check_design` 首页双尺寸 0 问题）
- [x] 6.2 后端新增 `@OptionalAuthUser` + `OptionalAuthUserResolver`（未登录注入 `CurrentUser.GUEST` 全零主体）；`SecurityConfig` 放行公开只读端点；认证入口 401 JSON / 授权拒绝 403 JSON（DESIGN 3.1/3.2、16.2）
- [x] 6.3 前端公开路由始终可进、私有路由 `RequireAuth` 跳 `/login?next=`；访客顶栏「登录」入口、手机菜单登录项；私有 `/me/*` GET 按登录态门控跳过；播放/阅读进度对访客不提交；未解析剧集提示「登录后播放」
- [x] 6.4 修复 `/actuator/health/**` 放行（readiness 探针）、封面 404 兜底确认、新增并执行 `scripts/cleanup-dev-test-residue.sql` 清理测试用户/草稿
- [x] 6.5 新增 `OptionalAuthUserResolverTest`（2 例）；后端 18 测试类全过；写 `docs/validation/polish-web-ui-liquid-glass.md`；README 里程碑补到 M35；DESIGN 追加第 25 节方向性变更记录

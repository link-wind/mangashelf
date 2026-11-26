import { MotionConfig } from 'motion/react'
import { Suspense, lazy, useEffect, useState } from 'react'
import { NavLink, Navigate, Route, Routes, useNavigate, useLocation } from 'react-router-dom'
import { useAuth } from './auth'
import { ToastProvider } from './components/Toast'
import BrandLogo from './components/ui/BrandLogo'
import CommandPalette, { pushRecentSearch } from './components/ui/CommandPalette'
import UserPopover from './components/UserPopover'
import { BRAND_NAME, BRAND_NAME_ZH, BRAND_TITLE_FULL, pageTitle } from './lib/brand'
import {
  BookmarkIcon, CloseIcon, LogOutIcon, MenuIcon, MoonIcon,
  SearchIcon, SettingsIcon, SunIcon,
} from './components/Icons'

// 路由级代码分割：把播放器（hls.js）、阅读器等重组件从首屏主包拆出
const LoginPage = lazy(() => import('./pages/Login'))
const Home = lazy(() => import('./pages/Home'))
const CatalogIndex = lazy(() => import('./pages/CatalogIndex'))
const Schedule = lazy(() => import('./pages/Schedule'))
const CatalogDetail = lazy(() => import('./pages/CatalogDetail'))
const MangaIndex = lazy(() => import('./pages/MangaIndex'))
const MangaDetailPage = lazy(() => import('./pages/MangaDetail'))
const ExternalMangaDetail = lazy(() => import('./pages/ExternalMangaDetail'))
const ExternalMangaReader = lazy(() => import('./pages/ExternalMangaReader'))
const WorkDetail = lazy(() => import('./pages/WorkDetail'))
const Reader = lazy(() => import('./pages/Reader'))
const Watch = lazy(() => import('./pages/Watch'))
const Me = lazy(() => import('./pages/Me'))
const Search = lazy(() => import('./pages/Search'))
const Settings = lazy(() => import('./pages/Settings'))
const ExternalEpisodes = lazy(() => import('./pages/ExternalEpisodes'))
const ExternalWatch = lazy(() => import('./pages/ExternalWatch'))
const BrowseWatch = lazy(() => import('./pages/BrowseWatch'))

const NAV_ENTRIES = [
  { to: '/', label: '番剧' },
  { to: '/manga', label: '漫画' },
  { to: '/index', label: '索引' },
  { to: '/schedule', label: '时刻表' },
]

const PAGE_TITLES: Array<[RegExp, string]> = [
  [/^\/$/, pageTitle('番剧')],
  [/^\/login$/, pageTitle('登录')],
  [/^\/manga$/, pageTitle('漫画')],
  [/^\/index$/, pageTitle('索引')],
  [/^\/anime$/, pageTitle('索引')],
  [/^\/schedule$/, pageTitle('新番时刻表')],
  [/^\/me$/, pageTitle('个人空间')],
  [/^\/search$/, pageTitle('搜索')],
  [/^\/settings$/, pageTitle('设置')],
]

/** 这些动态详情/播放/阅读路由由页面组件自行设置作品名称标题，App 不再在父级 effect 覆盖。 */
const SELF_TITLED_ROUTES = /^\/(catalog|browse|watch|works|manga\/|manga-ext|read\/)/

function currentTheme(): 'dark' | 'light' {
  return document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light'
}

function PageFallback() {
  return (
    <div className="page-center">
      <div className="row-inline">
        <div className="player-spinner" style={{ width: 22, height: 22, margin: 0, borderColor: 'var(--border)', borderTopColor: 'var(--accent)' }} />
        <span>加载页面…</span>
      </div>
    </div>
  )
}

function Nav() {
  const { me, logout } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [kw, setKw] = useState('')
  const [theme, setTheme] = useState<'dark' | 'light'>(currentTheme)
  const [menuOpen, setMenuOpen] = useState(false)
  const [cmdkOpen, setCmdkOpen] = useState(false)

  useEffect(() => {
    setMenuOpen(false)
  }, [location.pathname])

  function toggleTheme() {
    const next = currentTheme() === 'dark' ? 'light' : 'dark'
    document.documentElement.dataset.theme = next
    localStorage.setItem('ms-theme', next)
    setTheme(next)
  }

  function submit(e: React.FormEvent) {
    e.preventDefault()
    const q = kw.trim()
    if (q) pushRecentSearch(q)
    navigate(q ? `/search?q=${encodeURIComponent(q)}` : '/search')
  }

  async function doLogout() {
    await logout()
    navigate('/login')
  }

  return (
    <>
      <header className="nav">
        <NavLink to="/" className="brand" title={`${BRAND_NAME} · ${BRAND_NAME_ZH}`}>
          <BrandLogo size={30} />
          <span className="brand-text">{BRAND_NAME}</span>
        </NavLink>
        <nav aria-label="主导航">
          {NAV_ENTRIES.map(e => (
            <NavLink key={e.to} to={e.to} end={e.to === '/'}>{e.label}</NavLink>
          ))}
        </nav>
        <form className="nav-search" onSubmit={submit}>
          <span className="nav-search-icon"><SearchIcon size={15} /></span>
          <input value={kw} onChange={e => setKw(e.target.value)}
                 placeholder="搜索番剧、漫画…" aria-label="全站搜索" />
          {kw ? (
            <button type="button" className="nav-search-clear" aria-label="清空搜索词"
                    onClick={() => setKw('')}>
              <CloseIcon size={13} />
            </button>
          ) : (
            <button type="button" className="nav-kbd-btn" title="快速搜索与跳转 (⌘K)"
                    aria-label="打开快速搜索面板" onClick={() => setCmdkOpen(true)}>
              ⌘K
            </button>
          )}
          <button type="submit" className="pill">搜索</button>
        </form>
        <span className="spacer" />

        <button type="button" className="nav-icon-btn nav-mobile-search" aria-label="站内搜索" title="站内搜索"
                onClick={() => setCmdkOpen(true)}>
          <SearchIcon size={16} />
        </button>

        <UserPopover me={me} theme={theme} onToggleTheme={toggleTheme} onLogout={logout} />
      </header>

      <CommandPalette
        open={cmdkOpen}
        onOpenChange={setCmdkOpen}
        me={me}
        theme={theme}
        onToggleTheme={toggleTheme}
      />

      <button type="button" className={'nav-fab' + (menuOpen ? ' open' : '')}
              aria-label="导航菜单" aria-expanded={menuOpen}
              onClick={() => setMenuOpen(v => !v)}>
        {menuOpen ? <CloseIcon size={20} /> : <MenuIcon size={20} />}
      </button>
      {menuOpen && (
        <div className="nav-overlay" onClick={() => setMenuOpen(false)}>
          <nav className="nav-mobile-menu" aria-label="移动端导航" onClick={e => e.stopPropagation()}>
            {NAV_ENTRIES.map(e => (
              <NavLink key={e.to} to={e.to} end={e.to === '/'}>{e.label}</NavLink>
            ))}
            <NavLink to="/search"><SearchIcon size={15} />全站搜索</NavLink>
            <div className="nav-mobile-sep" />
            {me ? (
              <>
                <NavLink to="/me"><BookmarkIcon size={15} />个人空间</NavLink>
                <NavLink to="/settings"><SettingsIcon size={15} />账号与设置</NavLink>
              </>
            ) : (
              <NavLink to="/login"><LogOutIcon size={15} />登录 / 注册</NavLink>
            )}
            <button type="button" onClick={toggleTheme}>
              {theme === 'dark' ? <SunIcon size={15} /> : <MoonIcon size={15} />}
              {theme === 'dark' ? '切换亮色主题' : '切换暗色主题'}
            </button>
            {me && (
              <button type="button" onClick={() => void doLogout()}>
                <LogOutIcon size={15} />退出登录
              </button>
            )}
          </nav>
        </div>
      )}
    </>
  )
}

export default function App() {
  const { me, loading } = useAuth()
  const location = useLocation()

  useEffect(() => {
    const titleFromQuery = new URLSearchParams(location.search).get('title')?.trim()
    if (titleFromQuery && /^\/manga-ext/.test(location.pathname)) {
      document.title = pageTitle(titleFromQuery)
      return
    }
    if (SELF_TITLED_ROUTES.test(location.pathname)) {
      return
    }
    const matched = PAGE_TITLES.find(([re]) => re.test(location.pathname))
    document.title = matched ? matched[1] : BRAND_TITLE_FULL
  }, [location.pathname, location.search])

  if (loading) {
    return <PageFallback />
  }

  return (
    <MotionConfig reducedMotion="user">
      <ToastProvider>
        <div className="app-shell">
          <Nav />
          <div className="app-main">
            <Suspense fallback={<PageFallback />}>
              <Routes>
          <Route path="/login" element={me ? <Navigate to="/" /> : <LoginPage />} />
          {/* 公开内容：访客与登录用户都可访问（访客只看 PUBLIC+PUBLISHED） */}
          <Route path="/" element={<Home />} />
          <Route path="/index" element={<CatalogIndex />} />
          <Route path="/anime" element={<LegacyAnimeRedirect />} />
          <Route path="/manga" element={<MangaIndex />} />
          <Route path="/manga-index" element={<Navigate to="/index?kind=manga" replace />} />
          <Route path="/manga/:id" element={<MangaDetailPage />} />
          <Route path="/manga-ext" element={<Navigate to="/manga" replace />} />
          <Route path="/manga-ext/read/:chapterId" element={<ExternalMangaReader />} />
          <Route path="/manga-ext/:mangaId" element={<ExternalMangaDetail />} />
          <Route path="/schedule" element={<Schedule />} />
          <Route path="/catalog/:id" element={<CatalogDetail />} />
          <Route path="/works/:id" element={<WorkDetail />} />
          <Route path="/read/:revisionId" element={<Reader />} />
          <Route path="/watch/:animationId" element={<Watch />} />
          <Route path="/search" element={<Search />} />
          <Route path="/browse/:itemId" element={<BrowseWatch />} />
          <Route path="/browse/:itemId/:episodeId" element={<BrowseWatch />} />
          {/* 私有页面：未登录原地展示「需要登录」，已登录兼容旧路由并跳至 /me */}
          <Route path="/me" element={<RequireAuth><Me /></RequireAuth>} />
          <Route path="/shelf" element={<RequireAuth><Navigate to="/me?tab=favorites" replace /></RequireAuth>} />
          <Route path="/history" element={<RequireAuth><Navigate to="/me?tab=history" replace /></RequireAuth>} />
          <Route path="/uploads" element={<Navigate to="/me" replace />} />
          <Route path="/settings" element={<RequireAuth><Settings /></RequireAuth>} />
          <Route path="/works/:workId/external" element={<RequireAuth><ExternalEpisodes /></RequireAuth>} />
          <Route path="/works/:workId/external/:episodeId" element={<RequireAuth><ExternalWatch /></RequireAuth>} />
        </Routes>
      </Suspense>
      </div>
      <Footer />
      </div>
    </ToastProvider>
    </MotionConfig>
  )
}

/** 兼容旧路由 /anime?... → /index?... */
function LegacyAnimeRedirect() {
  const location = useLocation()
  return <Navigate to={`/index${location.search}`} replace />
}

/** 私有页面守卫：未登录时原地展示登录提示（不跳转），登录后回到原页面。 */
function RequireAuth({ children }: { children: React.ReactNode }) {
  const { me } = useAuth()
  const location = useLocation()
  if (!me) {
    const next = encodeURIComponent(location.pathname + location.search)
    return (
      <main className="page">
        <div className="login-required">
          <h1>需要登录</h1>
          <p className="muted">该页面需要登录后才能访问（追更、收藏、观看/阅读记录与个人设置均为私有数据）。</p>
          <div className="login-required-actions">
            <NavLink to={`/login?next=${next}`} className="pill nav-login-btn">去登录</NavLink>
            <NavLink to="/" className="ghost pill">返回番剧</NavLink>
          </div>
        </div>
      </main>
    )
  }
  return <>{children}</>
}

/** 页脚（无广告 / 无第三方统计，自动贴底）。 */
function Footer() {
  return (
    <footer className="site-footer">
      <div className="footer-inner">
        <div className="footer-col footer-brand">
          <span className="brand">
            <BrandLogo size={26} />
            <span>{BRAND_NAME}</span>
          </span>
          <p className="muted">杰米猫 · 私人番剧与漫画空间 · 简洁无广告</p>
        </div>
        <div className="footer-col">
          <h3>发现内容</h3>
          <NavLink to="/">番剧</NavLink>
          <NavLink to="/manga">漫画</NavLink>
          <NavLink to="/index">番剧与漫画索引</NavLink>
          <NavLink to="/schedule">新番时刻表</NavLink>
        </div>
        <div className="footer-col">
          <h3>个人空间</h3>
          <NavLink to="/me?space=anime">动画空间</NavLink>
          <NavLink to="/me?space=manga">漫画空间</NavLink>
          <NavLink to="/me?tab=history">足迹历史</NavLink>
        </div>
        <div className="footer-col">
          <h3>账号与搜索</h3>
          <NavLink to="/search">全站搜索</NavLink>
          <NavLink to="/settings">账号与设置</NavLink>
        </div>
      </div>
    </footer>
  )
}

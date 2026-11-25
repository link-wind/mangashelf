import { useEffect, useRef, useState } from 'react'
import { NavLink, Navigate, Route, Routes, useNavigate, useLocation } from 'react-router-dom'
import { useAuth } from './auth'
import { ToastProvider } from './components/Toast'
import {
  ChevronDownIcon, CloseIcon, LogOutIcon, MenuIcon, MoonIcon,
  SearchIcon, SettingsIcon, SunIcon, UploadIcon,
} from './components/Icons'
import LoginPage from './pages/Login'
import Home from './pages/Home'
import AnimeIndex from './pages/AnimeIndex'
import Schedule from './pages/Schedule'
import CatalogDetail from './pages/CatalogDetail'
import History from './pages/History'
import MangaIndex from './pages/MangaIndex'
import MangaCatalog from './pages/MangaCatalog'
import MangaDetailPage from './pages/MangaDetail'
import ExternalManga from './pages/ExternalManga'
import ExternalMangaDetail from './pages/ExternalMangaDetail'
import ExternalMangaReader from './pages/ExternalMangaReader'
import WorkDetail from './pages/WorkDetail'
import Reader from './pages/Reader'
import Watch from './pages/Watch'
import MyUploads from './pages/MyUploads'
import Shelf from './pages/Shelf'
import Search from './pages/Search'
import Settings from './pages/Settings'
import ExternalEpisodes from './pages/ExternalEpisodes'
import ExternalWatch from './pages/ExternalWatch'
import BrowseWatch from './pages/BrowseWatch'

const NAV_ENTRIES = [
  { to: '/', label: '首页', private: false },
  { to: '/manga', label: '漫画', private: false },
  { to: '/manga-index', label: '漫画索引', private: false },
  { to: '/anime', label: '番剧索引', private: false },
  { to: '/schedule', label: '新番时刻表', private: false },
  { to: '/shelf', label: '我的收藏', private: true },
  { to: '/history', label: '观看历史', private: true },
]

const PAGE_TITLES: Array<[RegExp, string]> = [
  [/^\/$/, '首页 · MangaShelf'],
  [/^\/login$/, '登录 · MangaShelf'],
  [/^\/manga$/, '漫画专区 · MangaShelf'],
  [/^\/manga-index$/, '漫画索引 · MangaShelf'],
  [/^\/manga\//, '漫画详情 · MangaShelf'],
  [/^\/manga-ext\/read\//, '阅读中 · MangaShelf'],
  [/^\/manga-ext/, '漫画详情 · MangaShelf'],
  [/^\/anime$/, '番剧索引 · MangaShelf'],
  [/^\/schedule$/, '新番时刻表 · MangaShelf'],
  [/^\/shelf$/, '我的收藏 · MangaShelf'],
  [/^\/history$/, '观看历史 · MangaShelf'],
  [/^\/search$/, '搜索 · MangaShelf'],
  [/^\/uploads$/, '我的素材 · MangaShelf'],
  [/^\/settings$/, '设置 · MangaShelf'],
  [/^\/read\//, '阅读中 · MangaShelf'],
  [/^\/(watch|browse)\//, '播放中 · MangaShelf'],
  [/^\/catalog\//, '番剧详情 · MangaShelf'],
  [/^\/works\//, '作品详情 · MangaShelf'],
]

function currentTheme(): 'dark' | 'light' {
  return document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light'
}

function Nav() {
  const { me, logout } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [kw, setKw] = useState('')
  const [theme, setTheme] = useState<'dark' | 'light'>(currentTheme)
  const [menuOpen, setMenuOpen] = useState(false)
  const [userOpen, setUserOpen] = useState(false)
  const userWrapRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    setMenuOpen(false)
    setUserOpen(false)
  }, [location.pathname])

  useEffect(() => {
    if (!userOpen) return
    function onDocClick(e: MouseEvent) {
      if (userWrapRef.current && !userWrapRef.current.contains(e.target as Node)) {
        setUserOpen(false)
      }
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') setUserOpen(false)
    }
    document.addEventListener('mousedown', onDocClick)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDocClick)
      document.removeEventListener('keydown', onKey)
    }
  }, [userOpen])

  // 访客也可浏览公开内容（DESIGN 3.1/3.2）；不再整体隐藏顶栏。

  function toggleTheme() {
    const next = currentTheme() === 'dark' ? 'light' : 'dark'
    document.documentElement.dataset.theme = next
    localStorage.setItem('ms-theme', next)
    setTheme(next)
  }

  function submit(e: React.FormEvent) {
    e.preventDefault()
    const q = kw.trim()
    navigate(q ? `/search?q=${encodeURIComponent(q)}` : '/search')
  }

  async function doLogout() {
    await logout()
    navigate('/login')
  }

  const avatarChar = (me?.login || 'U').slice(0, 1).toUpperCase()
  const visibleNav = NAV_ENTRIES.filter(e => !e.private || me)

  return (
    <>
      <header className="nav">
        <NavLink to="/" className="brand">MangaShelf</NavLink>
        <nav aria-label="主导航">
          {visibleNav.map(e => (
            <NavLink key={e.to} to={e.to} end={e.to === '/'}>{e.label}</NavLink>
          ))}
        </nav>
        <form className="nav-search" onSubmit={submit}>
          <span className="nav-search-icon"><SearchIcon size={15} /></span>
          <input value={kw} onChange={e => setKw(e.target.value)}
                 placeholder="搜索番剧、漫画、对白…" aria-label="全站搜索" />
          <button type="submit" className="pill">搜索</button>
        </form>
        <span className="spacer" />

        {/* 手机端（≤720px）顶栏快捷搜索入口，避免次级按钮与悬浮菜单重复 */}
        <NavLink to="/search" className="nav-icon-btn nav-mobile-search" aria-label="站内搜索" title="站内搜索">
          <SearchIcon size={16} />
        </NavLink>

        <div className="nav-actions">
          <button type="button" className="nav-icon-btn" onClick={toggleTheme}
                  aria-label="切换明暗主题" title={theme === 'dark' ? '切换到亮色主题' : '切换到暗色主题'}>
            {theme === 'dark' ? <SunIcon size={16} /> : <MoonIcon size={16} />}
          </button>

          {me ? (
            <div className="user-menu-wrap" ref={userWrapRef}>
              <button type="button" className="user-trigger" aria-expanded={userOpen}
                      onClick={() => setUserOpen(v => !v)}>
                <span className="user-avatar">{avatarChar}</span>
                <span className="user-name">{me.login}</span>
                <ChevronDownIcon size={13} />
              </button>
              {userOpen && (
                <div className="user-menu" role="menu">
                  <div className="user-menu-head">
                    <strong>{me.login}</strong>
                    <span>{me.role === 'ADMIN' ? '站点管理员' : '登录用户'}</span>
                  </div>
                  <NavLink to="/uploads" role="menuitem"><UploadIcon />我的素材</NavLink>
                  <NavLink to="/settings" role="menuitem"><SettingsIcon />设置与令牌</NavLink>
                  <button type="button" className="danger" role="menuitem" onClick={() => void doLogout()}>
                    <LogOutIcon />退出登录
                  </button>
                </div>
              )}
            </div>
          ) : (
            <NavLink to="/login" className="pill nav-login-btn">登录</NavLink>
          )}
        </div>
      </header>

      {/* 手机端液态玻璃悬浮导航（≤720px）：放在 header 外避免 backdrop-filter 改变 fixed 包含块 */}
      <button type="button" className={'nav-fab' + (menuOpen ? ' open' : '')}
              aria-label="导航菜单" aria-expanded={menuOpen}
              onClick={() => setMenuOpen(v => !v)}>
        {menuOpen ? <CloseIcon size={20} /> : <MenuIcon size={20} />}
      </button>
      {menuOpen && (
        <div className="nav-overlay" onClick={() => setMenuOpen(false)}>
          <nav className="nav-mobile-menu" aria-label="移动端导航" onClick={e => e.stopPropagation()}>
            {visibleNav.map(e => (
              <NavLink key={e.to} to={e.to} end={e.to === '/'}>{e.label}</NavLink>
            ))}
            <NavLink to="/search"><SearchIcon size={15} />站内搜索</NavLink>
            <div className="nav-mobile-sep" />
            {me ? (
              <>
                <NavLink to="/uploads"><UploadIcon size={15} />我的素材</NavLink>
                <NavLink to="/settings"><SettingsIcon size={15} />设置与令牌</NavLink>
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
    const matched = PAGE_TITLES.find(([re]) => re.test(location.pathname))
    document.title = matched ? matched[1] : 'MangaShelf · 漫画与动画书架'
  }, [location.pathname])

  if (loading) {
    return (
      <div className="page-center">
        <div className="row-inline">
          <div className="player-spinner" style={{ width: 22, height: 22, margin: 0, borderColor: 'var(--border)', borderTopColor: 'var(--accent)' }} />
          <span>加载中…</span>
        </div>
      </div>
    )
  }

  return (
    <ToastProvider>
      <Nav />
      <Routes>
        <Route path="/login" element={me ? <Navigate to="/" /> : <LoginPage />} />
        {/* 公开内容：访客与登录用户都可访问（访客只看 PUBLIC+PUBLISHED） */}
        <Route path="/" element={<Home />} />
        <Route path="/anime" element={<AnimeIndex />} />
        <Route path="/manga" element={<MangaIndex />} />
        <Route path="/manga-index" element={<MangaCatalog />} />
        <Route path="/manga/:id" element={<MangaDetailPage />} />
        <Route path="/manga-ext" element={<ExternalManga />} />
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
        {/* 私有页面：需登录，未登录跳转登录页并记住来源 */}
        <Route path="/history" element={<RequireAuth><History /></RequireAuth>} />
        <Route path="/uploads" element={<RequireAuth><MyUploads /></RequireAuth>} />
        <Route path="/settings" element={<RequireAuth><Settings /></RequireAuth>} />
        <Route path="/works/:workId/external" element={<RequireAuth><ExternalEpisodes /></RequireAuth>} />
        <Route path="/works/:workId/external/:episodeId" element={<RequireAuth><ExternalWatch /></RequireAuth>} />
        <Route path="/shelf" element={<RequireAuth><Shelf /></RequireAuth>} />
      </Routes>
      <Footer />
    </ToastProvider>
  )
}

/** 私有页面守卫：未登录跳转登录页，登录后回到原页面。 */
function RequireAuth({ children }: { children: React.ReactNode }) {
  const { me } = useAuth()
  const location = useLocation()
  if (!me) {
    return <Navigate to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} replace />
  }
  return <>{children}</>
}

/** 页脚（gugu 式分组链接；无广告 / 无第三方统计）。 */
function Footer() {
  return (
    <footer className="site-footer">
      <div className="footer-inner">
        <div className="footer-col footer-brand">
          <span className="brand">MangaShelf</span>
          <p className="muted">个人自托管 · 无广告 · 视频由浏览器直连来源</p>
        </div>
        <div className="footer-col">
          <h3>发现内容</h3>
          <NavLink to="/anime">番剧索引</NavLink>
          <NavLink to="/manga-index">漫画索引</NavLink>
          <NavLink to="/schedule">新番时刻表</NavLink>
          <NavLink to="/search">站内搜索</NavLink>
        </div>
        <div className="footer-col">
          <h3>我的书架</h3>
          <NavLink to="/shelf">我的收藏</NavLink>
          <NavLink to="/history">观看历史</NavLink>
          <NavLink to="/uploads">我的素材</NavLink>
        </div>
        <div className="footer-col">
          <h3>账号服务</h3>
          <NavLink to="/settings">设置与令牌</NavLink>
        </div>
      </div>
    </footer>
  )
}

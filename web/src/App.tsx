import { useEffect, useState } from 'react'
import { NavLink, Navigate, Route, Routes, useNavigate } from 'react-router-dom'
import { useAuth } from './auth'
import LoginPage from './pages/Login'
import Home from './pages/Home'
import Library from './pages/Library'
import AnimeIndex from './pages/AnimeIndex'
import Schedule from './pages/Schedule'
import CatalogDetail from './pages/CatalogDetail'
import History from './pages/History'
import WorkDetail from './pages/WorkDetail'
import Reader from './pages/Reader'
import Watch from './pages/Watch'
import MyUploads from './pages/MyUploads'
import Shelf from './pages/Shelf'
import Search from './pages/Search'
import Settings from './pages/Settings'
import ExternalEpisodes from './pages/ExternalEpisodes'
import ExternalWatch from './pages/ExternalWatch'

/** 明暗主题切换（M12）：存 localStorage，根节点 data-theme 驱动 CSS 变量。 */
function toggleTheme() {
  const root = document.documentElement
  const next = root.dataset.theme === 'dark' ? 'light' : 'dark'
  root.dataset.theme = next
  localStorage.setItem('ms-theme', next)
}

function Nav() {
  const { me, logout } = useAuth()
  const navigate = useNavigate()
  const [kw, setKw] = useState('')
  if (!me) return null
  function submit(e: React.FormEvent) {
    e.preventDefault()
    const q = kw.trim()
    navigate(q ? `/search?q=${encodeURIComponent(q)}` : '/search')
  }
  return (
    <header className="nav">
      <NavLink to="/" className="brand">MangaShelf</NavLink>
      <nav>
        <NavLink to="/" end>首页</NavLink>
        <NavLink to="/anime">番剧索引</NavLink>
        <NavLink to="/schedule">新番时刻表</NavLink>
        <NavLink to="/shelf">我的书架</NavLink>
        <NavLink to="/uploads">我的素材</NavLink>
        <NavLink to="/history">观看历史</NavLink>
      </nav>
      <form className="nav-search" onSubmit={submit}>
        <input value={kw} onChange={e => setKw(e.target.value)}
               placeholder="搜索番剧、标题、别名…" aria-label="全站搜索" />
        <button type="submit" className="pill">搜索</button>
      </form>
      <span className="spacer" />
      <span className="who">{me.login}（{me.role === 'ADMIN' ? '管理员' : '用户'}）</span>
      <button className="ghost pill mini" onClick={toggleTheme} title="切换明暗主题">◐</button>
      <NavLink to="/settings" className="btn ghost pill mini" style={{ padding: '4px 12px' }}>设置</NavLink>
      <button className="pill mini" style={{ padding: '5px 14px' }}
              onClick={async () => { await logout(); navigate('/login') }}>退出</button>
    </header>
  )
}

export default function App() {
  const { me, loading } = useAuth()
  useEffect(() => {
    const saved = localStorage.getItem('ms-theme')
    if (saved) document.documentElement.dataset.theme = saved
  }, [])
  if (loading) return <div className="page-center">加载中…</div>
  return (
    <>
      <Nav />
      <Routes>
        <Route path="/login" element={me ? <Navigate to="/" /> : <LoginPage />} />
        <Route path="/" element={me ? <Home /> : <Navigate to="/login" />} />
        <Route path="/anime" element={me ? <AnimeIndex /> : <Navigate to="/login" />} />
        <Route path="/schedule" element={me ? <Schedule /> : <Navigate to="/login" />} />
        <Route path="/catalog/:id" element={me ? <CatalogDetail /> : <Navigate to="/login" />} />
        <Route path="/history" element={me ? <History /> : <Navigate to="/login" />} />
        <Route path="/library" element={me ? <Library /> : <Navigate to="/login" />} />
        <Route path="/works/:id" element={me ? <WorkDetail /> : <Navigate to="/login" />} />
        <Route path="/read/:revisionId" element={me ? <Reader /> : <Navigate to="/login" />} />
        <Route path="/watch/:animationId" element={me ? <Watch /> : <Navigate to="/login" />} />
        <Route path="/uploads" element={me ? <MyUploads /> : <Navigate to="/login" />} />
        <Route path="/settings" element={me ? <Settings /> : <Navigate to="/login" />} />
        <Route path="/search" element={me ? <Search /> : <Navigate to="/login" />} />
        <Route path="/works/:workId/external" element={me ? <ExternalEpisodes /> : <Navigate to="/login" />} />
        <Route path="/works/:workId/external/:episodeId" element={me ? <ExternalWatch /> : <Navigate to="/login" />} />
        <Route path="/shelf" element={me ? <Shelf /> : <Navigate to="/login" />} />
      </Routes>
      {me && <Footer />}
    </>
  )
}

/** 页脚（M14，gugu 式分组链接；无广告 / 无第三方统计）。 */
function Footer() {
  return (
    <footer className="site-footer">
      <div className="footer-inner">
        <div className="footer-col footer-brand">
          <span className="brand">MangaShelf</span>
          <p className="muted">个人自托管 · 无广告 · 视频由浏览器直连来源</p>
        </div>
        <div className="footer-col">
          <h3>发现番剧</h3>
          <NavLink to="/anime">番剧索引</NavLink>
          <NavLink to="/schedule">新番时刻表</NavLink>
          <NavLink to="/search">站内搜索</NavLink>
        </div>
        <div className="footer-col">
          <h3>我的追番</h3>
          <NavLink to="/shelf">我的书架</NavLink>
          <NavLink to="/history">观看历史</NavLink>
          <NavLink to="/uploads">我的素材</NavLink>
        </div>
        <div className="footer-col">
          <h3>账号服务</h3>
          <NavLink to="/settings">设置与令牌</NavLink>
          <NavLink to="/library">旧版书库</NavLink>
        </div>
      </div>
    </footer>
  )
}

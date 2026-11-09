import { NavLink, Navigate, Route, Routes, useNavigate } from 'react-router-dom'
import { useAuth } from './auth'
import LoginPage from './pages/Login'
import Home from './pages/Home'
import Library from './pages/Library'
import AnimeIndex from './pages/AnimeIndex'
import Schedule from './pages/Schedule'
import WorkDetail from './pages/WorkDetail'
import Reader from './pages/Reader'
import Watch from './pages/Watch'
import MyUploads from './pages/MyUploads'
import Shelf from './pages/Shelf'
import Search from './pages/Search'
import Settings from './pages/Settings'
import ExternalEpisodes from './pages/ExternalEpisodes'
import ExternalWatch from './pages/ExternalWatch'

function Nav() {
  const { me, logout } = useAuth()
  const navigate = useNavigate()
  if (!me) return null
  return (
    <header className="nav">
      <span className="brand">MangaShelf</span>
      <nav>
        <NavLink to="/">首页</NavLink>
        <NavLink to="/anime">番剧索引</NavLink>
        <NavLink to="/schedule">新番时刻表</NavLink>
        <NavLink to="/shelf">我的书架</NavLink>
        <NavLink to="/uploads">我的素材</NavLink>
        <NavLink to="/search">搜索</NavLink>
      </nav>
      <span className="spacer" />
      <span className="who">{me.login}（{me.role === 'ADMIN' ? '管理员' : '用户'}）</span>
      <NavLink to="/settings">设置</NavLink>
      <button onClick={async () => { await logout(); navigate('/login') }}>退出</button>
    </header>
  )
}

export default function App() {
  const { me, loading } = useAuth()
  if (loading) return <div className="page-center">加载中…</div>
  return (
    <>
      <Nav />
      <Routes>
        <Route path="/login" element={me ? <Navigate to="/" /> : <LoginPage />} />
        <Route path="/" element={me ? <Home /> : <Navigate to="/login" />} />
        <Route path="/anime" element={me ? <AnimeIndex /> : <Navigate to="/login" />} />
        <Route path="/schedule" element={me ? <Schedule /> : <Navigate to="/login" />} />
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
    </>
  )
}

import { NavLink, Navigate, Route, Routes, useNavigate } from 'react-router-dom'
import { useAuth } from './auth'
import LoginPage from './pages/Login'
import Library from './pages/Library'
import WorkDetail from './pages/WorkDetail'
import Reader from './pages/Reader'
import MyUploads from './pages/MyUploads'
import Shelf from './pages/Shelf'

function Nav() {
  const { me, logout } = useAuth()
  const navigate = useNavigate()
  if (!me) return null
  return (
    <header className="nav">
      <span className="brand">MangaShelf</span>
      <nav>
        <NavLink to="/">书库</NavLink>
        <NavLink to="/shelf">我的书架</NavLink>
        <NavLink to="/uploads">我的素材</NavLink>
      </nav>
      <span className="spacer" />
      <span className="who">{me.login}（{me.role === 'ADMIN' ? '管理员' : '用户'}）</span>
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
        <Route path="/" element={me ? <Library /> : <Navigate to="/login" />} />
        <Route path="/works/:id" element={me ? <WorkDetail /> : <Navigate to="/login" />} />
        <Route path="/read/:revisionId" element={me ? <Reader /> : <Navigate to="/login" />} />
        <Route path="/uploads" element={me ? <MyUploads /> : <Navigate to="/login" />} />
        <Route path="/shelf" element={me ? <Shelf /> : <Navigate to="/login" />} />
      </Routes>
    </>
  )
}

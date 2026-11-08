import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api'
import { useAuth } from '../auth'

export default function LoginPage() {
  const [login, setLogin] = useState('')
  const [password, setPassword] = useState('')
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [error, setError] = useState('')
  const { refresh } = useAuth()
  const navigate = useNavigate()

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    try {
      if (mode === 'register') {
        await api.post('/api/v1/auth/register', { login, password })
      }
      await api.post('/api/v1/auth/login', { login, password })
      await refresh()
      navigate('/')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '网络错误')
    }
  }

  return (
    <div className="page-center">
      <form className="card login" onSubmit={submit}>
        <h1>MangaShelf</h1>
        <p className="muted">漫画阅读与动画内容平台</p>
        <label>登录名
          <input value={login} onChange={e => setLogin(e.target.value)} pattern="[a-zA-Z0-9_-]{3,32}"
                 title="3-32 位字母数字下划线连字符" required />
        </label>
        <label>密码
          <input type="password" value={password} onChange={e => setPassword(e.target.value)}
                 minLength={8} required />
        </label>
        {error && <p className="error">{error}</p>}
        <button type="submit">{mode === 'login' ? '登录' : '注册并登录'}</button>
        <button type="button" className="ghost"
                onClick={() => setMode(mode === 'login' ? 'register' : 'login')}>
          {mode === 'login' ? '没有账号？注册' : '已有账号？登录'}
        </button>
      </form>
    </div>
  )
}

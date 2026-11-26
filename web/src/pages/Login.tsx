import { motion } from 'motion/react'
import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { api, ApiError } from '../api'
import { useAuth } from '../auth'
import { EyeIcon } from '../components/Icons'
import { useToast } from '../components/Toast'
import BrandLogo from '../components/ui/BrandLogo'
import SegmentedTabs from '../components/ui/SegmentedTabs'

export default function LoginPage() {
  const [login, setLogin] = useState('')
  const [password, setPassword] = useState('')
  const [showPwd, setShowPwd] = useState(false)
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const { refresh } = useAuth()
  const { showToast } = useToast()
  const navigate = useNavigate()
  const [params] = useSearchParams()

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (busy) return
    setError('')
    setBusy(true)
    try {
      if (mode === 'register') {
        await api.post('/api/v1/auth/register', { login, password })
      }
      await api.post('/api/v1/auth/login', { login, password })
      await refresh()
      showToast(mode === 'register' ? `欢迎加入，${login}` : `欢迎回来，${login}`, 'success')
      // 登录后回到被拦截的原始页面（仅接受站内相对路径，防开放重定向）
      const next = params.get('next')
      const safe = next && next.startsWith('/') && !next.startsWith('//') ? next : '/'
      navigate(safe)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '网络错误')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login-page">
      <motion.form
        className="login-glass"
        onSubmit={submit}
        initial={{ opacity: 0, y: 12, scale: 0.985 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        transition={{ duration: 0.22, ease: [0.22, 1, 0.36, 1] }}
      >
        <div className="login-brand">
          <BrandLogo size={42} />
          <div>
            <h1>MangaShelf</h1>
            <p className="login-sub">番剧追更与跨源漫画私人空间</p>
          </div>
        </div>

        <SegmentedTabs
          layoutId="login-mode-tabs"
          ariaLabel="登录或注册"
          value={mode}
          onChange={nextMode => { setMode(nextMode); setError('') }}
          items={[
            { value: 'login', label: '登录账号' },
            { value: 'register', label: '注册新账号' },
          ]}
        />

        <label>
          登录名
          <input value={login} onChange={e => setLogin(e.target.value)}
                 pattern="[a-zA-Z0-9_\-]{3,32}" placeholder="3-32 位字母、数字、下划线或连字符"
                 autoComplete="username"
                 title="3-32 位字母数字下划线连字符" required />
        </label>

        <label>
          密码
          <div className="input-wrap">
            <input type={showPwd ? 'text' : 'password'} value={password}
                   onChange={e => setPassword(e.target.value)}
                   placeholder="至少 8 位密码"
                   autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
                   minLength={8} required />
            <button type="button" className="input-eye"
                    aria-label={showPwd ? '隐藏密码' : '显示密码'}
                    onClick={() => setShowPwd(v => !v)}>
              <EyeIcon off={showPwd} size={16} />
            </button>
          </div>
        </label>

        {error && <p className="error" style={{ margin: 0 }}>{error}</p>}

        <button type="submit" className="pill" disabled={busy} style={{ padding: '10px 16px', fontWeight: 600 }}>
          {busy ? '处理中…' : (mode === 'login' ? '登录' : '注册并登录')}
        </button>

        <div className="login-footer-row">
          <Link to="/" className="muted">← 返回首页继续浏览</Link>
        </div>
      </motion.form>
    </div>
  )
}

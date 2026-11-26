import { motion } from 'motion/react'
import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { api, ApiError } from '../api'
import { useAuth } from '../auth'
import { EyeIcon } from '../components/Icons'
import { useToast } from '../components/Toast'
import BrandLogo from '../components/ui/BrandLogo'
import SegmentedTabs from '../components/ui/SegmentedTabs'
import { BRAND_NAME, BRAND_TAGLINE } from '../lib/brand'

interface AuthConfig {
  emailVerificationRequired: boolean
  resendCooldownSeconds: number
}

export default function LoginPage() {
  const [login, setLogin] = useState('')
  const [email, setEmail] = useState('')
  const [verificationCode, setVerificationCode] = useState('')
  const [password, setPassword] = useState('')
  const [showPwd, setShowPwd] = useState(false)
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [emailRequired, setEmailRequired] = useState(false)
  const [cooldown, setCooldown] = useState(0)
  const [sendingCode, setSendingCode] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const { refresh } = useAuth()
  const { showToast } = useToast()
  const navigate = useNavigate()
  const [params] = useSearchParams()

  useEffect(() => {
    void api.get<AuthConfig>('/api/v1/auth/config')
      .then(cfg => setEmailRequired(Boolean(cfg.emailVerificationRequired)))
      .catch(() => setEmailRequired(false))
  }, [])

  useEffect(() => {
    if (cooldown <= 0) return
    const timer = window.setTimeout(() => setCooldown(c => Math.max(0, c - 1)), 1000)
    return () => window.clearTimeout(timer)
  }, [cooldown])

  async function sendCode() {
    if (sendingCode || cooldown > 0) return
    const trimmed = email.trim()
    if (!trimmed || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmed)) {
      setError('请先输入有效的电子邮箱地址')
      return
    }
    setError('')
    setSendingCode(true)
    try {
      const res = await api.post<{ sent: boolean; cooldownSeconds: number; expiresInMinutes: number }>(
        '/api/v1/auth/email-code',
        { email: trimmed },
      )
      setCooldown(res.cooldownSeconds || 60)
      showToast(`验证码已发送至邮箱，${res.expiresInMinutes || 10} 分钟内有效`, 'success')
    } catch (err) {
      if (err instanceof ApiError) {
        const retry = Number((err.details as { retryAfterSeconds?: number } | undefined)?.retryAfterSeconds)
        if (retry > 0) setCooldown(retry)
        setError(err.message)
      } else {
        setError('发送失败，请稍后重试')
      }
    } finally {
      setSendingCode(false)
    }
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (busy) return
    setError('')
    setBusy(true)
    try {
      if (mode === 'register') {
        await api.post('/api/v1/auth/register', {
          login: login.trim(),
          password,
          ...(emailRequired ? { email: email.trim(), verificationCode: verificationCode.trim() } : {}),
        })
      }
      await api.post('/api/v1/auth/login', { login: login.trim(), password })
      await refresh()
      showToast(mode === 'register' ? `欢迎加入，${login.trim()}` : '欢迎回来', 'success')
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
          <BrandLogo size={52} />
          <div>
            <h1>{BRAND_NAME}</h1>
            <p className="login-sub">{BRAND_TAGLINE}</p>
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

        {mode === 'login' ? (
          <label>
            用户名 / 邮箱
            <input value={login} onChange={e => setLogin(e.target.value)}
                   placeholder="输入用户名或已绑定邮箱"
                   autoComplete="username"
                   required />
          </label>
        ) : (
          <label>
            登录名
            <input value={login} onChange={e => setLogin(e.target.value)}
                   pattern="[a-zA-Z0-9_\-]{3,32}" placeholder="3-32 位字母、数字、下划线或连字符"
                   autoComplete="username"
                   title="3-32 位字母数字下划线连字符" required />
          </label>
        )}

        {mode === 'register' && emailRequired && (
          <>
            <label>
              电子邮箱
              <input type="email" value={email} onChange={e => setEmail(e.target.value)}
                     placeholder="用于接收 6 位验证码与邮箱登录"
                     autoComplete="email"
                     required />
            </label>

            <label>
              邮箱验证码
              <div className="login-code-row">
                <input
                  type="text"
                  inputMode="numeric"
                  maxLength={6}
                  pattern="[0-9]{6}"
                  className="login-code-input tabular-nums"
                  value={verificationCode}
                  onChange={e => setVerificationCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                  placeholder="6 位数字验证码"
                  autoComplete="one-time-code"
                  required
                />
                <button
                  type="button"
                  className="login-code-btn tabular-nums"
                  disabled={sendingCode || cooldown > 0 || !email.trim()}
                  onClick={() => void sendCode()}
                >
                  {sendingCode ? '发送中…' : (cooldown > 0 ? `${cooldown}s 后重发` : '获取验证码')}
                </button>
              </div>
            </label>
          </>
        )}

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

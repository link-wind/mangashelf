import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '../api'

interface TokenRow {
  id: string
  prefix: string
  scopes: string
  createdAt: string
  expiresAt: string | null
  revokedAt: string | null
}

const ALL_SCOPES = [
  { key: 'catalog:read', label: '读取目录（catalog:read）' },
  { key: 'materials:read', label: '导出漫画页面（materials:read）' },
  { key: 'search:read', label: '检索内容（search:read）' },
  { key: 'animations:write', label: '管理动画草稿（animations:write）' },
  { key: 'animations:publish', label: '发布成片（animations:publish）' },
]

/** 个人设置（DESIGN 4）：接入 Token 的创建（一次性展示）、摘要列表与撤销。 */
export default function Settings() {
  const [tokens, setTokens] = useState<TokenRow[]>([])
  const [selected, setSelected] = useState<Set<string>>(new Set(['catalog:read', 'materials:read', 'animations:write', 'animations:publish']))
  const [days, setDays] = useState('90')
  const [rawToken, setRawToken] = useState<string | null>(null)
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    try {
      setTokens(await api.get<TokenRow[]>('/api/v1/me/api-tokens'))
      setError('')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '加载失败')
    }
  }, [])
  useEffect(() => { void load() }, [load])

  function toggle(scope: string) {
    const next = new Set(selected)
    if (next.has(scope)) next.delete(scope)
    else next.add(scope)
    setSelected(next)
  }

  async function create() {
    try {
      const res = await api.post<{ token: TokenRow; rawToken: string }>('/api/v1/me/api-tokens', {
        scopes: [...selected], expiresInDays: Number(days) || 90,
      })
      setRawToken(res.rawToken)
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '创建失败')
    }
  }

  async function revoke(id: string) {
    try {
      await api.delete(`/api/v1/me/api-tokens/${id}`)
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '撤销失败')
    }
  }

  return (
    <main className="page">
      <h1>个人设置</h1>
      {error && <p className="error">{error}</p>}

      <h2>接入 Token（MangaWeaver 连接）</h2>
      <p className="muted">在 MangaWeaver 中配置平台地址与本 Token；原始值只在创建时展示一次。</p>
      <div className="card">
        <div className="row-inline">
          {ALL_SCOPES.map(s => (
            <label key={s.key} className="scope-item">
              <input type="checkbox" checked={selected.has(s.key)} onChange={() => toggle(s.key)} />
              {s.label}
            </label>
          ))}
        </div>
        <div className="row-inline" style={{ marginTop: 8 }}>
          有效期（天）<input type="number" min={1} style={{ width: 80 }} value={days}
                           onChange={e => setDays(e.target.value)} />
          <button onClick={() => void create()} disabled={selected.size === 0}>创建 Token</button>
        </div>
        {rawToken && (
          <div className="token-reveal">
            <p className="muted">请立即保存（仅此一次展示）：</p>
            <code>{rawToken}</code>
            <button className="ghost" onClick={() => { void navigator.clipboard.writeText(rawToken); setNotice('已复制') }}>复制</button>
          </div>
        )}
      </div>

      <ul className="chapters" style={{ marginTop: 16 }}>
        {tokens.map(t => (
          <li key={t.id}>
            <span>
              <code>{t.prefix}…</code> · {t.scopes}
              <br />
              <span className="muted">
                创建 {new Date(t.createdAt).toLocaleString()} ·
                过期 {t.expiresAt ? new Date(t.expiresAt).toLocaleDateString() : '—'}
                {t.revokedAt ? ' · 已撤销' : ''}
              </span>
            </span>
            {!t.revokedAt && <button className="ghost" onClick={() => void revoke(t.id)}>撤销</button>}
          </li>
        ))}
        {tokens.length === 0 && <li className="muted">还没有接入 Token。</li>}
      </ul>
    </main>
  )
}

function setNotice(msg: string) {
  // 简化提示：控制台输出（页面级提示由列表刷新体现）
  console.log(msg)
}

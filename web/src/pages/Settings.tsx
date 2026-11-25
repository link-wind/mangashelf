import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '../api'
import { useAuth } from '../auth'

interface TokenRow {
  id: string
  prefix: string
  scopes: string
  createdAt: string
  expiresAt: string | null
  revokedAt: string | null
}

interface SourceRow {
  id: string
  name: string
  baseUrl: string
  enabled: boolean
}

const ALL_SCOPES = [
  { key: 'catalog:read', label: '读取目录（catalog:read）' },
  { key: 'materials:read', label: '导出漫画页面（materials:read）' },
  { key: 'search:read', label: '检索内容（search:read）' },
  { key: 'animations:write', label: '管理动画草稿（animations:write）' },
  { key: 'animations:publish', label: '发布成片（animations:publish）' },
]

/** 个人设置（DESIGN 4）：接入 Token 的创建（一次性展示）、摘要列表与撤销；管理员规则源管理（M6）。 */
export default function Settings() {
  const { me } = useAuth()
  const [tokens, setTokens] = useState<TokenRow[]>([])
  const [selected, setSelected] = useState<Set<string>>(new Set(['catalog:read', 'materials:read', 'animations:write', 'animations:publish']))
  const [days, setDays] = useState('90')
  const [rawToken, setRawToken] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
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
      setCopied(false)
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
            <button className="ghost" onClick={() => {
              void navigator.clipboard.writeText(rawToken)
              setCopied(true)
            }}>{copied ? '已复制' : '复制'}</button>
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

      {me?.role === 'admin' && <ExternalSourceAdmin />}
    </main>
  )
}

/** 管理员规则源管理（M6）：导入规则 JSON、启停、删除。 */
function ExternalSourceAdmin() {
  const [sources, setSources] = useState<SourceRow[]>([])
  const [name, setName] = useState('')
  const [baseUrl, setBaseUrl] = useState('')
  const [rulesText, setRulesText] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    try {
      setSources(await api.get<SourceRow[]>('/api/v1/external-sources?enabledOnly=false'))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '加载规则源失败')
    }
  }, [])
  useEffect(() => { void load() }, [load])

  async function create() {
    setBusy(true); setError(''); setNotice('')
    let rules: unknown
    try {
      rules = JSON.parse(rulesText)
    } catch {
      setBusy(false); setError('规则不是合法 JSON'); return
    }
    try {
      await api.post('/api/v1/admin/external-sources', { name, baseUrl, rules, enabled: true })
      setName(''); setBaseUrl(''); setRulesText('')
      setNotice('规则源已导入')
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '导入失败')
    } finally { setBusy(false) }
  }

  async function toggle(s: SourceRow) {
    try {
      await api.post(`/api/v1/admin/external-sources/${s.id}/toggle?enabled=${!s.enabled}`)
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '操作失败')
    }
  }

  async function syncCatalog(s: SourceRow) {
    setBusy(true); setError(''); setNotice('')
    try {
      await api.post('/api/v1/admin/catalog-sync', {
        sourceId: s.id, mode: 'incremental', hours: 168, categories: [29, 30, 31, 32, 33],
      })
      setNotice(`已触发「${s.name}」最近 7 天番剧增量同步`)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '触发同步失败')
    } finally { setBusy(false) }
  }

  async function remove(s: SourceRow) {
    try {
      await api.delete(`/api/v1/admin/external-sources/${s.id}`)
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '删除失败')
    }
  }

  return (
    <div style={{ marginTop: 32 }}>
      <h2>外部动画规则源（管理员）</h2>
      <p className="muted">导入声明式规则（Kazumi 兼容 CSS 选择器子集 / JSONPath），作品详情页即可搜索绑定外部集。</p>
      {error && <p className="error">{error}</p>}
      {notice && <p className="muted">{notice}</p>}
      <BangumiSyncCard />

      <div className="card">
        <div className="stack">
          <input placeholder="规则源名称" style={{ width: 260 }} value={name} onChange={e => setName(e.target.value)} />
          <input placeholder="站点 Base URL（相对链接补全用）" style={{ width: 420 }} value={baseUrl}
                 onChange={e => setBaseUrl(e.target.value)} />
          <textarea placeholder='规则 JSON，如 {"searchMode":"html","searchUrl":"…/search?q={keyword}","searchList":".item","searchName":".title","searchUrlSelector":"a","episodeListSelector":".ep-list a"}'
                    rows={8} style={{ width: 640, fontFamily: 'monospace', fontSize: 12 }}
                    value={rulesText} onChange={e => setRulesText(e.target.value)} />
          <button onClick={() => void create()} disabled={busy || !name.trim() || !baseUrl.trim() || !rulesText.trim()}>
            导入规则源
          </button>
        </div>
      </div>

      <ul className="chapters" style={{ marginTop: 16 }}>
        {sources.map(s => (
          <li key={s.id}>
            <span>
              <strong>{s.name}</strong> <code className="muted">{s.baseUrl}</code>
              <br />
              <span className="muted">{s.enabled ? '已启用' : '已停用'}</span>
            </span>
            <span className="row-inline">
              {s.enabled && (
                <button onClick={() => void syncCatalog(s)} disabled={busy}>同步最新集</button>
              )}
              <button className="ghost" onClick={() => void toggle(s)}>{s.enabled ? '停用' : '启用'}</button>
              <button className="ghost" onClick={() => void remove(s)}>删除</button>
            </span>
          </li>
        ))}
        {sources.length === 0 && <li className="muted">还没有规则源。</li>}
      </ul>
    </div>
  )
}


/** Bangumi 元数据同步（M9）：拉取本季新番日历并桥接资源目录。 */
function BangumiSyncCard() {
  const [job, setJob] = useState<{ status: string; stage: string | null; errorMessage: string | null } | null>(null)
  const [busy, setBusy] = useState(false)
  const [enrichMsg, setEnrichMsg] = useState('')

  const load = useCallback(async () => {
    try { setJob(await api.get('/api/v1/admin/bangumi-sync')) } catch { /* 无任务记录 */ }
  }, [])
  useEffect(() => { void load() }, [load])

  async function trigger() {
    setBusy(true); setEnrichMsg('')
    try {
      await api.post('/api/v1/admin/bangumi-sync')
      await load()
    } catch { /* 卡片展示状态 */ } finally { setBusy(false) }
  }

  async function enrich() {
    setBusy(true); setEnrichMsg('正在通过 Bangumi v0 补齐本季题材、简介与首播年份…')
    try {
      const res = await api.post<{ checked: number; enriched: number; failed: number }>('/api/v1/admin/bangumi-enrich?limit=80')
      setEnrichMsg(`补齐完成：检查 ${res.checked} 部，更新 ${res.enriched} 部${res.failed > 0 ? `，失败 ${res.failed} 部` : ''}`)
    } catch (e) {
      setEnrichMsg(e instanceof Error ? `补齐失败：${e.message}` : '补齐失败')
    } finally { setBusy(false) }
  }

  return (
    <div className="card" style={{ marginBottom: 16 }}>
      <strong>Bangumi 本季新番与题材元数据</strong>
      <p className="muted" style={{ margin: '6px 0' }}>
        从 api.bgm.tv 拉取本季日历（封面/评分/放送星期）并补齐题材标签、首播年份与简介；
        最近一次：{job ? `${job.status}${job.errorMessage ? ' — ' + job.errorMessage : ''}` : '未同步'}
      </p>
      {enrichMsg && <p className="muted" style={{ margin: '4px 0 8px' }}>{enrichMsg}</p>}
      <div className="row-inline">
        <button onClick={() => void trigger()} disabled={busy}>
          {busy ? '处理中…' : '同步本季新番'}
        </button>
        <button className="ghost" onClick={() => void enrich()} disabled={busy}>
          补齐题材与年份
        </button>
      </div>
    </div>
  )
}

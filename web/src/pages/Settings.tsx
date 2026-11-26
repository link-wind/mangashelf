import {
  Check, Copy, Database, KeyRound, Moon, Plus, RefreshCw,
  Server, ShieldCheck, Sliders, Sparkles, Sun, Trash2,
} from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError } from '../api'
import { useAuth } from '../auth'
import { useToast } from '../components/Toast'
import EmptyState from '../components/ui/EmptyState'
import SegmentedTabs from '../components/ui/SegmentedTabs'
import Switch from '../components/ui/Switch'

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

type SettingsTab = 'preferences' | 'tokens' | 'admin'

const ALL_SCOPES = [
  { key: 'catalog:read', title: '读取目录', desc: '浏览作品、章节与目录索引' },
  { key: 'materials:read', title: '导出漫画页面', desc: '供 MangaWeaver 拉取高清页面素材' },
  { key: 'search:read', title: '检索内容', desc: '搜索标题与页内文本/台词' },
  { key: 'animations:write', title: '管理动画草稿', desc: '上传动画视频并绑定来源页' },
  { key: 'animations:publish', title: '发布成片', desc: '发布或下架已核验的动画版本' },
]

const EXPIRY_PRESETS = ['30', '90', '365']

/** 现代双栏账号与设置中心：偏好设置 + MangaWeaver API Token + 管理员维护中心（修复 ADMIN 大小写 Bug）。 */
export default function Settings() {
  const { me } = useAuth()
  const isAdmin = me?.role?.toUpperCase() === 'ADMIN'
  const [section, setSection] = useState<SettingsTab>('preferences')

  return (
    <main className="watch-page">
      <nav className="breadcrumb" aria-label="面包屑">
        <Link to="/me">个人空间</Link>
        <span>/</span>
        <span className="breadcrumb-cur">账号与设置</span>
      </nav>

      <div className="set-page-head">
        <div>
          <h1>账号与设置</h1>
          <p className="muted">管理界面外观、播放与阅读默认习惯、MangaWeaver 接入令牌{isAdmin ? '及站点数据维护' : ''}。</p>
        </div>
        <Link to="/me" className="btn ghost mini">← 返回个人空间</Link>
      </div>

      <div className="set-layout">
        <aside className="set-nav" aria-label="设置分区">
          <button
            type="button"
            className={'set-nav-item' + (section === 'preferences' ? ' is-active' : '')}
            onClick={() => setSection('preferences')}
          >
            <Sliders size={16} />
            <span>外观与偏好</span>
          </button>
          <button
            type="button"
            className={'set-nav-item' + (section === 'tokens' ? ' is-active' : '')}
            onClick={() => setSection('tokens')}
          >
            <KeyRound size={16} />
            <span>API 接入令牌</span>
          </button>
          {isAdmin && (
            <button
              type="button"
              className={'set-nav-item' + (section === 'admin' ? ' is-active' : '')}
              onClick={() => setSection('admin')}
            >
              <ShieldCheck size={16} />
              <span>站点管理中心</span>
            </button>
          )}
        </aside>

        <div className="set-content">
          {section === 'preferences' && <PreferencesSection />}
          {section === 'tokens' && <TokenSection />}
          {section === 'admin' && isAdmin && <ExternalSourceAdmin />}
        </div>
      </div>
    </main>
  )
}

function PreferencesSection() {
  const { showToast } = useToast()
  const [theme, setTheme] = useState<'dark' | 'light'>(
    () => (document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light'),
  )
  const [autoPlay, setAutoPlay] = useState(() => localStorage.getItem('ms-autoplay') !== 'off')
  const [danmaku, setDanmaku] = useState(() => localStorage.getItem('ms-danmaku') !== 'off')
  const [autoNextManga, setAutoNextManga] = useState(() => localStorage.getItem('ms-ext-manga-autonext') !== 'off')
  const [indexView, setIndexView] = useState<'grid' | 'list'>(
    () => (localStorage.getItem('ms-index-view') === 'list' ? 'list' : 'grid'),
  )

  function applyTheme(next: 'dark' | 'light') {
    document.documentElement.dataset.theme = next
    localStorage.setItem('ms-theme', next)
    setTheme(next)
    showToast(next === 'dark' ? '已切换为深色外观' : '已切换为浅色外观', 'info')
  }

  function toggleAutoPlay(v: boolean) {
    setAutoPlay(v)
    localStorage.setItem('ms-autoplay', v ? 'on' : 'off')
    showToast(v ? '已开启番剧自动连播' : '已关闭番剧自动连播', 'info')
  }

  function toggleDanmaku(v: boolean) {
    setDanmaku(v)
    localStorage.setItem('ms-danmaku', v ? 'on' : 'off')
    showToast(v ? '已开启默认弹幕显示' : '已关闭默认弹幕显示', 'info')
  }

  function toggleAutoNextManga(v: boolean) {
    setAutoNextManga(v)
    localStorage.setItem('ms-ext-manga-autonext', v ? 'on' : 'off')
    showToast(v ? '已开启漫画自动下一话' : '已关闭漫画自动下一话', 'info')
  }

  function changeIndexView(v: 'grid' | 'list') {
    setIndexView(v)
    localStorage.setItem('ms-index-view', v)
    showToast(`索引页默认视图已设为${v === 'grid' ? '海报网格' : '双栏列表'}`, 'info')
  }

  return (
    <div className="set-stack">
      <section className="set-card">
        <div className="set-card-head">
          <h2>界面外观</h2>
          <p className="muted">选择更适合当前环境光线的明暗配色方案，立即生效并保存至本机。</p>
        </div>
        <div className="set-theme-grid">
          <button
            type="button"
            className={'set-theme-tile' + (theme === 'dark' ? ' is-selected' : '')}
            onClick={() => applyTheme('dark')}
          >
            <div className="set-theme-preview is-dark" aria-hidden="true">
              <span className="stp-bar" />
              <span className="stp-cards"><i /><i /><i /></span>
            </div>
            <div className="set-theme-foot">
              <span className="row-inline"><Moon size={14} />深色影院模式</span>
              {theme === 'dark' && <Check size={15} className="is-cyan" />}
            </div>
          </button>
          <button
            type="button"
            className={'set-theme-tile' + (theme === 'light' ? ' is-selected' : '')}
            onClick={() => applyTheme('light')}
          >
            <div className="set-theme-preview is-light" aria-hidden="true">
              <span className="stp-bar" />
              <span className="stp-cards"><i /><i /><i /></span>
            </div>
            <div className="set-theme-foot">
              <span className="row-inline"><Sun size={14} />浅色清爽模式</span>
              {theme === 'light' && <Check size={15} className="is-cyan" />}
            </div>
          </button>
        </div>
      </section>

      <section className="set-card">
        <div className="set-card-head">
          <h2>播放与阅读默认习惯</h2>
          <p className="muted">统一控制动画播放器、漫画阅读器与索引页的默认交互行为。</p>
        </div>
        <div className="set-pref-list">
          <div className="set-pref-row">
            <div>
              <strong>动画播完自动连播下一集</strong>
              <p className="muted">单集播放结束时自动无缝衔接下一集。</p>
            </div>
            <Switch checked={autoPlay} onChange={toggleAutoPlay} ariaLabel="动画自动连播" />
          </div>
          <div className="set-pref-row">
            <div>
              <strong>默认显示站内弹幕</strong>
              <p className="muted">观看已绑定作品剧集时默认打开弹幕层。</p>
            </div>
            <Switch checked={danmaku} onChange={toggleDanmaku} ariaLabel="默认显示弹幕" />
          </div>
          <div className="set-pref-row">
            <div>
              <strong>漫画阅读器自动下一话</strong>
              <p className="muted">条漫滚动到底部或翻页越界时，自动衔接下一章节。</p>
            </div>
            <Switch checked={autoNextManga} onChange={toggleAutoNextManga} tone="rose" ariaLabel="漫画自动下一话" />
          </div>
          <div className="set-pref-row">
            <div>
              <strong>索引页默认展示视图</strong>
              <p className="muted">打开番剧与漫画索引时的默认排版方式。</p>
            </div>
            <SegmentedTabs
              layoutId="settings-index-view"
              size="sm"
              tone="neutral"
              value={indexView}
              onChange={changeIndexView}
              items={[
                { value: 'grid', label: '海报网格' },
                { value: 'list', label: '详情列表' },
              ]}
            />
          </div>
        </div>
      </section>
    </div>
  )
}

function TokenSection() {
  const { showToast } = useToast()
  const [tokens, setTokens] = useState<TokenRow[]>([])
  const [selected, setSelected] = useState<Set<string>>(
    new Set(['catalog:read', 'materials:read', 'animations:write', 'animations:publish']),
  )
  const [days, setDays] = useState('90')
  const [rawToken, setRawToken] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    try {
      setTokens(await api.get<TokenRow[]>('/api/v1/me/api-tokens'))
      setError('')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '加载令牌失败')
    }
  }, [])
  useEffect(() => { void load() }, [load])

  function toggleScope(scope: string) {
    const next = new Set(selected)
    if (next.has(scope)) next.delete(scope)
    else next.add(scope)
    setSelected(next)
  }

  function applyPreset(preset: 'readonly' | 'full') {
    if (preset === 'readonly') {
      setSelected(new Set(['catalog:read', 'materials:read', 'search:read']))
    } else {
      setSelected(new Set(ALL_SCOPES.map(s => s.key)))
    }
  }

  async function create() {
    setBusy(true)
    try {
      const res = await api.post<{ token: TokenRow; rawToken: string }>('/api/v1/me/api-tokens', {
        scopes: [...selected],
        expiresInDays: Math.max(1, Number(days) || 90),
      })
      setRawToken(res.rawToken)
      setCopied(false)
      showToast('API Token 创建成功，请立即复制保存', 'success')
      await load()
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : '创建失败'
      setError(msg)
      showToast(msg, 'error')
    } finally {
      setBusy(false)
    }
  }

  async function revoke(id: string) {
    try {
      await api.delete(`/api/v1/me/api-tokens/${id}`)
      showToast('令牌已撤销', 'info')
      await load()
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : '撤销失败'
      setError(msg)
      showToast(msg, 'error')
    }
  }

  function copyText(text: string, label: string) {
    void navigator.clipboard.writeText(text)
    showToast(`已复制${label}`, 'success')
  }

  const endpointUrl = typeof window !== 'undefined' ? window.location.origin : ''

  return (
    <div className="set-stack">
      {error && <p className="error">{error}</p>}

      <section className="set-card">
        <div className="set-card-head row-between">
          <div>
            <h2>创建 MangaWeaver 接入令牌</h2>
            <p className="muted">在桌面端配置站点地址与本令牌即可直连读写；原始令牌仅在创建时展示一次。</p>
          </div>
          <div className="row-inline">
            <button type="button" className="mini ghost" onClick={() => applyPreset('readonly')}>仅只读预设</button>
            <button type="button" className="mini ghost" onClick={() => applyPreset('full')}>全权限预设</button>
          </div>
        </div>

        <div className="set-endpoint-bar">
          <span className="muted">当前平台地址</span>
          <code>{endpointUrl}</code>
          <button type="button" className="mini ghost" onClick={() => copyText(endpointUrl, '平台地址')}>
            <Copy size={13} />复制地址
          </button>
        </div>

        <div className="set-scope-grid">
          {ALL_SCOPES.map(s => {
            const on = selected.has(s.key)
            return (
              <button
                key={s.key}
                type="button"
                className={'set-scope-tile' + (on ? ' is-selected' : '')}
                onClick={() => toggleScope(s.key)}
              >
                <div className="set-scope-top">
                  <strong>{s.title}</strong>
                  <span className={'set-scope-check' + (on ? ' is-on' : '')}>
                    {on && <Check size={12} />}
                  </span>
                </div>
                <span className="set-scope-desc">{s.desc}</span>
                <code className="set-scope-key">{s.key}</code>
              </button>
            )
          })}
        </div>

        <div className="set-token-foot">
          <div className="row-inline">
            <span className="muted" style={{ fontSize: 13 }}>有效期</span>
            {EXPIRY_PRESETS.map(p => (
              <button
                key={p}
                type="button"
                className={'set-exp-pill' + (days === p ? ' is-active' : '')}
                onClick={() => setDays(p)}
              >
                {p} 天
              </button>
            ))}
            <input
              type="text"
              inputMode="numeric"
              className="set-exp-input tabular-nums"
              value={days}
              onChange={e => setDays(e.target.value.replace(/\D/g, ''))}
              aria-label="自定义有效天数"
              placeholder="天数"
            />
          </div>
          <button
            type="button"
            className="pill"
            onClick={() => void create()}
            disabled={busy || selected.size === 0}
          >
            <Plus size={15} />
            {busy ? '生成中…' : '创建新令牌'}
          </button>
        </div>

        {rawToken && (
          <div className="set-token-reveal">
            <div className="row-between">
              <strong>请立即复制并妥善保存（离开页面后将不再展示）</strong>
              <button
                type="button"
                className="mini"
                onClick={() => { copyText(rawToken, 'API Token'); setCopied(true) }}
              >
                {copied ? <Check size={13} /> : <Copy size={13} />}
                {copied ? '已复制' : '复制令牌'}
              </button>
            </div>
            <code>{rawToken}</code>
          </div>
        )}
      </section>

      <section className="set-card">
        <div className="set-card-head">
          <h2>已签发的令牌 <span className="space-sec-count tabular-nums">{tokens.length}</span></h2>
        </div>
        {tokens.length === 0 ? (
          <EmptyState
            compact
            tone="neutral"
            icon={<KeyRound size={20} />}
            title="还没有接入令牌"
            description="在上方勾选所需权限并点击「创建新令牌」，即可通过 Bearer Token 接入 MangaWeaver 或自动化脚本。"
          />
        ) : (
          <div className="set-token-list">
            {tokens.map(t => {
              const revoked = Boolean(t.revokedAt)
              return (
                <div key={t.id} className={'set-token-row' + (revoked ? ' is-revoked' : '')}>
                  <div className="set-token-info">
                    <div className="row-inline">
                      <code className="set-token-prefix">{t.prefix}…</code>
                      <span className={'space-tag ' + (revoked ? 'is-watching' : 'is-done')}>
                        {revoked ? '已撤销' : '生效中'}
                      </span>
                    </div>
                    <div className="set-token-scopes">
                      {t.scopes.split(/[\s,]+/).filter(Boolean).map(sc => (
                        <span key={sc} className="set-mini-scope">{sc}</span>
                      ))}
                    </div>
                    <span className="muted tabular-nums" style={{ fontSize: 12 }}>
                      创建于 {new Date(t.createdAt).toLocaleString()} · 过期 {t.expiresAt ? new Date(t.expiresAt).toLocaleDateString() : '长期'}
                    </span>
                  </div>
                  {!revoked && (
                    <button type="button" className="mini ghost" onClick={() => void revoke(t.id)}>
                      <Trash2 size={13} />撤销
                    </button>
                  )}
                </div>
              )
            })}
          </div>
        )}
      </section>
    </div>
  )
}

/** 管理员数据维护中心：Bangumi 同步 + 高清横幅/封面补齐 + 外部规则源管理。 */
function ExternalSourceAdmin() {
  const { showToast } = useToast()
  const [sources, setSources] = useState<SourceRow[]>([])
  const [showImport, setShowImport] = useState(false)
  const [name, setName] = useState('')
  const [baseUrl, setBaseUrl] = useState('')
  const [rulesText, setRulesText] = useState('')
  const [error, setError] = useState('')
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
    setBusy(true); setError('')
    let rules: unknown
    try {
      rules = JSON.parse(rulesText)
    } catch {
      setBusy(false); setError('规则不是合法 JSON'); return
    }
    try {
      await api.post('/api/v1/admin/external-sources', { name, baseUrl, rules, enabled: true })
      setName(''); setBaseUrl(''); setRulesText(''); setShowImport(false)
      showToast('规则源已导入', 'success')
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '导入失败')
    } finally { setBusy(false) }
  }

  async function toggle(s: SourceRow) {
    try {
      await api.post(`/api/v1/admin/external-sources/${s.id}/toggle?enabled=${!s.enabled}`)
      showToast(s.enabled ? `已停用「${s.name}」` : `已启用「${s.name}」`, 'info')
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '操作失败')
    }
  }

  async function syncCatalog(s: SourceRow) {
    setBusy(true); setError('')
    try {
      await api.post('/api/v1/admin/catalog-sync', {
        sourceId: s.id, mode: 'incremental', hours: 168, categories: [29, 30, 31, 32, 33],
      })
      showToast(`已触发「${s.name}」近 7 天番剧增量同步`, 'success')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '触发同步失败')
    } finally { setBusy(false) }
  }

  async function remove(s: SourceRow) {
    try {
      await api.delete(`/api/v1/admin/external-sources/${s.id}`)
      showToast(`已删除规则源「${s.name}」`, 'info')
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '删除失败')
    }
  }

  return (
    <div className="set-stack">
      <BangumiSyncCard />

      <section className="set-card">
        <div className="set-card-head row-between">
          <div>
            <h2>外部动画规则源 <span className="space-sec-count tabular-nums">{sources.length}</span></h2>
            <p className="muted">管理声明式资源源（Kazumi 兼容子集 / MacCMS JSON），控制启停与定时增量同步。</p>
          </div>
          <button type="button" className="mini pill" onClick={() => setShowImport(v => !v)}>
            <Plus size={14} />{showImport ? '收起表单' : '导入规则源'}
          </button>
        </div>

        {error && <p className="error">{error}</p>}

        {showImport && (
          <div className="set-import-box">
            <div className="set-import-grid">
              <label>
                规则源名称
                <input placeholder="例如：量子资源站" value={name} onChange={e => setName(e.target.value)} />
              </label>
              <label>
                站点 Base URL
                <input placeholder="https://example.com" value={baseUrl} onChange={e => setBaseUrl(e.target.value)} />
              </label>
            </div>
            <label>
              规则 JSON
              <textarea
                placeholder='{"searchMode":"html","searchUrl":"…/search?q={keyword}"}'
                rows={6}
                style={{ fontFamily: 'monospace', fontSize: 12 }}
                value={rulesText}
                onChange={e => setRulesText(e.target.value)}
              />
            </label>
            <div className="row-inline">
              <button
                type="button"
                className="pill"
                onClick={() => void create()}
                disabled={busy || !name.trim() || !baseUrl.trim() || !rulesText.trim()}
              >
                确认导入
              </button>
              <button type="button" className="ghost pill" onClick={() => setShowImport(false)}>取消</button>
            </div>
          </div>
        )}

        {sources.length === 0 ? (
          <EmptyState
            compact
            tone="neutral"
            icon={<Server size={20} />}
            title="还没有配置外部规则源"
            description="点击右上角「导入规则源」添加番剧采集规则。"
          />
        ) : (
          <div className="set-token-list">
            {sources.map(s => (
              <div key={s.id} className="set-token-row">
                <div className="set-token-info">
                  <div className="row-inline">
                    <strong>{s.name}</strong>
                    <span className={'space-tag ' + (s.enabled ? 'is-done' : 'is-watching')}>
                      {s.enabled ? '已启用' : '已停用'}
                    </span>
                  </div>
                  <code className="muted" style={{ fontSize: 12 }}>{s.baseUrl}</code>
                </div>
                <div className="row-inline">
                  {s.enabled && (
                    <button type="button" className="mini ghost" onClick={() => void syncCatalog(s)} disabled={busy}>
                      <RefreshCw size={13} />增量同步
                    </button>
                  )}
                  <Switch checked={s.enabled} onChange={() => void toggle(s)} ariaLabel={`切换 ${s.name}`} />
                  <button type="button" className="space-row-del" title="删除规则源" onClick={() => void remove(s)}>
                    <Trash2 size={14} />
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  )
}

function BangumiSyncCard() {
  const { showToast } = useToast()
  const [job, setJob] = useState<{ status: string; stage: string | null; errorMessage: string | null } | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [statusMsg, setStatusMsg] = useState('')

  const load = useCallback(async () => {
    try { setJob(await api.get('/api/v1/admin/bangumi-sync')) } catch { /* 无任务记录 */ }
  }, [])
  useEffect(() => { void load() }, [load])

  async function triggerSync() {
    setBusy('sync'); setStatusMsg('')
    try {
      await api.post('/api/v1/admin/bangumi-sync')
      showToast('已入队本季新番同步任务', 'success')
      await load()
    } catch (e) {
      showToast(e instanceof Error ? e.message : '同步触发失败', 'error')
    } finally { setBusy(null) }
  }

  async function enrichMeta() {
    setBusy('meta'); setStatusMsg('正在通过 Bangumi v0 补齐本季题材、简介与首播时间…')
    try {
      const res = await api.post<{ checked: number; enriched: number; failed: number }>('/api/v1/admin/bangumi-enrich?limit=60')
      const msg = `元数据补齐完成：检查 ${res.checked} 部，更新 ${res.enriched} 部${res.failed > 0 ? `，失败 ${res.failed} 部` : ''}`
      setStatusMsg(msg)
      showToast(msg, 'success')
    } catch (e) {
      const msg = e instanceof Error ? `补齐失败：${e.message}` : '补齐失败'
      setStatusMsg(msg)
      showToast(msg, 'error')
    } finally { setBusy(null) }
  }

  async function enrichHd() {
    setBusy('hd'); setStatusMsg('正在通过 AniList 与 Bangumi 批量补齐 1900×400 宽幅横幅与热门动画高清封面（约需 30–60 秒）…')
    try {
      const res = await api.post<{
        bannersChecked: number; bannersFilled: number
        catalogChecked: number; catalogLinkedBangumi: number; catalogFilledHdCover: number
      }>('/api/v1/admin/anime-hd-enrich?banners=25&catalog=25')
      const msg = `高清补齐完成：横幅新增 ${res.bannersFilled}/${res.bannersChecked}，目录关联 Bangumi ${res.catalogLinkedBangumi} 部、AniList 高清封面 ${res.catalogFilledHdCover} 部`
      setStatusMsg(msg)
      showToast(msg, 'success')
    } catch (e) {
      const msg = e instanceof Error ? `高清补齐失败：${e.message}` : '高清补齐失败'
      setStatusMsg(msg)
      showToast(msg, 'error')
    } finally { setBusy(null) }
  }

  return (
    <section className="set-card">
      <div className="set-card-head">
        <h2>元数据与高清资源维护</h2>
        <p className="muted">
          管理 Bangumi 本季日历、题材标签、放送时间以及 AniList 宽幅横幅与高清封面回填。
          最近同步：{job ? `${job.status}${job.errorMessage ? ' — ' + job.errorMessage : ''}` : '暂无记录'}
        </p>
      </div>
      {statusMsg && <div className="set-status-note">{statusMsg}</div>}
      <div className="row-inline" style={{ gap: 10 }}>
        <button type="button" className="pill" onClick={() => void triggerSync()} disabled={busy !== null}>
          <RefreshCw size={14} />{busy === 'sync' ? '同步中…' : '同步本季新番'}
        </button>
        <button type="button" className="ghost pill" onClick={() => void enrichMeta()} disabled={busy !== null}>
          <Database size={14} />{busy === 'meta' ? '补齐中…' : '补齐题材与年份'}
        </button>
        <button type="button" className="ghost pill" onClick={() => void enrichHd()} disabled={busy !== null}>
          <Sparkles size={14} />{busy === 'hd' ? '补齐中…' : '批量补齐高清横幅与封面'}
        </button>
      </div>
    </section>
  )
}

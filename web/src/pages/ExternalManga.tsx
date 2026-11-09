import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api'
import { proxyImage } from '../externalManga'

interface ExtManga {
  id: string; title: string; altTitle: string; description: string
  coverUrl: string | null; status: string; year: string
  tags: string[]; langs: string[]
}

interface SearchItem {
  source: string; id: string; title: string; altTitle: string
  coverUrl: string | null; year: string | null
}

/** 外部漫画搜索（M16/M23）：MangaDex 与规则源（腾讯动漫）按源切换。 */
export default function ExternalManga() {
  const navigate = useNavigate()
  const [source, setSource] = useState('mangadex')
  const [q, setQ] = useState('')
  const [lang, setLang] = useState('zh')
  const [items, setItems] = useState<ExtManga[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [searched, setSearched] = useState(false)

  const run = useCallback(async (query: string, language: string, src: string) => {
    setLoading(true); setError('')
    try {
      if (src === 'mangadex') {
        const qs = new URLSearchParams({ lang: language, limit: '24' })
        if (query.trim()) qs.set('q', query.trim())
        setItems(await api.get<ExtManga[]>(`/api/v1/external-manga/search?${qs}`))
      } else {
        const qs = new URLSearchParams({ limit: '24' })
        if (query.trim()) qs.set('q', query.trim())
        const rows = await api.get<SearchItem[]>(`/api/v1/manga-sources/${src}/search?${qs}`)
        setItems(rows.map(r => ({
          id: r.id, title: r.title, altTitle: r.altTitle ?? '', description: '',
          coverUrl: r.coverUrl, status: '', year: r.year ?? '', tags: [], langs: ['中文'],
        })))
      }
      setSearched(true)
    } catch (e) {
      setError(e instanceof Error ? e.message : '搜索失败')
    } finally { setLoading(false) }
  }, [])

  useEffect(() => { void run('', lang, source) }, [lang, source, run])

  return (
    <main className="watch-page">
      <div className="watch-title"><h1>外部漫画</h1></div>
      <div className="watch-meta">
        <span>按数据源搜索：MangaDex（开放 API）/ 腾讯动漫（正版中文）</span>
        <Link to="/manga">← 本地漫画</Link>
      </div>

      <div className="row-inline" style={{ marginBottom: 14 }}>
        <input placeholder={source === 'mangadex' ? '搜索漫画标题（英文/日文原名更准）…' : '搜索漫画标题（中文）…'}
               className="search" style={{ width: 320 }}
               value={q} onChange={e => setQ(e.target.value)}
               onKeyDown={e => e.key === 'Enter' && void run(q, lang, source)} />
        <button onClick={() => void run(q, lang, source)} disabled={loading}>搜索</button>
        {(['mangadex', 'tencent'] as const).map(s => (
          <button key={s} className={source === s ? 'mini' : 'mini ghost'}
                  onClick={() => setSource(s)}>{s === 'mangadex' ? 'MangaDex' : '腾讯动漫'}</button>
        ))}
        {source === 'mangadex' && LANGS.map(l => (
          <button key={l.key} className={lang === l.key ? 'mini' : 'mini ghost'}
                  onClick={() => setLang(l.key)}>{l.label}</button>
        ))}
      </div>

      {error && <p className="error">{error}</p>}
      {loading && <p className="muted">加载中…</p>}
      {!loading && searched && items.length === 0 && (
        <p className="muted">
          {source === 'mangadex' ? '没有结果。中文条目较少时可切到 English 或「全部」。' : '没有结果。换个关键词试试。'}
        </p>
      )}

      <div className="card-grid">
        {items.map(m => (
          <button key={m.id} className="work-card ext-card"
                  onClick={() => navigate(`/manga-ext/${m.id}?source=${source}`)}>
            <div className="cover">
              {m.coverUrl
                ? <img src={proxyImage(m.coverUrl!)} alt={m.title} loading="lazy" decoding="async" />
                : <span className="cover-initial">{m.title.slice(0, 1)}</span>}
              {m.year && <span className="badge-anim">{m.year}</span>}
            </div>
            <div className="work-card-body">
              <div className="work-card-title clamp">{m.title}</div>
              <div className="work-card-meta">
                <span>{m.altTitle || (m.langs ?? []).slice(0, 3).join(' / ')}</span>
              </div>
            </div>
          </button>
        ))}
      </div>
    </main>
  )
}

const LANGS = [
  { key: 'zh', label: '中文' },
  { key: 'en', label: 'English' },
  { key: 'all', label: '全部' },
]

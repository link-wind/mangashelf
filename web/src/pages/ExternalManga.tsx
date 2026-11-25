import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api'
import CoverImage from '../components/CoverImage'

interface ExtManga {
  id: string; title: string; altTitle: string; description: string
  coverUrl: string | null; status: string; year: string
  tags: string[]; langs: string[]
}

interface SearchItem {
  source: string; id: string; title: string; altTitle: string
  coverUrl: string | null; year: string | null
}
interface SourceInfo {
  id: string; name: string; language: string; capabilities: string[]; note: string
}

/** 外部漫画搜索（M25）：仅中文数据源（MangaDex 等非中文源不再展示）。 */
export default function ExternalManga() {
  const navigate = useNavigate()
  const [sources, setSources] = useState<SourceInfo[]>([])
  const [source, setSource] = useState('')
  const [q, setQ] = useState('')
  const [items, setItems] = useState<ExtManga[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [searched, setSearched] = useState(false)

  const run = useCallback(async (query: string, src: string) => {
    if (!src) return
    setLoading(true); setError('')
    try {
      const qs = new URLSearchParams({ limit: '24' })
      if (query.trim()) qs.set('q', query.trim())
      const rows = await api.get<SearchItem[]>(`/api/v1/manga-sources/${src}/search?${qs}`)
      setItems(rows.map(r => ({
        id: r.id, title: r.title, altTitle: r.altTitle ?? '', description: '',
        coverUrl: r.coverUrl, status: '', year: r.year ?? '', tags: [], langs: ['中文'],
      })))
      setSearched(true)
    } catch (e) {
      setError(e instanceof Error ? e.message : '搜索失败')
    } finally { setLoading(false) }
  }, [])

  useEffect(() => {
    void (async () => {
      try {
        const list = (await api.get<SourceInfo[]>('/api/v1/manga-sources'))
          .filter(s => s.capabilities.includes('SEARCH') && s.language === 'zh')
        setSources(list)
        setSource(prev => (prev && list.some(s => s.id === prev))
          ? prev : (list.find(s => s.id === 'tencent')?.id ?? list[0]?.id ?? ''))
      } catch (e) {
        setError(e instanceof Error ? e.message : '数据源加载失败')
      }
    })()
  }, [])

  // 空关键词不自动请求（腾讯搜索接口不接受空词）；仅在切换源且已有关键词时重搜
  useEffect(() => {
    if (source && q.trim()) void run(q, source)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [source])

  return (
    <main className="watch-page">
      <div className="watch-title"><h1>外部漫画</h1></div>
      <div className="watch-meta">
        <span>按中文数据源搜索（腾讯动漫等正版源）</span>
        <Link to="/manga">← 漫画首页</Link>
      </div>

      <div className="row-inline" style={{ marginBottom: 14 }}>
        <input placeholder="搜索漫画标题（中文）…"
               className="search" style={{ width: 320 }}
               value={q} onChange={e => setQ(e.target.value)}
               onKeyDown={e => e.key === 'Enter' && void run(q, source)} />
        <button onClick={() => void run(q, source)} disabled={loading}>搜索</button>
        {sources.map(s => (
          <button key={s.id} className={source === s.id ? 'mini' : 'mini ghost'}
                  onClick={() => setSource(s.id)}>{s.name}</button>
        ))}
      </div>

      {error && <p className="error">{error}</p>}
      {loading && <p className="muted">加载中…</p>}
      {!loading && !searched && !error && (
        <p className="muted">输入漫画标题开始搜索（例如「航海王」「斗破苍穹」）。</p>
      )}
      {!loading && searched && items.length === 0 && (
        <p className="muted">没有结果。换个关键词试试。</p>
      )}

      <div className="card-grid">
        {items.map(m => (
          <button key={m.id} className="work-card ext-card"
                  onClick={() => navigate(`/manga-ext/${m.id}?source=${source}`)}>
            <div className="cover">
              <CoverImage src={m.coverUrl} alt={m.title} />
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

import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { proxyImage, onImgError } from '../externalManga'

interface SourceInfo {
  id: string; name: string; language: string
  capabilities: string[]; note: string
}
interface SearchItem {
  source: string; id: string; title: string; altTitle: string
  coverUrl: string | null; year: string | null
}

/**
 * 漫画索引（mobile-ui-index-and-reader）：参照番剧索引——按源 tab 切换 + 搜索 + 封面网格。
 * 数据来自各源实时 search，无统一资源目录；本地作品在 /manga 首页与收藏页，不在此混淆。
 */
export default function MangaCatalog() {
  const [sources, setSources] = useState<SourceInfo[]>([])
  const [source, setSource] = useState('')
  const [items, setItems] = useState<SearchItem[]>([])
  const [q, setQ] = useState('')
  const [activeQuery, setActiveQuery] = useState('')
  const [loading, setLoading] = useState(false)
  const [searched, setSearched] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    void api.get<SourceInfo[]>('/api/v1/manga-sources')
      .then(list => {
        const searchable = list.filter(s => s.capabilities.includes('SEARCH'))
        setSources(searchable)
        if (searchable.length > 0) setSource(prev => prev || searchable[0].id)
      })
      .catch(e => setError(e instanceof Error ? e.message : '源列表加载失败'))
  }, [])

  const load = useCallback(async (src: string, query: string) => {
    if (!src) return
    setLoading(true); setError(''); setSearched(true)
    try {
      const qs = new URLSearchParams({ limit: '24' })
      if (query) qs.set('q', query)
      const rows = await api.get<SearchItem[]>(`/api/v1/manga-sources/${src}/search?${qs}`)
      setItems(rows)
    } catch (e) {
      setItems([])
      setError(e instanceof Error ? e.message : '搜索失败')
    } finally { setLoading(false) }
  }, [])

  useEffect(() => {
    if (source) void load(source, '')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [source])

  function search() {
    setActiveQuery(q.trim())
    void load(source, q.trim())
  }

  const current = sources.find(s => s.id === source)

  return (
    <main className="page">
      <div className="row-between">
        <div>
          <h1>漫画索引</h1>
          <p className="muted">按数据源检索{current ? ` · ${current.name}` : ''}；本地作品在漫画首页与收藏页。</p>
        </div>
      </div>
      {error && <p className="error">{error}</p>}

      <div className="row-inline manga-index-bar">
        <input className="search" placeholder={`在${current?.name ?? '所选源'}搜索标题…`}
               value={q} onChange={e => setQ(e.target.value)}
               onKeyDown={e => { if (e.key === 'Enter') search() }} />
        <button onClick={search} disabled={loading || !source}>搜索</button>
        {sources.map(s => (
          <button key={s.id} className={source === s.id ? 'mini' : 'mini ghost'}
                  onClick={() => { setSource(s.id); setActiveQuery(''); setQ(''); setSearched(false) }}>
            {s.name}
          </button>
        ))}
      </div>

      {!loading && searched && items.length === 0 && !error && (
        <p className="muted">没有找到相关内容{activeQuery ? `（${activeQuery}）` : ''}。</p>
      )}
      {loading && <p className="muted">搜索中…</p>}

      <div className="card-grid">
        {items.map(r => (
          <Link key={`${r.source}-${r.id}`}
                to={`/manga-ext/${r.id}?${new URLSearchParams({ source: r.source, title: r.title })}`}
                className="work-card">
            <div className="cover">
              {r.coverUrl
                ? <img src={proxyImage(r.coverUrl)} alt={r.title} loading="lazy" decoding="async"
                       onError={onImgError} />
                : <span className="cover-initial">{r.title.slice(0, 1)}</span>}
              {r.year && <span className="badge-anim">{r.year}</span>}
            </div>
            <div className="work-card-body">
              <div className="work-card-title clamp">{r.title}</div>
              <div className="work-card-meta">
                <span className="clamp">{r.altTitle || r.source}</span>
              </div>
            </div>
          </Link>
        ))}
      </div>
    </main>
  )
}

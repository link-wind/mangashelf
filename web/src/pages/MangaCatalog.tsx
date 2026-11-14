import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'


interface SourceInfo {
  id: string; name: string; language: string
  capabilities: string[]; genres: string[]; note: string
}
interface SearchItem {
  source: string; id: string; title: string; altTitle: string
  coverUrl: string | null; year: string | null
}
interface CatalogEntry {
  source: string; sourceItemId: string; name: string; altTitle: string
  coverUrl: string | null; remarks: string | null; tags: string[]
  episodeCount: number | null
}
interface SourceInfo {
  id: string; name: string; language: string
  capabilities: string[]; genres: string[]; note: string
}
interface SearchItem {
  source: string; id: string; title: string; altTitle: string
  coverUrl: string | null; year: string | null
}
/** 库内目录条目 → 卡片（对齐原 SearchItem 渲染）。 */
function toCard(e: CatalogEntry): SearchItem {
  return { source: e.source, id: e.sourceItemId, title: e.name, altTitle: e.altTitle,
           coverUrl: e.coverUrl, year: e.episodeCount ? `${e.episodeCount} 集` : null }
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
  const [genre, setGenre] = useState('')
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

  // 主路径：读库（manga-catalog-sync）；「实时搜索」按钮走 per-source search 兜底
  const load = useCallback(async (src: string, query: string, gen: string, live: boolean) => {
    if (!src) return
    setLoading(true); setError(''); setSearched(true)
    try {
      if (live) {
        const qs = new URLSearchParams({ limit: '24' })
        if (query) qs.set('q', query)
        const rows = await api.get<SearchItem[]>(`/api/v1/manga-sources/${src}/search?${qs}`)
        setItems(rows)
        return
      }
      const qs = new URLSearchParams({ source: src, limit: '24' })
      if (query) qs.set('q', query)
      if (gen) qs.set('genre', gen)
      const pageData = await api.get<{ items: CatalogEntry[]; nextCursor: string | null; total: number }>(`/api/v1/manga-catalog?${qs}`)
      setItems(pageData.items.map(toCard))
    } catch (e) {
      setItems([])
      setError(e instanceof Error ? e.message : '加载失败')
    } finally { setLoading(false) }
  }, [])

  useEffect(() => {
    if (source) { setGenre(''); void load(source, '', '', false) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [source])

  function search(live = false) {
    setActiveQuery(q.trim())
    void load(source, q.trim(), live ? '' : genre, live)
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
        <button onClick={() => search()} disabled={loading || !source}>搜索</button>
        <button className="ghost" onClick={() => search(true)} disabled={loading || !source}
                title="库内无结果时实时搜索该源（结果自动入库）">实时搜索</button>
        {sources.map(s => (
          <button key={s.id} className={source === s.id ? 'mini' : 'mini ghost'}
                  onClick={() => { setSource(s.id); setActiveQuery(''); setQ(''); setSearched(false) }}>
            {s.name}
          </button>
        ))}
        {(current?.genres?.length ?? 0) > 0 && (
          <>
            <span className="muted" style={{ fontSize: 12 }}>题材：</span>
            <button className={genre === '' ? 'mini' : 'mini ghost'}
                    onClick={() => { setGenre(''); void load(source, activeQuery, '', false) }}>全部</button>
            {current?.genres.map(g => (
              <button key={g} className={genre === g ? 'mini' : 'mini ghost'}
                      onClick={() => { setGenre(g); void load(source, activeQuery, g, false) }}>{g}</button>
            ))}
          </>
        )}
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
                ? <img src={r.coverUrl ?? ''} alt={r.title} loading="lazy" decoding="async"
                       onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />
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

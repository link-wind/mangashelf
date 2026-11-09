import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api'
import { proxyImage } from '../externalManga'

interface MangaCard {
  id: string; title: string; description: string; visibility: string
  chapterCount: number; latestOrdinal: number | null; latestTitle: string | null
  coverUrl: string | null; updatedAt: string
}

/**
 * 漫画索引（M21，参照哔哩哔哩漫画）：左主区（本地漫画）+ 右侧栏（热门榜/外部入口）。
 */
export default function MangaIndex() {
  const [items, setItems] = useState<MangaCard[]>([])
  const [cursor, setCursor] = useState<string | null>(null)
  const [q, setQ] = useState('')
  const [activeQuery, setActiveQuery] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const load = useCallback(async (nextCursor: string | null, query: string, replace: boolean) => {
    setLoading(true); setError('')
    try {
      const qs = new URLSearchParams({ limit: '24' })
      if (nextCursor) qs.set('cursor', nextCursor)
      if (query) qs.set('q', query)
      const page = await api.get<{ items: MangaCard[]; nextCursor: string | null }>(`/api/v1/manga?${qs}`)
      setItems(prev => replace ? page.items : [...prev, ...page.items])
      setCursor(page.nextCursor)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败')
    } finally { setLoading(false) }
  }, [])

  useEffect(() => { void load(null, '', true) }, [load])

  function search() {
    setActiveQuery(q.trim())
    void load(null, q.trim(), true)
  }

  return (
    <main className="watch-page">
      <div className="watch-title"><h1>漫画</h1></div>
      <div className="watch-meta">
        <span>本地 {items.length} 部可读 · 封面取章节首页</span>
        <Link to="/manga-ext">外部漫画（MangaDex）→</Link>
        <Link to="/uploads">上传漫画 →</Link>
      </div>

      <div className="manga-layout">
        <div className="manga-main">
          <div className="row-inline" style={{ marginBottom: 14 }}>
            <input placeholder="按标题搜索本地漫画…" className="search" value={q}
                   onChange={e => setQ(e.target.value)}
                   onKeyDown={e => e.key === 'Enter' && search()} />
            <button onClick={search} disabled={loading}>搜索</button>
            {activeQuery && (
              <button className="ghost" onClick={() => { setQ(''); setActiveQuery(''); void load(null, '', true) }}>
                清除
              </button>
            )}
          </div>

          {error && <p className="error">{error}</p>}
          {items.length === 0 && !loading && (
            <p className="muted">还没有可读的漫画——到「我的素材」上传图片或 CBZ 并发布章节。</p>
          )}

          <div className="card-grid">
            {items.map(m => (
              <Link key={m.id} to={`/manga/${m.id}`} className="work-card">
                <div className="cover">
                  {m.coverUrl
                    ? <img src={m.coverUrl} alt={m.title} loading="lazy" decoding="async"
                           onError={e => {
                             const img = e.target as HTMLImageElement
                             img.style.display = 'none'
                             const fb = img.nextElementSibling as HTMLElement | null
                             if (fb) fb.style.display = 'grid'
                           }} />
                    : null}
                  <span className="cover-initial" style={m.coverUrl ? { display: 'none' } : {}}>
                    {m.title.slice(0, 1)}
                  </span>
                  {m.visibility === 'PRIVATE' && <span className="badge-anim" style={{ background: 'var(--priv)' }}>私人</span>}
                </div>
                <div className="work-card-body">
                  <div className="work-card-title clamp">{m.title}</div>
                  <div className="work-card-meta">
                    <span>{m.chapterCount} 话</span>
                    {m.latestOrdinal != null && <span>更新至第 {m.latestOrdinal} 话</span>}
                  </div>
                </div>
              </Link>
            ))}
          </div>

          {cursor && (
            <div style={{ marginTop: 20, textAlign: 'center' }}>
              <button className="ghost" disabled={loading} onClick={() => void load(cursor, activeQuery, false)}>
                {loading ? '加载中…' : '加载更多'}
              </button>
            </div>
          )}
        </div>

        <aside className="manga-side">
          <HotManga />
          <div className="card" style={{ marginTop: 14 }}>
            <h3 style={{ margin: '0 0 8px', fontSize: 15 }}>外部漫画</h3>
            <p className="muted" style={{ margin: '0 0 10px' }}>
              MangaDex 开放接口，可站内阅读托管章节。
            </p>
            <Link className="btn ghost pill" to="/manga-ext">去搜索 →</Link>
          </div>
        </aside>
      </div>
    </main>
  )
}

interface SourceInfo { id: string; name: string; capabilities: string[]; note: string }
interface RankItem {
  source: string; id: string; title: string; authors: string[]; coverUrl: string
  finished: boolean; latestLabel: string; readable: boolean; externalUrl: string | null
  readSource: string | null; readId: string | null
}

/** 热门漫画（M17/M21）：侧栏纵向榜单，按源切换。 */
function HotManga() {
  const navigate = useNavigate()
  const [sources, setSources] = useState<SourceInfo[]>([])
  const [source, setSource] = useState('')
  const [items, setItems] = useState<RankItem[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    void (async () => {
      try {
        const list = await api.get<SourceInfo[]>('/api/v1/manga-sources')
        const ranked = list.filter(s => s.capabilities.includes('RANK'))
        setSources(ranked)
        if (ranked[0]) setSource(ranked[0].id)
      } catch { /* 榜单不可用不影响本地漫画 */ }
    })()
  }, [])

  useEffect(() => {
    if (!source) return
    setLoading(true); setError('')
    void api.get<RankItem[]>(`/api/v1/manga-sources/${source}/rank?limit=12`)
      .then(setItems)
      .catch(e => setError(e instanceof Error ? e.message : '榜单加载失败'))
      .finally(() => setLoading(false))
  }, [source])

  if (sources.length === 0) return null
  const current = sources.find(s => s.id === source)

  function open(it: RankItem) {
    if (it.readId) {
      navigate(`/manga-ext/${it.readId}?lang=all&source=${it.readSource ?? 'mangadex'}` +
               `&title=${encodeURIComponent(it.title)}`)
    } else if (it.externalUrl) {
      window.open(it.externalUrl, '_blank', 'noopener,noreferrer')
    }
  }

  return (
    <div className="card">
      <div className="section-head" style={{ marginBottom: 8 }}>
        <h3 style={{ margin: 0, fontSize: 15 }}>热门漫画</h3>
        <span className="muted" style={{ fontSize: 12 }}>{current?.name}</span>
      </div>
      <div className="row-inline" style={{ marginBottom: 10 }}>
        {sources.map(s => (
          <button key={s.id} className={s.id === source ? 'mini' : 'mini ghost'}
                  onClick={() => setSource(s.id)}>{s.name}</button>
        ))}
      </div>
      {error && <p className="error">{error}</p>}
      {loading && items.length === 0 && <p className="muted">加载榜单…</p>}
      <ol className="hot-list">
        {items.map((it, i) => (
          <li key={it.source + it.id}>
            <button className="hot-row" onClick={() => open(it)}>
              <span className={'hot-no' + (i < 3 ? ' top' : '')}>{i + 1}</span>
              <img src={proxyImage(it.coverUrl)} alt="" loading="lazy" decoding="async"
                   onError={e => { (e.target as HTMLImageElement).style.visibility = 'hidden' }} />
              <span className="hot-body">
                <span className="hot-title clamp">{it.title}</span>
                <span className="hot-meta">
                  {it.latestLabel || (it.finished ? '已完结' : '连载中')}
                  {it.readId ? ' · 站内可读' : ' · 站外'}
                </span>
              </span>
            </button>
          </li>
        ))}
      </ol>
      <p className="muted" style={{ fontSize: 11, margin: '8px 0 0' }}>{current?.note}</p>
    </div>
  )
}

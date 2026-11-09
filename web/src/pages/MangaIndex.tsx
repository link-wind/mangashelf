import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { proxyImage } from '../externalManga'

interface MangaCard {
  id: string; title: string; description: string; visibility: string
  chapterCount: number; latestOrdinal: number | null; latestTitle: string | null
  coverUrl: string | null; updatedAt: string
}

/** 漫画索引（M15/M17）：热门榜（多源元数据）+ 本地漫画（首章缩略图封面）。 */
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
        <span>共 {items.length} 部可读作品 · 封面来自章节首页</span>
        <Link to="/manga-ext">外部漫画（MangaDex）→</Link>
        <Link to="/uploads">上传漫画 →</Link>
      </div>

      <HotManga />

      <div className="row-inline" style={{ marginBottom: 16 }}>
        <input placeholder="按标题搜索漫画…" className="search" value={q}
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
    </main>
  )
}


interface SourceInfo { id: string; name: string; capabilities: string[]; note: string }
interface RankItem {
  source: string; id: string; title: string; authors: string[]; coverUrl: string
  finished: boolean; latestLabel: string; readable: boolean; externalUrl: string | null
}

/** 热门漫画（M17）：按数据源切换；元数据源（不可站内读）跳原站。 */
function HotManga() {
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

  return (
    <section style={{ marginBottom: 26 }}>
      <div className="section-head">
        <h2>
          热门漫画{' '}
          <span className="muted" style={{ fontSize: 13, fontWeight: 400 }}>
            {current?.name}{current?.note ? ` · ${current.note}` : ''}
          </span>
        </h2>
        <div className="row-inline">
          {sources.map(s => (
            <button key={s.id} className={s.id === source ? 'mini' : 'mini ghost'}
                    onClick={() => setSource(s.id)}>{s.name}</button>
          ))}
        </div>
      </div>
      {error && <p className="error">{error}</p>}
      {loading && items.length === 0 && <p className="muted">加载榜单…</p>}
      <div className="hscroll">
        {items.map((it, i) => {
          const inner = (
            <>
              <div className="cover">
                <span className="rank-corner">{i + 1}</span>
                {it.coverUrl
                  ? <img src={proxyImage(it.coverUrl)} alt={it.title} loading="lazy" decoding="async" />
                  : <span className="cover-initial">{it.title.slice(0, 1)}</span>}
                {!it.readable && <span className="badge-anim" style={{ background: 'var(--priv)' }}>站外</span>}
              </div>
              <div className="work-card-body">
                <div className="work-card-title clamp">{it.title}</div>
                <div className="work-card-meta">
                  <span>{it.latestLabel || (it.finished ? '已完结' : '连载中')}</span>
                </div>
              </div>
            </>
          )
          return it.readable
            ? <Link key={it.source + it.id} to={`/manga-ext/${it.id}?lang=all`} className="work-card">{inner}</Link>
            : <a key={it.source + it.id} href={it.externalUrl ?? '#'} target="_blank"
                 rel="noopener noreferrer" className="work-card">{inner}</a>
        })}
      </div>
    </section>
  )
}

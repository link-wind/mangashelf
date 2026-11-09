import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'

interface MangaCard {
  id: string; title: string; description: string; visibility: string
  chapterCount: number; latestOrdinal: number | null; latestTitle: string | null
  coverUrl: string | null; updatedAt: string
}

/** 漫画索引（M15）：只列有已发布章节的作品，封面取首章首页缩略图。 */
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

import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Link } from 'react-router-dom'
import { api } from '../api'

interface CatalogHit {
  id: string; name: string; coverUrl: string; remarks: string; episodeCount: number
  workId: string | null; bangumiSubjectId: number | null
}

interface Hit {
  kind: 'work' | 'page'
  workId: string
  workTitle: string
  chapterId: string | null
  revisionId: string | null
  pageOrdinal: number
  chapterTitle: string | null
  snippet: string
  hasAnimation: boolean
}

/** 搜索页（DESIGN 15.3 + M10）：番剧目录 → 站内标题/对白/语义混合结果。 */
export default function Search() {
  const [params] = useSearchParams()
  const [q, setQ] = useState(params.get('q') ?? '')
  const [hits, setHits] = useState<Hit[]>([])
  const [catalog, setCatalog] = useState<CatalogHit[]>([])
  const [semanticEnabled, setSemanticEnabled] = useState(true)
  const [hasAnimation, setHasAnimation] = useState(false)
  const [searched, setSearched] = useState(false)
  const [error, setError] = useState('')

  const run = useCallback(async (query: string) => {
    if (!query.trim()) { setHits([]); setSearched(false); return }
    try {
      const res = await api.post<{ semanticEnabled: boolean; hits: Hit[] }>('/api/v1/search', {
        q: query, hasAnimation, limit: 15,
      })
      setHits(res.hits)
      setSemanticEnabled(res.semanticEnabled)
      setSearched(true)
      setError('')
    } catch (e) {
      setError(e instanceof Error ? e.message : '搜索失败')
    }
  }, [hasAnimation])

  useEffect(() => {
    const t = setTimeout(() => {
      void run(q)
      // 番剧目录（7500+ 部）与站内内容并行检索
      if (q.trim()) {
        void api.get<{ items: CatalogHit[] }>(`/api/v1/catalog-items?limit=12&q=${encodeURIComponent(q.trim())}`)
          .then(r => setCatalog(r.items))
          .catch(() => setCatalog([]))
      } else {
        setCatalog([])
      }
    }, 350)
    return () => clearTimeout(t)
  }, [q, hasAnimation, run])

  return (
    <main className="page">
      <h1>搜索</h1>
      <div className="row-inline">
        <input className="search" style={{ width: 420 }} placeholder="搜索标题或对白…"
               value={q} onChange={e => setQ(e.target.value)} autoFocus />
        <label className="scope-item">
          <input type="checkbox" checked={hasAnimation} onChange={e => setHasAnimation(e.target.checked)} />
          只看有动画的
        </label>
      </div>
      {!semanticEnabled && <p className="muted">语义检索未启用（本地嵌入模型缺失），字面检索可用。</p>}
      {error && <p className="error">{error}</p>}

      {catalog.length > 0 && (
        <section style={{ marginBottom: 22 }}>
          <h2 style={{ fontSize: 17 }}>番剧目录（{catalog.length}）</h2>
          <div className="card-grid">
            {catalog.map(c => (
              <Link key={c.id} to={`/catalog/${c.id}`} className="work-card">
                <div className="cover">
                  {c.bangumiSubjectId
                    ? <img src={`/api/v1/bangumi-covers/${c.bangumiSubjectId}`} alt={c.name} loading="lazy"
                           onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />
                    : c.coverUrl
                      ? <img src={c.coverUrl} alt={c.name} loading="lazy"
                             onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />
                      : <span className="cover-initial">{c.name.slice(0, 1)}</span>}
                  {c.workId && <span className="badge-anim" style={{ background: 'var(--pub)' }}>在书库</span>}
                </div>
                <div className="work-card-body">
                  <div className="work-card-title clamp">{c.name}</div>
                  <div className="work-card-meta"><span>{c.remarks || `${c.episodeCount} 集`}</span></div>
                </div>
              </Link>
            ))}
          </div>
        </section>
      )}

      {searched && hits.length === 0 && <p className="muted">没有找到相关内容。</p>}
      <div className="stack">
        {hits.map((h, i) => (
          <div key={i} className="card">
            {h.kind === 'work' ? (
              <>
                <div className="row-between">
                  <Link to={`/works/${h.workId}`}><strong>《{h.workTitle}》</strong></Link>
                  {h.hasAnimation && <span className="tag pub">有动画</span>}
                </div>
                <p className="muted">{h.snippet || '（作品）'}</p>
              </>
            ) : (
              <>
                <div className="row-between">
                  <Link to={`/read/${h.revisionId}?chapter=${h.chapterId}&resume=${h.pageOrdinal}`}>
                    <strong>《{h.workTitle}》{h.chapterTitle} · 第 {h.pageOrdinal} 页</strong>
                  </Link>
                  {h.hasAnimation && <span className="tag pub">有动画</span>}
                </div>
                <p className="muted clamp">{h.snippet}</p>
              </>
            )}
          </div>
        ))}
      </div>
    </main>
  )
}

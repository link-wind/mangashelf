import { useCallback, useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, ApiError } from '../api'
import CoverImage from '../components/CoverImage'

interface CatalogHit {
  id: string; name: string; coverUrl: string; remarks: string; episodeCount: number
  workId: string | null; bangumiSubjectId: number | null
}

interface StreamEntry {
  source: string; sourceName: string; id: string; readable: boolean
  coverUrl: string | null; remarks: string; workId: string | null
}
interface UnifiedMangaHit {
  title: string; altTitle: string; coverUrl: string | null
  primarySource: string; primarySourceName: string; primaryId: string
  workId: string | null; readable: boolean; remarks: string
  sourceCount: number; streams: StreamEntry[]
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

type FilterTab = 'all' | 'anime' | 'manga' | 'local'

function mangaHref(source: string, id: string, title: string, workId?: string | null): string {
  if (source === 'local' || workId) return `/manga/${workId ?? id}`
  return `/manga-ext/${id}?${new URLSearchParams({ lang: 'all', source, title })}`
}

/** 全局搜索页：并行检索番剧、漫画与站内作品/台词。 */
export default function Search() {
  const [params, setParams] = useSearchParams()
  const [q, setQ] = useState(params.get('q') ?? '')
  const [hits, setHits] = useState<Hit[]>([])
  const [catalog, setCatalog] = useState<CatalogHit[]>([])
  const [mangaHits, setMangaHits] = useState<UnifiedMangaHit[]>([])
  const [tab, setTab] = useState<FilterTab>('all')
  const [semanticEnabled, setSemanticEnabled] = useState(false)
  const [semantic, setSemantic] = useState(false)
  const [hasAnimation, setHasAnimation] = useState(false)
  const [searched, setSearched] = useState(false)
  const [loading, setLoading] = useState(false)
  const [errors, setErrors] = useState<string[]>([])

  const run = useCallback(async (query: string) => {
    const trimmed = query.trim()
    if (!trimmed) {
      setHits([]); setCatalog([]); setMangaHits([]); setSearched(false); setErrors([])
      return
    }
    setLoading(true)
    const nextErrors: string[] = []

    const localPromise = api.post<{ semanticEnabled: boolean; hits: Hit[] }>('/api/v1/search', {
      q: trimmed, mode: semantic ? 'semantic' : 'title', withAnimation: hasAnimation, limit: 15,
    }).then(res => {
      setHits(res.hits)
      setSemanticEnabled(res.semanticEnabled)
    }).catch(e => {
      if (e instanceof ApiError && e.code === 'SEMANTIC_UNAVAILABLE') {
        setSemantic(false)
        nextErrors.push(e.message)
      } else {
        nextErrors.push(`站内搜索失败：${e instanceof Error ? e.message : '未知错误'}`)
      }
      setHits([])
    })

    const animePromise = api.get<{ items: CatalogHit[] }>(
      `/api/v1/catalog-items?limit=12&page=1&q=${encodeURIComponent(trimmed)}`
    ).then(r => {
      setCatalog(r.items)
    }).catch(e => {
      setCatalog([])
      nextErrors.push(`番剧检索失败：${e instanceof Error ? e.message : '未知错误'}`)
    })

    const mangaPromise = api.get<{ items: UnifiedMangaHit[] }>(
      `/api/v1/manga-discovery/search?q=${encodeURIComponent(trimmed)}&source=all&limit=24`
    ).then(r => {
      setMangaHits(r.items ?? [])
    }).catch(e => {
      setMangaHits([])
      nextErrors.push(`漫画检索失败：${e instanceof Error ? e.message : '未知错误'}`)
    })

    await Promise.all([localPromise, animePromise, mangaPromise])
    setErrors(nextErrors)
    setSearched(true)
    setLoading(false)
  }, [hasAnimation, semantic])

  useEffect(() => {
    const t = setTimeout(() => {
      const trimmed = q.trim()
      const current = params.get('q') ?? ''
      if (trimmed !== current) {
        const next = new URLSearchParams(params)
        if (trimmed) next.set('q', trimmed)
        else next.delete('q')
        setParams(next, { replace: true })
      }
      void run(q)
    }, 350)
    return () => clearTimeout(t)
  }, [q, run])

  const totalCount = catalog.length + mangaHits.length + hits.length
  const showAnime = (tab === 'all' || tab === 'anime') && catalog.length > 0
  const showManga = (tab === 'all' || tab === 'manga') && mangaHits.length > 0
  const showLocal = (tab === 'all' || tab === 'local') && hits.length > 0

  return (
    <main className="page">
      <h1 style={{ margin: '0 0 8px' }}>全站搜索</h1>
      <div className="search-bar">
        <input className="search search-input-lg" placeholder="搜索番剧、漫画或台词…"
               value={q} onChange={e => setQ(e.target.value)} autoFocus />
        <label className="scope-item">
          <input type="checkbox" checked={hasAnimation} onChange={e => setHasAnimation(e.target.checked)} />
          仅看有动画
        </label>
        {semanticEnabled && (
          <label className="scope-item">
            <input type="checkbox" checked={semantic} onChange={e => setSemantic(e.target.checked)} />
            语义搜索
          </label>
        )}
      </div>

      {errors.map((err, i) => <p key={i} className="error" style={{ margin: '4px 0' }}>{err}</p>)}

      {searched && totalCount > 0 && (
        <div className="search-tabs" role="tablist" aria-label="结果分类">
          <button type="button" className={'mini ' + (tab === 'all' ? '' : 'ghost')} onClick={() => setTab('all')}>
            全部
          </button>
          <button type="button" className={'mini ' + (tab === 'anime' ? '' : 'ghost')} onClick={() => setTab('anime')}>
            番剧
          </button>
          <button type="button" className={'mini ' + (tab === 'manga' ? '' : 'ghost')} onClick={() => setTab('manga')}>
            漫画
          </button>
          <button type="button" className={'mini ' + (tab === 'local' ? '' : 'ghost')} onClick={() => setTab('local')}>
            站内内容
          </button>
        </div>
      )}

      {loading && <p className="muted">搜索中…</p>}

      {showAnime && (
        <section className="search-section">
          <h2>番剧</h2>
          <div className="card-grid">
            {catalog.map(c => (
              <Link key={c.id} to={`/catalog/${c.id}`} className="work-card">
                <div className="cover">
                  <CoverImage
                    src={c.bangumiSubjectId ? `/api/v1/bangumi-covers/${c.bangumiSubjectId}?size=large` : c.coverUrl}
                    alt={c.name}
                  />
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

      {showManga && (
        <section className="search-section">
          <h2>漫画</h2>
          <div className="card-grid">
            {mangaHits.map(m => {
              const href = mangaHref(m.primarySource, m.primaryId, m.title, m.workId)
              return (
                <Link key={`${m.primarySource}:${m.primaryId}`} to={href} className="work-card">
                  <div className="cover">
                    <CoverImage src={m.coverUrl} alt={m.title} />
                  </div>
                  <div className="work-card-body">
                    <div className="work-card-title clamp">{m.title}</div>
                    <div className="work-card-meta">
                      <span className="clamp">{m.remarks || m.altTitle || '漫画'}</span>
                    </div>
                  </div>
                </Link>
              )
            })}
          </div>
        </section>
      )}

      {showLocal && (
        <section className="search-section">
          <h2>站内作品与台词</h2>
          <div className="stack">
            {hits.map((h, i) => (
              <div key={i} className="card">
                {h.kind === 'work' ? (
                  <>
                    <div className="row-between">
                      <Link to={`/works/${h.workId}`}><strong>《{h.workTitle}》</strong></Link>
                      {h.hasAnimation && <span className="tag pub">有动画</span>}
                    </div>
                    <p className="muted" style={{ marginBottom: 0 }}>{h.snippet || '（作品）'}</p>
                  </>
                ) : (
                  <>
                    <div className="row-between">
                      <Link to={`/read/${h.revisionId}?chapter=${h.chapterId}&resume=${h.pageOrdinal}`}>
                        <strong>《{h.workTitle}》{h.chapterTitle} · 第 {h.pageOrdinal} 页</strong>
                      </Link>
                      {h.hasAnimation && <span className="tag pub">有动画</span>}
                    </div>
                    <p className="muted" style={{ marginBottom: 0 }}>{h.snippet}</p>
                  </>
                )}
              </div>
            ))}
          </div>
        </section>
      )}

      {searched && !loading && totalCount === 0 && errors.length === 0 && (
        <p className="muted">没有找到与「{q.trim()}」相关的番剧、漫画或站内内容。</p>
      )}
    </main>
  )
}

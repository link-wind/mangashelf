import { useCallback, useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, ApiError } from '../api'
import CoverImage from '../components/CoverImage'

interface CatalogHit {
  id: string; name: string; coverUrl: string; remarks: string; episodeCount: number
  workId: string | null; bangumiSubjectId: number | null
}

interface MangaSourceInfo {
  id: string; name: string; capabilities: string[]
}

interface MangaCatalogHit {
  source: string; sourceItemId: string; name: string; altTitle: string
  coverUrl: string | null; remarks: string | null; episodeCount: number | null
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

/** 搜索页：并行检索番剧目录、外部漫画目录与站内标题/对白/语义混合结果；显式报错不静默吞错。 */
export default function Search() {
  const [params] = useSearchParams()
  const [q, setQ] = useState(params.get('q') ?? '')
  const [hits, setHits] = useState<Hit[]>([])
  const [catalog, setCatalog] = useState<CatalogHit[]>([])
  const [mangaHits, setMangaHits] = useState<MangaCatalogHit[]>([])
  const [mangaSources, setMangaSources] = useState<MangaSourceInfo[]>([])
  const [tab, setTab] = useState<FilterTab>('all')
  const [semanticEnabled, setSemanticEnabled] = useState(false)
  const [semantic, setSemantic] = useState(false)
  const [hasAnimation, setHasAnimation] = useState(false)
  const [searched, setSearched] = useState(false)
  const [loading, setLoading] = useState(false)
  const [errors, setErrors] = useState<string[]>([])

  useEffect(() => {
    void api.get<MangaSourceInfo[]>('/api/v1/manga-sources')
      .then(list => setMangaSources(list.filter(s => s.capabilities.includes('SEARCH'))))
      .catch(() => { /* 源列表加载失败时仅跳过外部漫画目录 */ })
  }, [])

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
      `/api/v1/catalog-items?limit=12&q=${encodeURIComponent(trimmed)}`
    ).then(r => {
      setCatalog(r.items)
    }).catch(e => {
      setCatalog([])
      nextErrors.push(`番剧目录检索失败：${e instanceof Error ? e.message : '未知错误'}`)
    })

    const mangaPromise = (async () => {
      if (mangaSources.length === 0) {
        setMangaHits([])
        return
      }
      const results = await Promise.allSettled(
        mangaSources.map(src =>
          api.get<{ items: MangaCatalogHit[] }>(
            `/api/v1/manga-catalog?source=${encodeURIComponent(src.id)}&limit=8&q=${encodeURIComponent(trimmed)}`
          )
        )
      )
      const merged: MangaCatalogHit[] = []
      const seen = new Set<string>()
      let failedCount = 0
      for (const r of results) {
        if (r.status === 'fulfilled') {
          for (const item of r.value.items) {
            const key = `${item.source}:${item.sourceItemId}`
            if (!seen.has(key)) {
              seen.add(key)
              merged.push(item)
            }
          }
        } else {
          failedCount++
        }
      }
      setMangaHits(merged)
      if (failedCount > 0 && merged.length === 0) {
        nextErrors.push('外部漫画目录检索失败，请稍后重试。')
      }
    })()

    await Promise.all([localPromise, animePromise, mangaPromise])
    setErrors(nextErrors)
    setSearched(true)
    setLoading(false)
  }, [hasAnimation, semantic, mangaSources])

  useEffect(() => {
    const t = setTimeout(() => { void run(q) }, 320)
    return () => clearTimeout(t)
  }, [q, run])

  const totalCount = catalog.length + mangaHits.length + hits.length
  const showAnime = (tab === 'all' || tab === 'anime') && catalog.length > 0
  const showManga = (tab === 'all' || tab === 'manga') && mangaHits.length > 0
  const showLocal = (tab === 'all' || tab === 'local') && hits.length > 0

  return (
    <main className="page">
      <h1 style={{ margin: '0 0 8px' }}>搜索</h1>
      <div className="search-bar">
        <input className="search search-input-lg" placeholder="搜索番剧、漫画标题或站内对白…"
               value={q} onChange={e => setQ(e.target.value)} autoFocus />
        <label className="scope-item">
          <input type="checkbox" checked={hasAnimation} onChange={e => setHasAnimation(e.target.checked)} />
          站内只看有动画
        </label>
        {semanticEnabled && (
          <label className="scope-item">
            <input type="checkbox" checked={semantic} onChange={e => setSemantic(e.target.checked)} />
            语义检索（较慢）
          </label>
        )}
      </div>

      {!semanticEnabled && searched && (
        <p className="muted" style={{ marginTop: 0 }}>语义检索未启用（本地嵌入模型缺失），当前使用字面与目录检索。</p>
      )}
      {errors.map((err, i) => <p key={i} className="error" style={{ margin: '4px 0' }}>{err}</p>)}

      {searched && totalCount > 0 && (
        <div className="search-tabs" role="tablist" aria-label="结果分类">
          <button type="button" className={'mini ' + (tab === 'all' ? '' : 'ghost')} onClick={() => setTab('all')}>
            全部 · {totalCount}
          </button>
          <button type="button" className={'mini ' + (tab === 'anime' ? '' : 'ghost')} onClick={() => setTab('anime')}>
            番剧目录 · {catalog.length}
          </button>
          <button type="button" className={'mini ' + (tab === 'manga' ? '' : 'ghost')} onClick={() => setTab('manga')}>
            漫画目录 · {mangaHits.length}
          </button>
          <button type="button" className={'mini ' + (tab === 'local' ? '' : 'ghost')} onClick={() => setTab('local')}>
            站内内容 · {hits.length}
          </button>
        </div>
      )}

      {loading && <p className="muted">搜索中…</p>}

      {showAnime && (
        <section className="search-section">
          <h2>番剧目录（{catalog.length}）</h2>
          <div className="card-grid">
            {catalog.map(c => (
              <Link key={c.id} to={`/catalog/${c.id}`} className="work-card">
                <div className="cover">
                  <CoverImage
                    src={c.bangumiSubjectId ? `/api/v1/bangumi-covers/${c.bangumiSubjectId}?size=common` : c.coverUrl}
                    alt={c.name}
                  />
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

      {showManga && (
        <section className="search-section">
          <h2>漫画目录（{mangaHits.length}）</h2>
          <div className="card-grid">
            {mangaHits.map(m => {
              const qs = new URLSearchParams({ source: m.source, title: m.name })
              return (
                <Link key={`${m.source}:${m.sourceItemId}`} to={`/manga-ext/${m.sourceItemId}?${qs}`} className="work-card">
                  <div className="cover">
                    <CoverImage src={m.coverUrl} alt={m.name} />
                    <span className="badge-anim">{m.source}</span>
                  </div>
                  <div className="work-card-body">
                    <div className="work-card-title clamp">{m.name}</div>
                    <div className="work-card-meta">
                      <span>{m.remarks || (m.episodeCount ? `${m.episodeCount} 话` : '漫画')}</span>
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
          <h2>站内作品与对白（{hits.length}）</h2>
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
                    <p className="muted clamp" style={{ marginBottom: 0 }}>{h.snippet}</p>
                  </>
                )}
              </div>
            ))}
          </div>
        </section>
      )}

      {searched && !loading && totalCount === 0 && errors.length === 0 && (
        <p className="muted">没有找到与「{q.trim()}」相关的内容。</p>
      )}
    </main>
  )
}

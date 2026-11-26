import { BookOpen, Clock, Flame, Search as SearchIcon, Sparkles, Trash2, X } from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, ApiError } from '../api'
import AnimePosterCard from '../components/AnimePosterCard'
import {
  clearRecentSearches, loadRecentSearches, pushRecentSearch,
} from '../components/ui/CommandPalette'
import EmptyState from '../components/ui/EmptyState'
import SegmentedTabs from '../components/ui/SegmentedTabs'
import Switch from '../components/ui/Switch'
import { hdCover } from '../externalManga'
import { cleanMangaRemarks, cleanSubtitle, formatStatusLabel } from '../lib/mediaFormat'
import { useCatalogFavorites } from '../useCatalogFavorites'
import { mangaFavKey, useMangaFavorites } from '../useMangaFavorites'

interface CatalogHit {
  id: string; name: string; coverUrl: string; remarks: string; episodeCount: number
  workId: string | null; bangumiSubjectId: number | null; categoryName?: string | null
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

const QUICK_GENRES = ['热血', '奇幻', '冒险', '恋爱', '搞笑', '悬疑', '科幻', '治愈', '校园', '日常']

function mangaHref(source: string, id: string, title: string, workId?: string | null): string {
  if (source === 'local' || workId) return `/manga/${workId ?? id}`
  return `/manga-ext/${id}?${new URLSearchParams({ lang: 'all', source, title })}`
}

/** 现代全站搜索页：丰富探索空态（最近搜索 + 热门词 + 题材）+ 计数 Tab + 统一海报卡片 + 骨架屏。 */
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
  const [recent, setRecent] = useState<string[]>(loadRecentSearches)
  const [hotAnime, setHotAnime] = useState<string[]>([])
  const [hotManga, setHotManga] = useState<string[]>([])

  const animeFav = useCatalogFavorites()
  const mangaFav = useMangaFavorites()

  // 拉取热搜建议（仅首次加载用于丰富空态）
  useEffect(() => {
    void Promise.all([
      api.get<{ seasonalPicks?: { nameCn?: string; name: string }[] }>('/api/v1/recommendations/anime?limit=6')
        .then(r => (r.seasonalPicks ?? []).slice(0, 6).map(x => x.nameCn || x.name).filter(Boolean))
        .catch(() => [] as string[]),
      api.get<{ rows?: { items: { title: string }[] }[] }>('/api/v1/manga-discovery?limit=8')
        .then(r => (r.rows?.[0]?.items ?? []).slice(0, 6).map(x => x.title).filter(Boolean))
        .catch(() => [] as string[]),
    ]).then(([a, m]) => {
      setHotAnime(a)
      setHotManga(m)
    })
  }, [])

  const run = useCallback(async (query: string) => {
    const trimmed = query.trim()
    if (!trimmed) {
      setHits([]); setCatalog([]); setMangaHits([]); setSearched(false); setErrors([])
      return
    }
    pushRecentSearch(trimmed)
    setRecent(loadRecentSearches())
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
      `/api/v1/catalog-items?limit=14&page=1&q=${encodeURIComponent(trimmed)}`,
    ).then(r => {
      setCatalog(r.items)
    }).catch(e => {
      setCatalog([])
      nextErrors.push(`番剧检索失败：${e instanceof Error ? e.message : '未知错误'}`)
    })

    const mangaPromise = api.get<{ items: UnifiedMangaHit[] }>(
      `/api/v1/manga-discovery/search?q=${encodeURIComponent(trimmed)}&source=all&limit=21`,
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
    }, 320)
    return () => clearTimeout(t)
  }, [q, run])

  function applyQuery(term: string) {
    setQ(term)
    setTab('all')
  }

  function handleClearHistory() {
    clearRecentSearches()
    setRecent([])
  }

  const totalCount = catalog.length + mangaHits.length + hits.length
  const showAnime = (tab === 'all' || tab === 'anime') && catalog.length > 0
  const showManga = (tab === 'all' || tab === 'manga') && mangaHits.length > 0
  const showLocal = (tab === 'all' || tab === 'local') && hits.length > 0

  return (
    <main className="watch-page">
      <section className="search-hero-card">
        <div className="search-hero-top">
          <h1>全站搜索</h1>
          <p className="muted">并行检索全站番剧、跨源漫画与站内上传作品及台词 · 任意页面按 <kbd>⌘K</kbd> 可随时快搜</p>
        </div>
        <div className="search-hero-bar">
          <SearchIcon size={18} className="search-hero-ico" />
          <input
            value={q}
            onChange={e => setQ(e.target.value)}
            placeholder="输入番剧名、漫画名或台词关键词…"
            aria-label="全站搜索"
            autoFocus
          />
          {q && (
            <button type="button" className="search-hero-clear" aria-label="清空" onClick={() => setQ('')}>
              <X size={15} />
            </button>
          )}
        </div>
      </section>

      {errors.map((err, i) => <p key={i} className="error" style={{ marginTop: 10 }}>{err}</p>)}

      {/* 丰富的空态发现面板（消除空搜 400px 空白） */}
      {!q.trim() && (
        <div className="search-discover-grid">
          {recent.length > 0 && (
            <section className="search-disc-card">
              <div className="row-between">
                <h2><Clock size={15} />最近搜索</h2>
                <button type="button" className="mini ghost" onClick={handleClearHistory}>
                  <Trash2 size={12} />清空
                </button>
              </div>
              <div className="search-chip-wrap">
                {recent.map(term => (
                  <button key={term} type="button" className="search-chip" onClick={() => applyQuery(term)}>
                    {term}
                  </button>
                ))}
              </div>
            </section>
          )}

          {hotAnime.length > 0 && (
            <section className="search-disc-card">
              <h2><Flame size={15} className="is-cyan" />热门番剧推荐词</h2>
              <div className="search-chip-wrap">
                {hotAnime.map(term => (
                  <button key={term} type="button" className="search-chip is-cyan" onClick={() => applyQuery(term)}>
                    {term}
                  </button>
                ))}
              </div>
            </section>
          )}

          {hotManga.length > 0 && (
            <section className="search-disc-card">
              <h2><BookOpen size={15} className="is-rose" />热门漫画推荐词</h2>
              <div className="search-chip-wrap">
                {hotManga.map(term => (
                  <button key={term} type="button" className="search-chip is-rose" onClick={() => applyQuery(term)}>
                    {term}
                  </button>
                ))}
              </div>
            </section>
          )}

          <section className="search-disc-card is-wide">
            <h2><Sparkles size={15} />按题材直达索引</h2>
            <div className="search-chip-wrap">
              {QUICK_GENRES.map(g => (
                <Link key={`a-${g}`} to={`/index?genre=${encodeURIComponent(g)}`} className="search-chip is-cyan">
                  番剧 · {g}
                </Link>
              ))}
              {QUICK_GENRES.slice(0, 6).map(g => (
                <Link key={`m-${g}`} to={`/index?kind=manga&genre=${encodeURIComponent(g)}`} className="search-chip is-rose">
                  漫画 · {g}
                </Link>
              ))}
            </div>
          </section>
        </div>
      )}

      {/* 结果分类 Tab（带实时命中数） */}
      {q.trim() && (searched || loading) && (
        <div className="search-result-bar">
          <SegmentedTabs
            layoutId="search-tabs"
            value={tab}
            onChange={setTab}
            ariaLabel="结果分类"
            items={[
              { value: 'all', label: '全部', count: totalCount },
              { value: 'anime', label: '番剧', count: catalog.length },
              { value: 'manga', label: '漫画', count: mangaHits.length },
              { value: 'local', label: '站内内容', count: hits.length },
            ]}
          />

          {(tab === 'local' || tab === 'all') && (
            <div className="row-inline" style={{ gap: 14 }}>
              <Switch checked={hasAnimation} onChange={setHasAnimation} label="站内仅看有动画" />
              {semanticEnabled && (
                <Switch checked={semantic} onChange={setSemantic} label="语义搜索" />
              )}
            </div>
          )}
        </div>
      )}

      {/* 骨架屏加载态（告别裸文本“搜索中…”） */}
      {loading && (
        <div className="card-grid" style={{ marginTop: 20 }}>
          {Array.from({ length: 14 }).map((_, i) => (
            <div key={i}>
              <div className="skeleton-box card-skeleton-cover" />
              <div className="skeleton-box card-skeleton-line" />
              <div className="skeleton-box card-skeleton-line short" />
            </div>
          ))}
        </div>
      )}

      {!loading && showAnime && (
        <section className="search-section">
          <div className="section-head">
            <h2><span className="space-accent-bar is-cyan" style={{ display: 'inline-block', marginRight: 8, verticalAlign: -2 }} />番剧 <span className="space-sec-count tabular-nums">{catalog.length}</span></h2>
          </div>
          <div className="card-grid">
            {catalog.map(c => {
              const status = formatStatusLabel(c.remarks)
              const detailHref = c.workId ? `/works/${c.workId}` : `/catalog/${c.id}`
              const playHref = c.workId ? `/works/${c.workId}` : `/browse/${c.id}`
              return (
                <AnimePosterCard
                  key={c.id}
                  title={c.name}
                  coverUrl={c.bangumiSubjectId ? `/api/v1/bangumi-covers/${c.bangumiSubjectId}?size=large` : c.coverUrl}
                  detailHref={detailHref}
                  playHref={playHref}
                  topLeft={status}
                  bottomRight={c.remarks && c.remarks !== status ? c.remarks : (c.episodeCount > 0 ? `${c.episodeCount} 集` : null)}
                  metaLeft={c.categoryName || '动画'}
                  favorited={animeFav.favIds.has(c.id)}
                  busy={animeFav.busyId === c.id}
                  onToggleFavorite={() => void animeFav.toggle(c.id, c.name)}
                />
              )
            })}
          </div>
        </section>
      )}

      {!loading && showManga && (
        <section className="search-section">
          <div className="section-head">
            <h2><span className="space-accent-bar is-rose" style={{ display: 'inline-block', marginRight: 8, verticalAlign: -2 }} />漫画 <span className="space-sec-count tabular-nums">{mangaHits.length}</span></h2>
          </div>
          <div className="card-grid">
            {mangaHits.map(m => {
              const href = mangaHref(m.primarySource, m.primaryId, m.title, m.workId)
              const isLocal = m.primarySource === 'local' || Boolean(m.workId)
              const remarks = cleanMangaRemarks(m.remarks)
              const status = formatStatusLabel(m.remarks)
              const key = mangaFavKey(m.primarySource, m.primaryId)
              return (
                <AnimePosterCard
                  key={`${m.primarySource}:${m.primaryId}`}
                  actionKind="read"
                  title={m.title}
                  coverUrl={hdCover(m.coverUrl)}
                  detailHref={href}
                  playHref={href}
                  topLeft={status}
                  bottomRight={remarks || null}
                  metaLeft={cleanSubtitle(m.altTitle, m.primarySourceName || '跨源漫画')}
                  metaTag={m.sourceCount > 1 ? `${m.sourceCount} 源` : undefined}
                  canFavorite={!isLocal}
                  favorited={!isLocal && mangaFav.isFavorited(m.primarySource, m.primaryId)}
                  busy={mangaFav.busyKey === key}
                  onToggleFavorite={() => void mangaFav.toggle(m.primarySource, m.primaryId, m.title)}
                />
              )
            })}
          </div>
        </section>
      )}

      {!loading && showLocal && (
        <section className="search-section">
          <div className="section-head">
            <h2>站内作品与台词 <span className="space-sec-count tabular-nums">{hits.length}</span></h2>
          </div>
          <div className="stack">
            {hits.map((h, i) => (
              <div key={i} className="card">
                {h.kind === 'work' ? (
                  <>
                    <div className="row-between">
                      <Link to={`/works/${h.workId}`}><strong>《{h.workTitle}》</strong></Link>
                      {h.hasAnimation && <span className="tag pub">有动画</span>}
                    </div>
                    <p className="muted" style={{ marginBottom: 0 }}>{h.snippet || '（站内作品）'}</p>
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
        <div style={{ marginTop: 24 }}>
          <EmptyState
            icon={<SearchIcon size={22} />}
            title={`未找到与「${q.trim()}」相关的作品`}
            description="可以尝试更简短的关键词，或切换到全站索引按题材和年份浏览。"
            action={
              <>
                <button type="button" className="pill" onClick={() => setQ('')}>清空关键词</button>
                <Link to="/index" className="btn ghost pill">去全站索引</Link>
              </>
            }
          />
        </div>
      )}
    </main>
  )
}

import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import AnimePosterCard from '../components/AnimePosterCard'
import CoverImage from '../components/CoverImage'
import { BookOpenIcon, HeartIcon, InfoIcon } from '../components/Icons'
import { hdCover } from '../externalManga'
import { mangaFavKey, useMangaFavorites } from '../useMangaFavorites'

interface MangaCard {
  id: string; title: string; description: string; visibility: string
  chapterCount: number; latestOrdinal: number | null; latestTitle: string | null
  coverUrl: string | null; updatedAt: string
}

interface RankItem {
  source: string; id: string; title: string; authors: string[]; coverUrl: string | null
  finished: boolean; latestLabel: string; readable: boolean; externalUrl: string | null
  readSource: string | null; readId: string | null
}
interface DiscoveryItem extends RankItem {
  sourceCount?: number
  sources?: string[]
}
interface SourceSection {
  id: string; name: string; items: DiscoveryItem[]
  sourceId?: string | null; sourceName?: string | null
}
interface DiscoveryResult { rows: SourceSection[]; errors: string[] }

interface StreamEntry {
  source: string; sourceName: string; id: string; readable: boolean
  coverUrl: string | null; remarks: string; workId: string | null
}
interface UnifiedSearchItem {
  title: string; altTitle: string; coverUrl: string | null
  primarySource: string; primarySourceName: string; primaryId: string
  workId: string | null; readable: boolean; remarks: string
  sourceCount: number; streams: StreamEntry[]
}
interface UnifiedSearchResult {
  query: string; items: UnifiedSearchItem[]
}

interface MangaRecItem {
  source: string; sourceName: string; id: string; title: string
  coverUrl: string | null; remarks: string; readable: boolean
  sourceCount: number; sources: string[]; matchedTags: string[]
  reason: string; score: number
}
interface MangaRecResult {
  personalized: boolean; topPreferences: string[]; forYou: MangaRecItem[]; totalCandidates: number
}

interface ContinueRow {
  type: 'work-read' | 'manga-read' | 'animation-watch' | 'external-watch'
  title: string; subtitle: string | null; coverUrl: string | null
  workId: string | null; chapterId: string | null; revisionId: string | null
  pageOrdinal: number | null; source: string | null; mangaId: string | null
}

interface GenreTag { tag: string; items: number }

type MangaRankTab = 'hot' | 'rise' | 'new'
type MangaFavHook = ReturnType<typeof useMangaFavorites>

const CACHE_KEY = 'ms-manga-cache:v2:all'
const CACHE_TTL_MS = 10 * 60 * 1000

interface SectionCache { ts: number; sections: SourceSection[] }

function readCache(key: string): SectionCache | null {
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return null
    const cached = JSON.parse(raw) as SectionCache
    if (!cached.sections || Date.now() - cached.ts > CACHE_TTL_MS) return null
    return cached
  } catch { return null }
}

function writeCache(key: string, sections: SourceSection[]) {
  try {
    localStorage.setItem(key, JSON.stringify({ ts: Date.now(), sections }))
  } catch { /* 超配额时忽略 */ }
}

function mangaLink(source: string, id: string, title: string, workId?: string | null): string {
  if (source === 'local' || workId) return `/manga/${workId ?? id}`
  return `/manga-ext/${id}?${new URLSearchParams({ lang: 'all', source, title })}`
}

function targetSourceOf(it: RankItem): string {
  return it.readSource || it.source
}

function targetIdOf(it: RankItem): string {
  return it.readId || it.id
}

function statusOf(remarks: string | null | undefined, finished?: boolean): string {
  if (finished || remarks?.includes('完结')) return '已完结'
  return '连载中'
}

/** 漫画主页（与番剧主页对称：首屏高清 Hero → 继续阅读 → 70/30 推荐+多榜 → 精选专题 → 快捷探索）。 */
export default function MangaIndex() {
  const [params, setParams] = useSearchParams()
  const fav = useMangaFavorites()

  // 兼容旧链接 /manga?tab=index&... → 重定向到统一索引页 /index?kind=manga&...
  if (params.get('tab') === 'index') {
    const next = new URLSearchParams(params)
    next.delete('tab')
    next.set('kind', 'manga')
    return <Navigate to={`/index?${next.toString()}`} replace />
  }

  const activeSearch = params.get('q') ?? ''

  function clearSearch() {
    const next = new URLSearchParams(params)
    next.delete('q')
    setParams(next)
  }

  return (
    <main className="home-page">
      {activeSearch ? (
        <MangaSearchView query={activeSearch} onClear={clearSearch} fav={fav} />
      ) : (
        <>
          <MangaDiscovery fav={fav} />
          <LocalManga />
        </>
      )}
    </main>
  )
}

/** 漫画搜索结果视图（支持 /manga?q=... 快捷入口）。 */
function MangaSearchView({ query, onClear, fav }: { query: string; onClear: () => void; fav: MangaFavHook }) {
  const [result, setResult] = useState<UnifiedSearchResult | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    let cancelled = false
    void (async () => {
      setLoading(true); setError('')
      try {
        const qs = new URLSearchParams({ q: query, source: 'all', limit: '36' })
        const data = await api.get<UnifiedSearchResult>(`/api/v1/manga-discovery/search?${qs}`)
        if (!cancelled) setResult(data)
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : '搜索失败')
      } finally {
        if (!cancelled) setLoading(false)
      }
    })()
    return () => { cancelled = true }
  }, [query])

  const shown = result?.items ?? []

  return (
    <section>
      <div className="section-head">
        <h2>
          搜索结果：「{query}」
          {loading && <span className="muted" style={{ fontSize: 13, fontWeight: 400, marginLeft: 8 }}>搜索中…</span>}
        </h2>
        <button type="button" className="mini ghost" onClick={onClear}>返回漫画</button>
      </div>

      {error && <p className="error">{error}</p>}
      {loading && (
        <div className="card-grid">
          {Array.from({ length: 14 }).map((_, i) => (
            <div key={i}>
              <div className="skeleton-box card-skeleton-cover" />
              <div className="skeleton-box card-skeleton-line" />
              <div className="skeleton-box card-skeleton-line short" />
            </div>
          ))}
        </div>
      )}

      {!loading && shown.length === 0 && !error && (
        <p className="muted">未找到「{query}」相关漫画，可以尝试换个关键词。</p>
      )}

      {!loading && shown.length > 0 && (
        <div className="card-grid">
          {shown.map(item => {
            const activeStream = item.streams.find(s => s.source === item.primarySource) ?? item.streams[0]
            const href = mangaLink(activeStream.source, activeStream.id, item.title, activeStream.workId)
            const isLocal = activeStream.source === 'local' || Boolean(activeStream.workId)
            const key = mangaFavKey(activeStream.source, activeStream.id)
            return (
              <AnimePosterCard
                key={`${item.primarySource}:${item.primaryId}`}
                actionKind="read"
                title={item.title}
                coverUrl={hdCover(item.coverUrl)}
                detailHref={href}
                playHref={href}
                topLeft={statusOf(item.remarks)}
                bottomRight={item.remarks || null}
                metaLeft={item.altTitle || '漫画'}
                metaTag={item.remarks || undefined}
                canFavorite={!isLocal}
                favorited={!isLocal && fav.isFavorited(activeStream.source, activeStream.id)}
                busy={fav.busyKey === key}
                onToggleFavorite={() => void fav.toggle(activeStream.source, activeStream.id, item.title)}
              />
            )
          })}
        </div>
      )}
    </section>
  )
}

/** 漫画发现主体：高清 Hero → 继续阅读 → 70/30 推荐网格+多榜侧栏 → 精选专题横滑 → 快捷探索。 */
function MangaDiscovery({ fav }: { fav: MangaFavHook }) {
  const navigate = useNavigate()
  const { me } = useAuth()
  const [sections, setSections] = useState<SourceSection[]>([])
  const [genres, setGenres] = useState<GenreTag[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [heroIdx, setHeroIdx] = useState(0)
  const [heroPaused, setHeroPaused] = useState(false)
  const [rankTab, setRankTab] = useState<MangaRankTab>('hot')

  const [continueManga, setContinueManga] = useState<ContinueRow[]>([])
  const [rec, setRec] = useState<MangaRecResult | null>(null)
  const [recOffset, setRecOffset] = useState(0)
  const [recLoading, setRecLoading] = useState(false)

  useEffect(() => {
    void api.get<GenreTag[]>('/api/v1/manga-catalog/genres')
      .then(setGenres)
      .catch(() => setGenres([]))
  }, [])

  useEffect(() => {
    if (!me) { setContinueManga([]); return }
    void api.get<ContinueRow[]>('/api/v1/me/continue?limit=12')
      .then(rows => setContinueManga(rows.filter(r => r.type === 'manga-read' || r.type === 'work-read').slice(0, 8)))
      .catch(() => { /* 私有续读失败不影响发现页 */ })
  }, [me])

  const loadRec = useCallback(async (offset: number) => {
    setRecLoading(true)
    try {
      const data = await api.get<MangaRecResult>(`/api/v1/recommendations/manga?limit=8&offset=${offset}`)
      setRec(data)
    } catch { /* 推荐接口失败不阻断发现页 */ }
    finally { setRecLoading(false) }
  }, [])

  useEffect(() => { void loadRec(0) }, [loadRec, me])

  const load = useCallback(async () => {
    const cached = readCache(CACHE_KEY)
    setLoading(true); setError(''); setHeroIdx(0)
    if (cached) {
      setSections(cached.sections)
    } else {
      setSections([])
    }
    try {
      const result = await api.get<DiscoveryResult>('/api/v1/manga-discovery?limit=14')
      const rows = (result.rows ?? []).filter(r => r.items.length > 0)
      setSections(rows)
      writeCache(CACHE_KEY, rows)
    } catch (e) {
      const message = e instanceof Error ? e.message : '推荐加载失败'
      if (!cached) setError(message)
    } finally { setLoading(false) }
  }, [])

  useEffect(() => { void load() }, [load])

  const hotRow = useMemo(
    () => sections.find(s => s.id === 'all') ?? sections[0] ?? null,
    [sections]
  )
  const riseRow = useMemo(
    () => sections.find(s => s.id.includes('rise') || s.name.includes('飙升')) ?? sections[1] ?? hotRow,
    [sections, hotRow]
  )
  const newRow = useMemo(
    () => sections.find(s => s.id.includes('new') || s.name.includes('新作')) ?? sections[2] ?? hotRow,
    [sections, hotRow]
  )

  const featured = useMemo(() => {
    const pool = (hotRow?.items ?? []).filter(it => it.readable || it.readId)
    return (pool.length > 0 ? pool : (hotRow?.items ?? [])).slice(0, 5)
  }, [hotRow])
  const hero = featured[heroIdx] ?? null

  useEffect(() => {
    if (featured.length < 2 || heroPaused) return
    const timer = window.setInterval(() => setHeroIdx(i => (i + 1) % featured.length), 7000)
    return () => window.clearInterval(timer)
  }, [featured.length, heroPaused])

  const curatedRows = useMemo(() => {
    const usedIds = new Set<string>([hotRow?.id ?? '', riseRow?.id ?? '', newRow?.id ?? ''])
    const pick = (pred: (s: SourceSection) => boolean, title: string) => {
      const hit = sections.find(s => !usedIds.has(s.id) && pred(s))
      if (!hit) return null
      usedIds.add(hit.id)
      return { id: hit.id, title, items: hit.items }
    }
    const out: { id: string; title: string; items: DiscoveryItem[] }[] = []
    const editor = pick(s => s.id.includes('recommend') || s.name.includes('推荐'), '编辑精选')
    if (editor) out.push(editor)
    const binge = pick(s => s.id.includes('pay') || s.id.includes('hot') || s.name.includes('畅销') || s.name.includes('真香'), '人气长篇')
    if (binge) out.push(binge)
    if (out.length < 2) {
      for (const s of sections) {
        if (usedIds.has(s.id) || s.items.length === 0) continue
        usedIds.add(s.id)
        out.push({ id: s.id, title: s.name === '热门' ? '人气佳作' : s.name, items: s.items })
        if (out.length >= 2) break
      }
    }
    return out
  }, [sections, hotRow, riseRow, newRow])

  const activeRankItems = useMemo(() => {
    if (rankTab === 'rise') return riseRow?.items ?? []
    if (rankTab === 'new') return newRow?.items ?? []
    return hotRow?.items ?? []
  }, [rankTab, hotRow, riseRow, newRow])

  function refreshRec() {
    const next = recOffset + 8
    setRecOffset(next)
    void loadRec(next)
  }

  const forYou = (rec?.forYou ?? []).slice(0, 8)
  const heroSource = hero ? targetSourceOf(hero) : ''
  const heroId = hero ? targetIdOf(hero) : ''
  const heroHref = hero ? mangaLink(heroSource, heroId, hero.title) : '/manga'
  const heroFav = hero ? fav.isFavorited(heroSource, heroId) : false
  const heroBusy = hero ? fav.busyKey === mangaFavKey(heroSource, heroId) : false

  return (
    <section className="manga-discover" style={{ marginTop: 0 }}>
      {error && (
        <div className="discover-error">
          <span className="error">{error}</span>
          <button type="button" className="ghost mini" onClick={() => void load()}>重试</button>
        </div>
      )}

      {loading && sections.length === 0 && (
        <>
          <div className="skeleton-box hero-skeleton" />
          <div className="card-grid" style={{ marginTop: 28 }}>
            {Array.from({ length: 7 }).map((_, i) => (
              <div key={i}>
                <div className="skeleton-box card-skeleton-cover" />
                <div className="skeleton-box card-skeleton-line" />
                <div className="skeleton-box card-skeleton-line short" />
              </div>
            ))}
          </div>
        </>
      )}

      {/* 高清 Hero 轮播 */}
      {hero && (
        <section className="manga-hero-banner"
                 onMouseEnter={() => setHeroPaused(true)}
                 onMouseLeave={() => setHeroPaused(false)}>
          <div className="manga-hero-bg" key={heroSource + heroId}>
            {hero.coverUrl && (
              <img className="manga-hero-bg-img" src={hdCover(hero.coverUrl)} alt=""
                   referrerPolicy="no-referrer" decoding="async"
                   onError={e => { e.currentTarget.style.display = 'none' }} />
            )}
          </div>
          <div className="manga-hero-shade" />
          <div className="manga-hero-inner">
            <Link to={heroHref} className="manga-hero-cover" aria-label={hero.title}>
              <CoverImage src={hdCover(hero.coverUrl)} alt={hero.title} loading="eager" fetchPriority="high" />
            </Link>
            <div className="manga-hero-info">
              <div className="hero-badges" style={{ marginBottom: 8 }}>
                <span className="hb hb-on">人气精选</span>
                <span className="hb hb-tv">{statusOf(hero.latestLabel, hero.finished)}</span>
                {hero.latestLabel && <span className="hb hb-time">{hero.latestLabel}</span>}
              </div>
              <h2>{hero.title}</h2>
              {hero.authors?.length > 0 && (
                <p className="mh-author">{hero.authors.join(' / ')}</p>
              )}
              <div className="hero-actions" style={{ marginTop: 18 }}>
                <button type="button" className="pill hero-play" onClick={() => navigate(heroHref)}>
                  <BookOpenIcon size={15} />
                  开始阅读
                </button>
                <button type="button" className="pill hero-sub-btn" disabled={heroBusy}
                        onClick={() => void fav.toggle(heroSource, heroId, hero.title)}>
                  <HeartIcon size={15} filled={heroFav} />
                  {heroBusy ? '处理中…' : (heroFav ? '已收藏' : '收藏')}
                </button>
                <Link to={heroHref} className="hero-info" title="详情" aria-label="查看详情">
                  <InfoIcon size={17} />
                </Link>
              </div>
            </div>
          </div>
          {featured.length > 1 && (
            <div className="manga-hero-thumbs">
              {featured.map((it, i) => (
                <button type="button" key={it.source + it.id}
                        className={'manga-hero-thumb' + (i === heroIdx ? ' on' : '')}
                        onClick={() => setHeroIdx(i)} title={it.title}>
                  <CoverImage src={it.coverUrl} alt={it.title} loading="eager" />
                  <span className="manga-hero-thumb-title">{it.title}</span>
                </button>
              ))}
            </div>
          )}
        </section>
      )}

      {/* 继续阅读 */}
      {continueManga.length > 0 && (
        <section style={{ marginTop: 28 }}>
          <div className="section-head">
            <h2>继续阅读</h2>
            <Link to="/me?tab=continue&kind=manga" className="muted">全部进度 →</Link>
          </div>
          <div className="hscroll">
            {continueManga.map(r => {
              const readHref = r.type === 'work-read'
                ? `/read/${r.revisionId}?chapter=${r.chapterId}&resume=${r.pageOrdinal ?? 1}`
                : (r.chapterId
                    ? `/manga-ext/read/${r.chapterId}?${new URLSearchParams({ mangaId: r.mangaId ?? '', lang: 'all', source: r.source ?? '', title: r.title })}`
                    : mangaLink(r.source ?? '', r.mangaId ?? '', r.title))
              const detailHref = r.type === 'work-read'
                ? `/manga/${r.workId}`
                : mangaLink(r.source ?? '', r.mangaId ?? '', r.title)
              const canFav = r.type === 'manga-read' && Boolean(r.source && r.mangaId)
              const key = mangaFavKey(r.source, r.mangaId)
              return (
                <AnimePosterCard
                  key={`${r.type}-${r.workId ?? r.mangaId}-${r.chapterId}`}
                  actionKind="read"
                  title={r.title}
                  coverUrl={hdCover(r.coverUrl)}
                  detailHref={detailHref}
                  playHref={readHref}
                  topLeft={`第 ${r.pageOrdinal ?? 1} 页`}
                  topLeftVariant="accent"
                  bottomRight={r.subtitle || null}
                  metaLeft="继续阅读"
                  metaTag={r.subtitle || undefined}
                  canFavorite={canFav}
                  favorited={canFav && fav.isFavorited(r.source, r.mangaId)}
                  busy={fav.busyKey === key}
                  onToggleFavorite={canFav ? () => void fav.toggle(r.source, r.mangaId, r.title) : undefined}
                />
              )
            })}
          </div>
        </section>
      )}

      {/* 70/30 非对称探索区 */}
      {(forYou.length > 0 || activeRankItems.length > 0) && (
        <section className="home-split-grid" style={{ marginTop: 30 }}>
          <div className="home-split-main">
            <div className="section-head">
              <h2>
                {rec?.personalized ? '为你推荐' : '精选推荐'}
                {rec && rec.topPreferences.length > 0 && (
                  <span className="muted" style={{ fontSize: 13, fontWeight: 400, marginLeft: 8 }}>
                    常读：{rec.topPreferences.slice(0, 3).join(' · ')}
                  </span>
                )}
              </h2>
              {(rec?.totalCandidates ?? 0) > 8 && (
                <button type="button" className="mini ghost" disabled={recLoading} onClick={refreshRec}>
                  {recLoading ? '刷新中…' : '换一批 ↻'}
                </button>
              )}
            </div>
            <div className="card-grid card-grid-4">
              {forYou.map(it => {
                const href = mangaLink(it.source, it.id, it.title)
                const key = mangaFavKey(it.source, it.id)
                return (
                  <AnimePosterCard
                    key={`${it.source}:${it.id}`}
                    actionKind="read"
                    title={it.title}
                    coverUrl={hdCover(it.coverUrl)}
                    detailHref={href}
                    playHref={href}
                    topLeft={statusOf(it.remarks)}
                    bottomRight={it.remarks || null}
                    metaLeft="漫画"
                    metaTag={it.reason}
                    favorited={fav.isFavorited(it.source, it.id)}
                    busy={fav.busyKey === key}
                    onToggleFavorite={() => void fav.toggle(it.source, it.id, it.title)}
                  />
                )
              })}
            </div>
          </div>

          <aside className="card home-rank-card">
            <div className="home-rank-head">
              <h2>漫画榜单</h2>
              <div className="home-rank-tabs" role="tablist" aria-label="漫画榜单切换">
                <button type="button" role="tab" aria-selected={rankTab === 'hot'}
                        className={'mini ' + (rankTab === 'hot' ? '' : 'ghost')}
                        onClick={() => setRankTab('hot')}>热榜</button>
                <button type="button" role="tab" aria-selected={rankTab === 'rise'}
                        className={'mini ' + (rankTab === 'rise' ? '' : 'ghost')}
                        onClick={() => setRankTab('rise')}>飙升</button>
                <button type="button" role="tab" aria-selected={rankTab === 'new'}
                        className={'mini ' + (rankTab === 'new' ? '' : 'ghost')}
                        onClick={() => setRankTab('new')}>新作</button>
              </div>
            </div>

            <ol className="home-rank-list">
              {activeRankItems.slice(0, 8).map((it, i) => {
                const src = targetSourceOf(it)
                const mid = targetIdOf(it)
                const href = mangaLink(src, mid, it.title)
                return (
                  <li key={src + mid} className="home-rank-row">
                    <span className={'rank-no tabular-nums' + (i < 3 ? ' top' : '')}>
                      {String(i + 1).padStart(2, '0')}
                    </span>
                    <Link to={href} className="home-rank-thumb">
                      <CoverImage src={hdCover(it.coverUrl)} alt={it.title} />
                    </Link>
                    <div className="home-rank-info">
                      <Link to={href} className="home-rank-title">{it.title}</Link>
                      <span className="muted">
                        {it.authors?.length ? it.authors[0] : statusOf(it.latestLabel, it.finished)}
                      </span>
                    </div>
                    {it.latestLabel && (
                      <span className="home-rank-metric tabular-nums">
                        {it.latestLabel.replace(/^更新[到至]/, '')}
                      </span>
                    )}
                  </li>
                )
              })}
            </ol>
          </aside>
        </section>
      )}

      {/* 精选专题横滑 */}
      {curatedRows.map(row => (
        <section key={row.id} style={{ marginTop: 30 }}>
          <div className="section-head">
            <h2>{row.title}</h2>
            <Link to="/index?kind=manga" className="muted">全部漫画 →</Link>
          </div>
          <div className="hscroll">
            {row.items.map(it => {
              const src = targetSourceOf(it)
              const mid = targetIdOf(it)
              const href = mangaLink(src, mid, it.title)
              const key = mangaFavKey(src, mid)
              return (
                <AnimePosterCard
                  key={src + mid}
                  actionKind="read"
                  title={it.title}
                  coverUrl={hdCover(it.coverUrl)}
                  detailHref={href}
                  playHref={href}
                  topLeft={statusOf(it.latestLabel, it.finished)}
                  bottomRight={it.latestLabel || null}
                  metaLeft={it.authors?.length ? it.authors[0] : '漫画'}
                  metaTag={it.finished ? '已完结' : (it.latestLabel ? '连载' : undefined)}
                  favorited={fav.isFavorited(src, mid)}
                  busy={fav.busyKey === key}
                  onToggleFavorite={() => void fav.toggle(src, mid, it.title)}
                />
              )
            })}
          </div>
        </section>
      ))}

      {/* 底部快捷探索 */}
      <section className="card home-explore-card" style={{ marginTop: 32 }}>
        <div className="section-head" style={{ marginBottom: 10 }}>
          <h2>快捷探索</h2>
          <div className="row-inline">
            <Link to="/index?kind=manga" className="muted">漫画索引 →</Link>
            <Link to="/index" className="muted">番剧索引 →</Link>
          </div>
        </div>
        <div className="explore-tags">
          <Link to="/index?kind=manga&status=ongoing" className="explore-tag-pill">连载新作</Link>
          <Link to="/index?kind=manga&status=completed" className="explore-tag-pill">完结一口气读</Link>
          <Link to="/index?kind=manga&sort=episodes" className="explore-tag-pill">长篇必看</Link>
          <Link to="/index?kind=manga&sort=updated" className="explore-tag-pill">最近更新</Link>
          {genres.slice(0, 12).map(g => (
            <Link key={g.tag} to={`/index?kind=manga&genre=${encodeURIComponent(g.tag)}`} className="explore-tag-pill">
              {g.tag}
            </Link>
          ))}
        </div>
      </section>
    </section>
  )
}

/** 本地漫画：仅当用户有站内上传的漫画时展示。 */
function LocalManga() {
  const [items, setItems] = useState<MangaCard[]>([])
  const [cursor, setCursor] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const load = useCallback(async (nextCursor: string | null, replace: boolean) => {
    setLoading(true); setError('')
    try {
      const qs = new URLSearchParams({ limit: '12' })
      if (nextCursor) qs.set('cursor', nextCursor)
      const page = await api.get<{ items: MangaCard[]; nextCursor: string | null }>(`/api/v1/manga?${qs}`)
      setItems(prev => replace ? page.items : [...prev, ...page.items])
      setCursor(page.nextCursor)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败')
    } finally { setLoading(false) }
  }, [])

  useEffect(() => { void load(null, true) }, [load])

  if (!loading && !error && items.length === 0) return null

  return (
    <section className="manga-local">
      <div className="section-head" style={{ marginTop: 30 }}>
        <h2>我的书架漫画</h2>
        <Link to="/uploads">上传漫画 →</Link>
      </div>

      {error && <p className="error">{error}</p>}

      <div className="card-grid">
        {items.map(m => (
          <AnimePosterCard
            key={m.id}
            actionKind="read"
            title={m.title}
            coverUrl={m.coverUrl}
            detailHref={`/manga/${m.id}`}
            playHref={`/manga/${m.id}`}
            topLeft={m.visibility === 'PRIVATE' ? '私人' : '书架'}
            bottomRight={m.latestOrdinal != null ? `第 ${m.latestOrdinal} 话` : `${m.chapterCount} 话`}
            metaLeft="书架漫画"
            metaTag={`${m.chapterCount} 话`}
            canFavorite={false}
          />
        ))}
      </div>

      {cursor && (
        <div style={{ marginTop: 20, textAlign: 'center' }}>
          <button type="button" className="ghost" disabled={loading} onClick={() => void load(cursor, false)}>
            {loading ? '加载中…' : '加载更多'}
          </button>
        </div>
      )}
    </section>
  )
}

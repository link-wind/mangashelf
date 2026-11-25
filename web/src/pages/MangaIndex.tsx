import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import CoverImage from '../components/CoverImage'
import Pagination from '../components/Pagination'
import { hdCover } from '../externalManga'

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

interface CatalogEntry {
  source: string; sourceItemId: string; name: string; altTitle: string
  coverUrl: string | null; remarks: string | null; tags: string[]; episodeCount: number | null
}
interface CatalogPage {
  items: CatalogEntry[]; nextCursor: string | null; total: number
  page: number; limit: number; totalPages: number
}
interface GenreTag { tag: string; items: number }

const CACHE_KEY = 'ms-manga-cache:all'
const CACHE_TTL_MS = 10 * 60 * 1000
const INDEX_PAGE_SIZE = 28
const MANGA_VIEW_KEY = 'ms-index-view'

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

/** 漫画专区：发现推荐、分类索引与漫画搜索。 */
export default function MangaIndex() {
  const [params, setParams] = useSearchParams()
  const rawTab = params.get('tab')
  const tab: 'discover' | 'index' = rawTab === 'index' ? 'index' : 'discover'
  const activeSearch = params.get('q') ?? ''
  const [searchInput, setSearchInput] = useState(activeSearch)

  useEffect(() => { setSearchInput(activeSearch) }, [activeSearch])

  function switchTab(nextTab: 'discover' | 'index') {
    const next = new URLSearchParams()
    if (nextTab === 'index') next.set('tab', 'index')
    setParams(next)
  }

  function submitSearch() {
    const trimmed = searchInput.trim()
    const next = new URLSearchParams(params)
    if (trimmed) {
      next.set('q', trimmed)
      next.delete('page')
    } else {
      next.delete('q')
    }
    setParams(next)
  }

  function clearSearch() {
    setSearchInput('')
    const next = new URLSearchParams(params)
    next.delete('q')
    next.delete('stream')
    setParams(next)
  }

  return (
    <main className="watch-page">
      <div className="manga-header-bar">
        <div className="manga-header-left">
          <h1 style={{ margin: 0 }}>漫画</h1>
          <div className="row-inline" role="tablist" aria-label="漫画视图切换">
            <button type="button" role="tab" aria-selected={!activeSearch && tab === 'discover'}
                    className={'pill ' + (!activeSearch && tab === 'discover' ? '' : 'ghost')}
                    onClick={() => switchTab('discover')}>
              发现推荐
            </button>
            <button type="button" role="tab" aria-selected={!activeSearch && tab === 'index'}
                    className={'pill ' + (!activeSearch && tab === 'index' ? '' : 'ghost')}
                    onClick={() => switchTab('index')}>
              分类索引
            </button>
          </div>
        </div>

        <form className="manga-search-form" onSubmit={e => { e.preventDefault(); submitSearch() }}>
          <input className="search" value={searchInput}
                 onChange={e => setSearchInput(e.target.value)}
                 placeholder="搜索漫画…"
                 aria-label="搜索漫画" />
          <button type="submit" className="mini">搜索</button>
          {activeSearch && (
            <button type="button" className="mini ghost" onClick={clearSearch}>清除</button>
          )}
        </form>
      </div>

      {activeSearch ? (
        <MangaSearchView query={activeSearch} onClear={clearSearch} />
      ) : tab === 'index' ? (
        <MangaCatalogIndex />
      ) : (
        <>
          <MangaDiscovery />
          <LocalManga />
        </>
      )}
    </main>
  )
}

/** 漫画搜索结果视图。 */
function MangaSearchView({ query, onClear }: { query: string; onClear: () => void }) {
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
    <section style={{ marginTop: 18 }}>
      <div className="section-head">
        <h2>
          搜索结果：「{query}」
          {loading && <span className="muted" style={{ fontSize: 13, fontWeight: 400, marginLeft: 8 }}>搜索中…</span>}
        </h2>
        <button type="button" className="mini ghost" onClick={onClear}>返回漫画页</button>
      </div>

      {error && <p className="error">{error}</p>}
      {loading && (
        <div className="card-grid">
          {Array.from({ length: 8 }).map((_, i) => (
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
            const primaryHref = mangaLink(activeStream.source, activeStream.id, item.title, activeStream.workId)
            return (
              <Link key={`${item.primarySource}:${item.primaryId}`} to={primaryHref} className="work-card">
                <div className="cover">
                  <CoverImage src={item.coverUrl} alt={item.title} />
                </div>
                <div className="work-card-body">
                  <div className="work-card-title clamp">{item.title}</div>
                  <div className="work-card-meta">
                    <span className="clamp">{item.remarks || item.altTitle || '漫画'}</span>
                  </div>
                </div>
              </Link>
            )
          })}
        </div>
      )}
    </section>
  )
}

/** Tab 1 · 发现推荐：精选 Hero + 继续阅读 + 为你推荐 + 热门榜单。 */
function MangaDiscovery() {
  const navigate = useNavigate()
  const { me } = useAuth()
  const [sections, setSections] = useState<SourceSection[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [heroIdx, setHeroIdx] = useState(0)

  const [continueManga, setContinueManga] = useState<ContinueRow[]>([])
  const [rec, setRec] = useState<MangaRecResult | null>(null)
  const [recOffset, setRecOffset] = useState(0)
  const [recLoading, setRecLoading] = useState(false)

  useEffect(() => {
    if (!me) { setContinueManga([]); return }
    void api.get<ContinueRow[]>('/api/v1/me/continue?limit=12')
      .then(rows => setContinueManga(rows.filter(r => r.type === 'manga-read' || r.type === 'work-read').slice(0, 6)))
      .catch(() => { /* 私有续读失败不影响发现页 */ })
  }, [me])

  const loadRec = useCallback(async (offset: number) => {
    setRecLoading(true)
    try {
      const data = await api.get<MangaRecResult>(`/api/v1/recommendations/manga?limit=12&offset=${offset}`)
      setRec(data)
    } catch { /* 推荐接口失败不阻断榜单 */ }
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
      const rows = result.rows ?? []
      setSections(rows)
      writeCache(CACHE_KEY, rows)
    } catch (e) {
      const message = e instanceof Error ? e.message : '推荐加载失败'
      if (!cached) setError(message)
    } finally { setLoading(false) }
  }, [])

  useEffect(() => { void load() }, [load])

  const featuredSection = useMemo(
    () => sections.find(s => s.items.length > 0) ?? null, [sections])
  const featured = useMemo(() => {
    if (!featuredSection) return []
    const readable = featuredSection.items.filter(it => it.readable || it.readId)
    const pool = readable.length >= 3 ? readable : featuredSection.items
    return pool.slice(0, 5)
  }, [featuredSection])
  const hero = featured[heroIdx] ?? null
  const heroLabel = featuredSection?.name || '热门推荐'

  useEffect(() => {
    if (featured.length < 2) return
    const timer = window.setInterval(() => setHeroIdx(i => (i + 1) % featured.length), 6000)
    return () => window.clearInterval(timer)
  }, [featured.length])

  function open(it: RankItem) {
    if (it.externalUrl && !it.readId) {
      window.open(it.externalUrl, '_blank', 'noopener,noreferrer')
      return
    }
    navigate(mangaLink(it.readSource ?? it.source, it.readId ?? it.id, it.title))
  }

  function refreshRec() {
    const next = recOffset + 12
    setRecOffset(next)
    void loadRec(next)
  }

  const displaySections = useMemo(
    () => sections.filter(s => s.items.length > 0).slice(0, 5),
    [sections]
  )

  return (
    <section className="manga-discover">
      {error && (
        <div className="discover-error">
          <span className="error">{error}</span>
          <button type="button" className="ghost mini" onClick={() => void load()}>重试</button>
        </div>
      )}

      {loading && sections.length === 0 && (
        <>
          <div className="manga-hero-banner skeleton" />
          <p className="muted">加载推荐…</p>
        </>
      )}

      {hero && (
        <div className="manga-hero-banner">
          <div className="manga-hero-bg" key={hero.source + hero.id}>
            {hero.coverUrl && (
              <img className="manga-hero-bg-img" src={hdCover(hero.coverUrl)} alt=""
                   referrerPolicy="no-referrer" decoding="async"
                   onError={e => { e.currentTarget.style.display = 'none' }} />
            )}
          </div>
          <div className="manga-hero-shade" />
          <div className="manga-hero-inner">
            <button type="button" className="manga-hero-cover" onClick={() => open(hero)} aria-label={hero.title}>
              <CoverImage src={hdCover(hero.coverUrl)} alt={hero.title} loading="eager" fetchPriority="high" />
            </button>
            <div className="manga-hero-info">
              <span className="manga-hero-kicker">{heroLabel}</span>
              <h2>{hero.title}</h2>
              {hero.authors?.length > 0 && (
                <p className="mh-author">{hero.authors.join(' / ')}</p>
              )}
              <div className="mh-chips">
                {hero.latestLabel && <span className="chip">{hero.latestLabel}</span>}
                {hero.finished && <span className="chip">已完结</span>}
              </div>
              <div className="manga-hero-actions">
                <button type="button" className="pill" onClick={() => open(hero)}>
                  {hero.readable || hero.readId ? '开始阅读' : '查看详情'}
                </button>
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
        </div>
      )}

      {continueManga.length > 0 && (
        <section className="manga-section">
          <div className="section-head">
            <h2>继续阅读</h2>
            <Link to="/me?tab=continue&kind=manga" className="muted">全部进度 →</Link>
          </div>
          <div className="manga-row">
            {continueManga.map(r => {
              const href = r.type === 'work-read'
                ? `/read/${r.revisionId}?chapter=${r.chapterId}&resume=${r.pageOrdinal ?? 1}`
                : (r.chapterId
                    ? `/manga-ext/read/${r.chapterId}?${new URLSearchParams({ mangaId: r.mangaId ?? '', lang: 'all', source: r.source ?? '', title: r.title })}`
                    : mangaLink(r.source ?? '', r.mangaId ?? '', r.title))
              return (
                <Link key={`${r.type}-${r.workId ?? r.mangaId}-${r.chapterId}`} to={href} className="manga-tile">
                  <div className="mt-cover">
                    <CoverImage src={r.coverUrl} alt={r.title} />
                    <span className="mt-multi">第 {r.pageOrdinal ?? 1} 页</span>
                  </div>
                  <div className="mt-title clamp">{r.title}</div>
                  <div className="mt-meta clamp">{r.subtitle || '继续阅读'}</div>
                </Link>
              )
            })}
          </div>
        </section>
      )}

      {rec && rec.forYou.length > 0 && (
        <section className="manga-section">
          <div className="section-head">
            <h2>
              {rec.personalized ? '为你推荐' : '精选推荐'}
              {rec.topPreferences.length > 0 && (
                <span className="muted" style={{ fontSize: 12, fontWeight: 400, marginLeft: 8 }}>
                  偏好：{rec.topPreferences.slice(0, 3).join(' · ')}
                </span>
              )}
            </h2>
            {rec.totalCandidates > 12 && (
              <button type="button" className="mini ghost" disabled={recLoading} onClick={refreshRec}>
                {recLoading ? '刷新中…' : '换一批 ↻'}
              </button>
            )}
          </div>
          <div className="manga-row">
            {rec.forYou.map(it => (
              <Link key={`${it.source}:${it.id}`} to={mangaLink(it.source, it.id, it.title)} className="manga-tile">
                <div className="mt-cover">
                  <CoverImage src={it.coverUrl} alt={it.title} />
                </div>
                <div className="mt-title clamp">{it.title}</div>
                <div className="mt-reason clamp">{it.reason}</div>
              </Link>
            ))}
          </div>
        </section>
      )}

      {displaySections.map(section => (
        <SectionRow key={section.id + (section.sourceId ?? '')} section={section}
                    showRank={section.id === 'all' || section.name.includes('榜')}
                    onOpen={open} />
      ))}

      {!loading && !error && sections.length > 0 && sections.every(s => s.items.length === 0) && (
        <p className="muted">暂无推荐内容。</p>
      )}
    </section>
  )
}

/** 分区横向卡片行。 */
function SectionRow({ section, showRank, onOpen }: {
  section: SourceSection; showRank: boolean; onOpen: (it: RankItem) => void
}) {
  return (
    <section className="manga-section">
      <div className="section-head">
        <h2>{section.name}</h2>
      </div>
      <div className="manga-row">
        {section.items.map((it, i) => (
          <button type="button" key={it.source + it.id} className="manga-tile" onClick={() => onOpen(it)}
                  title={it.title}>
            <div className="mt-cover">
              <CoverImage src={it.coverUrl} alt={it.title} />
              {showRank && <span className={'mt-rank' + (i < 3 ? ' top' : '')}>{i + 1}</span>}
            </div>
            <div className="mt-title clamp">{it.title}</div>
            <div className="mt-meta">{it.latestLabel || (it.finished ? '已完结' : '连载中')}</div>
          </button>
        ))}
      </div>
    </section>
  )
}

/** Tab 2 · 漫画分类分页索引：题材 + 状态 + 排序 + 页码分页。 */
function MangaCatalogIndex() {
  const [params, setParams] = useSearchParams()
  const genre = params.get('genre') ?? ''
  const status = params.get('status') ?? ''
  const sort = params.get('sort') ?? 'updated'
  const page = Math.max(1, Number(params.get('page') ?? '1') || 1)

  const [genres, setGenres] = useState<GenreTag[]>([])
  const [expandGenres, setExpandGenres] = useState(false)
  const [viewMode, setViewMode] = useState<'grid' | 'list'>(
    () => (localStorage.getItem(MANGA_VIEW_KEY) === 'list' ? 'list' : 'grid'))
  const [items, setItems] = useState<CatalogEntry[]>([])
  const [total, setTotal] = useState(0)
  const [totalPages, setTotalPages] = useState(1)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  function switchView(next: 'grid' | 'list') {
    setViewMode(next)
    localStorage.setItem(MANGA_VIEW_KEY, next)
  }

  useEffect(() => {
    void api.get<GenreTag[]>('/api/v1/manga-catalog/genres')
      .then(setGenres)
      .catch(() => setGenres([]))
  }, [])

  useEffect(() => {
    let cancelled = false
    void (async () => {
      setLoading(true); setError('')
      try {
        const qs = new URLSearchParams({
          limit: String(INDEX_PAGE_SIZE),
          page: String(page),
          sort,
        })
        if (genre) qs.set('genre', genre)
        if (status) qs.set('status', status)
        const data = await api.get<CatalogPage>(`/api/v1/manga-catalog?${qs}`)
        if (cancelled) return
        setItems(data.items)
        setTotal(data.total)
        setTotalPages(data.totalPages || 1)
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : '漫画索引加载失败')
      } finally {
        if (!cancelled) setLoading(false)
      }
    })()
    return () => { cancelled = true }
  }, [genre, status, sort, page])

  function updateFilter(patch: Record<string, string>, resetPage = true) {
    const next = new URLSearchParams(params)
    next.set('tab', 'index')
    for (const [k, v] of Object.entries(patch)) {
      if (!v) next.delete(k)
      else next.set(k, v)
    }
    if (resetPage) next.delete('page')
    setParams(next)
  }

  return (
    <section style={{ marginTop: 18 }}>
      <div className="index-filter-panel card">
        {genres.length > 0 && (
          <div className="filter-row">
            <span className="filter-label">题材</span>
            <div className="filter-pills">
              <button type="button" className={'mini ' + (genre === '' ? '' : 'ghost')}
                      onClick={() => updateFilter({ genre: '' })}>全部</button>
              {(expandGenres ? genres : genres.slice(0, 12)).map(g => (
                <button key={g.tag} type="button"
                        className={'mini ' + (genre === g.tag ? '' : 'ghost')}
                        onClick={() => updateFilter({ genre: g.tag })}>
                  {g.tag}
                </button>
              ))}
              {genres.length > 12 && (
                <button type="button" className="mini ghost"
                        onClick={() => setExpandGenres(v => !v)}>
                  {expandGenres ? '收起' : '更多'}
                </button>
              )}
            </div>
          </div>
        )}

        <div className="filter-row">
          <span className="filter-label">状态</span>
          <div className="filter-pills">
            {[
              { v: '', label: '全部状态' },
              { v: 'ongoing', label: '连载中' },
              { v: 'completed', label: '已完结' },
            ].map(st => (
              <button key={st.v} type="button"
                      className={'mini ' + (status === st.v ? '' : 'ghost')}
                      onClick={() => updateFilter({ status: st.v })}>{st.label}</button>
            ))}
          </div>
        </div>

        <div className="filter-row filter-row-actions">
          <span className="filter-label">排序</span>
          <div className="filter-pills">
            <button type="button" className={'mini ' + (sort === 'updated' ? '' : 'ghost')}
                    onClick={() => updateFilter({ sort: 'updated' })}>最近更新</button>
            <button type="button" className={'mini ' + (sort === 'episodes' ? '' : 'ghost')}
                    onClick={() => updateFilter({ sort: 'episodes' })}>话数最多</button>
          </div>
          <div className="row-inline" style={{ marginLeft: 'auto' }}>
            <div className="view-toggle" role="group" aria-label="视图切换">
              <button type="button" className={'mini ' + (viewMode === 'grid' ? '' : 'ghost')}
                      onClick={() => switchView('grid')}>网格</button>
              <button type="button" className={'mini ' + (viewMode === 'list' ? '' : 'ghost')}
                      onClick={() => switchView('list')}>列表</button>
            </div>
            {(genre || status || sort !== 'updated') && (
              <button type="button" className="mini ghost"
                      onClick={() => setParams(new URLSearchParams({ tab: 'index' }))}>重置</button>
            )}
          </div>
        </div>
      </div>

      {error && <p className="error">{error}</p>}
      {loading && items.length === 0 ? (
        <div className="card-grid">
          {Array.from({ length: 14 }).map((_, i) => (
            <div key={i}>
              <div className="skeleton-box card-skeleton-cover" />
              <div className="skeleton-box card-skeleton-line" />
              <div className="skeleton-box card-skeleton-line short" />
            </div>
          ))}
        </div>
      ) : items.length === 0 ? (
        <p className="muted">当前筛选下暂无漫画，可以尝试搜索标题。</p>
      ) : viewMode === 'list' ? (
        <>
          <div className="index-list-grid" style={{ opacity: loading ? 0.65 : 1, transition: 'opacity .15s' }}>
            {items.map(r => (
              <Link key={`${r.source}-${r.sourceItemId}`}
                    to={mangaLink(r.source, r.sourceItemId, r.name)}
                    className="index-list-card card" style={{ textDecoration: 'none', color: 'inherit' }}>
                <div className="index-list-poster">
                  <CoverImage src={r.coverUrl} alt={r.name} />
                </div>
                <div className="index-list-body">
                  <div className="index-list-title">{r.name}</div>
                  {r.altTitle && <div className="muted clamp">{r.altTitle}</div>}
                  <div className="index-list-meta muted">
                    <span>{r.remarks || (r.episodeCount ? `${r.episodeCount} 话` : '连载中')}</span>
                    {(r.tags ?? []).slice(0, 3).map(t => <span key={t} className="chip ghost">{t}</span>)}
                  </div>
                </div>
              </Link>
            ))}
          </div>
          <Pagination page={page} totalPages={totalPages} total={total} unit="部漫画"
                      loading={loading}
                      onChange={p => updateFilter({ page: String(p) }, false)} />
        </>
      ) : (
        <>
          <div className="card-grid" style={{ opacity: loading ? 0.65 : 1, transition: 'opacity .15s' }}>
            {items.map(r => (
              <Link key={`${r.source}-${r.sourceItemId}`}
                    to={mangaLink(r.source, r.sourceItemId, r.name)}
                    className="work-card">
                <div className="cover">
                  <CoverImage src={r.coverUrl} alt={r.name} />
                  {(r.remarks || r.episodeCount) && (
                    <span className="badge-br tabular-nums">
                      {r.remarks || `${r.episodeCount} 话`}
                    </span>
                  )}
                </div>
                <div className="work-card-body">
                  <div className="work-card-title">{r.name}</div>
                  <div className="work-card-meta">
                    <span className="clamp">{r.altTitle || r.remarks || '连载中'}</span>
                  </div>
                </div>
              </Link>
            ))}
          </div>
          <Pagination page={page} totalPages={totalPages} total={total} unit="部漫画"
                      loading={loading}
                      onChange={p => updateFilter({ page: String(p) }, false)} />
        </>
      )}
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
          <Link key={m.id} to={`/manga/${m.id}`} className="work-card">
            <div className="cover">
              <CoverImage src={m.coverUrl} alt={m.title} />
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
          <button type="button" className="ghost" disabled={loading} onClick={() => void load(cursor, false)}>
            {loading ? '加载中…' : '加载更多'}
          </button>
        </div>
      )}
    </section>
  )
}

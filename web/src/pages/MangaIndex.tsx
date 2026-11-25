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

interface SourceInfo {
  id: string; name: string; language: string; capabilities: string[]; genres: string[]; note: string
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
interface SourceStatus {
  source: string; name: string; count: number; ok: boolean; error: string | null; durationMs: number
}
interface UnifiedSearchResult {
  query: string; items: UnifiedSearchItem[]; sourceStatus: SourceStatus[]
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

const SOURCE_KEY = 'ms-manga-source'
const ALL = 'all'
const CACHE_TTL_MS = 10 * 60 * 1000
const INDEX_PAGE_SIZE = 24

interface SectionCache { ts: number; sections: SourceSection[]; warnings: string[] }

function readCache(key: string): SectionCache | null {
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return null
    const cached = JSON.parse(raw) as SectionCache
    if (!cached.sections || Date.now() - cached.ts > CACHE_TTL_MS) return null
    return cached
  } catch { return null }
}

function writeCache(key: string, sections: SourceSection[], warnings: string[]) {
  try {
    localStorage.setItem(key, JSON.stringify({ ts: Date.now(), sections, warnings }))
  } catch { /* 超配额时忽略 */ }
}

function mangaLink(source: string, id: string, title: string, workId?: string | null): string {
  if (source === 'local' || workId) return `/manga/${workId ?? id}`
  return `/manga-ext/${id}?${new URLSearchParams({ lang: 'all', source, title })}`
}

/**
 * 漫画专区（双 Tab + 全流搜索）：
 * - 顶部统一全流漫画搜索（并发检索全部在线源 + 目录库 + 本地书库，跨流去重可切流）；
 * - Tab 1「发现推荐」：精选 Hero + 继续阅读 + 为你推荐（可换一批）+ 多源融合榜单 + 本地漫画；
 * - Tab 2「分类索引」：来源流 / 题材 / 状态 / 排序 + 页码分页，参数双向同步 URL。
 */
export default function MangaIndex() {
  const [params, setParams] = useSearchParams()
  const rawTab = params.get('tab')
  const tab: 'discover' | 'index' = rawTab === 'index' ? 'index' : 'discover'
  const activeSearch = params.get('q') ?? ''
  const [searchInput, setSearchInput] = useState(activeSearch)
  const [allSources, setAllSources] = useState<SourceInfo[]>([])

  useEffect(() => { setSearchInput(activeSearch) }, [activeSearch])

  useEffect(() => {
    void api.get<SourceInfo[]>('/api/v1/manga-sources')
      .then(list => setAllSources(list.filter(s => s.language === 'zh')))
      .catch(() => { /* 源列表加载失败不影响本地内容 */ })
  }, [])

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
                 placeholder="搜索全流漫画（腾讯 / 包子 / 哔哩 / 本地）…"
                 aria-label="全流漫画搜索" />
          <button type="submit" className="mini">全流搜索</button>
          {activeSearch && (
            <button type="button" className="mini ghost" onClick={clearSearch}>清除</button>
          )}
        </form>
      </div>

      {activeSearch ? (
        <AllStreamSearchView query={activeSearch} onClear={clearSearch} />
      ) : tab === 'index' ? (
        <MangaCatalogIndex sources={allSources} />
      ) : (
        <>
          <MangaDiscovery sources={allSources.filter(s => s.capabilities.includes('RANK'))} />
          <LocalManga />
        </>
      )}
    </main>
  )
}

/** 全流漫画搜索结果视图：并发覆盖全部在线搜索源 + 目录库 + 本地漫画，跨流去重与切流。 */
function AllStreamSearchView({ query, onClear }: { query: string; onClear: () => void }) {
  const [params, setParams] = useSearchParams()
  const streamFilter = params.get('stream') ?? 'all'
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
        if (!cancelled) setError(e instanceof Error ? e.message : '全流搜索失败')
      } finally {
        if (!cancelled) setLoading(false)
      }
    })()
    return () => { cancelled = true }
  }, [query])

  function setStream(src: string) {
    const next = new URLSearchParams(params)
    if (src === 'all') next.delete('stream')
    else next.set('stream', src)
    setParams(next, { replace: true })
  }

  const allItems = result?.items ?? []
  const streamCounts = useMemo(() => {
    const map = new Map<string, { name: string; count: number }>()
    for (const item of allItems) {
      for (const st of item.streams) {
        const cur = map.get(st.source)
        map.set(st.source, { name: st.sourceName, count: (cur?.count ?? 0) + 1 })
      }
    }
    return [...map.entries()]
  }, [allItems])

  const shown = streamFilter === 'all'
    ? allItems
    : allItems.filter(it => it.streams.some(s => s.source === streamFilter))

  return (
    <section style={{ marginTop: 18 }}>
      <div className="section-head">
        <h2>全流搜索：「{query}」<span className="muted" style={{ fontSize: 13, fontWeight: 400, marginLeft: 8 }}>
          {loading ? '正在并发检索各流…' : `去重后 ${shown.length} 部`}
        </span></h2>
        <button type="button" className="mini ghost" onClick={onClear}>返回漫画页</button>
      </div>

      {result && result.sourceStatus.length > 0 && (
        <div className="stream-status-bar">
          {result.sourceStatus.map(st => (
            <span key={st.source} className={'stream-pill ' + (st.ok ? 'ok' : 'warn')}
                  title={st.error ?? `${st.durationMs} ms`}>
              {st.name}：{st.ok ? `${st.count} 条` : (st.error ?? '失败')}
            </span>
          ))}
        </div>
      )}

      {streamCounts.length > 1 && (
        <div className="row-inline" style={{ marginBottom: 14 }}>
          <button type="button" className={'mini ' + (streamFilter === 'all' ? '' : 'ghost')}
                  onClick={() => setStream('all')}>全部流 · {allItems.length}</button>
          {streamCounts.map(([src, info]) => (
            <button key={src} type="button"
                    className={'mini ' + (streamFilter === src ? '' : 'ghost')}
                    onClick={() => setStream(src)}>
              {info.name} · {info.count}
            </button>
          ))}
        </div>
      )}

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
        <p className="muted">所有可用流均未找到「{query}」相关漫画，可以尝试换个简短关键词。</p>
      )}

      {!loading && shown.length > 0 && (
        <div className="card-grid">
          {shown.map(item => {
            const activeStream = streamFilter === 'all'
              ? item.streams.find(s => s.source === item.primarySource) ?? item.streams[0]
              : item.streams.find(s => s.source === streamFilter) ?? item.streams[0]
            const primaryHref = mangaLink(activeStream.source, activeStream.id, item.title, activeStream.workId)
            return (
              <div key={`${item.primarySource}:${item.primaryId}`} className="work-card">
                <Link to={primaryHref} className="cover">
                  <CoverImage src={item.coverUrl} alt={item.title} />
                  {item.sourceCount > 1
                    ? <span className="badge-anim">{item.sourceCount} 流可用</span>
                    : <span className="badge-anim" style={item.workId ? { background: 'var(--pub)' } : undefined}>
                        {activeStream.sourceName}
                      </span>}
                </Link>
                <div className="work-card-body">
                  <Link to={primaryHref} className="work-card-title clamp" style={{ color: 'inherit', textDecoration: 'none' }}>
                    {item.title}
                  </Link>
                  <div className="work-card-meta">
                    <span className="clamp">{item.remarks || item.altTitle || activeStream.sourceName}</span>
                  </div>
                  {item.streams.length > 1 && (
                    <div className="stream-switch-row">
                      {item.streams.map(st => (
                        <Link key={`${st.source}:${st.id}`}
                              to={mangaLink(st.source, st.id, item.title, st.workId)}
                              className="stream-chip">
                          {st.sourceName}
                        </Link>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            )
          })}
        </div>
      )}
    </section>
  )
}

/** Tab 1 · 发现推荐：精选 Hero + 继续阅读 + 为你推荐（可换一批）+ 多源融合榜单。 */
function MangaDiscovery({ sources }: { sources: SourceInfo[] }) {
  const navigate = useNavigate()
  const { me } = useAuth()
  const [source, setSource] = useState<string>(() => localStorage.getItem(SOURCE_KEY) ?? ALL)
  const [sections, setSections] = useState<SourceSection[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [warnings, setWarnings] = useState<string[]>([])
  const [heroIdx, setHeroIdx] = useState(0)

  const [continueManga, setContinueManga] = useState<ContinueRow[]>([])
  const [rec, setRec] = useState<MangaRecResult | null>(null)
  const [recOffset, setRecOffset] = useState(0)
  const [recLoading, setRecLoading] = useState(false)

  useEffect(() => {
    setSource(prev => (prev === ALL || sources.some(s => s.id === prev) ? prev : ALL))
  }, [sources])

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

  const load = useCallback(async (src: string) => {
    if (!src) return
    const cacheKey = `ms-manga-cache:${src}`
    const cached = readCache(cacheKey)
    setLoading(true); setError(''); setHeroIdx(0)
    if (cached) {
      setSections(cached.sections)
      setWarnings(cached.warnings ?? [])
    } else {
      setSections([]); setWarnings([])
    }
    try {
      let rows: SourceSection[]
      let warns: string[] = []
      if (src === ALL) {
        const result = await api.get<DiscoveryResult>('/api/v1/manga-discovery?limit=14')
        rows = result.rows ?? []
        warns = result.errors ?? []
      } else {
        rows = await api.get<SourceSection[]>(`/api/v1/manga-sources/${src}/sections?limit=14`)
      }
      setSections(rows)
      setWarnings(warns)
      writeCache(cacheKey, rows, warns)
    } catch (e) {
      const message = e instanceof Error ? e.message : '推荐加载失败'
      if (cached) setWarnings([message])
      else setError(message)
    } finally { setLoading(false) }
  }, [])

  useEffect(() => {
    if (!source) return
    localStorage.setItem(SOURCE_KEY, source)
    void load(source)
  }, [source, load])

  const current = sources.find(s => s.id === source)
  const featuredSection = useMemo(
    () => sections.find(s => s.items.length > 0) ?? null, [sections])
  // Hero 优先挑选站内可读的高共识条目，避免纯外链占头图
  const featured = useMemo(() => {
    if (!featuredSection) return []
    const readable = featuredSection.items.filter(it => it.readable || it.readId)
    const pool = readable.length >= 3 ? readable : featuredSection.items
    return pool.slice(0, 5)
  }, [featuredSection])
  const hero = featured[heroIdx] ?? null
  const heroLabel = featuredSection
    ? (featuredSection.sourceName
        ? `${featuredSection.sourceName} · ${featuredSection.name}`
        : `多源共识 · ${featuredSection.name}`)
    : ''
  const heroSources = hero?.sources ?? []

  useEffect(() => {
    if (featured.length < 2) return
    const timer = window.setInterval(() => setHeroIdx(i => (i + 1) % featured.length), 6000)
    return () => window.clearInterval(timer)
  }, [featured.length, source])

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

  // 综合模式下精简展示：综合热榜 + 最多 4 个分区行，避免过长堆砌
  const displaySections = useMemo(() => {
    const nonEmpty = sections.filter(s => s.items.length > 0)
    return source === ALL ? nonEmpty.slice(0, 5) : nonEmpty
  }, [sections, source])

  return (
    <section className="manga-discover">
      <div className="discover-head">
        <div className="source-tabs">
          <button type="button" className={source === ALL ? 'mini' : 'mini ghost'}
                  onClick={() => setSource(ALL)}>综合推荐</button>
          {sources.map(s => (
            <button type="button" key={s.id} className={s.id === source ? 'mini' : 'mini ghost'}
                    onClick={() => setSource(s.id)}>{s.name}</button>
          ))}
        </div>
        <span className="muted" style={{ fontSize: 12 }}>
          {loading && sections.length > 0 ? '更新中…' : (source === ALL
            ? '可读优先 + 跨源共识融合 + 同作者打散'
            : current?.note ?? '按单源浏览推荐与榜单')}
        </span>
      </div>

      {error && (
        <div className="discover-error">
          <span className="error">{error}</span>
          <button type="button" className="ghost mini" onClick={() => void load(source)}>重试</button>
        </div>
      )}
      {warnings.length > 0 && (
        <p className="muted" style={{ fontSize: 12, margin: '0 0 10px' }}>
          部分源提示：{warnings.join('；')}
        </p>
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
                {heroSources.length > 1 && (
                  <span className="chip ghost" title={heroSources.join('、')}>
                    {heroSources.length} 源共识
                  </span>
                )}
                <span className="chip ghost">{hero.readable ? '站内可读' : hero.readId ? '有站内目录' : '站外阅读'}</span>
              </div>
              <div className="manga-hero-actions">
                <button type="button" className="pill" onClick={() => open(hero)}>
                  {hero.readable ? '开始阅读' : hero.readId ? '查看章节' : '前往原站'}
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
                  <div className="mt-meta clamp">{r.subtitle || (r.source ?? '本地漫画')}</div>
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
              {rec.personalized ? '为你推荐' : '精选可读推荐'}
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
                  {it.sourceCount > 1 && <span className="mt-multi">{it.sourceCount} 源</span>}
                  {!it.readable && <span className="mt-outside">站外</span>}
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
        <p className="muted">该源暂无推荐内容。</p>
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
        <h2>
          {section.name}
          {section.sourceName && (
            <span className="muted" style={{ fontSize: 12, fontWeight: 400, marginLeft: 8 }}>
              {section.sourceName}
            </span>
          )}
        </h2>
        <span className="muted" style={{ fontSize: 12 }}>{section.items.length} 部</span>
      </div>
      <div className="manga-row">
        {section.items.map((it, i) => (
          <button type="button" key={it.source + it.id} className="manga-tile" onClick={() => onOpen(it)}
                  title={(it.sources?.length ?? 0) > 1
                    ? `${it.title}（${it.sources!.join('、')}）` : it.title}>
            <div className="mt-cover">
              <CoverImage src={it.coverUrl} alt={it.title} />
              {showRank && <span className={'mt-rank' + (i < 3 ? ' top' : '')}>{i + 1}</span>}
              {(it.sourceCount ?? 1) > 1 && <span className="mt-multi">{it.sourceCount} 源</span>}
              {!it.readable && !it.readId && <span className="mt-outside">站外</span>}
            </div>
            <div className="mt-title clamp">{it.title}</div>
            <div className="mt-meta">{it.latestLabel || (it.finished ? '已完结' : '连载中')}</div>
          </button>
        ))}
      </div>
    </section>
  )
}

/** Tab 2 · 漫画分类分页索引：全流/单源 + 题材 + 状态 + 排序 + 页码分页。 */
function MangaCatalogIndex({ sources }: { sources: SourceInfo[] }) {
  const [params, setParams] = useSearchParams()
  const source = params.get('source') ?? 'all'
  const genre = params.get('genre') ?? ''
  const status = params.get('status') ?? ''
  const sort = params.get('sort') ?? 'updated'
  const page = Math.max(1, Number(params.get('page') ?? '1') || 1)

  const [genres, setGenres] = useState<GenreTag[]>([])
  const [items, setItems] = useState<CatalogEntry[]>([])
  const [total, setTotal] = useState(0)
  const [totalPages, setTotalPages] = useState(1)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    const qs = source && source !== 'all' ? `?source=${encodeURIComponent(source)}` : ''
    void api.get<GenreTag[]>(`/api/v1/manga-catalog/genres${qs}`)
      .then(setGenres)
      .catch(() => setGenres([]))
  }, [source])

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
        if (source && source !== 'all') qs.set('source', source)
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
  }, [source, genre, status, sort, page])

  function updateFilter(patch: Record<string, string>, resetPage = true) {
    const next = new URLSearchParams(params)
    next.set('tab', 'index')
    for (const [k, v] of Object.entries(patch)) {
      if (!v || (k === 'source' && v === 'all')) next.delete(k)
      else next.set(k, v)
    }
    if (resetPage) next.delete('page')
    setParams(next)
  }

  const sourceNames = useMemo(() => {
    const m = new Map<string, string>()
    for (const s of sources) m.set(s.id, s.name)
    return m
  }, [sources])

  return (
    <section style={{ marginTop: 18 }}>
      <div className="index-filter-panel card">
        <div className="filter-row">
          <span className="filter-label">来源</span>
          <div className="filter-pills">
            <button type="button" className={'mini ' + (source === 'all' ? '' : 'ghost')}
                    onClick={() => updateFilter({ source: 'all', genre: '' })}>全部流</button>
            {sources.map(s => (
              <button key={s.id} type="button"
                      className={'mini ' + (source === s.id ? '' : 'ghost')}
                      onClick={() => updateFilter({ source: s.id, genre: '' })}>
                {s.name}
              </button>
            ))}
          </div>
        </div>

        {genres.length > 0 && (
          <div className="filter-row">
            <span className="filter-label">题材</span>
            <div className="filter-pills">
              <button type="button" className={'mini ' + (genre === '' ? '' : 'ghost')}
                      onClick={() => updateFilter({ genre: '' })}>全部</button>
              {genres.slice(0, 18).map(g => (
                <button key={g.tag} type="button"
                        className={'mini ' + (genre === g.tag ? '' : 'ghost')}
                        onClick={() => updateFilter({ genre: g.tag })}>
                  {g.tag}
                </button>
              ))}
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
                    onClick={() => updateFilter({ sort: 'updated' })}>最近入库</button>
            <button type="button" className={'mini ' + (sort === 'episodes' ? '' : 'ghost')}
                    onClick={() => updateFilter({ sort: 'episodes' })}>话数最多</button>
          </div>
          {(source !== 'all' || genre || status || sort !== 'updated') && (
            <button type="button" className="mini ghost"
                    onClick={() => setParams(new URLSearchParams({ tab: 'index' }))}>重置</button>
          )}
        </div>
      </div>

      {error && <p className="error">{error}</p>}
      {loading && items.length === 0 ? (
        <div className="card-grid">
          {Array.from({ length: 12 }).map((_, i) => (
            <div key={i}>
              <div className="skeleton-box card-skeleton-cover" />
              <div className="skeleton-box card-skeleton-line" />
              <div className="skeleton-box card-skeleton-line short" />
            </div>
          ))}
        </div>
      ) : items.length === 0 ? (
        <p className="muted">目录库在当前筛选下暂无条目——可以使用上方「全流搜索」实时检索各漫画源（命中条目会自动沉淀进索引）。</p>
      ) : (
        <>
          <div className="card-grid" style={{ opacity: loading ? 0.65 : 1, transition: 'opacity .15s' }}>
            {items.map(r => (
              <Link key={`${r.source}-${r.sourceItemId}`}
                    to={mangaLink(r.source, r.sourceItemId, r.name)}
                    className="work-card">
                <div className="cover">
                  <CoverImage src={r.coverUrl} alt={r.name} />
                  <span className="badge-anim">{sourceNames.get(r.source) ?? r.source}</span>
                </div>
                <div className="work-card-body">
                  <div className="work-card-title clamp">{r.name}</div>
                  <div className="work-card-meta">
                    <span className="clamp">
                      {r.remarks || (r.episodeCount ? `${r.episodeCount} 话` : (r.altTitle || (sourceNames.get(r.source) ?? r.source)))}
                    </span>
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

/** 本地漫画：保留站内上传的私人/公开本地漫画书库。 */
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

  return (
    <section className="manga-local">
      <div className="section-head" style={{ marginTop: 30 }}>
        <h2>站内本地漫画 <span className="muted" style={{ fontSize: 13, fontWeight: 400 }}>{items.length} 部</span></h2>
        <Link to="/uploads">上传漫画 →</Link>
      </div>

      {error && <p className="error">{error}</p>}
      {items.length === 0 && !loading && (
        <p className="muted">还没有本地漫画——可在「我的素材」上传图片或 CBZ 发布章节。</p>
      )}

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

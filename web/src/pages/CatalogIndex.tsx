import { BookOpen, Film, LayoutGrid, List } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { api } from '../api'
import AnimePosterCard from '../components/AnimePosterCard'
import CoverImage from '../components/CoverImage'
import Pagination from '../components/Pagination'
import EmptyState from '../components/ui/EmptyState'
import SegmentedTabs from '../components/ui/SegmentedTabs'
import { hdCover } from '../externalManga'
import { cleanMangaRemarks, cleanSubtitle, formatStatusLabel } from '../lib/mediaFormat'
import { useCatalogFavorites } from '../useCatalogFavorites'
import { mangaFavKey, useMangaFavorites } from '../useMangaFavorites'

type IndexKind = 'anime' | 'manga'
type ViewMode = 'grid' | 'list'

const PAGE_SIZE = 28
const VIEW_KEY = 'ms-index-view'

/** 统一内容索引页：支持「番剧 / 漫画」一键切换 + 多维筛选 + 网格/列表双视图 + 页码分页。 */
export default function CatalogIndex() {
  const [params, setParams] = useSearchParams()
  const kind: IndexKind = params.get('kind') === 'manga' ? 'manga' : 'anime'
  const [viewMode, setViewMode] = useState<ViewMode>(
    () => (localStorage.getItem(VIEW_KEY) === 'list' ? 'list' : 'grid')
  )

  function switchKind(nextKind: IndexKind) {
    if (nextKind === kind) return
    const next = new URLSearchParams()
    if (nextKind === 'manga') next.set('kind', 'manga')
    setParams(next)
  }

  function switchView(next: ViewMode) {
    setViewMode(next)
    localStorage.setItem(VIEW_KEY, next)
  }

  return (
    <main className={'watch-page index-page' + (kind === 'manga' ? ' is-manga' : '')}>
      <div className="index-top-bar">
        <div className="row-inline" style={{ gap: 16 }}>
          <h1 style={{ margin: 0, fontSize: 24 }}>索引</h1>
          <SegmentedTabs
            layoutId="index-kind-tabs"
            tone={kind === 'manga' ? 'rose' : 'cyan'}
            ariaLabel="索引类型切换"
            value={kind}
            onChange={switchKind}
            items={[
              { value: 'anime', label: '番剧', icon: <Film size={14} /> },
              { value: 'manga', label: '漫画', icon: <BookOpen size={14} /> },
            ]}
          />
        </div>

        <SegmentedTabs
          layoutId="index-view-tabs"
          size="sm"
          tone="neutral"
          ariaLabel="视图切换"
          value={viewMode}
          onChange={switchView}
          items={[
            { value: 'grid', label: '网格', icon: <LayoutGrid size={13} /> },
            { value: 'list', label: '列表', icon: <List size={13} /> },
          ]}
        />
      </div>

      {kind === 'manga'
        ? <MangaIndexPanel viewMode={viewMode} />
        : <AnimeIndexPanel viewMode={viewMode} />}
    </main>
  )
}

// ── 番剧索引面板 ──

interface AnimeCatalogItem {
  id: string; sourceId: string; name: string; coverUrl: string; remarks: string
  categoryId: number; categoryName: string; episodeCount: number
  workId: string | null
  bangumiSubjectId?: number | null
}
interface AnimePageResponse {
  items: AnimeCatalogItem[]
  nextCursor: string | null
  total: number
  page: number
  limit: number
  totalPages: number
}
interface GenreStat { tag: string; items: number }

const ANIME_CATEGORIES: { id: string; label: string }[] = [
  { id: '', label: '全部' },
  { id: 'season', label: '本季新番' },
  { id: '30', label: '日韩动漫' },
  { id: '29', label: '国产动漫' },
  { id: '31', label: '欧美动漫' },
  { id: '32', label: '港台动漫' },
  { id: '33', label: '海外动漫' },
]

const ANIME_STATUS = [
  { value: '', label: '全部状态' },
  { value: 'ongoing', label: '连载中' },
  { value: 'completed', label: '已完结' },
  { value: 'movie', label: '剧场版·HD' },
]

const ANIME_YEARS = [
  { value: '', label: '全部年份' },
  { value: '2026', label: '2026' },
  { value: '2025', label: '2025' },
  { value: '2024', label: '2024' },
  { value: '2023', label: '2023' },
  { value: 'earlier', label: '更早' },
]

const ANIME_SORTS = [
  { value: 'updated', label: '最近更新' },
  { value: 'rating', label: '评分最高' },
  { value: 'episodes', label: '集数最多' },
]

const FALLBACK_ANIME_GENRES = ['热血', '奇幻', '冒险', '恋爱', '搞笑', '科幻', '悬疑', '治愈', '日常', '校园', '战斗', '机甲']

function AnimeIndexPanel({ viewMode }: { viewMode: ViewMode }) {
  const navigate = useNavigate()
  const { favIds, busyId, toggle: toggleFav } = useCatalogFavorites()
  const [params, setParams] = useSearchParams()

  const category = params.get('category') ?? ''
  const genre = params.get('genre') ?? ''
  const status = params.get('status') ?? ''
  const year = params.get('year') ?? ''
  const sort = params.get('sort') ?? 'updated'
  const activeQuery = params.get('q') ?? ''
  const page = Math.max(1, Number(params.get('page') ?? '1') || 1)

  const [qInput, setQInput] = useState(activeQuery)
  const [genres, setGenres] = useState<string[]>(FALLBACK_ANIME_GENRES)
  const [expandGenres, setExpandGenres] = useState(false)
  const [items, setItems] = useState<AnimeCatalogItem[]>([])
  const [total, setTotal] = useState(0)
  const [totalPages, setTotalPages] = useState(1)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => { setQInput(activeQuery) }, [activeQuery])

  useEffect(() => {
    void api.get<GenreStat[]>('/api/v1/catalog-items/genres')
      .then(list => { if (list.length > 0) setGenres(list.map(g => g.tag)) })
      .catch(() => { /* 失败时保留预置题材 */ })
  }, [])

  useEffect(() => {
    let cancelled = false
    void (async () => {
      setLoading(true); setError('')
      try {
        const qs = new URLSearchParams({
          limit: String(PAGE_SIZE),
          page: String(page),
          sort,
        })
        if (activeQuery) qs.set('q', activeQuery)
        if (category === 'season') qs.set('season', 'true')
        else if (category) qs.set('categoryId', category)
        if (genre) qs.set('genre', genre)
        if (status) qs.set('status', status)
        if (year) qs.set('year', year)

        const data = await api.get<AnimePageResponse>(`/api/v1/catalog-items?${qs}`)
        if (cancelled) return
        setItems(data.items)
        setTotal(data.total)
        setTotalPages(data.totalPages || 1)
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : '加载失败')
      } finally {
        if (!cancelled) setLoading(false)
      }
    })()
    return () => { cancelled = true }
  }, [category, genre, status, year, sort, activeQuery, page])

  function updateFilter(patch: Record<string, string>, resetPage = true) {
    const next = new URLSearchParams(params)
    next.delete('kind')
    for (const [k, v] of Object.entries(patch)) {
      if (!v) next.delete(k)
      else next.set(k, v)
    }
    if (resetPage) next.delete('page')
    setParams(next)
  }

  function submitSearch() {
    updateFilter({ q: qInput.trim() })
  }

  const hasFilters = Boolean(category || genre || status || year || activeQuery || sort !== 'updated')
  const shownGenres = expandGenres ? genres : genres.slice(0, 12)

  return (
    <>
      <div className="index-filter-panel card">
        <div className="filter-row">
          <span className="filter-label">分区</span>
          <div className="filter-pills">
            {ANIME_CATEGORIES.map(c => (
              <button key={c.label} type="button"
                      className={'idx-chip' + (category === c.id ? ' is-active' : '')}
                      onClick={() => updateFilter({ category: c.id })}>
                {c.label}
              </button>
            ))}
          </div>
        </div>

        <div className="filter-row">
          <span className="filter-label">题材</span>
          <div className="filter-pills">
            <button type="button" className={'idx-chip' + (genre === '' ? ' is-active' : '')}
                    onClick={() => updateFilter({ genre: '' })}>全部</button>
            {shownGenres.map(g => (
              <button key={g} type="button"
                      className={'idx-chip' + (genre === g ? ' is-active' : '')}
                      onClick={() => updateFilter({ genre: g })}>{g}</button>
            ))}
            {genres.length > 12 && (
              <button type="button" className="idx-chip is-more"
                      onClick={() => setExpandGenres(v => !v)}>
                {expandGenres ? '收起' : '更多 ▾'}
              </button>
            )}
          </div>
        </div>

        <div className="filter-row">
          <span className="filter-label">状态</span>
          <div className="filter-pills">
            {ANIME_STATUS.map(st => (
              <button key={st.value} type="button"
                      className={'idx-chip' + (status === st.value ? ' is-active' : '')}
                      onClick={() => updateFilter({ status: st.value })}>{st.label}</button>
            ))}
          </div>
        </div>

        <div className="filter-row">
          <span className="filter-label">年份</span>
          <div className="filter-pills">
            {ANIME_YEARS.map(y => (
              <button key={y.value} type="button"
                      className={'idx-chip' + (year === y.value ? ' is-active' : '')}
                      onClick={() => updateFilter({ year: y.value })}>{y.label}</button>
            ))}
          </div>
        </div>

        <div className="filter-row filter-row-actions">
          <span className="filter-label">排序</span>
          <div className="filter-pills">
            {ANIME_SORTS.map(so => (
              <button key={so.value} type="button"
                      className={'idx-chip' + (sort === so.value ? ' is-active' : '')}
                      onClick={() => updateFilter({ sort: so.value })}>{so.label}</button>
            ))}
          </div>
          <div className="filter-search-inline">
            <span className="muted tabular-nums" style={{ fontSize: 12 }}>共 {total} 部</span>
            <input placeholder="按标题筛选…" className="search" value={qInput}
                   onChange={e => setQInput(e.target.value)}
                   onKeyDown={e => e.key === 'Enter' && submitSearch()} />
            <button type="button" className="mini pill" onClick={submitSearch} disabled={loading}>筛选</button>
            {hasFilters && (
              <button type="button" className="idx-chip is-reset"
                      onClick={() => { setQInput(''); setParams(new URLSearchParams()) }}>
                重置条件
              </button>
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
        <p className="muted">当前筛选条件下暂无番剧——尝试切换分类或重置筛选条件。</p>
      ) : viewMode === 'list' ? (
        <>
          <div className="index-list-grid" style={{ opacity: loading ? 0.65 : 1, transition: 'opacity .15s' }}>
            {items.map(it => (
              <article key={it.id} className="index-list-card card">
                <Link to={`/catalog/${it.id}`} className="index-list-poster">
                  <CoverImage
                    src={it.bangumiSubjectId ? `/api/v1/bangumi-covers/${it.bangumiSubjectId}?size=large` : it.coverUrl}
                    alt={it.name}
                  />
                </Link>
                <div className="index-list-body">
                  <Link to={`/catalog/${it.id}`} className="index-list-title">{it.name}</Link>
                  <div className="index-list-meta muted tabular-nums">
                    {it.categoryName && <span className="chip ghost">{it.categoryName}</span>}
                    <span>{it.remarks || `${it.episodeCount} 集`}</span>
                  </div>
                  <div className="row-inline" style={{ marginTop: 'auto' }}>
                    <button type="button" className="mini"
                            onClick={() => navigate(it.workId ? `/works/${it.workId}` : `/browse/${it.id}`)}>播放</button>
                    <button type="button" className={'mini ' + (favIds.has(it.id) ? '' : 'ghost')}
                            disabled={busyId === it.id}
                            onClick={() => void toggleFav(it.id, it.name)}>
                      {favIds.has(it.id) ? '已收藏' : '收藏'}
                    </button>
                    <Link className="btn mini ghost" to={`/catalog/${it.id}`}>详情</Link>
                  </div>
                </div>
              </article>
            ))}
          </div>
          <Pagination page={page} totalPages={totalPages} total={total}
                      loading={loading}
                      onChange={p => updateFilter({ page: String(p) }, false)} />
        </>
      ) : (
        <>
          <div className="card-grid" style={{ opacity: loading ? 0.65 : 1, transition: 'opacity .15s' }}>
            {items.map(it => {
              const stLabel = formatStatusLabel(it.remarks)
              const cleanRem = it.remarks && it.remarks !== stLabel ? it.remarks : (it.episodeCount > 0 ? `${it.episodeCount} 集` : null)
              return (
                <AnimePosterCard
                  key={it.id}
                  title={it.name}
                  coverUrl={it.bangumiSubjectId ? `/api/v1/bangumi-covers/${it.bangumiSubjectId}?size=large` : it.coverUrl}
                  detailHref={it.workId ? `/works/${it.workId}` : `/catalog/${it.id}`}
                  playHref={it.workId ? `/works/${it.workId}` : `/browse/${it.id}`}
                  topLeft={stLabel}
                  topLeftVariant="dark"
                  bottomRight={cleanRem}
                  metaLeft={it.categoryName || '番剧'}
                  favorited={favIds.has(it.id)}
                  busy={busyId === it.id}
                  onToggleFavorite={() => void toggleFav(it.id, it.name)}
                />
              )
            })}
          </div>
          <Pagination page={page} totalPages={totalPages} total={total}
                      loading={loading}
                      onChange={p => updateFilter({ page: String(p) }, false)} />
        </>
      )}
    </>
  )
}

// ── 漫画索引面板 ──

interface MangaCatalogEntry {
  source: string; sourceItemId: string; name: string; altTitle: string
  coverUrl: string | null; remarks: string | null; tags: string[]; episodeCount: number | null
}
interface MangaCatalogPage {
  items: MangaCatalogEntry[]; nextCursor: string | null; total: number
  page: number; limit: number; totalPages: number
}

function mangaLink(source: string, id: string, title: string): string {
  if (source === 'local') return `/manga/${id}`
  return `/manga-ext/${id}?${new URLSearchParams({ lang: 'all', source, title })}`
}

function MangaIndexPanel({ viewMode }: { viewMode: ViewMode }) {
  const navigate = useNavigate()
  const fav = useMangaFavorites()
  const [params, setParams] = useSearchParams()

  const genre = params.get('genre') ?? ''
  const status = params.get('status') ?? ''
  const sort = params.get('sort') ?? 'updated'
  const activeQuery = params.get('q') ?? ''
  const page = Math.max(1, Number(params.get('page') ?? '1') || 1)

  const [qInput, setQInput] = useState(activeQuery)
  const [genres, setGenres] = useState<GenreStat[]>([])
  const [expandGenres, setExpandGenres] = useState(false)
  const [items, setItems] = useState<MangaCatalogEntry[]>([])
  const [total, setTotal] = useState(0)
  const [totalPages, setTotalPages] = useState(1)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => { setQInput(activeQuery) }, [activeQuery])

  useEffect(() => {
    void api.get<GenreStat[]>('/api/v1/manga-catalog/genres')
      .then(setGenres)
      .catch(() => setGenres([]))
  }, [])

  useEffect(() => {
    let cancelled = false
    void (async () => {
      setLoading(true); setError('')
      try {
        const qs = new URLSearchParams({
          limit: String(PAGE_SIZE),
          page: String(page),
          sort,
        })
        if (genre) qs.set('genre', genre)
        if (status) qs.set('status', status)
        if (activeQuery) qs.set('q', activeQuery)
        const data = await api.get<MangaCatalogPage>(`/api/v1/manga-catalog?${qs}`)
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
  }, [genre, status, sort, activeQuery, page])

  function updateFilter(patch: Record<string, string>, resetPage = true) {
    const next = new URLSearchParams(params)
    next.set('kind', 'manga')
    for (const [k, v] of Object.entries(patch)) {
      if (!v) next.delete(k)
      else next.set(k, v)
    }
    if (resetPage) next.delete('page')
    setParams(next)
  }

  function submitSearch() {
    updateFilter({ q: qInput.trim() })
  }

  const hasFilters = Boolean(genre || status || activeQuery || sort !== 'updated')

  return (
    <>
      <div className="index-filter-panel card">
        {genres.length > 0 && (
          <div className="filter-row">
            <span className="filter-label">题材</span>
            <div className="filter-pills">
              <button type="button" className={'idx-chip' + (genre === '' ? ' is-active is-rose' : '')}
                      onClick={() => updateFilter({ genre: '' })}>全部</button>
              {(expandGenres ? genres : genres.slice(0, 12)).map(g => (
                <button key={g.tag} type="button"
                        className={'idx-chip' + (genre === g.tag ? ' is-active is-rose' : '')}
                        onClick={() => updateFilter({ genre: g.tag })}>
                  {g.tag}
                </button>
              ))}
              {genres.length > 12 && (
                <button type="button" className="idx-chip is-more"
                        onClick={() => setExpandGenres(v => !v)}>
                  {expandGenres ? '收起' : '更多 ▾'}
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
                      className={'idx-chip' + (status === st.v ? ' is-active is-rose' : '')}
                      onClick={() => updateFilter({ status: st.v })}>{st.label}</button>
            ))}
          </div>
        </div>

        <div className="filter-row filter-row-actions">
          <span className="filter-label">排序</span>
          <div className="filter-pills">
            <button type="button" className={'idx-chip' + (sort === 'updated' ? ' is-active is-rose' : '')}
                    onClick={() => updateFilter({ sort: 'updated' })}>最近更新</button>
            <button type="button" className={'idx-chip' + (sort === 'episodes' ? ' is-active is-rose' : '')}
                    onClick={() => updateFilter({ sort: 'episodes' })}>话数最多</button>
          </div>
          <div className="filter-search-inline">
            <span className="muted tabular-nums" style={{ fontSize: 12 }}>共 {total} 部</span>
            <input placeholder="按标题筛选…" className="search" value={qInput}
                   onChange={e => setQInput(e.target.value)}
                   onKeyDown={e => e.key === 'Enter' && submitSearch()} />
            <button type="button" className="mini pill space-btn-rose" onClick={submitSearch} disabled={loading}>筛选</button>
            {hasFilters && (
              <button type="button" className="idx-chip is-reset"
                      onClick={() => { setQInput(''); setParams(new URLSearchParams({ kind: 'manga' })) }}>
                重置条件
              </button>
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
        <EmptyState
          tone="rose"
          icon={<BookOpen size={22} />}
          title="当前筛选下暂无漫画"
          description="尝试放宽题材或状态筛选，或使用 ⌘K 全局检索。"
          action={hasFilters ? (
            <button type="button" className="pill space-btn-rose" onClick={() => { setQInput(''); setParams(new URLSearchParams({ kind: 'manga' })) }}>
              重置全部筛选
            </button>
          ) : undefined}
        />
      ) : viewMode === 'list' ? (
        <>
          <div className="index-list-grid" style={{ opacity: loading ? 0.65 : 1, transition: 'opacity .15s' }}>
            {items.map(r => {
              const href = mangaLink(r.source, r.sourceItemId, r.name)
              const isFav = fav.isFavorited(r.source, r.sourceItemId)
              const key = mangaFavKey(r.source, r.sourceItemId)
              return (
                <article key={`${r.source}-${r.sourceItemId}`} className="index-list-card card">
                  <Link to={href} className="index-list-poster">
                    <CoverImage src={hdCover(r.coverUrl)} alt={r.name} />
                  </Link>
                  <div className="index-list-body">
                    <Link to={href} className="index-list-title">{r.name}</Link>
                    <div className="index-list-meta muted">
                      <span>{r.remarks || (r.episodeCount ? `${r.episodeCount} 话` : '连载中')}</span>
                      {r.altTitle && <span className="clamp">{r.altTitle}</span>}
                      {(r.tags ?? []).slice(0, 3).map(t => <span key={t} className="chip ghost">{t}</span>)}
                    </div>
                    <div className="row-inline" style={{ marginTop: 'auto' }}>
                      <button type="button" className="mini" onClick={() => navigate(href)}>阅读</button>
                      <button type="button" className={'mini ' + (isFav ? '' : 'ghost')}
                              disabled={fav.busyKey === key}
                              onClick={() => void fav.toggle(r.source, r.sourceItemId, r.name)}>
                        {isFav ? '已收藏' : '收藏'}
                      </button>
                      <Link className="btn mini ghost" to={href}>详情</Link>
                    </div>
                  </div>
                </article>
              )
            })}
          </div>
          <Pagination page={page} totalPages={totalPages} total={total}
                      loading={loading}
                      onChange={p => updateFilter({ page: String(p) }, false)} />
        </>
      ) : (
        <>
          <div className="card-grid" style={{ opacity: loading ? 0.65 : 1, transition: 'opacity .15s' }}>
            {items.map(r => {
              const href = mangaLink(r.source, r.sourceItemId, r.name)
              const key = mangaFavKey(r.source, r.sourceItemId)
              const stLabel = formatStatusLabel(r.remarks)
              const cleanRem = cleanMangaRemarks(r.remarks) || (r.episodeCount ? `${r.episodeCount} 话` : null)
              const cleanAuthor = cleanSubtitle(r.altTitle)
              const primaryTag = (r.tags ?? []).find(t => t && t.length >= 2)
              return (
                <AnimePosterCard
                  key={`${r.source}-${r.sourceItemId}`}
                  actionKind="read"
                  title={r.name}
                  coverUrl={hdCover(r.coverUrl)}
                  detailHref={href}
                  playHref={href}
                  topLeft={stLabel}
                  bottomRight={cleanRem}
                  metaLeft={cleanAuthor || primaryTag || '跨源漫画'}
                  metaTag={cleanAuthor && primaryTag ? primaryTag : undefined}
                  favorited={fav.isFavorited(r.source, r.sourceItemId)}
                  busy={fav.busyKey === key}
                  onToggleFavorite={() => void fav.toggle(r.source, r.sourceItemId, r.name)}
                />
              )
            })}
          </div>
          <Pagination page={page} totalPages={totalPages} total={total}
                      loading={loading}
                      onChange={p => updateFilter({ page: String(p) }, false)} />
        </>
      )}
    </>
  )
}

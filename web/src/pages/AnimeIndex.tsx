import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { api } from '../api'
import AnimePosterCard from '../components/AnimePosterCard'
import CoverImage from '../components/CoverImage'
import Pagination from '../components/Pagination'
import { useCatalogFavorites } from '../useCatalogFavorites'

interface CatalogItem {
  id: string; sourceId: string; name: string; coverUrl: string; remarks: string
  categoryId: number; categoryName: string; episodeCount: number
  workId: string | null
  bangumiSubjectId?: number | null
}

interface CatalogPageResponse {
  items: CatalogItem[]
  nextCursor: string | null
  total: number
  page: number
  limit: number
  totalPages: number
}

interface GenreStat { tag: string; items: number }

const CATEGORIES: { id: string, label: string }[] = [
  { id: '', label: '全部' },
  { id: 'season', label: '本季新番' },
  { id: '30', label: '日韩动漫' },
  { id: '29', label: '国产动漫' },
  { id: '31', label: '欧美动漫' },
  { id: '32', label: '港台动漫' },
  { id: '33', label: '海外动漫' },
]

const STATUS = [
  { value: '', label: '全部状态' },
  { value: 'ongoing', label: '连载中' },
  { value: 'completed', label: '已完结' },
  { value: 'movie', label: '剧场版·HD' },
]

const YEARS = [
  { value: '', label: '全部年份' },
  { value: '2026', label: '2026' },
  { value: '2025', label: '2025' },
  { value: '2024', label: '2024' },
  { value: '2023', label: '2023' },
  { value: 'earlier', label: '更早' },
]

const SORTS = [
  { value: 'updated', label: '最近更新' },
  { value: 'rating', label: '评分最高' },
  { value: 'episodes', label: '集数最多' },
]

const FALLBACK_GENRES = ['热血', '奇幻', '冒险', '恋爱', '搞笑', '科幻', '悬疑', '治愈', '日常', '校园', '战斗', '机甲']
const PAGE_SIZE = 28
const VIEW_KEY = 'ms-index-view'

/** 番剧索引：多维分类筛选 + 网格/列表双视图切换 + 页码分页直达 + 全高清 large 封面。 */
export default function AnimeIndex() {
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
  const [genres, setGenres] = useState<string[]>(FALLBACK_GENRES)
  const [expandGenres, setExpandGenres] = useState(false)
  const [viewMode, setViewMode] = useState<'grid' | 'list'>(
    () => (localStorage.getItem(VIEW_KEY) === 'list' ? 'list' : 'grid'))
  const [items, setItems] = useState<CatalogItem[]>([])
  const [total, setTotal] = useState(0)
  const [totalPages, setTotalPages] = useState(1)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => { setQInput(activeQuery) }, [activeQuery])

  useEffect(() => {
    void api.get<GenreStat[]>('/api/v1/catalog-items/genres')
      .then(list => {
        if (list.length > 0) setGenres(list.map(g => g.tag))
      })
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

        const data = await api.get<CatalogPageResponse>(`/api/v1/catalog-items?${qs}`)
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
    for (const [k, v] of Object.entries(patch)) {
      if (!v) next.delete(k)
      else next.set(k, v)
    }
    if (resetPage) next.delete('page')
    setParams(next)
  }

  function switchView(next: 'grid' | 'list') {
    setViewMode(next)
    localStorage.setItem(VIEW_KEY, next)
  }

  function submitSearch() {
    updateFilter({ q: qInput.trim() })
  }

  const hasFilters = Boolean(category || genre || status || year || activeQuery || sort !== 'updated')
  const shownGenres = expandGenres ? genres : genres.slice(0, 12)

  return (
    <main className="watch-page">
      <div className="section-head">
        <h1>番剧索引</h1>
        <div className="row-inline">
          <span className="muted tabular-nums">共 {total} 部</span>
          <div className="view-toggle" role="group" aria-label="视图切换">
            <button type="button" className={'mini ' + (viewMode === 'grid' ? '' : 'ghost')}
                    onClick={() => switchView('grid')}>网格</button>
            <button type="button" className={'mini ' + (viewMode === 'list' ? '' : 'ghost')}
                    onClick={() => switchView('list')}>列表</button>
          </div>
        </div>
      </div>

      <div className="index-filter-panel card">
        <div className="filter-row">
          <span className="filter-label">分区</span>
          <div className="filter-pills">
            {CATEGORIES.map(c => (
              <button key={c.label} type="button"
                      className={'mini ' + (category === c.id ? '' : 'ghost')}
                      onClick={() => updateFilter({ category: c.id })}>
                {c.label}
              </button>
            ))}
          </div>
        </div>

        <div className="filter-row">
          <span className="filter-label">题材</span>
          <div className="filter-pills">
            <button type="button" className={'mini ' + (genre === '' ? '' : 'ghost')}
                    onClick={() => updateFilter({ genre: '' })}>全部</button>
            {shownGenres.map(g => (
              <button key={g} type="button"
                      className={'mini ' + (genre === g ? '' : 'ghost')}
                      onClick={() => updateFilter({ genre: g })}>{g}</button>
            ))}
            {genres.length > 12 && (
              <button type="button" className="mini ghost"
                      onClick={() => setExpandGenres(v => !v)}>
                {expandGenres ? '收起' : `更多 (+${genres.length - 12})`}
              </button>
            )}
          </div>
        </div>

        <div className="filter-row">
          <span className="filter-label">状态</span>
          <div className="filter-pills">
            {STATUS.map(st => (
              <button key={st.value} type="button"
                      className={'mini ' + (status === st.value ? '' : 'ghost')}
                      onClick={() => updateFilter({ status: st.value })}>{st.label}</button>
            ))}
          </div>
        </div>

        <div className="filter-row">
          <span className="filter-label">年份</span>
          <div className="filter-pills">
            {YEARS.map(y => (
              <button key={y.value} type="button"
                      className={'mini ' + (year === y.value ? '' : 'ghost')}
                      onClick={() => updateFilter({ year: y.value })}>{y.label}</button>
            ))}
          </div>
        </div>

        <div className="filter-row filter-row-actions">
          <span className="filter-label">排序</span>
          <div className="filter-pills">
            {SORTS.map(so => (
              <button key={so.value} type="button"
                      className={'mini ' + (sort === so.value ? '' : 'ghost')}
                      onClick={() => updateFilter({ sort: so.value })}>{so.label}</button>
            ))}
          </div>
          <div className="filter-search-inline">
            <input placeholder="按标题筛选…" className="search" value={qInput}
                   onChange={e => setQInput(e.target.value)}
                   onKeyDown={e => e.key === 'Enter' && submitSearch()} />
            <button type="button" className="mini" onClick={submitSearch} disabled={loading}>筛选</button>
            {hasFilters && (
              <button type="button" className="mini ghost"
                      onClick={() => { setQInput(''); setParams(new URLSearchParams()) }}>
                重置
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
                  {it.workId && <span className="badge-tl is-pub">在库</span>}
                </Link>
                <div className="index-list-body">
                  <Link to={`/catalog/${it.id}`} className="index-list-title">{it.name}</Link>
                  <div className="index-list-meta muted tabular-nums">
                    {it.categoryName && <span className="chip ghost">{it.categoryName}</span>}
                    <span>{it.remarks || `${it.episodeCount} 集`}</span>
                  </div>
                  <div className="row-inline" style={{ marginTop: 'auto' }}>
                    {it.workId
                      ? <Link className="btn mini ghost" to={`/works/${it.workId}`}>打开作品</Link>
                      : <>
                          <button type="button" className="mini" onClick={() => navigate(`/browse/${it.id}`)}>播放</button>
                          <button type="button" className={'mini ' + (favIds.has(it.id) ? '' : 'ghost')}
                                  disabled={busyId === it.id}
                                  onClick={() => void toggleFav(it.id, it.name)}>
                            {favIds.has(it.id) ? '已收藏' : '收藏'}
                          </button>
                          <Link className="btn mini ghost" to={`/catalog/${it.id}`}>详情</Link>
                        </>
                    }
                  </div>
                </div>
              </article>
            ))}
          </div>
          <Pagination page={page} totalPages={totalPages} total={total} unit="部番剧"
                      loading={loading}
                      onChange={p => updateFilter({ page: String(p) }, false)} />
        </>
      ) : (
        <>
          <div className="card-grid" style={{ opacity: loading ? 0.65 : 1, transition: 'opacity .15s' }}>
            {items.map(it => (
              <AnimePosterCard
                key={it.id}
                title={it.name}
                coverUrl={it.bangumiSubjectId ? `/api/v1/bangumi-covers/${it.bangumiSubjectId}?size=large` : it.coverUrl}
                detailHref={it.workId ? `/works/${it.workId}` : `/catalog/${it.id}`}
                playHref={it.workId ? `/works/${it.workId}` : `/browse/${it.id}`}
                topLeft={it.workId ? '在库' : (it.remarks?.includes('完结') ? '已完结' : '连载中')}
                topLeftVariant={it.workId ? 'pub' : 'dark'}
                bottomRight={it.remarks || `${it.episodeCount} 集`}
                metaLeft={it.categoryName || '番剧'}
                metaTag={it.episodeCount > 0 ? `${it.episodeCount} 集` : undefined}
                favorited={favIds.has(it.id)}
                busy={busyId === it.id}
                onToggleFavorite={() => void toggleFav(it.id, it.name)}
              />
            ))}
          </div>
          <Pagination page={page} totalPages={totalPages} total={total} unit="部番剧"
                      loading={loading}
                      onChange={p => updateFilter({ page: String(p) }, false)} />
        </>
      )}
    </main>
  )
}

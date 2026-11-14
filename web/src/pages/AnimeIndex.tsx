import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { api } from '../api'

interface CatalogItem {
  id: string; sourceId: string; name: string; coverUrl: string; remarks: string
  categoryId: number; categoryName: string; episodeCount: number
  workId: string | null
}

// id 为数字（资源站分类）或 'season'（本季新番视图，anime-index-full-catalog）
const CATEGORIES: { id: number | 'season' | undefined, label: string }[] = [
  { id: undefined, label: '全部' },
  { id: 'season', label: '本季新番' },
  { id: 30, label: '日韩动漫' },
  { id: 29, label: '国产动漫' },
  { id: 31, label: '欧美动漫' },
  { id: 33, label: '海外动漫' },
]
// 状态与排序（mobile-ui-index-and-reader，咕咕番式筛选）：服务端按 remarks/category_name 过滤
const STATUS = [
  { value: '', label: '全部状态' },
  { value: 'ongoing', label: '连载中' },
  { value: 'completed', label: '已完结' },
  { value: 'movie', label: '剧场版·HD' },
]
const SORTS = [
  { value: 'updated', label: '最近更新' },
  { value: 'episodes', label: '集数最多' },
]
// 题材（mobile-nav-genre-remove-mangadex）：与 Bangumi 条目 tags 匹配，服务端过滤
const GENRES = ['热血', '机甲', '恋爱', '搞笑', '冒险', '科幻', '奇幻', '运动', '悬疑', '治愈', '日常', '古风']

/** 番剧索引（M8 目录层）：资源站全库封面卡 + 分类筛选 + 搜索 + 一键入书库。 */
export default function AnimeIndex() {
  const navigate = useNavigate()
  const [items, setItems] = useState<CatalogItem[]>([])
  const [cursor, setCursor] = useState<string | null>(null)
  const [q, setQ] = useState('')
  const [activeQuery, setActiveQuery] = useState('')
  const [params] = useSearchParams()
  const initialCat = params.get('category')
  const [categoryId, setCategoryId] = useState<string | undefined>(
    initialCat ? (initialCat === 'season' ? 'season' : String(Number(initialCat))) : undefined)
  const [status, setStatus] = useState('')
  const [sort, setSort] = useState('updated')
  const [genre, setGenre] = useState('')
  const [pageIndex, setPageIndex] = useState(0)
  const [loading, setLoading] = useState(false)
  const [busyItem, setBusyItem] = useState<string | null>(null)
  const [error, setError] = useState('')

  // 集数排序用偏移分页（无键集游标），其余用游标。
  const load = useCallback(async (nextCursor: string | null, query: string, cat: string | undefined,
                                  st: string, sot: string, gen: string, pageIdx: number, replace: boolean) => {
    setLoading(true); setError('')
    try {
      const qs = new URLSearchParams({ limit: '24' })
      if (sot === 'episodes') {
        qs.set('sort', 'episodes')
        qs.set('page', String(pageIdx))
      } else if (nextCursor) {
        qs.set('cursor', nextCursor)
      }
      if (query) qs.set('q', query)
      if (cat === 'season') {
        qs.set('season', 'true')
      } else if (cat !== undefined && cat !== '') {
        qs.set('categoryId', cat)
      }
      if (st) qs.set('status', st)
      if (gen) qs.set('genre', gen)
      const page = await api.get<{ items: CatalogItem[]; nextCursor: string | null }>(`/api/v1/catalog-items?${qs}`)
      setItems(prev => replace ? page.items : [...prev, ...page.items])
      setCursor(page.nextCursor)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败')
    } finally { setLoading(false) }
  }, [])

  useEffect(() => { void load(null, '', initialCat ?? undefined, status, sort, genre, 0, true) },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [load, initialCat, status, sort])

  function search() {
    setActiveQuery(q.trim())
    void load(null, q.trim(), categoryId, status, sort, genre, 0, true)
  }

  function pickCategory(id: string | undefined) {
    setCategoryId(id)
    void load(null, activeQuery, id, status, sort, genre, 0, true)
  }

  // 播放直接进入浏览态，不创建作品；收藏是独立操作。
  function play(item: CatalogItem) {
    navigate(`/browse/${item.id}`)
  }

  async function favorite(item: CatalogItem) {
    setBusyItem(item.id); setError('')
    try {
      await api.put(`/api/v1/me/favorites/catalog/${item.id}`)
    } catch (e) {
      setError(e instanceof Error ? e.message : '收藏失败')
    } finally { setBusyItem(null) }
  }

  return (
    <main className="watch-page">
      <div className="section-head">
        <h2>全部番剧</h2>
        <span className="muted">来自资源站同步 · 收藏/导入在详情页</span>
      </div>

      <div className="row-inline" style={{ marginBottom: 14 }}>
        <input placeholder="按标题搜索目录…" className="search" value={q}
               onChange={e => setQ(e.target.value)}
               onKeyDown={e => e.key === 'Enter' && search()} />
        <button onClick={search} disabled={loading}>搜索</button>
        {activeQuery && <button className="ghost" onClick={() => { setQ(''); setActiveQuery(''); void load(null, '', categoryId, status, sort, genre, 0, true) }}>清除</button>}
        <span style={{ width: 12 }} />
        <select className="mini" value={status}
                onChange={e => { setStatus(e.target.value); void load(null, activeQuery, categoryId, e.target.value, sort, genre, 0, true) }}>
          {STATUS.map(st => <option key={st.value} value={st.value}>{st.label}</option>)}
        </select>
        <select className="mini" value={sort}
                onChange={e => { setSort(e.target.value); void load(null, activeQuery, categoryId, status, e.target.value, genre, 0, true) }}>
          {SORTS.map(so => <option key={so.value} value={so.value}>{so.label}</option>)}
        </select>
        <select className="mini" value={genre}
                onChange={e => { setGenre(e.target.value); void load(null, activeQuery, categoryId, status, sort, e.target.value, 0, true) }}>
          <option value="">全部题材</option>
          {GENRES.map(g => <option key={g} value={g}>{g}</option>)}
        </select>
        {CATEGORIES.map(c => (
          <button key={c.label}
                  className={categoryId === c.id ? '' : 'ghost'}
                  onClick={() => pickCategory(c.id === 'season' ? 'season' : c.id === undefined ? undefined : String(c.id))}>{c.label}</button>
        ))}
      </div>

      {error && <p className="error">{error}</p>}
      {items.length === 0 && !loading && <p className="muted">目录为空——先在设置页触发一次目录同步。</p>}

      <div className="card-grid">
        {items.map(it => (
          <div key={it.id} className="work-card">
            <Link to={`/catalog/${it.id}`} className="cover">
              {it.coverUrl
                ? <img src={it.coverUrl} alt={it.name} loading="lazy"
                       onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />
                : null}
              <span className="cover-initial" style={it.coverUrl ? { display: 'none' } : {}}>{it.name.slice(0, 1)}</span>
              {it.workId && <span className="badge-anim" style={{ background: 'var(--pub)' }}>在书库</span>}
            </Link>
            <div className="work-card-body">
              <Link to={`/catalog/${it.id}`} className="work-card-title clamp" style={{ color: 'inherit', textDecoration: 'none' }}>{it.name}</Link>
              <div className="work-card-meta">
                <span>{it.remarks || `${it.episodeCount} 集`}</span>
              </div>
              <div className="row-inline" style={{ marginTop: 8 }}>
                {it.workId
                  ? <Link className="btn ghost" style={{ fontSize: 12, padding: '4px 10px' }}
                          to={`/works/${it.workId}`}>打开</Link>
                  : <>
                      <button className="ghost" style={{ fontSize: 12, padding: '4px 10px' }}
                              disabled={busyItem === it.id}
                              onClick={() => void favorite(it)}>收藏</button>
                      <button style={{ fontSize: 12, padding: '4px 10px' }}
                              onClick={() => play(it)}>播放</button>
                    </>
                }
              </div>
            </div>
          </div>
        ))}
      </div>

      {cursor && (
        <div style={{ marginTop: 20, textAlign: 'center' }}>
          <button className="ghost" disabled={loading}
                  onClick={() => void load(cursor, activeQuery, categoryId, status, sort, genre,
                    cursor?.startsWith('page:') ? Number(cursor.slice(5)) : 0, false)}>
            {loading ? '加载中…' : '加载更多'}
          </button>
        </div>
      )}
    </main>
  )
}

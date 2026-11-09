import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api'
import SeasonSection from './SeasonSection'

interface CatalogItem {
  id: string; sourceId: string; name: string; coverUrl: string; remarks: string
  categoryId: number; categoryName: string; episodeCount: number
  workId: string | null
}

const CATEGORIES = [
  { id: undefined as number | undefined, label: '全部' },
  { id: 30, label: '日韩动漫' },
  { id: 29, label: '国产动漫' },
  { id: 31, label: '欧美动漫' },
  { id: 33, label: '海外动漫' },
]

/** 番剧索引（M8 目录层）：资源站全库封面卡 + 分类筛选 + 搜索 + 一键入书库。 */
export default function AnimeIndex() {
  const navigate = useNavigate()
  const [items, setItems] = useState<CatalogItem[]>([])
  const [cursor, setCursor] = useState<string | null>(null)
  const [total, setTotal] = useState(0)
  const [q, setQ] = useState('')
  const [activeQuery, setActiveQuery] = useState('')
  const [categoryId, setCategoryId] = useState<number | undefined>(undefined)
  const [loading, setLoading] = useState(false)
  const [busyItem, setBusyItem] = useState<string | null>(null)
  const [error, setError] = useState('')

  const load = useCallback(async (nextCursor: string | null, query: string, cat: number | undefined, replace: boolean) => {
    setLoading(true); setError('')
    try {
      const qs = new URLSearchParams({ limit: '24' })
      if (nextCursor) qs.set('cursor', nextCursor)
      if (query) qs.set('q', query)
      if (cat !== undefined) qs.set('categoryId', String(cat))
      const page = await api.get<{ items: CatalogItem[]; nextCursor: string | null; total: number }>(`/api/v1/catalog-items?${qs}`)
      setItems(prev => replace ? page.items : [...prev, ...page.items])
      setCursor(page.nextCursor)
      setTotal(page.total)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败')
    } finally { setLoading(false) }
  }, [])

  useEffect(() => { void load(null, '', undefined, true) }, [load])

  function search() {
    setActiveQuery(q.trim())
    void load(null, q.trim(), categoryId, true)
  }

  function pickCategory(id: number | undefined) {
    setCategoryId(id)
    void load(null, activeQuery, id, true)
  }

  async function materialize(item: CatalogItem, thenPlay: boolean) {
    setBusyItem(item.id); setError('')
    try {
      const res = await api.post<{ workId: string; created: boolean }>(`/api/v1/catalog-items/${item.id}/materialize`, {})
      if (thenPlay) navigate(`/works/${res.workId}`)
      else { navigate(`/works/${res.workId}`) }
    } catch (e) {
      setError(e instanceof Error ? e.message : '加入书库失败')
    } finally { setBusyItem(null) }
  }

  return (
    <main className="watch-page">
      <div className="watch-title"><h1>番剧索引</h1></div>
      <div className="watch-meta">
        <span>本季新番来自 Bangumi 日历 · 资源目录共 {total} 部</span>
        <Link to="/library">我的书库 →</Link>
      </div>

      <div style={{ marginBottom: 30 }}>
        <SeasonSection />
      </div>

      <div className="section-head">
        <h2>资源目录</h2>
        <span className="muted">加入书库后可在线播放</span>
      </div>

      <div className="row-inline" style={{ marginBottom: 14 }}>
        <input placeholder="按标题搜索目录…" className="search" value={q}
               onChange={e => setQ(e.target.value)}
               onKeyDown={e => e.key === 'Enter' && search()} />
        <button onClick={search} disabled={loading}>搜索</button>
        {activeQuery && <button className="ghost" onClick={() => { setQ(''); setActiveQuery(''); void load(null, '', categoryId, true) }}>清除</button>}
        <span style={{ width: 12 }} />
        {CATEGORIES.map(c => (
          <button key={c.label}
                  className={categoryId === c.id ? '' : 'ghost'}
                  onClick={() => pickCategory(c.id)}>{c.label}</button>
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
                      <button style={{ fontSize: 12, padding: '4px 10px' }}
                              disabled={busyItem === it.id}
                              onClick={() => void materialize(it, false)}>
                        {busyItem === it.id ? '处理中…' : '加入书库'}
                      </button>
                      <button className="ghost" style={{ fontSize: 12, padding: '4px 10px' }}
                              disabled={busyItem === it.id}
                              onClick={() => void materialize(it, true)}>播放</button>
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
                  onClick={() => void load(cursor, activeQuery, categoryId, false)}>
            {loading ? '加载中…' : '加载更多'}
          </button>
        </div>
      )}
    </main>
  )
}

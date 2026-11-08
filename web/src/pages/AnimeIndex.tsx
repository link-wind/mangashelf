import { useCallback, useEffect, useState } from 'react'
import { api } from '../api'
import { WorkCardView } from './Home'

interface WorkCard {
  id: string; title: string; description: string; status: string; visibility: string
  animations: number; episodes: number; posterUrl: string | null
}

type Filter = 'all' | 'animation' | 'external'

const FILTERS: { key: Filter; label: string }[] = [
  { key: 'all', label: '全部' },
  { key: 'animation', label: '有动画' },
  { key: 'external', label: '有外部集' },
]

/** 番剧索引（gugu 式）：封面卡片网格 + 标题搜索 + 类型筛选 + 游标分页。 */
export default function AnimeIndex() {
  const [items, setItems] = useState<WorkCard[]>([])
  const [cursor, setCursor] = useState<string | null>(null)
  const [q, setQ] = useState('')
  const [activeQuery, setActiveQuery] = useState('')
  const [filter, setFilter] = useState<Filter>('all')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const load = useCallback(async (nextCursor: string | null, query: string, replace: boolean) => {
    setLoading(true); setError('')
    try {
      const qs = new URLSearchParams({ limit: '24' })
      if (nextCursor) qs.set('cursor', nextCursor)
      if (query) qs.set('q', query)
      const page = await api.get<{ items: WorkCard[]; nextCursor: string | null }>(`/api/v1/works?${qs}`)
      setItems(prev => replace ? page.items : [...prev, ...page.items])
      setCursor(page.nextCursor)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败')
    } finally { setLoading(false) }
  }, [])

  useEffect(() => { void load(null, '', true) }, [load])

  function search() {
    setActiveQuery(q.trim())
    void load(null, q.trim(), true)
  }

  const shown = items.filter(w =>
    filter === 'all' ? true
      : filter === 'animation' ? w.animations > 0
        : w.episodes > 0)

  return (
    <main className="watch-page">
      <div className="watch-title"><h1>番剧索引</h1></div>
      <div className="watch-meta">
        <span>共加载 {items.length} 部作品</span>
      </div>

      <div className="row-inline" style={{ marginBottom: 14 }}>
        <input placeholder="按标题搜索…" className="search" value={q}
               onChange={e => setQ(e.target.value)}
               onKeyDown={e => e.key === 'Enter' && search()} />
        <button onClick={search} disabled={loading}>搜索</button>
        {activeQuery && <button className="ghost" onClick={() => { setQ(''); setActiveQuery(''); void load(null, '', true) }}>清除</button>}
        <span style={{ width: 12 }} />
        {FILTERS.map(f => (
          <button key={f.key}
                  className={filter === f.key ? '' : 'ghost'}
                  onClick={() => setFilter(f.key)}>{f.label}</button>
        ))}
      </div>

      {error && <p className="error">{error}</p>}
      {shown.length === 0 && !loading && <p className="muted">没有匹配的作品。</p>}

      <div className="card-grid">
        {shown.map(w => <WorkCardView key={w.id} w={w} />)}
      </div>

      {cursor && (
        <div style={{ marginTop: 20, textAlign: 'center' }}>
          <button className="ghost" disabled={loading} onClick={() => void load(cursor, activeQuery, false)}>
            {loading ? '加载中…' : '加载更多'}
          </button>
        </div>
      )}
    </main>
  )
}

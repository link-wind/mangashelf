import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'

interface Favorite {
  type: 'work' | 'manga' | 'catalog'
  title: string
  coverUrl: string | null
  workId: string | null
  source: string | null
  mangaId: string | null
  catalogItemId: string | null
  createdAt: string
}

type Tab = 'manga' | 'anime'

function keyOf(f: Favorite): string {
  if (f.type === 'work') return `work:${f.workId}`
  if (f.type === 'manga') return `manga:${f.source}:${f.mangaId}`
  return `catalog:${f.catalogItemId}`
}

function linkOf(f: Favorite): string {
  if (f.type === 'work') return `/works/${f.workId}`
  if (f.type === 'manga') {
    const params = new URLSearchParams({ source: f.source ?? '', title: f.title })
    return `/manga-ext/${f.mangaId}?${params}`
  }
  return `/catalog/${f.catalogItemId}`
}

function deleteUrl(f: Favorite): string {
  if (f.type === 'work') return `/api/v1/me/favorites/work/${f.workId}`
  if (f.type === 'manga') {
    return `/api/v1/me/favorites/manga?source=${encodeURIComponent(f.source ?? '')}`
      + `&mangaId=${encodeURIComponent(f.mangaId ?? '')}`
  }
  return `/api/v1/me/favorites/catalog/${f.catalogItemId}`
}

/** 我的收藏（separate-favorites-and-history）：只展示收藏关系，不含阅读/观看记录。 */
export default function Shelf() {
  const [items, setItems] = useState<Favorite[]>([])
  const [tab, setTab] = useState<Tab>('manga')
  const [error, setError] = useState('')

  useEffect(() => {
    void api.get<Favorite[]>('/api/v1/me/favorites')
      .then(setItems)
      .catch(e => setError(e instanceof Error ? e.message : '加载失败'))
  }, [])

  async function remove(f: Favorite) {
    try {
      await api.delete(deleteUrl(f))
      setItems(prev => prev.filter(item => keyOf(item) !== keyOf(f)))
    } catch (e) {
      setError(e instanceof Error ? e.message : '取消收藏失败')
    }
  }

  const manga = items.filter(f => f.type === 'work' || f.type === 'manga')
  const anime = items.filter(f => f.type === 'catalog')
  const shown = tab === 'manga' ? manga : anime

  return (
    <main className="page">
      <div className="row-between">
        <div>
          <h1>我的收藏</h1>
          <p className="muted">收藏是主动关系；阅读与观看记录在「观看历史」，继续阅读由进度计算。</p>
        </div>
      </div>
      {error && <p className="error">{error}</p>}

      <div className="row-inline" role="tablist" aria-label="收藏分类" style={{ margin: '18px 0 22px' }}>
        <button role="tab" aria-selected={tab === 'manga'} className={tab === 'manga' ? '' : 'ghost'}
                onClick={() => setTab('manga')}>漫画 · {manga.length}</button>
        <button role="tab" aria-selected={tab === 'anime'} className={tab === 'anime' ? '' : 'ghost'}
                onClick={() => setTab('anime')}>动漫 · {anime.length}</button>
      </div>

      <div className="grid">
        {shown.map(f => (
          <div key={keyOf(f)} className="card work">
            <div className="cover">
              {f.coverUrl
                ? <img src={f.coverUrl} alt={f.title} loading="lazy" decoding="async"
                       onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />
                : <span className="cover-initial">{f.title.slice(0, 1)}</span>}
            </div>
            <h3><Link to={linkOf(f)}>{f.title}</Link></h3>
            <span className="muted" style={{ fontSize: 12 }}>
              {f.type === 'work' ? '本地作品' : f.type === 'manga' ? `外部漫画 · ${f.source}` : '番剧目录'}
            </span>
            <button className="ghost" onClick={() => void remove(f)}>取消收藏</button>
          </div>
        ))}
        {shown.length === 0 && (
          <p className="muted">
            {tab === 'manga'
              ? '还没有收藏漫画——在漫画详情页点「收藏」。'
              : '还没有收藏番剧——在番剧索引或目录详情点「收藏」。'}
          </p>
        )}
      </div>
    </main>
  )
}

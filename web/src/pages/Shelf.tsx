import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import CoverImage from '../components/CoverImage'
import { CloseIcon } from '../components/Icons'
import { useToast } from '../components/Toast'

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

/** 我的收藏：统一使用全站 3:4 封面卡片，取消收藏改为悬浮玻璃按钮 + Toast 反馈。 */
export default function Shelf() {
  const [items, setItems] = useState<Favorite[]>([])
  const [loading, setLoading] = useState(true)
  const [tab, setTab] = useState<Tab>('manga')
  const [error, setError] = useState('')
  const { showToast } = useToast()

  useEffect(() => {
    void api.get<Favorite[]>('/api/v1/me/favorites')
      .then(setItems)
      .catch(e => setError(e instanceof Error ? e.message : '加载失败'))
      .finally(() => setLoading(false))
  }, [])

  async function remove(f: Favorite) {
    try {
      await api.delete(deleteUrl(f))
      setItems(prev => prev.filter(item => keyOf(item) !== keyOf(f)))
      showToast(`已取消收藏「${f.title}」`, 'info')
    } catch (e) {
      const msg = e instanceof Error ? e.message : '取消收藏失败'
      setError(msg)
      showToast(msg, 'error')
    }
  }

  const manga = items.filter(f => f.type === 'work' || f.type === 'manga')
  const anime = items.filter(f => f.type === 'catalog')
  const shown = tab === 'manga' ? manga : anime

  return (
    <main className="page">
      <div className="row-between">
        <div>
          <h1 style={{ margin: '0 0 6px' }}>我的收藏</h1>
          <p className="muted" style={{ margin: 0 }}>收藏是主动关系；阅读与观看记录在「观看历史」，继续阅读由进度计算。</p>
        </div>
      </div>
      {error && <p className="error">{error}</p>}

      <div className="row-inline" role="tablist" aria-label="收藏分类" style={{ margin: '18px 0 22px' }}>
        <button role="tab" aria-selected={tab === 'manga'} className={'pill ' + (tab === 'manga' ? '' : 'ghost')}
                onClick={() => setTab('manga')}>漫画 · {manga.length}</button>
        <button role="tab" aria-selected={tab === 'anime'} className={'pill ' + (tab === 'anime' ? '' : 'ghost')}
                onClick={() => setTab('anime')}>动漫 · {anime.length}</button>
      </div>

      {loading ? (
        <div className="card-grid">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i}>
              <div className="skeleton-box card-skeleton-cover" />
              <div className="skeleton-box card-skeleton-line" />
              <div className="skeleton-box card-skeleton-line short" />
            </div>
          ))}
        </div>
      ) : shown.length === 0 ? (
        <p className="muted">
          {tab === 'manga'
            ? '还没有收藏漫画——在漫画详情页点「收藏」。'
            : '还没有收藏番剧——在番剧索引或目录详情点「收藏」。'}
        </p>
      ) : (
        <div className="card-grid">
          {shown.map(f => (
            <div key={keyOf(f)} className="shelf-card-wrap">
              <Link to={linkOf(f)} className="work-card">
                <div className="cover">
                  <CoverImage src={f.coverUrl} alt={f.title} />
                  <span className="badge-anim" style={f.type === 'work' ? { background: 'var(--pub)' } : undefined}>
                    {f.type === 'work' ? '本地' : f.type === 'manga' ? (f.source || '漫画') : '番剧'}
                  </span>
                </div>
                <div className="work-card-body">
                  <div className="work-card-title clamp">{f.title}</div>
                  <div className="work-card-meta">
                    <span>{f.type === 'work' ? '本地作品' : f.type === 'manga' ? `外部漫画 · ${f.source}` : '番剧目录'}</span>
                  </div>
                </div>
              </Link>
              <button type="button" className="shelf-unfav"
                      aria-label={`取消收藏 ${f.title}`} title="取消收藏"
                      onClick={() => void remove(f)}>
                <CloseIcon size={14} />
              </button>
            </div>
          ))}
        </div>
      )}
    </main>
  )
}

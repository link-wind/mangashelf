import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
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
  status?: 'WATCHING' | 'COMPLETED'
  createdAt: string
}

type ActivityType = 'work-read' | 'manga-read' | 'animation-watch' | 'external-watch'

interface Activity {
  type: ActivityType
  title: string
  subtitle: string | null
  coverUrl: string | null
  workId: string | null
  catalogItemId: string | null
  chapterId: string | null
  revisionId: string | null
  pageOrdinal: number | null
  animationId: string | null
  episodeId: string | null
  ordinal: number | null
  positionMs: number | null
  durationMs: number | null
  source: string | null
  mangaId: string | null
  updatedAt: string
}

interface CatchUpItem {
  catalogItemId: string
  workId: string | null
  title: string
  coverUrl: string
  remarks: string
  watchedOrdinal: number
  latestOrdinal: number
  newEpisodes: number
  resumeEpisodeId: string | null
}

type MainTab = 'continue' | 'favorites' | 'history'
type MediaKind = 'all' | 'anime' | 'manga'

function isAnimeActivity(a: Activity): boolean {
  return a.type === 'animation-watch' || a.type === 'external-watch'
}

function isMangaActivity(a: Activity): boolean {
  return a.type === 'work-read' || a.type === 'manga-read'
}

function fmt(ms: number | null | undefined): string {
  if (!ms || ms <= 0) return '00:00'
  const s = Math.floor(ms / 1000)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  const mm = String(m).padStart(2, '0')
  const ss = String(sec).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}

function activitySummary(a: Activity): string {
  if (a.type === 'work-read') return `${a.subtitle ?? '章节'} · 第 ${a.pageOrdinal ?? 1} 页`
  if (a.type === 'manga-read') {
    return `${a.subtitle ? `${a.subtitle} · ` : ''}第 ${a.pageOrdinal ?? 1} 页`
  }
  if (a.type === 'animation-watch') return `看到 ${fmt(a.positionMs)}`
  return `第 ${a.ordinal ?? '?'} 话${a.subtitle ? ` ${a.subtitle}` : ''} · 看到 ${fmt(a.positionMs)}`
}

function activityLink(a: Activity): string {
  if (a.type === 'work-read') {
    return `/read/${a.revisionId}?chapter=${a.chapterId}&resume=${a.pageOrdinal ?? 1}`
  }
  if (a.type === 'manga-read') {
    const params = new URLSearchParams({
      mangaId: a.mangaId ?? '', lang: 'all', source: a.source ?? '', title: a.title,
    })
    return a.chapterId ? `/manga-ext/read/${a.chapterId}?${params}` : `/manga-ext/${a.mangaId}?${params}`
  }
  if (a.type === 'animation-watch') return `/watch/${a.animationId}`
  return a.workId
    ? `/works/${a.workId}/external/${a.episodeId}`
    : `/browse/${a.catalogItemId}/${a.episodeId}`
}

function favKey(f: Favorite): string {
  if (f.type === 'work') return `work:${f.workId}`
  if (f.type === 'manga') return `manga:${f.source}:${f.mangaId}`
  return `catalog:${f.catalogItemId}`
}

function favLink(f: Favorite): string {
  if (f.type === 'work') return `/works/${f.workId}`
  if (f.type === 'manga') {
    const params = new URLSearchParams({ source: f.source ?? '', title: f.title })
    return `/manga-ext/${f.mangaId}?${params}`
  }
  return `/catalog/${f.catalogItemId}`
}

function favDeleteUrl(f: Favorite): string {
  if (f.type === 'work') return `/api/v1/me/favorites/work/${f.workId}`
  if (f.type === 'manga') {
    return `/api/v1/me/favorites/manga?source=${encodeURIComponent(f.source ?? '')}`
      + `&mangaId=${encodeURIComponent(f.mangaId ?? '')}`
  }
  return `/api/v1/me/favorites/catalog/${f.catalogItemId}`
}

/** 「我的」个人中心：统一承载动画与漫画的继续追更、收藏与历史记录。 */
export default function Me() {
  const [params, setParams] = useSearchParams()
  const rawTab = params.get('tab')
  const tab: MainTab = rawTab === 'favorites' || rawTab === 'history' ? rawTab : 'continue'
  const rawKind = params.get('kind')
  const kind: MediaKind = rawKind === 'anime' || rawKind === 'manga' ? rawKind : 'all'

  const [favorites, setFavorites] = useState<Favorite[]>([])
  const [favStatusFilter, setFavStatusFilter] = useState<'all' | 'WATCHING' | 'COMPLETED'>('all')
  const [continueList, setContinueList] = useState<Activity[]>([])
  const [historyList, setHistoryList] = useState<Activity[]>([])
  const [catchUp, setCatchUp] = useState<CatchUpItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const { showToast } = useToast()

  useEffect(() => {
    void (async () => {
      setLoading(true); setError('')
      try {
        const [favs, cont, hist, rec] = await Promise.all([
          api.get<Favorite[]>('/api/v1/me/favorites'),
          api.get<Activity[]>('/api/v1/me/continue?limit=40'),
          api.get<Activity[]>('/api/v1/me/history?limit=100'),
          api.get<{ catchUp: CatchUpItem[] }>('/api/v1/recommendations/anime?limit=6')
            .catch(() => ({ catchUp: [] })),
        ])
        setFavorites(favs)
        setContinueList(cont)
        setHistoryList(hist)
        setCatchUp(rec.catchUp ?? [])
      } catch (e) {
        setError(e instanceof Error ? e.message : '加载失败')
      } finally {
        setLoading(false)
      }
    })()
  }, [])

  function setTab(nextTab: MainTab) {
    const next = new URLSearchParams(params)
    next.set('tab', nextTab)
    setParams(next, { replace: true })
  }

  function setKind(nextKind: MediaKind) {
    const next = new URLSearchParams(params)
    if (nextKind === 'all') next.delete('kind')
    else next.set('kind', nextKind)
    setParams(next, { replace: true })
  }

  async function removeFavorite(f: Favorite) {
    try {
      await api.delete(favDeleteUrl(f))
      setFavorites(prev => prev.filter(item => favKey(item) !== favKey(f)))
      showToast(`已取消收藏「${f.title}」`, 'info')
    } catch (e) {
      const msg = e instanceof Error ? e.message : '取消收藏失败'
      setError(msg)
      showToast(msg, 'error')
    }
  }

  async function toggleFavoriteStatus(f: Favorite) {
    const nextStatus: 'WATCHING' | 'COMPLETED' = f.status === 'COMPLETED' ? 'WATCHING' : 'COMPLETED'
    let url: string
    if (f.type === 'work') {
      url = `/api/v1/me/favorites/work/${f.workId}/status?status=${nextStatus}`
    } else if (f.type === 'manga') {
      url = `/api/v1/me/favorites/manga/status?${new URLSearchParams({
        source: f.source ?? '', mangaId: f.mangaId ?? '', title: f.title, status: nextStatus,
      })}`
    } else {
      url = `/api/v1/me/favorites/catalog/${f.catalogItemId}/status?status=${nextStatus}`
    }
    try {
      await api.put(url)
      setFavorites(prev => prev.map(item => favKey(item) === favKey(f) ? { ...item, status: nextStatus } : item))
      showToast(nextStatus === 'COMPLETED' ? `已标记「${f.title}」为已看完` : `已改回在追「${f.title}」`, 'success')
    } catch (e) {
      showToast(e instanceof Error ? e.message : '更新状态失败', 'error')
    }
  }

  const shownFavs = favorites.filter(f => {
    if (kind === 'anime' && f.type !== 'catalog') return false
    if (kind === 'manga' && f.type === 'catalog') return false
    const st = f.status ?? 'WATCHING'
    if (favStatusFilter !== 'all' && st !== favStatusFilter) return false
    return true
  })

  const shownContinue = continueList.filter(a => {
    if (kind === 'anime') return isAnimeActivity(a)
    if (kind === 'manga') return isMangaActivity(a)
    return true
  })

  const shownHistory = historyList.filter(a => {
    if (kind === 'anime') return isAnimeActivity(a)
    if (kind === 'manga') return isMangaActivity(a)
    return true
  })

  const showCatchUp = kind !== 'manga' && catchUp.length > 0

  return (
    <main className="page">
      <div className="row-between" style={{ flexWrap: 'wrap', gap: 12 }}>
        <div>
          <h1 style={{ margin: '0 0 6px' }}>我的</h1>
          <p className="muted" style={{ margin: 0 }}>动画与漫画的追更提醒、收藏夹与观看/阅读足迹。</p>
        </div>
        <div className="row-inline">
          <Link to="/uploads" className="btn ghost mini">我的素材</Link>
          <Link to="/settings" className="btn ghost mini">设置</Link>
        </div>
      </div>

      {error && <p className="error" style={{ marginTop: 12 }}>{error}</p>}

      <div className="me-toolbar">
        <div className="row-inline" role="tablist" aria-label="我的内容视图">
          <button type="button" role="tab" aria-selected={tab === 'continue'}
                  className={'pill ' + (tab === 'continue' ? '' : 'ghost')}
                  onClick={() => setTab('continue')}>
            继续追更
          </button>
          <button type="button" role="tab" aria-selected={tab === 'favorites'}
                  className={'pill ' + (tab === 'favorites' ? '' : 'ghost')}
                  onClick={() => setTab('favorites')}>
            收藏
          </button>
          <button type="button" role="tab" aria-selected={tab === 'history'}
                  className={'pill ' + (tab === 'history' ? '' : 'ghost')}
                  onClick={() => setTab('history')}>
            历史
          </button>
        </div>

        <div className="row-inline" role="group" aria-label="内容类型筛选">
          <button type="button" className={'mini ' + (kind === 'all' ? '' : 'ghost')}
                  onClick={() => setKind('all')}>全部</button>
          <button type="button" className={'mini ' + (kind === 'anime' ? '' : 'ghost')}
                  onClick={() => setKind('anime')}>动画</button>
          <button type="button" className={'mini ' + (kind === 'manga' ? '' : 'ghost')}
                  onClick={() => setKind('manga')}>漫画</button>
        </div>
      </div>

      {loading ? (
        <div className="card-grid" style={{ marginTop: 20 }}>
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i}>
              <div className="skeleton-box card-skeleton-cover" />
              <div className="skeleton-box card-skeleton-line" />
              <div className="skeleton-box card-skeleton-line short" />
            </div>
          ))}
        </div>
      ) : tab === 'continue' ? (
        <div style={{ marginTop: 18 }}>
          {showCatchUp && (
            <section style={{ marginBottom: 28 }}>
              <div className="section-head">
                <h2>新集更新提醒</h2>
                <span className="muted" style={{ fontSize: 12 }}>已追连载番有新集</span>
              </div>
              <div className="card-grid">
                {catchUp.map(item => (
                  <Link key={item.catalogItemId}
                        to={item.workId ? `/works/${item.workId}` : `/browse/${item.catalogItemId}`}
                        className="work-card">
                    <div className="cover">
                      <CoverImage src={item.coverUrl} alt={item.title} />
                      <span className="badge-anim badge-new">+{item.newEpisodes} 新集</span>
                    </div>
                    <div className="work-card-body">
                      <div className="work-card-title clamp">{item.title}</div>
                      <div className="work-card-meta">
                        <span>看到第 {item.watchedOrdinal} 集 · 更至 {item.latestOrdinal} 集</span>
                      </div>
                    </div>
                  </Link>
                ))}
              </div>
            </section>
          )}

          <section>
            <div className="section-head">
              <h2>继续观看 / 阅读</h2>
            </div>
            {shownContinue.length === 0 ? (
              <p className="muted">还没有正在追的{kind === 'anime' ? '番剧' : kind === 'manga' ? '漫画' : '内容'}——去<Link to="/">番剧</Link>或<Link to="/manga">漫画</Link>逛逛。</p>
            ) : (
              <div className="card-grid">
                {shownContinue.map(a => {
                  const pct = a.durationMs && a.positionMs
                    ? Math.min(99, Math.max(2, Math.round(a.positionMs / a.durationMs * 100)))
                    : null
                  const isAnime = isAnimeActivity(a)
                  return (
                    <Link key={`${a.type}-${a.workId ?? a.catalogItemId ?? a.mangaId ?? a.animationId}`}
                          to={activityLink(a)} className="work-card">
                      <div className="cover">
                        <CoverImage src={a.coverUrl} alt={a.title} />
                        <span className="badge-anim">
                          {isAnime ? (pct !== null ? `${pct}%` : `第 ${a.ordinal ?? 1} 集`) : '漫画'}
                        </span>
                        {pct !== null && (
                          <div className="card-progress" aria-hidden="true">
                            <div className="card-progress-fill" style={{ width: `${pct}%` }} />
                          </div>
                        )}
                      </div>
                      <div className="work-card-body">
                        <div className="work-card-title clamp">{a.title}</div>
                        <div className="work-card-meta">
                          <span className="clamp">{activitySummary(a)}</span>
                        </div>
                      </div>
                    </Link>
                  )
                })}
              </div>
            )}
          </section>
        </div>
      ) : tab === 'favorites' ? (
        <div style={{ marginTop: 18 }}>
          <div className="row-inline" style={{ marginBottom: 14 }}>
            <button type="button" className={'mini ' + (favStatusFilter === 'all' ? '' : 'ghost')}
                    onClick={() => setFavStatusFilter('all')}>全部</button>
            <button type="button" className={'mini ' + (favStatusFilter === 'WATCHING' ? '' : 'ghost')}
                    onClick={() => setFavStatusFilter('WATCHING')}>
              在追
            </button>
            <button type="button" className={'mini ' + (favStatusFilter === 'COMPLETED' ? '' : 'ghost')}
                    onClick={() => setFavStatusFilter('COMPLETED')}>
              已看完
            </button>
          </div>
          {shownFavs.length === 0 ? (
            <p className="muted">
              当前筛选下暂无收藏——在详情页或播放页点击「收藏」或「标记已看完」。
            </p>
          ) : (
            <div className="card-grid">
              {shownFavs.map(f => {
                const done = f.status === 'COMPLETED'
                return (
                  <div key={favKey(f)} className="shelf-card-wrap">
                    <Link to={favLink(f)} className="work-card">
                      <div className="cover">
                        <CoverImage src={f.coverUrl} alt={f.title} />
                        <span className="badge-tl">
                          {f.type === 'catalog' ? '番剧' : '漫画'}
                        </span>
                        {done && <span className="badge-br is-pub" style={{ background: 'var(--pub)' }}>✓ 已看完</span>}
                      </div>
                      <div className="work-card-body">
                        <div className="work-card-title">{f.title}</div>
                        <div className="row-between" style={{ marginTop: 6 }}>
                          <span className="muted" style={{ fontSize: 11.5 }}>
                            {f.type === 'catalog' ? '番剧' : '漫画'}
                          </span>
                          <button type="button" className="fav-status-btn"
                                  onClick={e => { e.preventDefault(); void toggleFavoriteStatus(f) }}>
                            {done ? '改回在追' : '标已看完'}
                          </button>
                        </div>
                      </div>
                    </Link>
                    <button type="button" className="shelf-unfav"
                            aria-label={`取消收藏 ${f.title}`} title="取消收藏"
                            onClick={() => void removeFavorite(f)}>
                      <CloseIcon size={14} />
                    </button>
                  </div>
                )
              })}
            </div>
          )}
        </div>
      ) : (
        <div style={{ marginTop: 18 }}>
          {shownHistory.length === 0 ? (
            <p className="muted">还没有{kind === 'anime' ? '动画观看' : kind === 'manga' ? '漫画阅读' : '观看或阅读'}记录。</p>
          ) : (
            <ul className="chapters">
              {shownHistory.map(a => (
                <li key={`${a.type}-${a.episodeId ?? a.chapterId ?? a.animationId}-${a.updatedAt}`}>
                  <span className="row-inline" style={{ gap: 12 }}>
                    {a.coverUrl && (
                      <img src={a.coverUrl} alt="" className="history-thumb" referrerPolicy="no-referrer"
                           onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />
                    )}
                    <span>
                      <strong>{a.title}</strong>
                      <span className="tag" style={{ marginLeft: 8, fontSize: 11 }}>
                        {isAnimeActivity(a) ? '动画' : '漫画'}
                      </span>
                      <br />
                      <span className="muted">{activitySummary(a)}</span>
                    </span>
                  </span>
                  <Link className="btn ghost mini" to={activityLink(a)}>
                    {isAnimeActivity(a) ? '继续观看' : '继续阅读'}
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </main>
  )
}

import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError } from '../api'
import Player from '../components/Player'
import { useEpisodePlayback } from '../useEpisodePlayback'

interface BrowseEpisode {
  id: string; title: string; ordinal: number; lineName: string
  lineRank: number; episodeType: 'MAIN' | 'EXTRA'
}
interface BrowseLine { name: string; rank: number; episodes: BrowseEpisode[] }
interface Item {
  id: string; name: string; coverUrl: string; remarks: string; episodeCount: number; workId: string | null
}
interface BrowseEpisodes { item: Item; lines: BrowseLine[] }

/**
 * 浏览态播放（M20）：直接播目录条目的剧集，不创建作品；
 * 进度按目录条目 + 集号保存（separate-favorites-and-history）。
 */
export default function BrowseWatch() {
  const { itemId, episodeId } = useParams()
  const navigate = useNavigate()
  const [data, setData] = useState<BrowseEpisodes | null>(null)
  const [line, setLine] = useState('')
  const [tab, setTab] = useState<'MAIN' | 'EXTRA'>('MAIN')
  const [error, setError] = useState('')
  const [favorited, setFavorited] = useState(false)
  const { status, resumeMs, notice, resolve, saveProgress, resolving, failed } =
    useEpisodePlayback(episodeId)

  useEffect(() => {
    void (async () => {
      if (!itemId) return
      try {
        setData(await api.get<BrowseEpisodes>(`/api/v1/catalog-items/${itemId}/episodes`))
      } catch (e) {
        setError(e instanceof ApiError ? e.message : '加载剧集失败')
      }
    })()
  }, [itemId])

  useEffect(() => {
    if (!itemId) return
    void api.get<{ catalogItemId: string | null }[]>('/api/v1/me/favorites?type=catalog')
      .then(list => setFavorited(list.some(f => f.catalogItemId === itemId)))
      .catch(() => { /* 收藏状态不可用不影响播放 */ })
  }, [itemId])

  // 无集号时跳到第一集
  useEffect(() => {
    if (!itemId || episodeId || !data) return
    void api.get<{ episodeId?: string }>(`/api/v1/catalog-items/${itemId}/play-target`)
      .then(t => {
        if (t.episodeId) navigate(`/browse/${itemId}/${t.episodeId}`, { replace: true })
      })
      .catch(() => { /* 无剧集时停留 */ })
  }, [itemId, episodeId, data, navigate])

  const all = data?.lines.flatMap(l => l.episodes) ?? []
  const current = all.find(e => e.id === episodeId) ?? null

  useEffect(() => {
    if (current) setLine(current.lineName)
  }, [current?.id])

  async function toggleFavorite() {
    if (!itemId) return
    try {
      if (favorited) {
        await api.delete(`/api/v1/me/favorites/catalog/${itemId}`)
        setFavorited(false)
      } else {
        await api.put(`/api/v1/me/favorites/catalog/${itemId}`)
        setFavorited(true)
      }
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '收藏操作失败')
    }
  }

  const lines = data?.lines ?? []
  const episodes = (lines.find(l => l.name === line)?.episodes ?? [])
    .filter(e => e.episodeType === tab)
    .sort((a, b) => a.ordinal - b.ordinal)
  const index = episodes.findIndex(e => e.id === episodeId)
  const prev = index > 0 ? episodes[index - 1] : null
  const next = index >= 0 && index < episodes.length - 1 ? episodes[index + 1] : null
  const goto = (id: string) => navigate(`/browse/${itemId}/${id}`)
  const hasExtra = all.some(e => e.episodeType === 'EXTRA')

  if (error) return <main className="watch-page"><p className="error">{error}</p></main>
  if (!data) return <main className="watch-page page-center">加载中…</main>
  if (!current) return <main className="watch-page page-center">正在准备剧集…</main>

  return (
    <main className="watch-page">
      <div className="watch-title">
        <h1>{data.item.name}</h1>
        <span className="ep-name">第 {current.ordinal} 话 {current.title}</span>
      </div>
      <div className="watch-meta">
        <span>浏览模式（{favorited ? '已收藏' : '未收藏'}）· 共 {episodes.length} 集 · 线路 {line}</span>
        <Link to={`/catalog/${itemId}`}>作品详情</Link>
      </div>
      {notice && <p className="muted" role="status" style={{ margin: '6px 0 0' }}>{notice}</p>}

      <div className="watch-layout">
        <div>
          <Player
            src={status?.playUrl ?? null}
            kind={(status?.playKind as 'hls' | 'mp4' | null) ?? null}
            resumeMs={resumeMs}
            onProgress={saveProgress}
            onEnded={() => next && goto(next.id)}
            onFatal={() => void resolve(current.id)}
            overlay={resolving ? (
              <div className="player-overlay">
                <div>
                  <div className="player-spinner" />
                  <div className="big">正在获取视频源</div>
                  <div className="sub">无头浏览器解析直链中，通常需要 5～15 秒</div>
                </div>
              </div>
            ) : failed ? (
              <div className="player-overlay">
                <div>
                  <div className="big">解析失败</div>
                  <div className="sub">{status?.errorMessage ?? '未知错误'}</div>
                  <button style={{ marginTop: 12 }} onClick={() => void resolve(current.id)}>重试解析</button>
                </div>
              </div>
            ) : undefined}
          />
          <div className="watch-actions">
            <button className="ghost" disabled={!prev} onClick={() => prev && goto(prev.id)}>← 上一集</button>
            <button className="ghost" disabled={!next} onClick={() => next && goto(next.id)}>下一集 →</button>
            <button className="ghost" onClick={() => void resolve(current.id)}>重新解析</button>
            <button className="pill" onClick={() => void toggleFavorite()}>
              {favorited ? '取消收藏' : '收藏'}
            </button>
          </div>
        </div>

        <aside className="watch-side">
          <div className="card">
            <h3>选集（{episodes.length}）</h3>
            <div className="row-inline" style={{ marginBottom: 10 }}>
              {lines.map(l => (
                <button key={l.name} className={l.name === line ? 'mini' : 'mini ghost'}
                        onClick={() => {
                          setLine(l.name)
                          const first = l.episodes.find(e => e.episodeType === tab)
                          if (first) goto(first.id)
                        }}>{l.name}</button>
              ))}
            </div>
            {hasExtra && (
              <div className="row-inline" style={{ marginBottom: 10 }}>
                <button className={tab === 'MAIN' ? 'mini' : 'mini ghost'} onClick={() => setTab('MAIN')}>正片</button>
                <button className={tab === 'EXTRA' ? 'mini' : 'mini ghost'} onClick={() => setTab('EXTRA')}>PV/特典</button>
              </div>
            )}
            <div className="episode-grid">
              {episodes.map(e => (
                <button key={e.id}
                        className={'episode-cell' + (e.id === current.id ? ' active' : '')}
                        onClick={() => goto(e.id)}>
                  <div className="no">{String(e.ordinal).padStart(2, '0')}</div>
                  <div className="name">{e.title}</div>
                </button>
              ))}
            </div>
          </div>
          <div className="card">
            <h3>播放信息</h3>
            <ul className="info-list">
              <li><span className="k">模式</span><span>浏览（{favorited ? '已收藏' : '未收藏'}，不占作品）</span></li>
              <li><span className="k">线路</span><span>{line}</span></li>
              <li><span className="k">解析状态</span><span>{status?.playUrl ? '已就绪' : failed ? '失败' : '解析中…'}</span></li>
              <li><span className="k">进度</span><span>服务端保存</span></li>
            </ul>
          </div>
        </aside>
      </div>
    </main>
  )
}

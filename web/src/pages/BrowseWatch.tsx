import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, ApiError } from '../api'
import { useAuth } from '../auth'
import EpisodePlayerShell, { type PlayerWorkOverview, type SimilarAnime } from '../components/EpisodePlayerShell'
import { CheckCircleIcon, HeartIcon } from '../components/Icons'
import { useToast } from '../components/Toast'
import { formatAnimeLine } from '../lineLabel'
import { formatEpisodeTitle } from '../lib/mediaFormat'
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

interface CatalogDetailMeta {
  item: {
    coverUrl: string
    categoryName?: string | null
    remarks?: string | null
    episodeCount?: number | null
    bangumiSubjectId?: number | null
  }
  bangumiNameCn?: string | null
  rating: number | null
  summary: string | null
  airWeekday?: number | null
  airDate: string | null
  airTime?: string | null
  studio?: string | null
  mediaType?: string | null
  tags: string[]
}

/** 番剧在线播放页：gugu.fan 启发式 8:4 影院布局 + Bangumi 中文主标题统一 + 追番/已看完联动。 */
export default function BrowseWatch() {
  const { itemId, episodeId } = useParams()
  const navigate = useNavigate()
  const { me } = useAuth()
  const { showToast } = useToast()
  const [data, setData] = useState<BrowseEpisodes | null>(null)
  const [line, setLine] = useState('')
  const [tab, setTab] = useState<'MAIN' | 'EXTRA'>('MAIN')
  const [error, setError] = useState('')
  const [favorited, setFavorited] = useState(false)
  const [favStatus, setFavStatus] = useState<'WATCHING' | 'COMPLETED'>('WATCHING')
  const [similar, setSimilar] = useState<SimilarAnime[]>([])
  const [displayTitle, setDisplayTitle] = useState('')
  const [overview, setOverview] = useState<PlayerWorkOverview | null>(null)
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
    setDisplayTitle('')
    void api.get<CatalogDetailMeta>(`/api/v1/catalog-items/${itemId}`)
      .then(d => {
        if (d.bangumiNameCn) setDisplayTitle(d.bangumiNameCn)
        setOverview({
          coverUrl: d.item.bangumiSubjectId ? `/api/v1/bangumi-covers/${d.item.bangumiSubjectId}?size=large` : d.item.coverUrl,
          rating: d.rating,
          summary: d.summary,
          tags: d.tags,
          categoryName: d.item.categoryName,
          airDate: d.airDate,
          airWeekday: d.airWeekday,
          airTime: d.airTime,
          studio: d.studio,
          mediaType: d.mediaType,
          remarks: d.item.remarks,
          totalEpisodes: d.item.episodeCount,
        })
      })
      .catch(() => setOverview(null))
  }, [itemId])

  useEffect(() => {
    if (!itemId) return
    void api.get<SimilarAnime[]>(`/api/v1/recommendations/anime/${itemId}/similar?limit=8`)
      .then(setSimilar)
      .catch(() => setSimilar([]))
  }, [itemId])

  useEffect(() => {
    if (!itemId || !me) { setFavorited(false); setFavStatus('WATCHING'); return }
    void api.get<{ catalogItemId: string | null; status?: 'WATCHING' | 'COMPLETED' }[]>('/api/v1/me/favorites?type=catalog')
      .then(list => {
        const hit = list.find(f => f.catalogItemId === itemId)
        setFavorited(Boolean(hit))
        setFavStatus(hit?.status === 'COMPLETED' ? 'COMPLETED' : 'WATCHING')
      })
      .catch(() => { /* 收藏状态不可用不影响播放 */ })
  }, [itemId, me])

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
  const finalTitle = displayTitle || data?.item.name || ''

  useEffect(() => {
    if (current) {
      setLine(current.lineName)
      if (current.episodeType === 'EXTRA') setTab('EXTRA')
    }
  }, [current?.id])

  useEffect(() => {
    if (finalTitle) {
      document.title = current
        ? `${finalTitle} · ${formatEpisodeTitle(current.ordinal, current.title)} · MangaShelf`
        : `${finalTitle} · MangaShelf`
    }
  }, [finalTitle, current?.ordinal, current?.title, episodeId])

  async function toggleFavorite() {
    if (!itemId) return
    if (!me) { showToast('请先登录后再追番收藏', 'info'); return }
    try {
      if (favorited) {
        await api.delete(`/api/v1/me/favorites/catalog/${itemId}`)
        setFavorited(false)
        setFavStatus('WATCHING')
        showToast('已取消追番', 'info')
      } else {
        await api.put(`/api/v1/me/favorites/catalog/${itemId}`)
        setFavorited(true)
        setFavStatus('WATCHING')
        showToast('已加入「动画空间」追番', 'success')
      }
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : '收藏操作失败'
      showToast(msg, 'error')
    }
  }

  async function toggleCompleted() {
    if (!itemId) return
    if (!me) { showToast('请先登录后再标记观看状态', 'info'); return }
    const nextStatus = favStatus === 'COMPLETED' ? 'WATCHING' : 'COMPLETED'
    try {
      await api.put(`/api/v1/me/favorites/catalog/${itemId}/status?status=${nextStatus}`)
      setFavorited(true)
      setFavStatus(nextStatus)
      showToast(nextStatus === 'COMPLETED' ? '已标记为「已看完」' : '已改回「在追」', 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.message : '更新观看状态失败', 'error')
    }
  }

  const lines = data?.lines ?? []
  const displayLines = lines.map((l, idx) => ({ key: l.name, label: formatAnimeLine(idx) }))
  const activeLineLabel = displayLines.find(l => l.key === line)?.label ?? '国内线路 1'
  const lineAllEpisodes = lines.find(l => l.name === line)?.episodes ?? []
  const mainCount = lineAllEpisodes.filter(e => e.episodeType !== 'EXTRA').length
  const extraCount = lineAllEpisodes.filter(e => e.episodeType === 'EXTRA').length
  const episodes = lineAllEpisodes
    .filter(e => e.episodeType === tab)
    .sort((a, b) => a.ordinal - b.ordinal)
  const goto = (id: string) => navigate(`/browse/${itemId}/${id}`)

  if (error) return <main className="watch-page"><p className="error">{error}</p></main>
  if (!data) return <main className="watch-page page-center">加载中…</main>
  if (!current) return <main className="watch-page page-center">正在准备剧集…</main>

  return (
    <EpisodePlayerShell
      title={finalTitle}
      current={current}
      activeLineLabel={activeLineLabel}
      backLinkTo={`/catalog/${itemId}`}
      backLinkLabel="作品详情"
      notice={notice}
      status={status}
      resumeMs={resumeMs}
      resolving={resolving}
      failed={failed}
      onProgress={saveProgress}
      onResolve={() => void resolve(current.id)}
      lines={displayLines}
      activeLine={line}
      onSelectLine={target => {
        setLine(target)
        const targetLine = lines.find(l => l.name === target)
        const match = targetLine?.episodes.find(e => e.episodeType === tab && e.ordinal === current.ordinal)
          ?? targetLine?.episodes.find(e => e.episodeType === tab)
        if (match) goto(match.id)
      }}
      hasExtra={lineAllEpisodes.some(e => e.episodeType === 'EXTRA')}
      mainCount={mainCount}
      extraCount={extraCount}
      tab={tab}
      onSelectTab={setTab}
      episodes={episodes}
      onSelectEpisode={goto}
      similar={similar}
      overview={overview}
      extraActions={
        <>
          <button
            type="button"
            className={'watch-fav-pill' + (favorited ? ' is-fav' : '')}
            onClick={() => void toggleFavorite()}
          >
            <HeartIcon size={14} filled={favorited} />
            {favorited ? '已追番' : '追番'}
          </button>
          <button
            type="button"
            className={'watch-dock-icon' + (favStatus === 'COMPLETED' ? ' is-done' : '')}
            title={favStatus === 'COMPLETED' ? '已看完（点击改回在追）' : '标记已看完'}
            aria-label={favStatus === 'COMPLETED' ? '已看完' : '标记已看完'}
            aria-pressed={favStatus === 'COMPLETED'}
            onClick={() => void toggleCompleted()}
          >
            <CheckCircleIcon size={15} />
          </button>
        </>
      }
    />
  )
}

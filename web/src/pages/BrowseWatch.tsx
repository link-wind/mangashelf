import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, ApiError } from '../api'
import EpisodePlayerShell, { type SimilarAnime } from '../components/EpisodePlayerShell'
import { useToast } from '../components/Toast'
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

/** 浏览态播放（M20）：复用 EpisodePlayerShell，进度按目录条目 + 集号保存。 */
export default function BrowseWatch() {
  const { itemId, episodeId } = useParams()
  const navigate = useNavigate()
  const { showToast } = useToast()
  const [data, setData] = useState<BrowseEpisodes | null>(null)
  const [line, setLine] = useState('')
  const [tab, setTab] = useState<'MAIN' | 'EXTRA'>('MAIN')
  const [error, setError] = useState('')
  const [favorited, setFavorited] = useState(false)
  const [similar, setSimilar] = useState<SimilarAnime[]>([])
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
    void api.get<SimilarAnime[]>(`/api/v1/recommendations/anime/${itemId}/similar?limit=6`)
      .then(setSimilar)
      .catch(() => setSimilar([]))
  }, [itemId])

  useEffect(() => {
    if (!itemId) return
    void api.get<{ catalogItemId: string | null }[]>('/api/v1/me/favorites?type=catalog')
      .then(list => setFavorited(list.some(f => f.catalogItemId === itemId)))
      .catch(() => { /* 收藏状态不可用不影响播放 */ })
  }, [itemId])

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
        showToast('已取消收藏', 'info')
      } else {
        await api.put(`/api/v1/me/favorites/catalog/${itemId}`)
        setFavorited(true)
        showToast('已加入「我的」收藏', 'success')
      }
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : '收藏操作失败'
      setError(msg)
      showToast(msg, 'error')
    }
  }

  const lines = data?.lines ?? []
  const episodes = (lines.find(l => l.name === line)?.episodes ?? [])
    .filter(e => e.episodeType === tab)
    .sort((a, b) => a.ordinal - b.ordinal)
  const goto = (id: string) => navigate(`/browse/${itemId}/${id}`)

  if (error) return <main className="watch-page"><p className="error">{error}</p></main>
  if (!data) return <main className="watch-page page-center">加载中…</main>
  if (!current) return <main className="watch-page page-center">正在准备剧集…</main>

  return (
    <EpisodePlayerShell
      title={data.item.name}
      current={current}
      metaText={`浏览模式（${favorited ? '已收藏' : '未收藏'}）· 共 ${episodes.length} 集 · 线路 ${line}`}
      backLinkTo={`/catalog/${itemId}`}
      backLinkLabel="作品详情"
      notice={notice}
      status={status}
      resumeMs={resumeMs}
      resolving={resolving}
      failed={failed}
      onProgress={saveProgress}
      onResolve={() => void resolve(current.id)}
      lines={lines.map(l => l.name)}
      activeLine={line}
      onSelectLine={target => {
        setLine(target)
        const targetLine = lines.find(l => l.name === target)
        const match = targetLine?.episodes.find(e => e.episodeType === tab && e.ordinal === current.ordinal)
          ?? targetLine?.episodes.find(e => e.episodeType === tab)
        if (match) goto(match.id)
      }}
      hasExtra={all.some(e => e.episodeType === 'EXTRA')}
      tab={tab}
      onSelectTab={setTab}
      episodes={episodes}
      onSelectEpisode={goto}
      similar={similar}
      extraActions={
        <button className="pill" onClick={() => void toggleFavorite()}>
          {favorited ? '取消收藏' : '收藏'}
        </button>
      }
      infoRows={[
        { label: '模式', value: `浏览（${favorited ? '已收藏' : '未收藏'}）` },
        { label: '线路', value: line },
        { label: '解析状态', value: status?.playUrl ? '已就绪' : failed ? '失败' : '解析中…' },
        { label: '进度', value: '服务端保存' },
      ]}
    />
  )
}

import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, ApiError } from '../api'
import { useAuth } from '../auth'
import EpisodePlayerShell, { type PlayerWorkOverview } from '../components/EpisodePlayerShell'
import {
  loadDanmakuSettings,
  saveDanmakuSettings,
  type DanmakuItem,
  type DanmakuSettings,
} from '../components/Player'
import { formatAnimeLine } from '../lineLabel'
import { formatEpisodeTitle } from '../lib/mediaFormat'
import { useEpisodePlayback } from '../useEpisodePlayback'

interface BoundEpisode {
  id: string; sourceId: string; sourceName: string; title: string
  episodeUrl: string; ordinal: number; lineName: string; lineRank: number
  episodeType: 'MAIN' | 'EXTRA'
}

interface WorkExternalMeta {
  coverUrl: string | null
  catalogTitle: string | null
  rating: number | null
  summary: string | null
  airWeekday: number | null
  lines: string[]
}

/** 已入库作品播放页：复用 8:4 影院壳 + 外置弹幕发送条 + 弹幕外观设置 + 右侧本集弹幕列表。 */
export default function ExternalWatch() {
  const { workId, episodeId } = useParams()
  const navigate = useNavigate()
  const { me } = useAuth()
  const [episodes, setEpisodes] = useState<BoundEpisode[]>([])
  const [lineName, setLineName] = useState('')
  const [tab, setTab] = useState<'MAIN' | 'EXTRA'>('MAIN')
  const [loadError, setLoadError] = useState('')
  const [workTitle, setWorkTitle] = useState('')
  const [overview, setOverview] = useState<PlayerWorkOverview | null>(null)
  const [danmakuItems, setDanmakuItems] = useState<DanmakuItem[]>([])
  const [dmSettings, setDmSettings] = useState<DanmakuSettings>(loadDanmakuSettings)
  const [sending, setSending] = useState(false)
  const { status, resumeMs, notice, resolve, saveProgress, resolving, failed } =
    useEpisodePlayback(episodeId)
  const current = episodes.find(e => e.id === episodeId) ?? null

  useEffect(() => {
    void (async () => {
      if (!workId) return
      try {
        const list = await api.get<BoundEpisode[]>(`/api/v1/works/${workId}/external-episodes`)
        setEpisodes(list)
        void api.get<{ work: { title: string; description?: string } }>(`/api/v1/works/${workId}`)
          .then(v => setWorkTitle(v.work.title))
          .catch(() => { /* 标题失败不影响播放 */ })
        void api.get<WorkExternalMeta | null>(`/api/v1/works/${workId}/external-meta`)
          .then(m => {
            if (m) {
              setOverview({
                coverUrl: m.coverUrl,
                rating: m.rating,
                summary: m.summary,
                airWeekday: m.airWeekday,
              })
            }
          })
          .catch(() => { /* 无扩展元数据时忽略 */ })
      } catch (e) {
        setLoadError(e instanceof ApiError ? e.message : '加载选集失败')
      }
    })()
  }, [workId])

  useEffect(() => {
    if (current) {
      setLineName(current.lineName)
      if (current.episodeType === 'EXTRA') setTab('EXTRA')
    }
  }, [current?.id])

  useEffect(() => {
    if (workTitle) {
      document.title = current
        ? `${workTitle} · ${formatEpisodeTitle(current.ordinal, current.title)} · GemiNeko`
        : `${workTitle} · GemiNeko`
    }
  }, [workTitle, current?.ordinal, current?.title])

  useEffect(() => {
    if (workId && current) {
      void api.get<DanmakuItem[]>(`/api/v1/danmaku?workId=${workId}&ordinal=${current.ordinal}`)
        .then(setDanmakuItems)
        .catch(() => setDanmakuItems([]))
    }
  }, [workId, current?.ordinal])

  function updateDmSettings(next: DanmakuSettings) {
    setDmSettings(next)
    saveDanmakuSettings(next)
  }

  async function sendDanmaku(content: string, color: string, timeMs: number) {
    if (!workId || !current) return
    setSending(true)
    try {
      const created = await api.post<DanmakuItem & { ordinal: number }>('/api/v1/danmaku', {
        workId, ordinal: current.ordinal, episodeId: current.id, timeMs, content, color,
      })
      setDanmakuItems(prev => [...prev, created].sort((a, b) => a.timeMs - b.timeMs))
    } finally {
      setSending(false)
    }
  }

  const rawLines = [...new Map(episodes.map(e => [e.lineName, e.lineRank])).entries()]
    .sort((a, b) => a[1] - b[1])
    .map(([name]) => name)
  const displayLines = rawLines.map((key, idx) => ({ key, label: formatAnimeLine(idx, key) }))
  const activeLineLabel = displayLines.find(l => l.key === lineName)?.label ?? '国内线路 1'

  const lineAll = episodes.filter(e => e.lineName === lineName)
  const mainCount = lineAll.filter(e => e.episodeType !== 'EXTRA').length
  const extraCount = lineAll.filter(e => e.episodeType === 'EXTRA').length
  const lineEpisodes = lineAll
    .filter(e => e.episodeType === tab)
    .sort((a, b) => a.ordinal - b.ordinal)
  const goto = (id: string) => navigate(`/works/${workId}/external/${id}`)

  if (loadError) return <main className="watch-page"><p className="error">{loadError}</p></main>
  if (!current) return <main className="watch-page page-center">加载中…</main>

  return (
    <EpisodePlayerShell
      title={workTitle || '动画播放'}
      current={current}
      activeLineLabel={activeLineLabel}
      backLinkTo={`/works/${workId}`}
      backLinkLabel="作品详情"
      notice={notice}
      status={status}
      resumeMs={resumeMs}
      resolving={resolving}
      failed={failed}
      onProgress={saveProgress}
      onResolve={() => void resolve(current.id)}
      lines={displayLines}
      activeLine={lineName}
      onSelectLine={target => {
        setLineName(target)
        const match = episodes.find(e => e.lineName === target && e.ordinal === current.ordinal)
          ?? episodes.find(e => e.lineName === target && e.episodeType === tab)
        if (match) goto(match.id)
      }}
      hasExtra={lineAll.some(e => e.episodeType === 'EXTRA')}
      mainCount={mainCount}
      extraCount={extraCount}
      tab={tab}
      onSelectTab={setTab}
      episodes={lineEpisodes}
      onSelectEpisode={goto}
      overview={overview}
      danmaku={{
        items: danmakuItems,
        settings: dmSettings,
        sending,
        canSend: Boolean(me),
        onUpdateSettings: updateDmSettings,
        onSend: sendDanmaku,
      }}
    />
  )
}

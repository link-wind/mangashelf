import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, ApiError } from '../api'
import EpisodePlayerShell from '../components/EpisodePlayerShell'
import type { DanmakuItem } from '../components/Player'
import { formatAnimeLine } from '../lineLabel'
import { useEpisodePlayback } from '../useEpisodePlayback'

interface BoundEpisode {
  id: string; sourceId: string; sourceName: string; title: string
  episodeUrl: string; ordinal: number; lineName: string; lineRank: number
  episodeType: 'MAIN' | 'EXTRA'
}

/** 作品播放页：复用 EpisodePlayerShell，线路显示为「国内线路 1/2」，附带弹幕功能。 */
export default function ExternalWatch() {
  const { workId, episodeId } = useParams()
  const navigate = useNavigate()
  const [episodes, setEpisodes] = useState<BoundEpisode[]>([])
  const [lineName, setLineName] = useState('')
  const [tab, setTab] = useState<'MAIN' | 'EXTRA'>('MAIN')
  const [loadError, setLoadError] = useState('')
  const [workTitle, setWorkTitle] = useState('')
  const [danmakuItems, setDanmakuItems] = useState<DanmakuItem[]>([])
  const [danmakuEnabled, setDanmakuEnabled] = useState<boolean>(
    () => localStorage.getItem('ms-danmaku') !== 'off')
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
        void api.get<{ work: { title: string } }>(`/api/v1/works/${workId}`)
          .then(v => setWorkTitle(v.work.title))
          .catch(() => { /* 标题失败不影响播放 */ })
      } catch (e) {
        setLoadError(e instanceof ApiError ? e.message : '加载选集失败')
      }
    })()
  }, [workId])

  useEffect(() => {
    if (current) setLineName(current.lineName)
  }, [current?.id])

  useEffect(() => {
    if (workId && current) {
      void api.get<DanmakuItem[]>(`/api/v1/danmaku?workId=${workId}&ordinal=${current.ordinal}`)
        .then(setDanmakuItems)
        .catch(() => setDanmakuItems([]))
    }
  }, [workId, current?.ordinal])

  function toggleDanmaku() {
    setDanmakuEnabled(v => {
      localStorage.setItem('ms-danmaku', v ? 'off' : 'on')
      return !v
    })
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
  const displayLines = rawLines.map((key, idx) => ({ key, label: formatAnimeLine(idx) }))
  const activeLineLabel = displayLines.find(l => l.key === lineName)?.label ?? '国内线路 1'

  const lineEpisodes = episodes
    .filter(e => e.lineName === lineName && e.episodeType === tab)
    .sort((a, b) => a.ordinal - b.ordinal)
  const goto = (id: string) => navigate(`/works/${workId}/external/${id}`)

  if (loadError) return <main className="watch-page"><p className="error">{loadError}</p></main>
  if (!current) return <main className="watch-page page-center">加载中…</main>

  return (
    <EpisodePlayerShell
      title={workTitle || '动画播放'}
      current={current}
      metaText={`共 ${lineEpisodes.length} 集 · ${activeLineLabel}`}
      backLinkTo={`/works/${workId}`}
      backLinkLabel="← 返回作品"
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
      hasExtra={episodes.some(e => e.episodeType === 'EXTRA')}
      tab={tab}
      onSelectTab={setTab}
      episodes={lineEpisodes}
      onSelectEpisode={goto}
      danmaku={{
        items: danmakuItems,
        enabled: danmakuEnabled,
        sending,
        onToggle: toggleDanmaku,
        onSend: sendDanmaku,
      }}
    />
  )
}

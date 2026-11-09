import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import Hls from 'hls.js'
import { api, ApiError } from '../api'

interface BoundEpisode {
  id: string; sourceId: string; sourceName: string; title: string
  episodeUrl: string; ordinal: number; lineName: string; lineRank: number
  episodeType: 'MAIN' | 'EXTRA'
}
interface ResolveStatus {
  jobId: string | null; jobState: string
  playUrl: string | null; playKind: string | null
  resolvedAt: string | null; errorCode: string | null; errorMessage: string | null
}
interface ProgressRow {
  workId: string; workTitle: string; ordinal: number; episodeTitle: string | null
  episodeId: string | null; lineName: string | null; coverUrl: string | null
  positionMs: number; durationMs: number | null; updatedAt: string
}

const RESOLVE_POLL_MS = 2000
const RESOLVE_MAX_MS = 90_000

/** 外部集观看页：多线路切换 + 正片/特典分组 + 服务端进度续播（M10/M11）。 */
export default function ExternalWatch() {
  const { workId, episodeId } = useParams()
  const navigate = useNavigate()
  const [episodes, setEpisodes] = useState<BoundEpisode[]>([])
  const [status, setStatus] = useState<ResolveStatus | null>(null)
  const [savedProgress, setSavedProgress] = useState<ProgressRow | null>(null)
  const [lineName, setLineName] = useState('')
  const [tab, setTab] = useState<'MAIN' | 'EXTRA'>('MAIN')
  const [loadError, setLoadError] = useState('')
  const current = episodes.find(e => e.id === episodeId) ?? null
  const currentRef = useRef(current)
  currentRef.current = current

  useEffect(() => {
    void (async () => {
      if (!workId) return
      try {
        const list = await api.get<BoundEpisode[]>(`/api/v1/works/${workId}/external-episodes`)
        setEpisodes(list)
        const rows = await api.get<ProgressRow[]>('/api/v1/me/continue-watching?limit=100')
        setSavedProgress(rows.find(r => r.workId === workId) ?? null)
      } catch (e) {
        setLoadError(e instanceof ApiError ? e.message : '加载选集失败')
      }
    })()
  }, [workId])

  // 当前集所在线路同步到选择器
  useEffect(() => {
    if (current) setLineName(current.lineName)
  }, [current?.id])

  useEffect(() => {
    setStatus(null)
    if (episodeId) void resolveAndPlay(episodeId)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [episodeId])

  const resolveAndPlay = useCallback(async (id: string) => {
    try {
      let st = await api.get<ResolveStatus>(`/api/v1/external-episodes/${id}/resolve`)
      if (!st.playUrl) {
        await api.post<ResolveStatus>(`/api/v1/external-episodes/${id}/resolve`, {})
        const startedAt = Date.now()
        for (;;) {
          if (Date.now() - startedAt > RESOLVE_MAX_MS) {
            st = { jobId: null, jobState: 'FAILED', playUrl: null, playKind: null, resolvedAt: null,
                   errorCode: 'RESOLVE_TIMEOUT', errorMessage: '解析超时，请稍后重试' }
            break
          }
          st = await api.get<ResolveStatus>(`/api/v1/external-episodes/${id}/resolve`)
          if (st.playUrl || st.jobState === 'FAILED' || st.errorCode) break
          await new Promise(r => setTimeout(r, RESOLVE_POLL_MS))
        }
      }
      if (currentRef.current?.id === id) setStatus(st)
    } catch (e) {
      if (currentRef.current?.id === id) setStatus({
        jobId: null, jobState: 'FAILED', playUrl: null, playKind: null, resolvedAt: null,
        errorCode: 'RESOLVE_ERROR',
        errorMessage: e instanceof ApiError ? e.message : '解析请求失败',
      })
    }
  }, [])

  const lines = [...new Map(episodes.map(e => [e.lineName, e.lineRank])).entries()]
    .sort((a, b) => a[1] - b[1])
    .map(([name]) => name)

  const lineEpisodes = episodes
    .filter(e => e.lineName === lineName && e.episodeType === tab)
    .sort((a, b) => a.ordinal - b.ordinal)

  const index = lineEpisodes.findIndex(e => e.id === episodeId)
  const prev = index > 0 ? lineEpisodes[index - 1] : null
  const next = index >= 0 && index < lineEpisodes.length - 1 ? lineEpisodes[index + 1] : null

  const goto = (id: string) => navigate(`/works/${workId}/external/${id}`)

  /** 切线路：按同一集号映射到目标线路（进度按 work+ordinal 记录，不丢）。 */
  function switchLine(target: string) {
    setLineName(target)
    if (!current) return
    const match = episodes.find(e => e.lineName === target && e.ordinal === current.ordinal)
    if (match) goto(match.id)
  }

  const hasExtra = episodes.some(e => e.episodeType === 'EXTRA')

  if (loadError) return <main className="watch-page"><p className="error">{loadError}</p></main>
  if (!current) return <main className="watch-page page-center">加载中…</main>

  return (
    <main className="watch-page">
      <div className="watch-title">
        <h1>{current.sourceName}</h1>
        <span className="ep-name">第 {current.ordinal} 话 {current.title}</span>
      </div>
      <div className="watch-meta">
        <span>共 {lineEpisodes.length} 集 · 线路 {lineName || '默认'}</span>
        <Link to={`/works/${workId}`}>← 返回作品</Link>
      </div>

      <div className="watch-layout">
        <div>
          <ExternalPlayer episodeId={current.id} workId={workId ?? ''} ordinal={current.ordinal}
                          status={status}
                          resumeMs={savedProgress?.ordinal === current.ordinal ? savedProgress.positionMs : 0}
                          onEnded={() => next && goto(next.id)}
                          onReResolve={() => void resolveAndPlay(current.id)} />
          <div className="watch-actions">
            <button className="ghost" disabled={!prev} onClick={() => prev && goto(prev.id)}>← 上一集</button>
            <button className="ghost" disabled={!next} onClick={() => next && goto(next.id)}>下一集 →</button>
            <button className="ghost" onClick={() => void resolveAndPlay(current.id)}>重新解析</button>
            <a className="btn ghost" href={current.episodeUrl} target="_blank" rel="noopener noreferrer">跳转原站</a>
            {status?.playKind && <span className="muted">流类型 {status.playKind === 'hls' ? 'HLS' : 'MP4'}</span>}
          </div>
        </div>

        <aside className="watch-side">
          <div className="card">
            <h3>选集（{lineEpisodes.length}）</h3>
            <div className="row-inline" style={{ marginBottom: 10 }}>
              {lines.map(name => (
                <button key={name} className={name === lineName ? 'mini' : 'mini ghost'}
                        onClick={() => switchLine(name)}>{name}</button>
              ))}
            </div>
            {hasExtra && (
              <div className="row-inline" style={{ marginBottom: 10 }}>
                <button className={tab === 'MAIN' ? 'mini' : 'mini ghost'} onClick={() => setTab('MAIN')}>正片</button>
                <button className={tab === 'EXTRA' ? 'mini' : 'mini ghost'} onClick={() => setTab('EXTRA')}>PV/特典</button>
              </div>
            )}
            <div className="episode-grid">
              {lineEpisodes.map(e => (
                <button key={e.id}
                        className={'episode-cell' + (e.id === current.id ? ' active' : '')}
                        onClick={() => goto(e.id)}>
                  <div className="no">{String(e.ordinal).padStart(2, '0')}</div>
                  <div className="name">{e.title}</div>
                </button>
              ))}
              {lineEpisodes.length === 0 && <p className="muted">该线路此分类暂无剧集</p>}
            </div>
          </div>
          <div className="card">
            <h3>播放信息</h3>
            <ul className="info-list">
              <li><span className="k">来源</span><span>{current.sourceName}</span></li>
              <li><span className="k">线路</span><span>{lineName || '默认'}</span></li>
              <li><span className="k">解析状态</span><span>{resolveText(status)}</span></li>
              <li><span className="k">播放方式</span><span>浏览器直连（不经服务器）</span></li>
              <li><span className="k">进度</span><span>服务端保存（跨设备续播）</span></li>
            </ul>
          </div>
        </aside>
      </div>
    </main>
  )
}

function resolveText(st: ResolveStatus | null): string {
  if (!st) return '待解析'
  if (st.playUrl) return '已就绪'
  if (st.errorCode) return `失败（${st.errorMessage ?? st.errorCode}）`
  return '解析中…'
}

function loadSavedPosition(episodeId: string): number {
  const v = Number(localStorage.getItem(`ms-ext-pos:${episodeId}`) ?? '0')
  return Number.isFinite(v) && v > 0 ? v : 0
}

/** hls.js 播放器：解析覆盖层 + 失效重解析 + 服务端进度续播/上报。 */
function ExternalPlayer({ episodeId, workId, ordinal, status, resumeMs, onEnded, onReResolve }: {
  episodeId: string
  workId: string
  ordinal: number
  status: ResolveStatus | null
  resumeMs: number
  onEnded: () => void
  onReResolve: () => void
}) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const hlsRef = useRef<Hls | null>(null)
  const retriedRef = useRef(false)
  const [fatal, setFatal] = useState<string | null>(null)

  const save = useCallback((force: boolean) => {
    const v = videoRef.current
    if (!v || !workId) return
    const ms = Math.floor(v.currentTime * 1000)
    if (!force && ms < 5000) return
    localStorage.setItem(`ms-ext-pos:${episodeId}`, String(v.currentTime))
    void api.put('/api/v1/external-progress', {
      workId, ordinal, episodeId, positionMs: ms,
      durationMs: Number.isFinite(v.duration) ? Math.floor(v.duration * 1000) : null,
    }).catch(() => { /* 进度上报失败不打断播放 */ })
  }, [episodeId, workId, ordinal])

  useEffect(() => {
    const v = videoRef.current
    if (!v || !status?.playUrl) return
    setFatal(null)
    let hls: Hls | null = null
    if (Hls.isSupported()) {
      hls = new Hls({ enableWorker: true })
      hlsRef.current = hls
      hls.loadSource(status.playUrl)
      hls.attachMedia(v)
      hls.on(Hls.Events.ERROR, (_evt, data) => {
        if (!data.fatal) return
        if (!retriedRef.current && data.type === Hls.ErrorTypes.NETWORK_ERROR) {
          retriedRef.current = true
          save(true)
          hls?.destroy()
          onReResolve()
        } else {
          setFatal('视频流中断且重试未恢复，请重新解析或跳转原站')
        }
      })
    } else {
      v.src = status.playUrl
    }
    const onTime = () => {
      if (Math.floor(v.currentTime) % 10 === 0) save(false)
    }
    const onLoaded = () => {
      const target = Math.max(resumeMs, loadSavedPosition(episodeId))
      if (target > 0 && target < (v.duration || Infinity) * 1000 - 5000) {
        v.currentTime = target / 1000
      }
    }
    v.addEventListener('timeupdate', onTime)
    v.addEventListener('loadedmetadata', onLoaded)
    v.addEventListener('ended', onEnded)
    v.play().catch(() => { /* 自动播放被拦截时等用户点击 */ })
    return () => {
      save(true)
      v.removeEventListener('timeupdate', onTime)
      v.removeEventListener('loadedmetadata', onLoaded)
      v.removeEventListener('ended', onEnded)
      hls?.destroy()
      hlsRef.current = null
      v.removeAttribute('src')
      v.load()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status?.playUrl, episodeId])

  const resolving = !status || (!status.playUrl && !status.errorCode
    && (status.jobState === 'QUEUED' || status.jobState === 'RUNNING' || status.jobState === 'NONE'))
  const failed = !!status?.errorCode || !!fatal

  return (
    <div className="player-shell">
      <video ref={videoRef} controls playsInline />
      {resolving && (
        <div className="player-overlay">
          <div>
            <div className="player-spinner" />
            <div className="big">正在获取视频源</div>
            <div className="sub">无头浏览器解析直链中，通常需要 5～15 秒</div>
          </div>
        </div>
      )}
      {failed && (
        <div className="player-overlay">
          <div>
            <div className="big">解析失败</div>
            <div className="sub">{fatal ?? status?.errorMessage ?? '未知错误'}</div>
            <button style={{ marginTop: 12 }} onClick={onReResolve}>重试解析</button>
          </div>
        </div>
      )}
    </div>
  )
}

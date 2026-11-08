import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import Hls from 'hls.js'
import { api, ApiError } from '../api'

interface BoundEpisode {
  id: string; sourceId: string; sourceName: string; title: string
  episodeUrl: string; ordinal: number
}
interface ResolveStatus {
  jobId: string | null; jobState: string
  playUrl: string | null; playKind: string | null
  resolvedAt: string | null; errorCode: string | null; errorMessage: string | null
}

const RESOLVE_POLL_MS = 2000
const RESOLVE_MAX_MS = 90_000

/** 外部集观看页（gugu 式版式）：解析 → hls.js 直连播放 → 失效重解析续播。 */
export default function ExternalWatch() {
  const { workId, episodeId } = useParams()
  const navigate = useNavigate()
  const [episodes, setEpisodes] = useState<BoundEpisode[]>([])
  const [status, setStatus] = useState<ResolveStatus | null>(null)
  const [loadError, setLoadError] = useState('')
  const current = episodes.find(e => e.id === episodeId) ?? null
  const currentRef = useRef(current)
  currentRef.current = current

  useEffect(() => {
    void (async () => {
      if (!workId) return
      try {
        setEpisodes(await api.get<BoundEpisode[]>(`/api/v1/works/${workId}/external-episodes`))
      } catch (e) {
        setLoadError(e instanceof ApiError ? e.message : '加载选集失败')
      }
    })()
  }, [workId])

  // 切集时清空上一集的解析状态并自动解析
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

  const index = episodes.findIndex(e => e.id === episodeId)
  const prev = index > 0 ? episodes[index - 1] : null
  const next = index >= 0 && index < episodes.length - 1 ? episodes[index + 1] : null

  const goto = (id: string) => navigate(`/works/${workId}/external/${id}`)

  if (loadError) return <main className="watch-page"><p className="error">{loadError}</p></main>
  if (!current) return <main className="watch-page page-center">加载中…</main>

  return (
    <main className="watch-page">
      <div className="watch-title">
        <h1>{current.sourceName}</h1>
        <span className="ep-name">第 {current.ordinal} 话 {current.title}</span>
      </div>
      <div className="watch-meta">
        <span>共 {episodes.length} 集 · 外部来源</span>
        <Link to={`/works/${workId}`}>← 返回作品</Link>
      </div>

      <div className="watch-layout">
        <div>
          <ExternalPlayer episodeId={current.id} status={status}
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
            <h3>选集（{episodes.length}）</h3>
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
              <li><span className="k">来源</span><span>{current.sourceName}</span></li>
              <li><span className="k">解析状态</span><span>{resolveText(status)}</span></li>
              <li><span className="k">播放方式</span><span>浏览器直连（不经服务器）</span></li>
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

/** hls.js 播放器：解析态覆盖层（"正在获取视频源"）+ 失效重解析续播。 */
function ExternalPlayer({ episodeId, status, onEnded, onReResolve }: {
  episodeId: string
  status: ResolveStatus | null
  onEnded: () => void
  onReResolve: () => void
}) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const hlsRef = useRef<Hls | null>(null)
  const retriedRef = useRef(false)
  const [fatal, setFatal] = useState<string | null>(null)

  const save = useCallback(() => {
    const v = videoRef.current
    if (v && v.currentTime > 0) localStorage.setItem(`ms-ext-pos:${episodeId}`, String(v.currentTime))
  }, [episodeId])

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
          save()
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
      if (Math.floor(v.currentTime) % 10 === 0) save()
    }
    const onLoaded = () => {
      const saved = loadSavedPosition(episodeId)
      if (saved > 0 && saved < (v.duration || Infinity) - 5) v.currentTime = saved
    }
    v.addEventListener('timeupdate', onTime)
    v.addEventListener('loadedmetadata', onLoaded)
    v.addEventListener('ended', onEnded)
    v.play().catch(() => { /* 自动播放被拦截时等用户点击 */ })
    return () => {
      save()
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

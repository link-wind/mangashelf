import { useCallback, useEffect, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import Hls from 'hls.js'
import { api, ApiError } from '../api'

interface SourceRow { id: string; name: string; baseUrl: string; enabled: boolean }
interface SearchItem { name: string; url: string }
interface EpisodeItem { title: string; url: string }
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

/** 作品外部集管理（M6 绑定 + M7 站内播放：解析 → hls.js 直连 → 过期重取）。 */
export default function ExternalEpisodes() {
  const { workId } = useParams()
  const [sources, setSources] = useState<SourceRow[]>([])
  const [selectedSource, setSelectedSource] = useState('')
  const [keyword, setKeyword] = useState('')
  const [results, setResults] = useState<SearchItem[]>([])
  const [episodes, setEpisodes] = useState<EpisodeItem[]>([])
  const [selectedEpisodes, setSelectedEpisodes] = useState<Set<number>>(new Set())
  const [bound, setBound] = useState<BoundEpisode[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [player, setPlayer] = useState<{ episode: BoundEpisode; status: ResolveStatus | null } | null>(null)

  const loadBound = useCallback(async () => {
    if (!workId) return
    try {
      setBound(await api.get<BoundEpisode[]>(`/api/v1/works/${workId}/external-episodes`))
    } catch { /* ignore */ }
  }, [workId])
  useEffect(() => { void loadBound() }, [loadBound])

  const loadSources = useCallback(async () => {
    try {
      const list = await api.get<SourceRow[]>('/api/v1/external-sources')
      setSources(list)
      if (list.length > 0 && !selectedSource) setSelectedSource(list[0].id)
    } catch { /* ignore */ }
  }, [selectedSource])
  useEffect(() => { void loadSources() }, [loadSources])

  async function search() {
    if (!selectedSource || !keyword.trim()) return
    setBusy(true); setError(''); setResults([]); setEpisodes([])
    try {
      setResults(await api.post<SearchItem[]>(`/api/v1/works/${workId}/external-search`,
        { sourceId: selectedSource, keyword: keyword.trim() }))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '搜索失败')
    } finally { setBusy(false) }
  }

  async function parseEpisodes(url: string) {
    setBusy(true); setError('')
    try {
      const list = await api.post<EpisodeItem[]>(`/api/v1/works/${workId}/external-episodes/parse`,
        { sourceId: selectedSource, episodePageUrl: url })
      setEpisodes(list)
      setSelectedEpisodes(new Set(list.map((_, i) => i)))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '解析失败')
    } finally { setBusy(false) }
  }

  async function bind() {
    if (!workId || !selectedSource || episodes.length === 0) return
    setBusy(true)
    try {
      const payload = episodes
        .filter((_, i) => selectedEpisodes.has(i))
        .map((e, i) => ({ title: e.title, url: e.url, ordinal: i + 1 }))
      await api.post(`/api/v1/works/${workId}/external-episodes`,
        { sourceId: selectedSource, episodes: payload })
      await loadBound()
      setEpisodes([]); setResults([]); setNotice('外部集绑定成功')
      setTimeout(() => setNotice(''), 3000)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '绑定失败')
    } finally { setBusy(false) }
  }

  async function unbind(id: string) {
    try { await api.delete(`/api/v1/external-episodes/${id}`); await loadBound() }
    catch (e) { setError(e instanceof ApiError ? e.message : '解绑失败') }
  }

  function play(episode: BoundEpisode) {
    setPlayer({ episode, status: null })
    void startPlayback(episode)
  }

  async function startPlayback(episode: BoundEpisode) {
    try {
      const st = await api.get<ResolveStatus>(`/api/v1/external-episodes/${episode.id}/resolve`)
      let status = st
      if (!st.playUrl) {
        status = await resolveUntilDone(episode.id)
      }
      setPlayer({ episode, status })
    } catch (e) {
      setPlayer({ episode, status: {
        jobId: null, jobState: 'FAILED', playUrl: null, playKind: null, resolvedAt: null,
        errorCode: 'RESOLVE_ERROR',
        errorMessage: e instanceof ApiError ? e.message : '解析请求失败',
      } })
    }
  }

  async function resolveUntilDone(id: string): Promise<ResolveStatus> {
    await api.post<ResolveStatus>(`/api/v1/external-episodes/${id}/resolve`, {})
    const startedAt = Date.now()
    for (;;) {
      if (Date.now() - startedAt > RESOLVE_MAX_MS) {
        return { jobId: null, jobState: 'FAILED', playUrl: null, playKind: null, resolvedAt: null,
                 errorCode: 'RESOLVE_TIMEOUT', errorMessage: '解析超时，请稍后重试或跳转原站' }
      }
      const st = await api.get<ResolveStatus>(`/api/v1/external-episodes/${id}/resolve`)
      if (st.playUrl || st.jobState === 'FAILED' || st.errorCode) return st
      await new Promise(r => setTimeout(r, RESOLVE_POLL_MS))
    }
  }

  return (
    <div className="indent" style={{ marginTop: 16 }}>
      <strong>外部动画源</strong>
      {error && <p className="error">{error}</p>}
      {notice && <p className="muted">{notice}</p>}

      <div className="row-inline" style={{ marginTop: 8 }}>
        <select value={selectedSource} onChange={e => setSelectedSource(e.target.value)}>
          {sources.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
          {sources.length === 0 && <option value="">（无可用规则源）</option>}
        </select>
        <input placeholder="搜索关键词" style={{ width: 180 }} value={keyword}
               onChange={e => setKeyword(e.target.value)}
               onKeyDown={e => e.key === 'Enter' && void search()} />
        <button onClick={() => void search()} disabled={busy || !selectedSource}>搜索</button>
      </div>

      {results.length > 0 && (
        <div className="stack" style={{ marginTop: 8 }}>
          <p className="muted">搜索结果（点击解析分集）：</p>
          {results.map((r, i) => (
            <button key={i} className="ghost" style={{ textAlign: 'left', fontSize: 13 }}
                    onClick={() => void parseEpisodes(r.url)}>
              {r.name}
            </button>
          ))}
        </div>
      )}

      {episodes.length > 0 && (
        <div className="stack" style={{ marginTop: 8 }}>
          <p className="muted">解析到 {episodes.length} 集（勾选后绑定）：</p>
          {episodes.map((e, i) => (
            <label key={i} className="scope-item">
              <input type="checkbox" checked={selectedEpisodes.has(i)}
                     onChange={() => {
                       const next = new Set(selectedEpisodes)
                       if (next.has(i)) next.delete(i); else next.add(i)
                       setSelectedEpisodes(next)
                     }} />
              {e.title}
            </label>
          ))}
          <button onClick={() => void bind()} disabled={busy || selectedEpisodes.size === 0}>
            绑定选中集
          </button>
        </div>
      )}

      {bound.length > 0 && (
        <ul className="revisions" style={{ marginTop: 12 }}>
          {bound.map(e => (
            <li key={e.id}>
              <span>{e.sourceName} · {e.title}</span>
              <span className="row-inline">
                <button className="btn ghost" onClick={() => play(e)}>播放</button>
                <a className="btn ghost" href={e.episodeUrl} target="_blank" rel="noopener noreferrer">跳转原站</a>
                <button className="ghost" onClick={() => void unbind(e.id)}>解绑</button>
              </span>
            </li>
          ))}
        </ul>
      )}

      {player && (
        <ExternalPlayer episode={player.episode} status={player.status}
                        onRetry={() => void startPlayback(player.episode)}
                        onClose={() => setPlayer(null)} />
      )}
    </div>
  )
}

function loadSavedPosition(episodeId: string): number {
  const v = Number(localStorage.getItem(`ms-ext-pos:${episodeId}`) ?? '0')
  return Number.isFinite(v) && v > 0 ? v : 0
}

/** 站内 hls.js 播放器（M7 design.md D6）：直连源站 CDN，过期重取续播。 */
function ExternalPlayer({ episode, status, onRetry, onClose }: {
  episode: BoundEpisode
  status: ResolveStatus | null
  onRetry: () => void
  onClose: () => void
}) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const hlsRef = useRef<Hls | null>(null)
  const retriedRef = useRef(false)
  const [fatal, setFatal] = useState<string | null>(null)
  const saveRef = useRef<() => void>(() => {})

  const save = useCallback(() => {
    const v = videoRef.current
    if (v && v.currentTime > 0) localStorage.setItem(`ms-ext-pos:${episode.id}`, String(v.currentTime))
  }, [episode.id])
  saveRef.current = save

  useEffect(() => {
    const v = videoRef.current
    if (!v || !status?.playUrl) return
    const url = status.playUrl
    setFatal(null)
    let hls: Hls | null = null
    if (Hls.isSupported()) {
      hls = new Hls({ enableWorker: true })
      hlsRef.current = hls
      hls.loadSource(url)
      hls.attachMedia(v)
      hls.on(Hls.Events.ERROR, (_evt, data) => {
        if (!data.fatal) return
        // 源站签名 token 过期：重解析后 seek 回原位置（design.md D6）
        if (!retriedRef.current && data.type === Hls.ErrorTypes.NETWORK_ERROR) {
          retriedRef.current = true
          saveRef.current()
          hls?.destroy()
          onRetry()
        } else {
          setFatal('视频流中断且重试未恢复，请重新解析或跳转原站')
        }
      })
    } else {
      // Safari 原生 HLS
      v.src = url
    }
    const onTime = () => {
      if (Math.floor(v.currentTime) % 10 === 0) saveRef.current()
    }
    const onLoaded = () => {
      const saved = loadSavedPosition(episode.id)
      if (saved > 0 && saved < (v.duration || Infinity) - 5) v.currentTime = saved
    }
    v.addEventListener('timeupdate', onTime)
    v.addEventListener('loadedmetadata', onLoaded)
    v.play().catch(() => { /* 自动播放被拦截时等用户点播放键 */ })
    return () => {
      saveRef.current()
      v.removeEventListener('timeupdate', onTime)
      v.removeEventListener('loadedmetadata', onLoaded)
      hls?.destroy()
      hlsRef.current = null
      v.removeAttribute('src')
      v.load()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status?.playUrl])

  const resolving = !status || (!status.playUrl && !status.errorCode
    && (status.jobState === 'QUEUED' || status.jobState === 'RUNNING' || status.jobState === 'NONE'))
  const failed = !!status?.errorCode

  return (
    <div className="card" style={{ marginTop: 16 }}>
      <div className="row-inline" style={{ justifyContent: 'space-between' }}>
        <strong>正在播放：{episode.sourceName} · {episode.title}</strong>
        <button className="ghost" onClick={onClose}>关闭</button>
      </div>
      {resolving && <p className="muted">解析视频直链中…（通常需要 5～15 秒）</p>}
      {(failed || fatal) && (
        <div className="stack">
          <p className="error">{failed ? `解析失败：${status?.errorMessage ?? '未知错误'}` : fatal}</p>
          <div className="row-inline">
            <button onClick={onRetry}>重试解析</button>
            <a className="btn ghost" href={episode.episodeUrl} target="_blank" rel="noopener noreferrer">跳转原站</a>
          </div>
        </div>
      )}
      <video ref={videoRef} controls playsInline style={{ width: '100%', maxHeight: 420, marginTop: 8 }} />
      {!failed && !fatal && (
        <p className="muted" style={{ fontSize: 12 }}>
          视频由浏览器直连来源播放；若中途断流将自动重新解析并续播。
        </p>
      )}
    </div>
  )
}

import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError } from '../api'
import Player, { type DanmakuItem } from '../components/Player'

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
  workId: string; ordinal: number; episodeId: string | null
  positionMs: number; durationMs: number | null; version: number; updatedAt: string | null
}

const RESOLVE_POLL_MS = 2000
const RESOLVE_MAX_MS = 90_000

/** 外部集观看页：多线路 + 正片/特典 + 服务端进度 + 自定义播放器与弹幕（M10–M13）。 */
export default function ExternalWatch() {
  const { workId, episodeId } = useParams()
  const navigate = useNavigate()
  const [episodes, setEpisodes] = useState<BoundEpisode[]>([])
  const [status, setStatus] = useState<ResolveStatus | null>(null)
  const [savedProgress, setSavedProgress] = useState<ProgressRow | null>(null)
  const [lineName, setLineName] = useState('')
  const [tab, setTab] = useState<'MAIN' | 'EXTRA'>('MAIN')
  const [loadError, setLoadError] = useState('')
  const [workTitle, setWorkTitle] = useState('')
  const [danmakuItems, setDanmakuItems] = useState<DanmakuItem[]>([])
  const [danmakuEnabled, setDanmakuEnabled] = useState<boolean>(
    () => localStorage.getItem('ms-danmaku') !== 'off')
  const [sending, setSending] = useState(false)
  const [progressNotice, setProgressNotice] = useState('')
  // 进度版本（unify-progress-conflict-and-search）：单飞行写入 + 最新位置合并。
  const versionRef = useRef(0)
  const versionOrdinalRef = useRef(-1)
  const pendingRef = useRef<{ id: string; ordinal: number; positionMs: number } | null>(null)
  const savingRef = useRef(false)
  const lastConflictRef = useRef(0)
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

  // 单集进度：拿到版本供写入携带；无记录时服务端返回版本 0。
  useEffect(() => {
    if (!workId || !current) { setSavedProgress(null); versionRef.current = 0; versionOrdinalRef.current = -1; return }
    let cancelled = false
    pendingRef.current = null
    versionOrdinalRef.current = current.ordinal
    void api.get<ProgressRow>(`/api/v1/me/external-progress?workId=${workId}&ordinal=${current.ordinal}`)
      .then(row => { if (!cancelled) { setSavedProgress(row); versionRef.current = row.version } })
      .catch(() => { if (!cancelled) { setSavedProgress(null); versionRef.current = 0 } })
    return () => { cancelled = true }
  }, [workId, current?.ordinal])

  const notifyConflict = useCallback(() => {
    const now = Date.now()
    if (now - lastConflictRef.current < 30_000) return
    lastConflictRef.current = now
    setProgressNotice('其他设备已更新进度，已对齐版本继续保存')
    window.setTimeout(() => setProgressNotice(''), 4000)
  }, [])

  const flushProgress = useCallback(() => {
    if (savingRef.current) return
    savingRef.current = true
    void (async () => {
      try {
        while (pendingRef.current) {
          const next = pendingRef.current
          pendingRef.current = null
          try {
            const res = await api.put<{ version: number }>('/api/v1/external-progress', {
              workId, ordinal: next.ordinal, episodeId: next.id,
              positionMs: next.positionMs, durationMs: null,
              expectedVersion: versionRef.current,
            })
            if (versionOrdinalRef.current === next.ordinal) versionRef.current = res.version
          } catch (e) {
            if (e instanceof ApiError && e.code === 'PROGRESS_CONFLICT') {
              const cur = (e.details?.current ?? {}) as { version?: number }
              if (typeof cur.version === 'number' && versionOrdinalRef.current === next.ordinal) {
                versionRef.current = cur.version
              }
              notifyConflict()
            }
          }
        }
      } finally { savingRef.current = false }
    })()
  }, [workId, notifyConflict])

  useEffect(() => {
    if (current) setLineName(current.lineName)
  }, [current?.id])

  // 换集：清解析状态、拉弹幕
  useEffect(() => {
    setStatus(null)
    if (!episodeId) return
    void resolveAndPlay(episodeId)
    if (workId && current) {
      void api.get<DanmakuItem[]>(`/api/v1/danmaku?workId=${workId}&ordinal=${current.ordinal}`)
        .then(setDanmakuItems)
        .catch(() => setDanmakuItems([]))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [episodeId, current?.ordinal])

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
      setStatus(prev => (episodeId === id ? st : prev))
    } catch (e) {
      setStatus({
        jobId: null, jobState: 'FAILED', playUrl: null, playKind: null, resolvedAt: null,
        errorCode: 'RESOLVE_ERROR',
        errorMessage: e instanceof ApiError ? e.message : '解析请求失败',
      })
    }
  }, [episodeId])

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

  function switchLine(target: string) {
    setLineName(target)
    if (!current) return
    const match = episodes.find(e => e.lineName === target && e.ordinal === current.ordinal)
    if (match) goto(match.id)
  }

  const hasExtra = episodes.some(e => e.episodeType === 'EXTRA')

  if (loadError) return <main className="watch-page"><p className="error">{loadError}</p></main>
  if (!current) return <main className="watch-page page-center">加载中…</main>

  const resolving = !status || (!status.playUrl && !status.errorCode
    && (status.jobState === 'QUEUED' || status.jobState === 'RUNNING' || status.jobState === 'NONE'))
  const failed = !!status?.errorCode

  return (
    <main className="watch-page">
      <div className="watch-title">
        <h1>{workTitle || current.sourceName}</h1>
        <span className="ep-name">第 {current.ordinal} 话 {current.title}</span>
      </div>
      <div className="watch-meta">
        <span>共 {lineEpisodes.length} 集 · 线路 {lineName || '默认'}</span>
        <Link to={`/works/${workId}`}>← 返回作品</Link>
      </div>

      {progressNotice && <p className="muted" role="status" style={{ margin: '6px 0 10px' }}>{progressNotice}</p>}
      <div className="watch-layout">
        <div>
          <Player
            src={status?.playUrl ?? null}
            kind={(status?.playKind as 'hls' | 'mp4' | null) ?? null}
            resumeMs={savedProgress?.positionMs ?? 0}
            onProgress={(ms) => {
              if (!workId || !current) return
              pendingRef.current = { id: current.id, ordinal: current.ordinal, positionMs: ms }
              flushProgress()
            }}
            onEnded={() => next && goto(next.id)}
            onFatal={() => void resolveAndPlay(current.id)}
            danmaku={{
              items: danmakuItems,
              enabled: danmakuEnabled,
              sending,
              onToggle: toggleDanmaku,
              onSend: sendDanmaku,
            }}
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
                  <button style={{ marginTop: 12 }} onClick={() => void resolveAndPlay(current.id)}>重试解析</button>
                </div>
              </div>
            ) : undefined}
          />
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
              <li><span className="k">弹幕</span><span>{danmakuItems.length} 条 · {danmakuEnabled ? '显示' : '已关闭'}</span></li>
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

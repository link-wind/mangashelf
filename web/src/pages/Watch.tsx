import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, ApiError, mediaUrl } from '../api'

interface SourceView {
  chapterRevisionId: string
  pageStart: number
  pageEnd: number
  chapterId: string
  chapterOrdinal: number
  chapterTitle: string
  workId: string
  workTitle: string
}

interface Playback {
  animationId: string
  revisionId: string
  durationMs: number | null
  videoUrl: string
  posterUrl: string | null
  subtitleUrl: string | null
  progress: { revisionId: string; positionMs: number; version: number } | null
  revisionMatches: boolean
  sources: SourceView[]
}

/** 观看页（DESIGN 14.2 / 4.1，gugu 式版式）：续播、字幕、来源信息与回到原作。 */
export default function Watch() {
  const { animationId } = useParams()
  const [pb, setPb] = useState<Playback | null>(null)
  const [title, setTitle] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const videoRef = useRef<HTMLVideoElement>(null)
  const versionRef = useRef(0)
  const lastSavedRef = useRef(0)

  useEffect(() => {
    void (async () => {
      try {
        const p = await api.get<Playback>(`/api/v1/animations/${animationId}/playback`)
        setPb(p)
        versionRef.current = p.progress?.version ?? 0
        if (!p.revisionMatches && p.progress) {
          setNotice('动画已更新版本，将从开头播放（旧进度属于旧版本）')
        } else if (p.progress && p.progress.positionMs > 0) {
          setNotice(`上次观看位置 ${Math.floor(p.progress.positionMs / 1000)} 秒，已续播`)
        }
      } catch (e) {
        setError(e instanceof ApiError ? e.message : '加载失败')
      }
    })()
    void (async () => {
      try {
        const a = await api.get<{ title?: string }>(`/api/v1/animations/${animationId}`)
        setTitle(a.title ?? '')
      } catch { /* 标题获取失败不阻塞播放 */ }
    })()
  }, [animationId])

  const saveProgress = useCallback(async (force = false) => {
    const video = videoRef.current
    if (!video || !pb) return
    const ms = Math.floor(video.currentTime * 1000)
    if (!force && Math.abs(ms - lastSavedRef.current) < 4500) return
    lastSavedRef.current = ms
    try {
      const snap = await api.put<{ version: number }>(`/api/v1/me/watch-progress/${animationId}`, {
        positionMs: ms, expectedVersion: versionRef.current,
      })
      versionRef.current = snap.version
      setNotice('')
    } catch (e) {
      const err = e as { code?: string; details?: { current?: { version: number } } }
      if (err.code === 'PROGRESS_CONFLICT' && err.details?.current) {
        versionRef.current = err.details.current.version
        setNotice('进度已在其他设备更新，已同步版本号')
      }
    }
  }, [animationId, pb])

  if (error && !pb) return <main className="watch-page"><p className="error">{error}</p></main>
  if (!pb) return <main className="watch-page page-center">加载中…</main>

  const source = pb.sources[0]

  return (
    <main className="watch-page">
      <div className="watch-title">
        <h1>{title || '改编动画'}</h1>
        {source && <span className="ep-name">改编自《{source.workTitle}》</span>}
      </div>
      <div className="watch-meta">
        <span>时长 {Math.round((pb.durationMs ?? 0) / 1000)} 秒</span>
        <span>版本 {pb.revisionId.slice(0, 8)}</span>
        {notice && <span className="muted">{notice}</span>}
      </div>

      <div className="watch-layout">
        <div>
          <div className="player-shell">
            <video
              ref={videoRef}
              controls
              playsInline
              poster={pb.posterUrl ? mediaUrl(pb.posterUrl) : undefined}
              onLoadedMetadata={() => {
                const video = videoRef.current
                if (!video) return
                if (pb.revisionMatches && pb.progress && pb.progress.positionMs > 0
                    && (pb.durationMs == null || pb.progress.positionMs < pb.durationMs - 1000)) {
                  video.currentTime = pb.progress.positionMs / 1000
                }
              }}
              onTimeUpdate={() => void saveProgress()}
              onPause={() => void saveProgress(true)}
            >
              <source src={mediaUrl(pb.videoUrl)} type="video/mp4" />
              {pb.subtitleUrl && (
                <track kind="subtitles" label="中文字幕" srcLang="zh-CN"
                       src={mediaUrl(pb.subtitleUrl)} default />
              )}
            </video>
          </div>
          <div className="watch-actions">
            {source && (
              <Link className="btn ghost"
                    to={`/read/${source.chapterRevisionId}?chapter=${source.chapterId}&resume=${source.pageStart}`}>
                回到原作（第 {source.pageStart} 页）
              </Link>
            )}
          </div>
        </div>

        <aside className="watch-side">
          <div className="card">
            <h3>改编来源（{pb.sources.length}）</h3>
            <div className="episode-grid">
              {pb.sources.map(s => (
                <Link key={s.chapterRevisionId + '-' + s.pageStart}
                      className="episode-cell"
                      style={{ textDecoration: 'none' }}
                      to={`/read/${s.chapterRevisionId}?chapter=${s.chapterId}&resume=${s.pageStart}`}>
                  <div className="no">第 {s.chapterOrdinal} 话</div>
                  <div className="name">{s.chapterTitle}</div>
                </Link>
              ))}
              {pb.sources.length === 0 && <p className="muted">未登记来源</p>}
            </div>
          </div>
          <div className="card">
            <h3>播放信息</h3>
            <ul className="info-list">
              <li><span className="k">播放方式</span><span>站内签名地址</span></li>
              <li><span className="k">进度保存</span><span>服务端（多设备同步）</span></li>
              <li><span className="k">字幕</span><span>{pb.subtitleUrl ? '中文字幕' : '无'}</span></li>
            </ul>
          </div>
        </aside>
      </div>
    </main>
  )
}

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

/** 观看页（DESIGN 14.2 / 4.1）：续播、字幕、来源信息与回到原作。 */
export default function Watch() {
  const { animationId } = useParams()
  const [pb, setPb] = useState<Playback | null>(null)
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
          setNotice(`上次观看位置 ${Math.floor(p.progress.positionMs / 1000)} 秒，将续播`)
        }
      } catch (e) {
        setError(e instanceof ApiError ? e.message : '加载失败')
      }
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

  if (error && !pb) return <main className="page"><p className="error">{error}</p></main>
  if (!pb) return <main className="page-center">加载中…</main>

  return (
    <main className="page watch">
      <div className="player-wrap">
        <video
          ref={videoRef}
          className="player"
          controls
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
      {notice && <p className="muted">{notice}</p>}
      <h1>动画</h1>
      <p className="muted">时长 {Math.round((pb.durationMs ?? 0) / 1000)} 秒 · 版本 {pb.revisionId.slice(0, 8)}</p>

      <h2>改编来源</h2>
      <ul className="chapters">
        {pb.sources.map(s => (
          <li key={s.chapterRevisionId + '-' + s.pageStart}>
            <span>
              《{s.workTitle}》第 {s.chapterOrdinal} 话 {s.chapterTitle}
              （第 {s.pageStart}–{s.pageEnd} 页）
            </span>
            <Link className="btn ghost"
                  to={`/read/${s.chapterRevisionId}?chapter=${s.chapterId}&resume=${s.pageStart}`}>
              回到原作（第 {s.pageStart} 页）
            </Link>
          </li>
        ))}
        {pb.sources.length === 0 && <li className="muted">未登记来源</li>}
      </ul>
      <p className="muted">返回后定位到来源起始页（DESIGN 4.1）。</p>
    </main>
  )
}

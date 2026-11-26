import { BookOpen, Keyboard, Lightbulb, RectangleHorizontal, Share2 } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, ApiError, mediaUrl } from '../api'
import { useAuth } from '../auth'
import Player from '../components/Player'
import { useToast } from '../components/Toast'

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

/** 站内改编动画观看页：与番剧播放页统一 8:4 影院版式，接入自定义播放器、宽屏、关灯与原作溯源。 */
export default function Watch() {
  const { me } = useAuth()
  const { animationId } = useParams()
  const { showToast } = useToast()
  const [pb, setPb] = useState<Playback | null>(null)
  const [title, setTitle] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [widescreen, setWidescreen] = useState(false)
  const [lightsOff, setLightsOff] = useState(false)
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
          setNotice(`上次观看位置 ${Math.floor(p.progress.positionMs / 1000)} 秒，已自动续播`)
        }
      } catch (e) {
        setError(e instanceof ApiError ? e.message : '加载失败')
      }
    })()
    void (async () => {
      try {
        const a = await api.get<{ title?: string }>(`/api/v1/animations/${animationId}`)
        setTitle(a.title ?? '')
        if (a.title) document.title = `${a.title} · MangaShelf`
      } catch { /* 标题获取失败不阻塞播放 */ }
    })()
  }, [animationId])

  const saveProgress = useCallback(async (posMs: number) => {
    if (!me || !pb) return
    if (Math.abs(posMs - lastSavedRef.current) < 4000) return
    lastSavedRef.current = posMs
    try {
      const snap = await api.put<{ version: number }>(`/api/v1/me/watch-progress/${animationId}`, {
        positionMs: posMs, expectedVersion: versionRef.current,
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
  }, [animationId, me, pb])

  if (error && !pb) return <main className="watch-page"><p className="error">{error}</p></main>
  if (!pb) return <main className="watch-page page-center">加载中…</main>

  const source = pb.sources[0]
  const resumeMs = pb.revisionMatches && pb.progress && pb.progress.positionMs > 0 ? pb.progress.positionMs : 0
  const durationSec = Math.round((pb.durationMs ?? 0) / 1000)

  function handleShare() {
    const url = window.location.href
    void navigator.clipboard?.writeText(url)
      .then(() => showToast('已复制播放页链接', 'success'))
      .catch(() => showToast(url, 'info'))
  }

  return (
    <main className={'watch-page watch-page-v2' + (lightsOff ? ' is-lights-off' : '')}>
      {lightsOff && (
        <div
          className="watch-lights-backdrop"
          onClick={() => setLightsOff(false)}
          aria-label="点击退出关灯模式"
        />
      )}

      <header className="watch-head-v2">
        <div className="watch-head-row1">
          <div className="watch-head-title-group">
            <h1>{title || '改编动画'}</h1>
            {source && <span className="watch-head-ep">改编自《{source.workTitle}》</span>}
            <span className="watch-head-badge is-cyan">站内动画</span>
            {durationSec > 0 && <span className="watch-head-badge tabular-nums">时长 {durationSec} 秒</span>}
          </div>
        </div>
        <div className="watch-head-row2">
          <div className="watch-head-meta">
            <span className="tabular-nums">版本 {pb.revisionId.slice(0, 8)}</span>
            <span>{pb.subtitleUrl ? '含中文字幕' : '原生音轨'}</span>
            {source && (
              <Link to={`/manga/${source.workId}`} className="watch-head-detail-link">
                查看原作漫画 →
              </Link>
            )}
          </div>
        </div>
        {notice && <p className="watch-head-notice" role="status">{notice}</p>}
      </header>

      <div className={'watch-grid-v2' + (widescreen ? ' is-widescreen' : '')}>
        <div className="watch-main-v2">
          <div className="watch-stage-wrap">
            <Player
              src={mediaUrl(pb.videoUrl)}
              kind="mp4"
              resumeMs={resumeMs}
              poster={pb.posterUrl ? mediaUrl(pb.posterUrl) : null}
              subtitleUrl={pb.subtitleUrl ? mediaUrl(pb.subtitleUrl) : null}
              widescreen={widescreen}
              lightsOff={lightsOff}
              onToggleWidescreen={() => setWidescreen(v => !v)}
              onToggleLightsOff={() => setLightsOff(v => !v)}
              onProgress={posMs => void saveProgress(posMs)}
            />

            <div className="watch-dock">
              <div className="watch-dock-row1">
                <div className="watch-dock-left">
                  {source && (
                    <Link
                      className="watch-dock-btn"
                      style={{ textDecoration: 'none' }}
                      to={`/read/${source.chapterRevisionId}?chapter=${source.chapterId}&resume=${source.pageStart}`}
                    >
                      <BookOpen size={14} />
                      <span>回到原作（第 {source.pageStart} 页）</span>
                    </Link>
                  )}
                </div>
                <div className="watch-dock-right">
                  <button
                    type="button"
                    className={'watch-dock-btn' + (widescreen ? ' is-on' : '')}
                    onClick={() => setWidescreen(v => !v)}
                    title="宽屏模式 (T)"
                  >
                    <RectangleHorizontal size={14} /><span>{widescreen ? '退出宽屏' : '宽屏'}</span>
                  </button>
                  <button
                    type="button"
                    className={'watch-dock-btn' + (lightsOff ? ' is-on' : '')}
                    onClick={() => setLightsOff(v => !v)}
                    title="关灯沉浸模式 (L)"
                  >
                    <Lightbulb size={14} /><span>{lightsOff ? '开灯' : '关灯'}</span>
                  </button>
                  <button
                    type="button"
                    className="watch-dock-icon"
                    onClick={handleShare}
                    aria-label="复制播放页链接"
                    title="复制播放页链接"
                  >
                    <Share2 size={14} />
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>

        <aside className="watch-side-v2">
          <section className="watch-ep-dock">
            <div className="watch-ep-head">
              <div className="watch-ep-tabs">
                <span className="watch-ep-tab is-active">
                  改编来源 <span className="tabular-nums">({pb.sources.length})</span>
                </span>
              </div>
            </div>
            <div className="watch-ep-grid-v2" style={{ gridTemplateColumns: 'repeat(2, minmax(0, 1fr))' }}>
              {pb.sources.map(s => (
                <Link
                  key={s.chapterRevisionId + '-' + s.pageStart}
                  className="watch-ep-tile"
                  style={{ textDecoration: 'none' }}
                  to={`/read/${s.chapterRevisionId}?chapter=${s.chapterId}&resume=${s.pageStart}`}
                >
                  <div className="wet-no-row">
                    <span className="wet-no tabular-nums">第 {s.chapterOrdinal} 话</span>
                  </div>
                  <span className="wet-sub">
                    {s.chapterTitle || `第 ${s.pageStart}–${s.pageEnd} 页`}
                  </span>
                </Link>
              ))}
              {pb.sources.length === 0 && (
                <p className="muted" style={{ gridColumn: '1 / -1', margin: '10px 0' }}>未登记来源章节</p>
              )}
            </div>
          </section>

          <section className="watch-facts-card">
            <div className="watch-facts-head">
              <h3>播放信息</h3>
              <span className="watch-facts-type">站内直连</span>
            </div>
            <dl className="watch-facts-dl">
              <div className="watch-facts-row">
                <dt>播放方式</dt>
                <dd>站内签名地址</dd>
              </div>
              <div className="watch-facts-row">
                <dt>进度同步</dt>
                <dd>云端多设备续播</dd>
              </div>
              <div className="watch-facts-row">
                <dt>字幕轨道</dt>
                <dd>{pb.subtitleUrl ? '中文字幕' : '无外挂字幕'}</dd>
              </div>
            </dl>
          </section>

          <section className="watch-shortcuts-card">
            <div className="watch-facts-head" style={{ marginBottom: 10 }}>
              <h3 className="row-inline" style={{ gap: 6 }}>
                <Keyboard size={15} />播放快捷键
              </h3>
            </div>
            <div className="watch-sc-grid">
              <span><kbd>Space</kbd> 播放/暂停</span>
              <span><kbd>←</kbd> <kbd>→</kbd> 快退/快进 5s</span>
              <span><kbd>↑</kbd> <kbd>↓</kbd> 调节音量</span>
              <span><kbd>M</kbd> 静音切换</span>
              <span><kbd>T</kbd> 宽屏模式</span>
              <span><kbd>L</kbd> 关灯沉浸</span>
              <span><kbd>F</kbd> 全屏播放</span>
            </div>
          </section>
        </aside>
      </div>
    </main>
  )
}

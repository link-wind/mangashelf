import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import CoverImage from './CoverImage'
import Player, { type DanmakuItem } from './Player'
import type { PlaybackStatus } from '../useEpisodePlayback'

export interface ShellEpisode {
  id: string
  title: string
  ordinal: number
  episodeType: 'MAIN' | 'EXTRA'
}

export interface ShellLine {
  key: string
  label: string
}

export interface SimilarAnime {
  catalogItemId: string
  workId: string | null
  title: string
  coverUrl: string
  rating: number | null
  reason: string
}

interface EpisodePlayerShellProps {
  title: string
  current: ShellEpisode
  metaText: string
  backLinkTo: string
  backLinkLabel: string
  notice?: string | null
  status: PlaybackStatus | null
  resumeMs: number
  resolving: boolean
  failed: boolean
  onProgress: (posMs: number, durMs?: number) => void
  onResolve: () => void
  lines: ShellLine[]
  activeLine: string
  onSelectLine: (lineKey: string) => void
  hasExtra: boolean
  tab: 'MAIN' | 'EXTRA'
  onSelectTab: (tab: 'MAIN' | 'EXTRA') => void
  episodes: ShellEpisode[]
  onSelectEpisode: (id: string) => void
  extraActions?: ReactNode
  similar?: SimilarAnime[]
  danmaku?: {
    items: DanmakuItem[]
    enabled: boolean
    sending: boolean
    onToggle: () => void
    onSend: (content: string, color: string, timeMs: number) => Promise<void>
  }
}

const CHUNK_SIZE = 24
const AUTOPLAY_KEY = 'ms-autoplay'

/** 共享剧集播放壳：简洁面向用户文案（国内线路 1/2、选集正倒序与分段、自动连播、相似推荐）。 */
export default function EpisodePlayerShell({
  title, current, metaText, backLinkTo, backLinkLabel, notice,
  status, resumeMs, resolving, failed, onProgress, onResolve,
  lines, activeLine, onSelectLine, hasExtra, tab, onSelectTab,
  episodes, onSelectEpisode, extraActions, similar = [], danmaku,
}: EpisodePlayerShellProps) {
  const [reverse, setReverse] = useState(false)
  const [autoPlay, setAutoPlay] = useState<boolean>(() => localStorage.getItem(AUTOPLAY_KEY) !== 'off')
  const [chunkIdx, setChunkIdx] = useState(0)

  const index = episodes.findIndex(e => e.id === current.id)
  const prev = index > 0 ? episodes[index - 1] : null
  const next = index >= 0 && index < episodes.length - 1 ? episodes[index + 1] : null

  const ordered = useMemo(() => (reverse ? [...episodes].reverse() : episodes), [episodes, reverse])
  const needsChunks = ordered.length > 30
  const chunks = useMemo(() => {
    if (!needsChunks) return []
    const out: { label: string; items: ShellEpisode[] }[] = []
    for (let i = 0; i < ordered.length; i += CHUNK_SIZE) {
      const slice = ordered.slice(i, i + CHUNK_SIZE)
      const firstNo = String(slice[0].ordinal).padStart(2, '0')
      const lastNo = String(slice[slice.length - 1].ordinal).padStart(2, '0')
      out.push({ label: `${firstNo}–${lastNo}`, items: slice })
    }
    return out
  }, [ordered, needsChunks])

  useEffect(() => {
    if (!needsChunks) { setChunkIdx(0); return }
    const pos = ordered.findIndex(e => e.id === current.id)
    if (pos >= 0) setChunkIdx(Math.floor(pos / CHUNK_SIZE))
  }, [current.id, needsChunks, ordered])

  const shownEpisodes = needsChunks ? (chunks[chunkIdx]?.items ?? ordered) : ordered

  function toggleAutoPlay() {
    setAutoPlay(v => {
      const nextVal = !v
      localStorage.setItem(AUTOPLAY_KEY, nextVal ? 'on' : 'off')
      return nextVal
    })
  }

  return (
    <main className="watch-page">
      <div className="watch-title">
        <h1>{title}</h1>
        <span className="ep-name">第 {current.ordinal} 话 {current.title}</span>
      </div>
      <div className="watch-meta">
        <span>{metaText}</span>
        <Link to={backLinkTo}>{backLinkLabel}</Link>
      </div>
      {notice && <p className="muted" role="status" style={{ margin: '6px 0 10px' }}>{notice}</p>}

      <div className="watch-layout">
        <div>
          <Player
            src={status?.playUrl ?? null}
            kind={(status?.playKind as 'hls' | 'mp4' | null) ?? null}
            resumeMs={resumeMs}
            onProgress={onProgress}
            onEnded={() => autoPlay && next && onSelectEpisode(next.id)}
            onFatal={onResolve}
            danmaku={danmaku}
            overlay={resolving ? (
              <div className="player-overlay">
                <div>
                  <div className="player-spinner" />
                  <div className="big">视频加载中</div>
                  <div className="sub">首次加载可能需要数秒，请稍候</div>
                </div>
              </div>
            ) : failed ? (
              status?.errorCode === 'NEED_LOGIN' ? (
                <div className="player-overlay">
                  <div>
                    <div className="big">登录后播放</div>
                    <div className="sub">登录后即可继续观看</div>
                    <Link to="/login" className="pill nav-login-btn" style={{ marginTop: 12, display: 'inline-flex' }}>去登录</Link>
                  </div>
                </div>
              ) : (
                <div className="player-overlay">
                  <div>
                    <div className="big">视频暂时无法播放</div>
                    <div className="sub">请尝试切换其他线路或点击重试</div>
                    <button style={{ marginTop: 12 }} onClick={onResolve}>重试</button>
                  </div>
                </div>
              )
            ) : undefined}
          />
          <div className="watch-actions">
            <button className="ghost" disabled={!prev} onClick={() => prev && onSelectEpisode(prev.id)}>← 上一集</button>
            <button className="ghost" disabled={!next} onClick={() => next && onSelectEpisode(next.id)}>下一集 →</button>
            <button className="ghost" onClick={onResolve}>刷新视频</button>
            {extraActions}
          </div>
        </div>

        <aside className="watch-side">
          <div className="card">
            <div className="row-between" style={{ marginBottom: 10 }}>
              <h3 style={{ margin: 0 }}>选集</h3>
              <div className="row-inline" style={{ gap: 6 }}>
                <label className="autoplay-toggle">
                  <input type="checkbox" checked={autoPlay} onChange={toggleAutoPlay} />
                  连播
                </label>
                <button type="button" className="mini ghost" title="切换正序/倒序"
                        onClick={() => setReverse(v => !v)}>
                  {reverse ? '倒序 ↓' : '正序 ↑'}
                </button>
              </div>
            </div>
            {lines.length > 0 && (
              <div className="row-inline" style={{ marginBottom: 10 }}>
                {lines.map(l => (
                  <button key={l.key} type="button" className={l.key === activeLine ? 'mini' : 'mini ghost'}
                          onClick={() => onSelectLine(l.key)}>{l.label}</button>
                ))}
              </div>
            )}
            {hasExtra && (
              <div className="row-inline" style={{ marginBottom: 10 }}>
                <button type="button" className={tab === 'MAIN' ? 'mini' : 'mini ghost'} onClick={() => onSelectTab('MAIN')}>正片</button>
                <button type="button" className={tab === 'EXTRA' ? 'mini' : 'mini ghost'} onClick={() => onSelectTab('EXTRA')}>PV/特典</button>
              </div>
            )}
            {needsChunks && (
              <div className="row-inline ep-chunks" style={{ marginBottom: 10 }}>
                {chunks.map((c, idx) => (
                  <button key={c.label} type="button"
                          className={'mini tabular-nums ' + (idx === chunkIdx ? '' : 'ghost')}
                          onClick={() => setChunkIdx(idx)}>
                    {c.label}
                  </button>
                ))}
              </div>
            )}
            <div className="episode-grid">
              {shownEpisodes.map(e => (
                <button key={e.id} type="button"
                        className={'episode-cell' + (e.id === current.id ? ' active' : '')}
                        onClick={() => onSelectEpisode(e.id)}>
                  <div className="no tabular-nums">{String(e.ordinal).padStart(2, '0')}</div>
                  <div className="name">{e.title}</div>
                </button>
              ))}
              {shownEpisodes.length === 0 && <p className="muted">该线路暂无剧集</p>}
            </div>
          </div>

          {similar.length > 0 && (
            <div className="card">
              <h3>相似推荐</h3>
              <div className="similar-grid">
                {similar.slice(0, 6).map(item => (
                  <Link key={item.catalogItemId}
                        to={item.workId ? `/works/${item.workId}` : `/catalog/${item.catalogItemId}`}
                        className="similar-tile" title={item.title}>
                    <div className="similar-cover">
                      <CoverImage src={item.coverUrl} alt={item.title} />
                      {typeof item.rating === 'number' && item.rating > 0 && (
                        <span className="badge-tr badge-score tabular-nums">★ {item.rating.toFixed(1)}</span>
                      )}
                    </div>
                    <div className="similar-title">{item.title}</div>
                  </Link>
                ))}
              </div>
            </div>
          )}
        </aside>
      </div>
    </main>
  )
}

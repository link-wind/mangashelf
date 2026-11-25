import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import Player, { type DanmakuItem } from './Player'
import type { PlaybackStatus } from '../useEpisodePlayback'

export interface ShellEpisode {
  id: string
  title: string
  ordinal: number
  episodeType: 'MAIN' | 'EXTRA'
}

export interface ShellInfoRow {
  label: string
  value: string
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
  lines: string[]
  activeLine: string
  onSelectLine: (line: string) => void
  hasExtra: boolean
  tab: 'MAIN' | 'EXTRA'
  onSelectTab: (tab: 'MAIN' | 'EXTRA') => void
  episodes: ShellEpisode[]
  onSelectEpisode: (id: string) => void
  extraActions?: ReactNode
  infoRows: ShellInfoRow[]
  danmaku?: {
    items: DanmakuItem[]
    enabled: boolean
    sending: boolean
    onToggle: () => void
    onSend: (content: string, color: string, timeMs: number) => Promise<void>
  }
}

/** 共享剧集播放壳：合并 BrowseWatch 与 ExternalWatch 的播放器、线路切换、正片/特典选集与上下集控制。 */
export default function EpisodePlayerShell({
  title, current, metaText, backLinkTo, backLinkLabel, notice,
  status, resumeMs, resolving, failed, onProgress, onResolve,
  lines, activeLine, onSelectLine, hasExtra, tab, onSelectTab,
  episodes, onSelectEpisode, extraActions, infoRows, danmaku,
}: EpisodePlayerShellProps) {
  const index = episodes.findIndex(e => e.id === current.id)
  const prev = index > 0 ? episodes[index - 1] : null
  const next = index >= 0 && index < episodes.length - 1 ? episodes[index + 1] : null

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
            onEnded={() => next && onSelectEpisode(next.id)}
            onFatal={onResolve}
            danmaku={danmaku}
            overlay={resolving ? (
              <div className="player-overlay">
                <div>
                  <div className="player-spinner" />
                  <div className="big">正在获取视频源</div>
                  <div className="sub">无头浏览器解析直链中，通常需要 5～15 秒</div>
                </div>
              </div>
            ) : failed ? (
              status?.errorCode === 'NEED_LOGIN' ? (
                <div className="player-overlay">
                  <div>
                    <div className="big">登录后播放</div>
                    <div className="sub">该剧集尚未解析直链，登录后可触发解析并播放</div>
                    <Link to="/login" className="pill nav-login-btn" style={{ marginTop: 12, display: 'inline-flex' }}>去登录</Link>
                  </div>
                </div>
              ) : (
                <div className="player-overlay">
                  <div>
                    <div className="big">解析失败</div>
                    <div className="sub">{status?.errorMessage ?? '未知错误'}</div>
                    <button style={{ marginTop: 12 }} onClick={onResolve}>重试解析</button>
                  </div>
                </div>
              )
            ) : undefined}
          />
          <div className="watch-actions">
            <button className="ghost" disabled={!prev} onClick={() => prev && onSelectEpisode(prev.id)}>← 上一集</button>
            <button className="ghost" disabled={!next} onClick={() => next && onSelectEpisode(next.id)}>下一集 →</button>
            <button className="ghost" onClick={onResolve}>重新解析</button>
            {extraActions}
          </div>
        </div>

        <aside className="watch-side">
          <div className="card">
            <h3>选集（{episodes.length}）</h3>
            {lines.length > 0 && (
              <div className="row-inline" style={{ marginBottom: 10 }}>
                {lines.map(name => (
                  <button key={name} className={name === activeLine ? 'mini' : 'mini ghost'}
                          onClick={() => onSelectLine(name)}>{name}</button>
                ))}
              </div>
            )}
            {hasExtra && (
              <div className="row-inline" style={{ marginBottom: 10 }}>
                <button className={tab === 'MAIN' ? 'mini' : 'mini ghost'} onClick={() => onSelectTab('MAIN')}>正片</button>
                <button className={tab === 'EXTRA' ? 'mini' : 'mini ghost'} onClick={() => onSelectTab('EXTRA')}>PV/特典</button>
              </div>
            )}
            <div className="episode-grid">
              {episodes.map(e => (
                <button key={e.id}
                        className={'episode-cell' + (e.id === current.id ? ' active' : '')}
                        onClick={() => onSelectEpisode(e.id)}>
                  <div className="no">{String(e.ordinal).padStart(2, '0')}</div>
                  <div className="name">{e.title}</div>
                </button>
              ))}
              {episodes.length === 0 && <p className="muted">该线路此分类暂无剧集</p>}
            </div>
          </div>
          <div className="card">
            <h3>播放信息</h3>
            <ul className="info-list">
              {infoRows.map(row => (
                <li key={row.label}><span className="k">{row.label}</span><span>{row.value}</span></li>
              ))}
            </ul>
          </div>
        </aside>
      </div>
    </main>
  )
}

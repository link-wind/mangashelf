import { ArrowLeft, ArrowRight, ChevronDown, ChevronUp, RefreshCw, Star } from 'lucide-react'
import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import AnimePosterCard from './AnimePosterCard'
import CoverImage from './CoverImage'
import Player, { type DanmakuItem } from './Player'
import Switch from './ui/Switch'
import { formatEpisodeTitle, splitBilingualSummary } from '../lib/mediaFormat'
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
  remarks?: string | null
}

export interface PlayerWorkOverview {
  coverUrl: string | null
  rating?: number | null
  summary?: string | null
  tags?: string[]
  categoryName?: string | null
  airDate?: string | null
  remarks?: string | null
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
  overview?: PlayerWorkOverview | null
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

/**
 * 现代化剧集播放壳：
 * - 统一面包屑与去重剧集标题（告别「第 1 话 第01集」）；
 * - 播放器下方提供「作品速览卡 + 宽幅猜你喜欢」，彻底消除左栏 900px 黑洞；
 * - 侧栏升级自定义 Switch 自动连播开关与现代选集格。
 */
export default function EpisodePlayerShell({
  title, current, metaText, backLinkTo, backLinkLabel, notice,
  status, resumeMs, resolving, failed, onProgress, onResolve,
  lines, activeLine, onSelectLine, hasExtra, tab, onSelectTab,
  episodes, onSelectEpisode, extraActions, similar = [], overview, danmaku,
}: EpisodePlayerShellProps) {
  const [reverse, setReverse] = useState(false)
  const [autoPlay, setAutoPlay] = useState<boolean>(() => localStorage.getItem(AUTOPLAY_KEY) !== 'off')
  const [chunkIdx, setChunkIdx] = useState(0)
  const [expandSummary, setExpandSummary] = useState(false)

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
  const epLabel = formatEpisodeTitle(current.ordinal, current.title)
  const parsedSummary = splitBilingualSummary(overview?.summary)

  function toggleAutoPlay(nextVal: boolean) {
    setAutoPlay(nextVal)
    localStorage.setItem(AUTOPLAY_KEY, nextVal ? 'on' : 'off')
  }

  return (
    <main className="watch-page">
      <nav className="breadcrumb" aria-label="面包屑">
        <Link to="/">番剧</Link>
        <span>/</span>
        <Link to={backLinkTo}>{title}</Link>
        <span>/</span>
        <span className="breadcrumb-cur">{epLabel}</span>
      </nav>

      <div className="watch-header-row">
        <div className="watch-title">
          <h1>{title}</h1>
          <span className="ep-name">{epLabel}</span>
        </div>
        <div className="watch-meta-inline">
          <span>{metaText}</span>
          <Link to={backLinkTo} className="btn ghost mini">{backLinkLabel} →</Link>
        </div>
      </div>
      {notice && <p className="muted" role="status" style={{ margin: '4px 0 10px' }}>{notice}</p>}

      <div className="watch-layout">
        <div className="watch-main-col">
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
                <div className="player-loading-card">
                  <div className="player-spinner" />
                  <div className="big">正在解析高清播放地址</div>
                  <div className="sub">首次加载通常需要 2–5 秒，也可在右侧切换其他线路</div>
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
                    <button type="button" className="pill" style={{ marginTop: 12 }} onClick={onResolve}>重试</button>
                  </div>
                </div>
              )
            ) : undefined}
          />

          <div className="watch-actions-bar">
            <div className="row-inline">
              <button type="button" className="watch-act-pill" disabled={!prev} onClick={() => prev && onSelectEpisode(prev.id)}>
                <ArrowLeft size={14} />上一集
              </button>
              <button type="button" className="watch-act-pill" disabled={!next} onClick={() => next && onSelectEpisode(next.id)}>
                下一集<ArrowRight size={14} />
              </button>
              <button type="button" className="watch-act-pill" onClick={onResolve}>
                <RefreshCw size={13} />刷新线路
              </button>
            </div>
            <div className="row-inline">
              {extraActions}
            </div>
          </div>

          {/* 播放器下方：作品速览卡（消除左栏大面积黑洞） */}
          {overview && (
            <section className="watch-overview-card">
              <Link to={backLinkTo} className="watch-ov-cover">
                <CoverImage src={overview.coverUrl} alt={title} />
              </Link>
              <div className="watch-ov-body">
                <div className="watch-ov-head">
                  <Link to={backLinkTo} className="watch-ov-title">{title}</Link>
                  <div className="row-inline">
                    {typeof overview.rating === 'number' && overview.rating > 0 && (
                      <span className="watch-ov-score tabular-nums">
                        <Star size={12} fill="currentColor" />{overview.rating.toFixed(1)}
                      </span>
                    )}
                    {overview.categoryName && <span className="space-tag is-anime">{overview.categoryName}</span>}
                    {overview.airDate && <span className="muted tabular-nums" style={{ fontSize: 12 }}>{overview.airDate.slice(0, 7)}</span>}
                    {overview.remarks && <span className="muted" style={{ fontSize: 12 }}>{overview.remarks}</span>}
                  </div>
                </div>

                {overview.tags && overview.tags.length > 0 && (
                  <div className="watch-ov-tags">
                    {overview.tags.slice(0, 8).map(t => (
                      <Link key={t} to={`/index?genre=${encodeURIComponent(t)}`} className="watch-ov-tag">
                        #{t}
                      </Link>
                    ))}
                  </div>
                )}

                {parsedSummary.zh && (
                  <div className="watch-ov-desc-wrap">
                    <p className={'watch-ov-desc' + (expandSummary ? ' is-open' : '')}>{parsedSummary.zh}</p>
                    {parsedSummary.zh.length > 90 && (
                      <button type="button" className="watch-ov-more" onClick={() => setExpandSummary(v => !v)}>
                        {expandSummary ? <>收起简介 <ChevronUp size={13} /></> : <>展开完整简介 <ChevronDown size={13} /></>}
                      </button>
                    )}
                  </div>
                )}
              </div>
            </section>
          )}

          {/* 播放器下方：宽幅 6 列猜你喜欢（取代右侧拥挤小图，拉齐左右列高） */}
          {similar.length > 0 && (
            <section className="watch-similar-section">
              <div className="section-head">
                <h2>
                  <span className="space-accent-bar is-cyan" style={{ display: 'inline-block', marginRight: 8, verticalAlign: -2 }} />
                  猜你喜欢
                </h2>
                <Link to="/index" className="muted">更多番剧 →</Link>
              </div>
              <div className="watch-similar-grid">
                {similar.slice(0, 6).map(item => (
                  <AnimePosterCard
                    key={item.catalogItemId}
                    title={item.title}
                    coverUrl={item.coverUrl}
                    detailHref={item.workId ? `/works/${item.workId}` : `/catalog/${item.catalogItemId}`}
                    playHref={item.workId ? `/works/${item.workId}` : `/browse/${item.catalogItemId}`}
                    rating={item.rating}
                    bottomRight={item.remarks || null}
                    metaLeft={item.reason || '同题材推荐'}
                    canFavorite={false}
                  />
                ))}
              </div>
            </section>
          )}
        </div>

        <aside className="watch-side">
          <div className="watch-side-card">
            <div className="row-between" style={{ marginBottom: 12 }}>
              <h3 style={{ margin: 0 }}>选集 <span className="space-sec-count tabular-nums">{episodes.length}</span></h3>
              <div className="row-inline" style={{ gap: 10 }}>
                <Switch checked={autoPlay} onChange={toggleAutoPlay} label="连播" />
                <button
                  type="button"
                  className="watch-act-pill is-xs"
                  title="切换正序/倒序"
                  onClick={() => setReverse(v => !v)}
                >
                  {reverse ? '倒序 ↓' : '正序 ↑'}
                </button>
              </div>
            </div>

            {lines.length > 0 && (
              <div className="watch-side-group">
                {lines.map(l => (
                  <button
                    key={l.key}
                    type="button"
                    className={'watch-seg-chip' + (l.key === activeLine ? ' is-active' : '')}
                    onClick={() => onSelectLine(l.key)}
                  >
                    {l.label}
                  </button>
                ))}
              </div>
            )}

            {hasExtra && (
              <div className="watch-side-group">
                <button type="button" className={'watch-seg-chip' + (tab === 'MAIN' ? ' is-active' : '')} onClick={() => onSelectTab('MAIN')}>正片</button>
                <button type="button" className={'watch-seg-chip' + (tab === 'EXTRA' ? ' is-active' : '')} onClick={() => onSelectTab('EXTRA')}>PV/特典</button>
              </div>
            )}

            {needsChunks && (
              <div className="watch-side-group">
                {chunks.map((c, idx) => (
                  <button
                    key={c.label}
                    type="button"
                    className={'watch-seg-chip tabular-nums' + (idx === chunkIdx ? ' is-active' : '')}
                    onClick={() => setChunkIdx(idx)}
                  >
                    {c.label}
                  </button>
                ))}
              </div>
            )}

            <div className="episode-grid">
              {shownEpisodes.map(e => (
                <button
                  key={e.id}
                  type="button"
                  className={'episode-cell' + (e.id === current.id ? ' active' : '')}
                  onClick={() => onSelectEpisode(e.id)}
                  title={formatEpisodeTitle(e.ordinal, e.title)}
                >
                  <div className="no tabular-nums">{String(e.ordinal).padStart(2, '0')}</div>
                  <div className="name">{e.title}</div>
                </button>
              ))}
              {shownEpisodes.length === 0 && <p className="muted">该线路暂无剧集</p>}
            </div>
          </div>
        </aside>
      </div>
    </main>
  )
}

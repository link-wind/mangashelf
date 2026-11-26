import {
  ArrowLeft, ArrowRight, ArrowUpDown, ChevronDown, ChevronUp, Keyboard,
  Lightbulb, MessageSquare, Radio, RectangleHorizontal, RefreshCw,
  Send, Share2, SlidersHorizontal, Star,
} from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import AnimePosterCard from './AnimePosterCard'
import Player, {
  DEFAULT_DANMAKU_SETTINGS,
  type DanmakuDensity,
  type DanmakuItem,
  type DanmakuSettings,
} from './Player'
import { useToast } from './Toast'
import Switch from './ui/Switch'
import {
  cleanStudioName,
  formatEpisodeTitle,
  formatStatusLabel,
  isCompletedStatus,
  splitBilingualSummary,
} from '../lib/mediaFormat'
import { useCatalogFavorites } from '../useCatalogFavorites'
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
  airWeekday?: number | null
  airTime?: string | null
  studio?: string | null
  mediaType?: string | null
  remarks?: string | null
  totalEpisodes?: number | null
}

export interface ShellDanmakuProps {
  items: DanmakuItem[]
  settings: DanmakuSettings
  sending?: boolean
  canSend?: boolean
  onUpdateSettings: (next: DanmakuSettings) => void
  onSend: (content: string, color: string, timeMs: number) => Promise<void>
}

interface EpisodePlayerShellProps {
  title: string
  current: ShellEpisode
  activeLineLabel: string
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
  mainCount?: number
  extraCount?: number
  tab: 'MAIN' | 'EXTRA'
  onSelectTab: (tab: 'MAIN' | 'EXTRA') => void
  episodes: ShellEpisode[]
  onSelectEpisode: (id: string) => void
  extraActions?: ReactNode
  similar?: SimilarAnime[]
  overview?: PlayerWorkOverview | null
  danmaku?: ShellDanmakuProps
}

const CHUNK_SIZE = 20
const AUTOPLAY_KEY = 'ms-autoplay'
const WEEKDAYS = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']
const DM_COLORS = ['#ffffff', '#00aeec', '#ff9500', '#ff5c8a', '#7c5cff', '#00c48c']

function cleanSubTitle(ep: ShellEpisode): string {
  const raw = (ep.title ?? '').trim()
  if (!raw) return ''
  if (/^(?:第?\s*0*\d+\s*[集话話回期]?|ep\.?\s*0*\d+)$/i.test(raw)) return ''
  return raw
}

function fmtShortTime(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000))
  const m = Math.floor(s / 60)
  const ss = String(s % 60).padStart(2, '0')
  return `${String(m).padStart(2, '0')}:${ss}`
}

/**
 * gugu.fan 启发式 12 列（8:4）影院动画播放壳：
 * - 顶部双行标题头（片名 + 内联集号 + 制式/状态胶囊 + 右侧 Bangumi 评分胶囊 + 更新周期）；
 * - 16:9 影院播放器 + 一体化操控与外置弹幕栏（上下集、弹幕密度/设置、宽屏、关灯、追番、已看完、分享）；
 * - 右侧 432px 选集与线路一体化面板（长番单行横滑+跳段下拉、4 列圆角选集卡+动态播放音柱、底部线路胶囊）；
 * - 右下方作品企划资料卡 + 快捷键/弹幕列表；左下方剧情简介 + 4 列猜你喜欢海报墙。
 */
export default function EpisodePlayerShell({
  title, current, activeLineLabel, backLinkTo, backLinkLabel, notice,
  status, resumeMs, resolving, failed, onProgress, onResolve,
  lines, activeLine, onSelectLine, hasExtra, mainCount, extraCount, tab, onSelectTab,
  episodes, onSelectEpisode, extraActions, similar = [], overview, danmaku,
}: EpisodePlayerShellProps) {
  const { showToast } = useToast()
  const { favIds: simFavIds, busyId: simBusyId, toggle: toggleSimFav } = useCatalogFavorites()

  const [reverse, setReverse] = useState(false)
  const [autoPlay, setAutoPlay] = useState<boolean>(() => localStorage.getItem(AUTOPLAY_KEY) !== 'off')
  const [chunkIdx, setChunkIdx] = useState(0)
  const [widescreen, setWidescreen] = useState(false)
  const [lightsOff, setLightsOff] = useState(false)
  const [expandSummary, setExpandSummary] = useState(false)
  const [showOrigSummary, setShowOrigSummary] = useState(false)
  const [dmSettingsOpen, setDmSettingsOpen] = useState(false)
  const [dmText, setDmText] = useState('')
  const [dmColor, setDmColor] = useState('#ffffff')

  const curTimeSecRef = useRef(0)
  const activeEpBtnRef = useRef<HTMLButtonElement>(null)
  const dmPopRef = useRef<HTMLDivElement>(null)

  const index = episodes.findIndex(e => e.id === current.id)
  const prev = index > 0 ? episodes[index - 1] : null
  const next = index >= 0 && index < episodes.length - 1 ? episodes[index + 1] : null

  const ordered = useMemo(() => (reverse ? [...episodes].reverse() : episodes), [episodes, reverse])
  const needsChunks = ordered.length > CHUNK_SIZE
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

  // 切换集数时让高亮剧集在选集盒内平滑居中（不滚动整页）
  useEffect(() => {
    const el = activeEpBtnRef.current
    if (!el) return
    const parent = el.parentElement
    if (!parent) return
    const targetTop = el.offsetTop - parent.clientHeight / 2 + el.clientHeight / 2
    parent.scrollTo({ top: Math.max(0, targetTop), behavior: 'smooth' })
  }, [current.id, chunkIdx])

  // 点击外部关闭弹幕设置弹层
  useEffect(() => {
    if (!dmSettingsOpen) return
    const onDown = (e: MouseEvent) => {
      if (dmPopRef.current && !dmPopRef.current.contains(e.target as Node)) setDmSettingsOpen(false)
    }
    window.addEventListener('mousedown', onDown)
    return () => window.removeEventListener('mousedown', onDown)
  }, [dmSettingsOpen])

  const shownEpisodes = needsChunks ? (chunks[chunkIdx]?.items ?? ordered) : ordered
  const epLabel = formatEpisodeTitle(current.ordinal, current.title)
  const parsedSummary = splitBilingualSummary(overview?.summary)
  const isCompleted = isCompletedStatus(overview?.remarks)
  const statusWord = formatStatusLabel(overview?.remarks)
  const totalEpCount = overview?.totalEpisodes || episodes.length
  const studioClean = cleanStudioName(overview?.studio, 28)
  const mediaBadge = overview?.mediaType || (overview?.categoryName ? overview.categoryName.replace(/动漫$/, '') : 'TV')
  const scheduleText = (() => {
    if (isCompleted) return '全季完结'
    if (overview?.airWeekday && WEEKDAYS[overview.airWeekday]) {
      return `每${WEEKDAYS[overview.airWeekday]}${overview.airTime ? ` ${overview.airTime}` : ''} 更新`
    }
    return ''
  })()

  function toggleAutoPlay(nextVal: boolean) {
    setAutoPlay(nextVal)
    localStorage.setItem(AUTOPLAY_KEY, nextVal ? 'on' : 'off')
  }

  const cycleDanmakuMode = useCallback(() => {
    if (!danmaku) return
    const order: DanmakuDensity[] = ['all', 'lite', 'off']
    const nextMode = order[(order.indexOf(danmaku.settings.mode) + 1) % order.length]
    danmaku.onUpdateSettings({ ...danmaku.settings, mode: nextMode })
    const label = nextMode === 'all' ? '全量' : nextMode === 'lite' ? '精简' : '已关闭'
    showToast(`弹幕模式：${label}`, 'info')
  }, [danmaku, showToast])

  function handleShare() {
    const url = window.location.href
    void navigator.clipboard?.writeText(url)
      .then(() => showToast('已复制当前播放页链接', 'success'))
      .catch(() => showToast(url, 'info'))
  }

  async function handleSendDanmaku(e: React.FormEvent) {
    e.preventDefault()
    if (!danmaku || !dmText.trim()) return
    try {
      await danmaku.onSend(dmText.trim(), dmColor, Math.floor(curTimeSecRef.current * 1000))
      setDmText('')
      showToast('弹幕已发送', 'success')
    } catch (err) {
      showToast(err instanceof Error ? err.message : '弹幕发送失败', 'error')
    }
  }

  const dmModeLabel = danmaku?.settings.mode === 'all' ? '全量' : danmaku?.settings.mode === 'lite' ? '精简' : '关闭'
  const hasManyChunks = chunks.length > 6

  return (
    <main className={'watch-page watch-page-v2' + (lightsOff ? ' is-lights-off' : '')}>
      {lightsOff && (
        <div
          className="watch-lights-backdrop"
          onClick={() => setLightsOff(false)}
          aria-label="点击退出关灯模式"
        />
      )}

      {/* 顶部双行标题头（借鉴 gugu.fan：高辨识标题 + 内联集号 + 右侧 Bangumi 评分胶囊） */}
      <header className="watch-head-v2">
        <div className="watch-head-row1">
          <div className="watch-head-title-group">
            <h1>
              <Link to={backLinkTo} className="watch-head-link">{title}</Link>
            </h1>
            <span className="watch-head-ep">{epLabel}</span>
            {mediaBadge && <span className="watch-head-badge is-cyan">{mediaBadge}</span>}
            <span className="watch-head-badge">
              {statusWord}{totalEpCount > 0 ? ` · 共 ${totalEpCount} 集` : ''}
            </span>
          </div>

          {typeof overview?.rating === 'number' && overview.rating > 0 && (
            <div className="watch-rating-pill tabular-nums" title="Bangumi 作品评分">
              <span className="wrp-score">
                <Star size={14} fill="currentColor" />
                {overview.rating.toFixed(1)}
              </span>
              <span className="wrp-sep" aria-hidden="true" />
              <span className="wrp-lbl">Bangumi 评分</span>
            </div>
          )}
        </div>

        <div className="watch-head-row2">
          <div className="watch-head-meta">
            {overview?.categoryName && <span>{overview.categoryName}</span>}
            {overview?.airDate && <span className="tabular-nums">{overview.airDate.slice(0, 7)}</span>}
            <span>当前：{activeLineLabel}</span>
            <Link to={backLinkTo} className="watch-head-detail-link">{backLinkLabel} →</Link>
          </div>
          {scheduleText && (
            <span className={'watch-head-schedule tabular-nums' + (isCompleted ? ' is-done' : '')}>
              {scheduleText}
            </span>
          )}
        </div>

        {notice && <p className="watch-head-notice" role="status">{notice}</p>}
      </header>

      {/* 12 列（8:4）影院主网格，支持一键切换宽屏模式 */}
      <div className={'watch-grid-v2' + (widescreen ? ' is-widescreen' : '')}>
        {/* 左主栏（8fr） */}
        <div className="watch-main-v2">
          <div className="watch-stage-wrap">
            <Player
              src={status?.playUrl ?? null}
              kind={(status?.playKind as 'hls' | 'mp4' | null) ?? null}
              resumeMs={resumeMs}
              poster={overview?.coverUrl}
              widescreen={widescreen}
              lightsOff={lightsOff}
              onToggleWidescreen={() => setWidescreen(v => !v)}
              onToggleLightsOff={() => setLightsOff(v => !v)}
              onPrev={prev ? () => onSelectEpisode(prev.id) : null}
              onNext={next ? () => onSelectEpisode(next.id) : null}
              onTimeTick={sec => { curTimeSecRef.current = sec }}
              onProgress={onProgress}
              onEnded={() => autoPlay && next && onSelectEpisode(next.id)}
              onFatal={onResolve}
              danmaku={danmaku ? {
                items: danmaku.items,
                settings: danmaku.settings,
                onCycleMode: cycleDanmakuMode,
              } : undefined}
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
                    <div className="player-loading-card">
                      <div className="big">登录后开启播放</div>
                      <div className="sub">登录后即可解锁高清线路与云端进度同步</div>
                      <Link to="/login" className="pill nav-login-btn" style={{ marginTop: 12, display: 'inline-flex' }}>去登录</Link>
                    </div>
                  </div>
                ) : (
                  <div className="player-overlay">
                    <div className="player-loading-card">
                      <div className="big">当前线路暂时无法播放</div>
                      <div className="sub">请尝试在右侧切换其他播放线路或点击重试</div>
                      <button type="button" className="pill" style={{ marginTop: 12 }} onClick={onResolve}>重试解析</button>
                    </div>
                  </div>
                )
              ) : undefined}
            />

            {/* 紧贴播放器底部的一体化操控与外置弹幕栏 */}
            <div className="watch-dock">
              <div className="watch-dock-row1">
                <div className="watch-dock-left">
                  <button
                    type="button"
                    className="watch-dock-btn"
                    disabled={!prev}
                    onClick={() => prev && onSelectEpisode(prev.id)}
                    title="上一集 (Shift+P)"
                  >
                    <ArrowLeft size={14} /><span>上一集</span>
                  </button>
                  <button
                    type="button"
                    className="watch-dock-btn"
                    disabled={!next}
                    onClick={() => next && onSelectEpisode(next.id)}
                    title="下一集 (Shift+N)"
                  >
                    <span>下一集</span><ArrowRight size={14} />
                  </button>
                  <button
                    type="button"
                    className="watch-dock-btn"
                    onClick={onResolve}
                    title="重新解析当前播放线路"
                  >
                    <RefreshCw size={13} /><span>刷新线路</span>
                  </button>

                  {danmaku && (
                    <div className="watch-dm-ctrl" ref={dmPopRef}>
                      <span className="watch-dm-stat tabular-nums">
                        <i className={'watch-dm-dot' + (danmaku.settings.mode === 'off' ? ' is-off' : '')} />
                        {danmaku.items.length > 0 ? `${danmaku.items.length} 条弹幕` : '本集暂无弹幕'}
                      </span>
                      <button
                        type="button"
                        className={'watch-dock-btn is-sm' + (danmaku.settings.mode !== 'off' ? ' is-on' : '')}
                        onClick={cycleDanmakuMode}
                        title="切换弹幕密度：全量 → 精简 → 关闭 (D)"
                      >
                        {dmModeLabel}
                      </button>
                      <button
                        type="button"
                        className={'watch-dock-icon' + (dmSettingsOpen ? ' is-on' : '')}
                        onClick={() => setDmSettingsOpen(v => !v)}
                        aria-label="弹幕外观设置"
                        title="弹幕外观设置"
                      >
                        <SlidersHorizontal size={14} />
                      </button>

                      {dmSettingsOpen && (
                        <div className="watch-dm-popover" role="dialog" aria-label="弹幕设置">
                          <div className="watch-dm-pop-head">
                            <strong>弹幕外观设置</strong>
                            <button
                              type="button"
                              className="watch-dm-reset"
                              onClick={() => danmaku.onUpdateSettings(DEFAULT_DANMAKU_SETTINGS)}
                            >
                              恢复默认
                            </button>
                          </div>
                          <label className="watch-dm-slider">
                            <span>不透明度 <em>{Math.round(danmaku.settings.opacity * 100)}%</em></span>
                            <input
                              type="range" min={0.3} max={1} step={0.05}
                              value={danmaku.settings.opacity}
                              onChange={e => danmaku.onUpdateSettings({ ...danmaku.settings, opacity: Number(e.target.value) })}
                            />
                          </label>
                          <label className="watch-dm-slider">
                            <span>字体大小 <em>{danmaku.settings.fontSize}px</em></span>
                            <input
                              type="range" min={14} max={24} step={1}
                              value={danmaku.settings.fontSize}
                              onChange={e => danmaku.onUpdateSettings({ ...danmaku.settings, fontSize: Number(e.target.value) })}
                            />
                          </label>
                          <label className="watch-dm-slider">
                            <span>滚动速度 <em>{danmaku.settings.speed <= 6 ? '较快' : danmaku.settings.speed >= 10 ? '较慢' : '适中'}</em></span>
                            <input
                              type="range" min={5} max={12} step={1}
                              value={17 - danmaku.settings.speed}
                              onChange={e => danmaku.onUpdateSettings({ ...danmaku.settings, speed: 17 - Number(e.target.value) })}
                            />
                          </label>
                          <label className="watch-dm-slider">
                            <span>显示区域 <em>{Math.round(danmaku.settings.area * 100)}%</em></span>
                            <input
                              type="range" min={0.25} max={1} step={0.25}
                              value={danmaku.settings.area}
                              onChange={e => danmaku.onUpdateSettings({ ...danmaku.settings, area: Number(e.target.value) })}
                            />
                          </label>
                        </div>
                      )}
                    </div>
                  )}
                </div>

                <div className="watch-dock-right">
                  <button
                    type="button"
                    className={'watch-dock-btn' + (widescreen ? ' is-on' : '')}
                    onClick={() => setWidescreen(v => !v)}
                    aria-pressed={widescreen}
                    title="宽屏影院模式 (T)"
                  >
                    <RectangleHorizontal size={14} /><span>{widescreen ? '退出宽屏' : '宽屏'}</span>
                  </button>
                  <button
                    type="button"
                    className={'watch-dock-btn' + (lightsOff ? ' is-on' : '')}
                    onClick={() => setLightsOff(v => !v)}
                    aria-pressed={lightsOff}
                    title="关灯沉浸模式 (L)"
                  >
                    <Lightbulb size={14} /><span>{lightsOff ? '开灯' : '关灯'}</span>
                  </button>
                  {extraActions}
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

              {/* 外置弹幕发送条（仅当启用弹幕能力时显示，不遮挡视频字幕） */}
              {danmaku && (
                <form className="watch-dm-composer" onSubmit={e => void handleSendDanmaku(e)}>
                  <div className="watch-dm-colors" role="radiogroup" aria-label="弹幕颜色">
                    {DM_COLORS.map(c => (
                      <button
                        key={c}
                        type="button"
                        className={'watch-dm-swatch' + (dmColor === c ? ' is-active' : '')}
                        style={{ background: c }}
                        aria-label={`弹幕颜色 ${c}`}
                        onClick={() => setDmColor(c)}
                      />
                    ))}
                  </div>
                  <input
                    value={dmText}
                    maxLength={200}
                    disabled={danmaku.canSend === false}
                    placeholder={danmaku.canSend === false ? '登录后发送实时弹幕…' : '发一条友善的弹幕见证此刻…'}
                    onChange={e => setDmText(e.target.value)}
                  />
                  <button
                    type="submit"
                    className="watch-dm-send-btn"
                    disabled={danmaku.canSend === false || danmaku.sending || !dmText.trim()}
                  >
                    <Send size={13} />
                    <span>发送</span>
                  </button>
                </form>
              )}
            </div>
          </div>

          {/* 左下方卡 1：剧情简介（中文纯净展示 + 可展开日文原文） */}
          {parsedSummary.zh && (
            <section className="watch-synopsis-card">
              <div className="watch-card-head">
                <h2>
                  <span className="space-accent-bar is-cyan" />
                  剧情简介
                </h2>
                <Link to={backLinkTo} className="watch-card-more">查看完整资料 →</Link>
              </div>
              <p className={'watch-syn-text' + (expandSummary ? ' is-open' : '')}>{parsedSummary.zh}</p>
              <div className="watch-syn-foot">
                {parsedSummary.zh.length > 140 && (
                  <button type="button" className="watch-ov-more" onClick={() => setExpandSummary(v => !v)}>
                    {expandSummary ? <>收起简介 <ChevronUp size={13} /></> : <>展开完整简介 <ChevronDown size={13} /></>}
                  </button>
                )}
                {parsedSummary.original && (
                  <button type="button" className="detail-orig-toggle" onClick={() => setShowOrigSummary(v => !v)}>
                    {showOrigSummary ? <>收起日文原文 <ChevronUp size={13} /></> : <>展开日文原文 <ChevronDown size={13} /></>}
                  </button>
                )}
              </div>
              {showOrigSummary && parsedSummary.original && (
                <p className="detail-orig-box">{parsedSummary.original}</p>
              )}
            </section>
          )}

          {/* 左下方卡 2：猜你喜欢（4 列高清海报墙，支持直接追番与直达播放） */}
          {similar.length > 0 && (
            <section className="watch-similar-section">
              <div className="watch-card-head" style={{ marginBottom: 14 }}>
                <h2>
                  <span className="space-accent-bar is-rose" />
                  猜你喜欢
                </h2>
                <Link to="/index" className="watch-card-more">更多番剧 →</Link>
              </div>
              <div className="watch-similar-grid-4">
                {similar.slice(0, 8).map(item => (
                  <AnimePosterCard
                    key={item.catalogItemId}
                    title={item.title}
                    coverUrl={item.coverUrl}
                    detailHref={item.workId ? `/works/${item.workId}` : `/catalog/${item.catalogItemId}`}
                    playHref={item.workId ? `/works/${item.workId}` : `/browse/${item.catalogItemId}`}
                    rating={item.rating}
                    bottomRight={item.remarks || null}
                    metaLeft={item.reason || '同题材推荐'}
                    favorited={simFavIds.has(item.catalogItemId)}
                    busy={simBusyId === item.catalogItemId}
                    onToggleFavorite={() => void toggleSimFav(item.catalogItemId, item.title)}
                  />
                ))}
              </div>
            </section>
          )}
        </div>

        {/* 右侧 432px 选集、线路与企划资料栏（4fr） */}
        <aside className="watch-side-v2">
          {/* 卡片 1：选集与线路一体化面板 */}
          <section className="watch-ep-dock">
            <div className="watch-ep-head">
              <div className="watch-ep-tabs" role="tablist" aria-label="剧集类型">
                <button
                  type="button"
                  role="tab"
                  aria-selected={tab === 'MAIN'}
                  className={'watch-ep-tab' + (tab === 'MAIN' ? ' is-active' : '')}
                  onClick={() => onSelectTab('MAIN')}
                >
                  正片选集 <span className="tabular-nums">({mainCount ?? (tab === 'MAIN' ? episodes.length : 0)})</span>
                </button>
                {hasExtra && (
                  <button
                    type="button"
                    role="tab"
                    aria-selected={tab === 'EXTRA'}
                    className={'watch-ep-tab' + (tab === 'EXTRA' ? ' is-active' : '')}
                    onClick={() => onSelectTab('EXTRA')}
                  >
                    PV·特典 <span className="tabular-nums">({extraCount ?? (tab === 'EXTRA' ? episodes.length : 0)})</span>
                  </button>
                )}
              </div>
              <div className="watch-ep-tools">
                <Switch checked={autoPlay} onChange={toggleAutoPlay} label="连播" />
                <button
                  type="button"
                  className="watch-ep-sort-btn"
                  title={reverse ? '当前倒序，点击切换正序' : '当前正序，点击切换倒序'}
                  aria-label="切换剧集正倒序"
                  onClick={() => setReverse(v => !v)}
                >
                  <ArrowUpDown size={14} />
                </button>
              </div>
            </div>

            {/* 长番智能分段：≤6 组平铺胶囊，>6 组单行横滑 + 快捷下拉跳段，杜绝撑爆首屏 */}
            {needsChunks && (
              <div className={'watch-chunk-bar' + (hasManyChunks ? ' is-scroll' : '')}>
                <div className="watch-chunk-pills">
                  {chunks.map((c, idx) => (
                    <button
                      key={c.label}
                      type="button"
                      className={'watch-chunk-pill tabular-nums' + (idx === chunkIdx ? ' is-active' : '')}
                      onClick={() => setChunkIdx(idx)}
                    >
                      {c.label}
                    </button>
                  ))}
                </div>
                {hasManyChunks && (
                  <select
                    className="watch-chunk-select tabular-nums"
                    aria-label="快速跳转集数分段"
                    value={chunkIdx}
                    onChange={e => setChunkIdx(Number(e.target.value))}
                  >
                    {chunks.map((c, idx) => (
                      <option key={c.label} value={idx}>第 {c.label} 集</option>
                    ))}
                  </select>
                )}
              </div>
            )}

            {/* 4 列大圆角剧集卡片网格（带最大高度内部滚动，确保线路栏始终首屏可见） */}
            <div className="watch-ep-grid-v2">
              {shownEpisodes.map(e => {
                const isActive = e.id === current.id
                const sub = cleanSubTitle(e)
                return (
                  <button
                    key={e.id}
                    ref={isActive ? activeEpBtnRef : undefined}
                    type="button"
                    className={'watch-ep-tile' + (isActive ? ' is-active' : '')}
                    onClick={() => onSelectEpisode(e.id)}
                    title={formatEpisodeTitle(e.ordinal, e.title)}
                  >
                    <div className="wet-no-row">
                      {isActive && (
                        <span className="watch-eq-bars" aria-hidden="true">
                          <i /><i /><i />
                        </span>
                      )}
                      <span className="wet-no tabular-nums">{String(e.ordinal).padStart(2, '0')}</span>
                    </div>
                    <span className="wet-sub">{sub || (isActive ? '正在播放' : `第 ${e.ordinal} 集`)}</span>
                  </button>
                )
              })}
              {shownEpisodes.length === 0 && (
                <p className="muted" style={{ gridColumn: '1 / -1', margin: '12px 0' }}>该线路暂无剧集</p>
              )}
            </div>

            {/* 底部嵌入式播放线路切换 */}
            {lines.length > 0 && (
              <div className="watch-line-footer">
                <div className="watch-line-head">
                  <Radio size={14} className="is-cyan" />
                  <span>播放线路</span>
                  <span className="muted tabular-nums" style={{ marginLeft: 'auto', fontSize: 11.5 }}>
                    共 {lines.length} 条
                  </span>
                </div>
                <div className="watch-line-pills">
                  {lines.map(l => (
                    <button
                      key={l.key}
                      type="button"
                      className={'watch-line-pill' + (l.key === activeLine ? ' is-active' : '')}
                      onClick={() => onSelectLine(l.key)}
                    >
                      {l.label}
                    </button>
                  ))}
                </div>
              </div>
            )}
          </section>

          {/* 卡片 2：作品企划资料（借鉴 gugu.fan，100% 真实数据，无值自动隐藏） */}
          {overview && (
            <section className="watch-facts-card">
              <div className="watch-facts-head">
                <h3>作品企划资料</h3>
                {mediaBadge && <span className="watch-facts-type">{mediaBadge} 系列</span>}
              </div>
              <dl className="watch-facts-dl">
                {studioClean && (
                  <div className="watch-facts-row">
                    <dt>动画制作</dt>
                    <dd title={overview.studio || ''}>{studioClean}</dd>
                  </div>
                )}
                {overview.categoryName && (
                  <div className="watch-facts-row">
                    <dt>作品分区</dt>
                    <dd>{overview.categoryName}</dd>
                  </div>
                )}
                {overview.airDate && (
                  <div className="watch-facts-row">
                    <dt>首播时间</dt>
                    <dd className="tabular-nums">{overview.airDate}</dd>
                  </div>
                )}
                {scheduleText && (
                  <div className="watch-facts-row">
                    <dt>更新周期</dt>
                    <dd className="tabular-nums">{scheduleText}</dd>
                  </div>
                )}
                <div className="watch-facts-row">
                  <dt>剧集进度</dt>
                  <dd className="tabular-nums">
                    {overview.remarks || (totalEpCount > 0 ? `${statusWord} · 共 ${totalEpCount} 集` : statusWord)}
                  </dd>
                </div>
              </dl>

              {overview.tags && overview.tags.length > 0 && (
                <div className="watch-facts-tags">
                  {overview.tags.slice(0, 8).map(t => (
                    <Link key={t} to={`/index?genre=${encodeURIComponent(t)}`} className="watch-ov-tag">
                      # {t}
                    </Link>
                  ))}
                </div>
              )}
            </section>
          )}

          {/* 卡片 3：有弹幕时展示「本集弹幕列表」，否则展示精简「播放快捷键」提示 */}
          {danmaku ? (
            <section className="watch-dm-list-card">
              <div className="watch-facts-head">
                <h3 className="row-inline" style={{ gap: 6 }}>
                  <MessageSquare size={15} />本集弹幕
                </h3>
                <span className="muted tabular-nums" style={{ fontSize: 12 }}>{danmaku.items.length} 条</span>
              </div>
              {danmaku.items.length === 0 ? (
                <p className="muted" style={{ margin: 0, fontSize: 12.5 }}>
                  还没有弹幕，在播放器下方发一条弹幕开个头吧。
                </p>
              ) : (
                <ul className="watch-dm-list">
                  {danmaku.items.slice(0, 60).map(item => (
                    <li key={item.id} className="watch-dm-row">
                      <span className="watch-dm-time tabular-nums">{fmtShortTime(item.timeMs)}</span>
                      <span className="watch-dm-text" title={item.content}>{item.content}</span>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          ) : (
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
                <span><kbd>Shift</kbd>+<kbd>N</kbd> 下一集</span>
              </div>
            </section>
          )}
        </aside>
      </div>
    </main>
  )
}

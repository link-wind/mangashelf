import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom'
import { api } from '../api'
import type { Me } from '../auth'
import CoverImage from './CoverImage'
import {
  BoltIcon, BookOpenIcon, BookmarkIcon, CalendarIcon, ChevronDownIcon,
  ClockIcon, CloseIcon, FilmIcon, LogOutIcon, MoonIcon, PlayIcon,
  SettingsIcon, SparklesIcon, SunIcon, UserIcon,
} from './Icons'

interface MiniFavorite {
  type: 'work' | 'manga' | 'catalog'
  status?: 'WATCHING' | 'COMPLETED'
}

interface MiniActivity {
  type: 'work-read' | 'manga-read' | 'animation-watch' | 'external-watch'
  title: string
  subtitle: string | null
  coverUrl: string | null
  workId: string | null
  catalogItemId: string | null
  chapterId: string | null
  revisionId: string | null
  pageOrdinal: number | null
  animationId: string | null
  episodeId: string | null
  ordinal: number | null
  positionMs: number | null
  durationMs: number | null
  source: string | null
  mangaId: string | null
}

interface PopoverSummary {
  animeWatching: number
  mangaWatching: number
  historyCount: number
  catchUpCount: number
  latest: MiniActivity | null
  fetchedAt: number
}

function fmtTime(ms: number | null | undefined): string {
  if (!ms || ms <= 0) return '00:00'
  const s = Math.floor(ms / 1000)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  const mm = String(m).padStart(2, '0')
  const ss = String(sec).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}

function miniActivityLink(a: MiniActivity): string {
  if (a.type === 'work-read') {
    return `/read/${a.revisionId}?chapter=${a.chapterId}&resume=${a.pageOrdinal ?? 1}`
  }
  if (a.type === 'manga-read') {
    const params = new URLSearchParams({
      mangaId: a.mangaId ?? '', lang: 'all', source: a.source ?? '', title: a.title,
    })
    return a.chapterId ? `/manga-ext/read/${a.chapterId}?${params}` : `/manga-ext/${a.mangaId}?${params}`
  }
  if (a.type === 'animation-watch') return `/watch/${a.animationId}`
  return a.workId ? `/works/${a.workId}/external/${a.episodeId}` : `/browse/${a.catalogItemId}/${a.episodeId}`
}

function miniActivityText(a: MiniActivity): string {
  if (a.type === 'work-read') return `${a.subtitle ?? '章节'} · 第 ${a.pageOrdinal ?? 1} 页`
  if (a.type === 'manga-read') return `${a.subtitle ? `${a.subtitle} · ` : ''}第 ${a.pageOrdinal ?? 1} 页`
  if (a.type === 'animation-watch') return `看到 ${fmtTime(a.positionMs)}`
  return `第 ${a.ordinal ?? 1} 集 · ${fmtTime(a.positionMs)}`
}

interface Props {
  me: Me | null
  theme: 'dark' | 'light'
  onToggleTheme: () => void
  onLogout: () => Promise<void>
}

/** 顶栏右上角快捷入口（收藏 / 历史 / 主题）+ 液态玻璃双态个人卡片。 */
export default function UserPopover({ me, theme, onToggleTheme, onLogout }: Props) {
  const [open, setOpen] = useState(false)
  const [summary, setSummary] = useState<PopoverSummary | null>(null)
  const [loadingSummary, setLoadingSummary] = useState(false)
  const wrapRef = useRef<HTMLDivElement>(null)
  const location = useLocation()
  const navigate = useNavigate()

  useEffect(() => {
    setOpen(false)
  }, [location.pathname, location.search])

  useEffect(() => {
    setSummary(null)
  }, [me?.id])

  // 登录后静默拉取一次轻量摘要，供顶栏追更角标与浮层秒开使用
  useEffect(() => {
    if (!me) return
    const fresh = summary && Date.now() - summary.fetchedAt < 60_000
    if (!open && summary) return
    if (open && fresh) return
    let cancelled = false
    setLoadingSummary(true)
    void Promise.all([
      api.get<MiniFavorite[]>('/api/v1/me/favorites').catch(() => [] as MiniFavorite[]),
      api.get<MiniActivity[]>('/api/v1/me/continue?limit=3').catch(() => [] as MiniActivity[]),
      api.get<MiniActivity[]>('/api/v1/me/history?limit=100').catch(() => [] as MiniActivity[]),
      api.get<{ catchUp?: unknown[] }>('/api/v1/recommendations/anime?limit=4').catch(() => ({ catchUp: [] })),
    ]).then(([favs, cont, hist, rec]) => {
      if (cancelled) return
      const animeWatching = favs.filter(f => f.type === 'catalog' && f.status !== 'COMPLETED').length
      const mangaWatching = favs.filter(f => f.type !== 'catalog' && f.status !== 'COMPLETED').length
      setSummary({
        animeWatching,
        mangaWatching,
        historyCount: hist.length,
        catchUpCount: Array.isArray(rec.catchUp) ? rec.catchUp.length : 0,
        latest: cont[0] ?? hist[0] ?? null,
        fetchedAt: Date.now(),
      })
    }).finally(() => {
      if (!cancelled) setLoadingSummary(false)
    })
    return () => { cancelled = true }
  }, [me, open])

  useEffect(() => {
    if (!open) return
    function onDocClick(e: MouseEvent) {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDocClick)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDocClick)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  async function handleLogout() {
    setOpen(false)
    await onLogout()
    navigate('/login')
  }

  const avatarChar = (me?.login || 'U').slice(0, 1).toUpperCase()
  const hasCatchUp = (summary?.catchUpCount ?? 0) > 0
  const latest = summary?.latest ?? null
  const latestIsAnime = latest ? (latest.type === 'animation-watch' || latest.type === 'external-watch') : false

  return (
    <div className="nav-actions">
      {me && (
        <>
          <NavLink
            to="/me?tab=favorites"
            className="nav-icon-btn nav-quick-btn"
            aria-label="我的追番与漫画书架"
            title="我的书架"
          >
            <BookmarkIcon size={16} />
            {hasCatchUp && <span className="nav-icon-dot" aria-label="有新集更新" />}
          </NavLink>
          <NavLink
            to="/me?tab=history"
            className="nav-icon-btn nav-quick-btn"
            aria-label="观看与阅读历史"
            title="足迹历史"
          >
            <ClockIcon size={16} />
          </NavLink>
        </>
      )}

      <button
        type="button"
        className="nav-icon-btn"
        onClick={onToggleTheme}
        aria-label="切换明暗主题"
        title={theme === 'dark' ? '切换到亮色主题' : '切换到暗色主题'}
      >
        {theme === 'dark' ? <SunIcon size={16} /> : <MoonIcon size={16} />}
      </button>

      <div className="user-menu-wrap" ref={wrapRef}>
        {me ? (
          <button
            type="button"
            className={'user-trigger' + (open ? ' is-open' : '')}
            aria-haspopup="dialog"
            aria-expanded={open}
            onClick={() => setOpen(v => !v)}
          >
            <span className="user-avatar">{avatarChar}</span>
            <span className="user-name">{me.login}</span>
            <ChevronDownIcon size={13} />
          </button>
        ) : (
          <button
            type="button"
            className={'user-guest-trigger' + (open ? ' is-open' : '')}
            aria-label="打开账号面板"
            aria-haspopup="dialog"
            aria-expanded={open}
            title="登录 / 账号面板"
            onClick={() => setOpen(v => !v)}
          >
            <UserIcon size={16} />
          </button>
        )}

        <AnimatePresence>
          {open && (
            <motion.div
              className="user-popover"
              role="dialog"
              aria-label={me ? '个人快捷卡片' : '访客账号面板'}
              initial={{ opacity: 0, y: -6, scale: 0.97 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, y: -6, scale: 0.97 }}
              transition={{ type: 'spring', stiffness: 480, damping: 32 }}
            >
            {me ? (
              <>
                <div className="up-head">
                  <div className="up-identity">
                    <span className="up-avatar">{avatarChar}</span>
                    <div className="up-id-text">
                      <div className="up-name-row">
                        <strong>{me.login}</strong>
                        <span className={'up-role-pill' + (me.role === 'ADMIN' ? ' is-admin' : '')}>
                          {me.role === 'ADMIN' ? '管理员' : '成员'}
                        </span>
                      </div>
                      <span className="up-sub">动画与漫画私人空间</span>
                    </div>
                  </div>
                  <Link to="/me" className="up-space-link" onClick={() => setOpen(false)}>
                    个人空间 ›
                  </Link>
                </div>

                <div className="up-stats-row tabular-nums">
                  <Link to="/me?space=anime&tab=favorites" className="up-stat-pill" onClick={() => setOpen(false)}>
                    <strong className="is-cyan">{loadingSummary && !summary ? '…' : (summary?.animeWatching ?? 0)}</strong>
                    <span>在追动画</span>
                  </Link>
                  <Link to="/me?space=manga&tab=favorites" className="up-stat-pill" onClick={() => setOpen(false)}>
                    <strong className="is-rose">{loadingSummary && !summary ? '…' : (summary?.mangaWatching ?? 0)}</strong>
                    <span>在追漫画</span>
                  </Link>
                  <Link to="/me?tab=history" className="up-stat-pill" onClick={() => setOpen(false)}>
                    <strong>{loadingSummary && !summary ? '…' : (summary?.historyCount ?? 0)}</strong>
                    <span>足迹记录</span>
                  </Link>
                </div>

                {latest && (
                  <Link to={miniActivityLink(latest)} className="up-resume-card" onClick={() => setOpen(false)}>
                    <div className="up-resume-cover">
                      <CoverImage src={latest.coverUrl} alt={latest.title} />
                    </div>
                    <div className="up-resume-info">
                      <div className="up-resume-eyebrow">
                        <span className={'up-media-dot ' + (latestIsAnime ? 'is-cyan' : 'is-rose')} />
                        <span>上次{latestIsAnime ? '观看到' : '阅读到'}</span>
                      </div>
                      <div className="up-resume-title">{latest.title}</div>
                      <div className="up-resume-meta">{miniActivityText(latest)}</div>
                    </div>
                    <span className={'up-resume-go ' + (latestIsAnime ? 'is-cyan' : 'is-rose')}>
                      {latestIsAnime ? <PlayIcon size={12} /> : <BookOpenIcon size={12} />}
                      继续
                    </span>
                  </Link>
                )}

                <div className="up-tile-grid">
                  <Link to="/me" className="up-tile" onClick={() => setOpen(false)}>
                    <span className="up-tile-ico is-cyan"><BookmarkIcon size={15} /></span>
                    <span className="up-tile-copy">
                      <strong>个人空间</strong>
                      <span>书架与喜好 DNA</span>
                    </span>
                  </Link>
                  <Link to="/me?tab=continue" className="up-tile" onClick={() => setOpen(false)}>
                    <span className="up-tile-ico is-rose"><BoltIcon size={15} /></span>
                    <span className="up-tile-copy">
                      <strong>
                        继续追更
                        {hasCatchUp && <em className="up-tile-badge">+{summary?.catchUpCount}</em>}
                      </strong>
                      <span>{hasCatchUp ? `${summary?.catchUpCount} 部新集待看` : '动画与漫画续看'}</span>
                    </span>
                  </Link>
                  <Link to="/schedule" className="up-tile" onClick={() => setOpen(false)}>
                    <span className="up-tile-ico is-amber"><CalendarIcon size={15} /></span>
                    <span className="up-tile-copy">
                      <strong>新番时刻表</strong>
                      <span>每周放送与高分前瞻</span>
                    </span>
                  </Link>
                  <Link to="/settings" className="up-tile" onClick={() => setOpen(false)}>
                    <span className="up-tile-ico is-slate"><SettingsIcon size={15} /></span>
                    <span className="up-tile-copy">
                      <strong>偏好与设置</strong>
                      <span>外观主题与播放习惯</span>
                    </span>
                  </Link>
                </div>

                <div className="up-foot">
                  <button type="button" className="up-foot-btn" onClick={onToggleTheme}>
                    {theme === 'dark' ? <SunIcon size={14} /> : <MoonIcon size={14} />}
                    <span>{theme === 'dark' ? '浅色外观' : '深色外观'}</span>
                  </button>
                  <button type="button" className="up-foot-btn is-danger" onClick={() => void handleLogout()}>
                    <LogOutIcon size={14} />
                    <span>退出登录</span>
                  </button>
                </div>
              </>
            ) : (
              <>
                <div className="up-head">
                  <div className="up-identity">
                    <span className="up-avatar is-guest"><UserIcon size={20} /></span>
                    <div className="up-id-text">
                      <div className="up-name-row">
                        <strong>你好，旅人！</strong>
                        <span className="up-role-pill">访客浏览</span>
                      </div>
                      <span className="up-sub">登录后同步你的追番与漫画书架</span>
                    </div>
                  </div>
                  <button type="button" className="up-close-btn" aria-label="关闭账号面板" onClick={() => setOpen(false)}>
                    <CloseIcon size={15} />
                  </button>
                </div>

                <div className="up-guest-banner">
                  <div className="up-guest-eyebrow">
                    <SparklesIcon size={13} />
                    <span>MangaShelf · 动画与漫画空间</span>
                  </div>
                  <strong>收藏好故事 · 记录每份进度</strong>
                  <p>跨设备同步番剧播放进度、漫画书架与每周更新提醒。</p>
                </div>

                <Link to="/login" className="up-login-cta" onClick={() => setOpen(false)}>
                  立即登录 / 注册 →
                </Link>

                <div className="up-tile-grid is-static">
                  <div className="up-tile">
                    <span className="up-tile-ico is-cyan"><FilmIcon size={15} /></span>
                    <span className="up-tile-copy">
                      <strong>追番进度同步</strong>
                      <span>自动记录集数与秒数</span>
                    </span>
                  </div>
                  <div className="up-tile">
                    <span className="up-tile-ico is-rose"><BookOpenIcon size={15} /></span>
                    <span className="up-tile-copy">
                      <strong>漫画跨源书架</strong>
                      <span>多源收藏与自动下一话</span>
                    </span>
                  </div>
                  <div className="up-tile">
                    <span className="up-tile-ico is-cyan"><CalendarIcon size={15} /></span>
                    <span className="up-tile-copy">
                      <strong>新番更新雷达</strong>
                      <span>本周排期与新集提醒</span>
                    </span>
                  </div>
                  <div className="up-tile">
                    <span className="up-tile-ico is-amber"><SparklesIcon size={15} /></span>
                    <span className="up-tile-copy">
                      <strong>高分精选索引</strong>
                      <span>按地区与评分智能发现</span>
                    </span>
                  </div>
                </div>

                <div className="up-foot">
                  <button type="button" className="up-foot-btn" onClick={() => { onToggleTheme(); setOpen(false) }}>
                    {theme === 'dark' ? <SunIcon size={14} /> : <MoonIcon size={14} />}
                    <span>{theme === 'dark' ? '切换浅色模式' : '切换深色模式'}</span>
                  </button>
                  <Link to="/index" className="up-foot-btn" onClick={() => setOpen(false)}>
                    <span>浏览番剧索引 ›</span>
                  </Link>
                </div>
              </>
            )}
            </motion.div>
          )}
        </AnimatePresence>
      </div>
    </div>
  )
}

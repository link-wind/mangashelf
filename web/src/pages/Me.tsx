import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import CoverImage from '../components/CoverImage'
import {
  BoltIcon, BookOpenIcon, BookmarkIcon, CalendarIcon, CheckCircleIcon,
  ClockIcon, CloseIcon, FilmIcon, GridIcon, ListIcon, PlayIcon,
  SearchIcon, SettingsIcon, SparklesIcon, UploadIcon,
} from '../components/Icons'
import { useToast } from '../components/Toast'

interface Favorite {
  type: 'work' | 'manga' | 'catalog'
  title: string
  coverUrl: string | null
  workId: string | null
  source: string | null
  mangaId: string | null
  catalogItemId: string | null
  status?: 'WATCHING' | 'COMPLETED'
  createdAt: string
}

type ActivityType = 'work-read' | 'manga-read' | 'animation-watch' | 'external-watch'

interface Activity {
  type: ActivityType
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
  updatedAt: string
}

interface CatchUpItem {
  catalogItemId: string
  workId: string | null
  title: string
  coverUrl: string
  remarks: string
  watchedOrdinal: number
  latestOrdinal: number
  newEpisodes: number
  resumeEpisodeId: string | null
}

interface CalendarEntry {
  subjectId: number
  name: string
  nameCn: string
  coverUrl: string
  rating: number | null
  airWeekday: number | null
  airTime?: string | null
  catalogItemId: string | null
  workId: string | null
  remarks: string | null
}

type MainTab = 'continue' | 'favorites' | 'completed' | 'history'
type MediaKind = 'all' | 'anime' | 'manga'
type ViewMode = 'grid' | 'list'
type FavSort = 'recent' | 'title'

const WEEKDAYS = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']

function isAnimeActivity(a: Activity): boolean {
  return a.type === 'animation-watch' || a.type === 'external-watch'
}

function isMangaActivity(a: Activity): boolean {
  return a.type === 'work-read' || a.type === 'manga-read'
}

function isAnimeFavorite(f: Favorite): boolean {
  return f.type === 'catalog'
}

function fmt(ms: number | null | undefined): string {
  if (!ms || ms <= 0) return '00:00'
  const s = Math.floor(ms / 1000)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  const mm = String(m).padStart(2, '0')
  const ss = String(sec).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}

function fmtDate(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function fmtRelativeTime(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const diffSec = Math.max(0, Math.floor((Date.now() - d.getTime()) / 1000))
  if (diffSec < 60) return '刚刚'
  if (diffSec < 3600) return `${Math.floor(diffSec / 60)} 分钟前`
  if (diffSec < 86400) return `${Math.floor(diffSec / 3600)} 小时前`
  const hhmm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  const now = new Date()
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1)
  if (d >= yesterday && d < new Date(now.getFullYear(), now.getMonth(), now.getDate())) {
    return `昨天 ${hhmm}`
  }
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${hhmm}`
}

function historyGroupLabel(iso: string): '今天' | '昨天' | '更早' {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '更早'
  const now = new Date()
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const yesterdayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1)
  if (d >= todayStart) return '今天'
  if (d >= yesterdayStart) return '昨天'
  return '更早'
}

function activitySummary(a: Activity): string {
  if (a.type === 'work-read') return `${a.subtitle ?? '章节'} · 第 ${a.pageOrdinal ?? 1} 页`
  if (a.type === 'manga-read') {
    return `${a.subtitle ? `${a.subtitle} · ` : ''}第 ${a.pageOrdinal ?? 1} 页`
  }
  if (a.type === 'animation-watch') return `看到 ${fmt(a.positionMs)}`
  return `第 ${a.ordinal ?? '?'} 集${a.subtitle ? ` ${a.subtitle}` : ''} · 看到 ${fmt(a.positionMs)}`
}

function activityLink(a: Activity): string {
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
  return a.workId
    ? `/works/${a.workId}/external/${a.episodeId}`
    : `/browse/${a.catalogItemId}/${a.episodeId}`
}

function activityUniqueKey(a: Activity): string {
  if (a.type === 'work-read') return `work:${a.workId}`
  if (a.type === 'manga-read') return `manga:${a.source}:${a.mangaId}`
  if (a.type === 'animation-watch') return `anim:${a.animationId}`
  return `cat:${a.workId ?? a.catalogItemId}`
}

function favKey(f: Favorite): string {
  if (f.type === 'work') return `work:${f.workId}`
  if (f.type === 'manga') return `manga:${f.source}:${f.mangaId}`
  return `catalog:${f.catalogItemId}`
}

function favLink(f: Favorite): string {
  if (f.type === 'work') return `/works/${f.workId}`
  if (f.type === 'manga') {
    const params = new URLSearchParams({ source: f.source ?? '', title: f.title })
    return `/manga-ext/${f.mangaId}?${params}`
  }
  return `/catalog/${f.catalogItemId}`
}

function favDeleteUrl(f: Favorite): string {
  if (f.type === 'work') return `/api/v1/me/favorites/work/${f.workId}`
  if (f.type === 'manga') {
    return `/api/v1/me/favorites/manga?source=${encodeURIComponent(f.source ?? '')}`
      + `&mangaId=${encodeURIComponent(f.mangaId ?? '')}`
  }
  return `/api/v1/me/favorites/catalog/${f.catalogItemId}`
}

/** 个人空间：支持动画与漫画双栖追更、书架管理、足迹时间线、本周追更雷达与偏好 DNA。 */
export default function Me() {
  const { me } = useAuth()
  const [params, setParams] = useSearchParams()
  const rawTab = params.get('tab')
  const tab: MainTab = rawTab === 'favorites' || rawTab === 'completed' || rawTab === 'history'
    ? rawTab : 'continue'
  const rawKind = params.get('kind')
  const kind: MediaKind = rawKind === 'anime' || rawKind === 'manga' ? rawKind : 'all'

  const [favorites, setFavorites] = useState<Favorite[]>([])
  const [favStatusFilter, setFavStatusFilter] = useState<'all' | 'WATCHING' | 'COMPLETED'>('all')
  const [favSort, setFavSort] = useState<FavSort>('recent')
  const [viewMode, setViewMode] = useState<ViewMode>('grid')
  const [filterQuery, setFilterQuery] = useState('')
  const [continueList, setContinueList] = useState<Activity[]>([])
  const [historyList, setHistoryList] = useState<Activity[]>([])
  const [catchUp, setCatchUp] = useState<CatchUpItem[]>([])
  const [animePrefs, setAnimePrefs] = useState<string[]>([])
  const [mangaPrefs, setMangaPrefs] = useState<string[]>([])
  const [calendar, setCalendar] = useState<CalendarEntry[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const { showToast } = useToast()

  useEffect(() => {
    void (async () => {
      setLoading(true)
      setError('')
      try {
        const [favs, cont, hist, animeRec, mangaRec, cal] = await Promise.all([
          api.get<Favorite[]>('/api/v1/me/favorites'),
          api.get<Activity[]>('/api/v1/me/continue?limit=40'),
          api.get<Activity[]>('/api/v1/me/history?limit=100'),
          api.get<{ catchUp?: CatchUpItem[]; topPreferences?: string[] }>('/api/v1/recommendations/anime?limit=6')
            .catch(() => ({ catchUp: [], topPreferences: [] })),
          api.get<{ topPreferences?: string[] }>('/api/v1/recommendations/manga?limit=6')
            .catch(() => ({ topPreferences: [] })),
          api.get<CalendarEntry[]>('/api/v1/calendar').catch(() => [] as CalendarEntry[]),
        ])
        setFavorites(favs)
        setContinueList(cont)
        setHistoryList(hist)
        setCatchUp(animeRec.catchUp ?? [])
        setAnimePrefs(animeRec.topPreferences ?? [])
        setMangaPrefs(mangaRec.topPreferences ?? [])
        setCalendar(cal)
      } catch (e) {
        setError(e instanceof Error ? e.message : '加载失败')
      } finally {
        setLoading(false)
      }
    })()
  }, [])

  function setTab(nextTab: MainTab, nextKind?: MediaKind) {
    const next = new URLSearchParams(params)
    next.set('tab', nextTab)
    if (nextKind !== undefined) {
      if (nextKind === 'all') next.delete('kind')
      else next.set('kind', nextKind)
    }
    setParams(next, { replace: true })
  }

  function setKind(nextKind: MediaKind) {
    const next = new URLSearchParams(params)
    if (nextKind === 'all') next.delete('kind')
    else next.set('kind', nextKind)
    setParams(next, { replace: true })
  }

  async function removeFavorite(f: Favorite) {
    try {
      await api.delete(favDeleteUrl(f))
      setFavorites(prev => prev.filter(item => favKey(item) !== favKey(f)))
      showToast(`已取消收藏「${f.title}」`, 'info')
    } catch (e) {
      const msg = e instanceof Error ? e.message : '取消收藏失败'
      setError(msg)
      showToast(msg, 'error')
    }
  }

  async function toggleFavoriteStatus(f: Favorite) {
    const nextStatus: 'WATCHING' | 'COMPLETED' = f.status === 'COMPLETED' ? 'WATCHING' : 'COMPLETED'
    let url: string
    if (f.type === 'work') {
      url = `/api/v1/me/favorites/work/${f.workId}/status?status=${nextStatus}`
    } else if (f.type === 'manga') {
      url = `/api/v1/me/favorites/manga/status?${new URLSearchParams({
        source: f.source ?? '', mangaId: f.mangaId ?? '', title: f.title, status: nextStatus,
      })}`
    } else {
      url = `/api/v1/me/favorites/catalog/${f.catalogItemId}/status?status=${nextStatus}`
    }
    try {
      await api.put(url)
      setFavorites(prev => prev.map(item => favKey(item) === favKey(f) ? { ...item, status: nextStatus } : item))
      showToast(nextStatus === 'COMPLETED' ? `已标记「${f.title}」为已看完` : `已改回在追「${f.title}」`, 'success')
    } catch (e) {
      showToast(e instanceof Error ? e.message : '更新状态失败', 'error')
    }
  }

  // ── 统计指标（全部基于真实接口聚合）──
  const stats = useMemo(() => {
    const animeWatching = favorites.filter(f => isAnimeFavorite(f) && f.status !== 'COMPLETED').length
    const mangaWatching = favorites.filter(f => !isAnimeFavorite(f) && f.status !== 'COMPLETED').length
    const animeCompleted = favorites.filter(f => isAnimeFavorite(f) && f.status === 'COMPLETED').length
    const mangaCompleted = favorites.filter(f => !isAnimeFavorite(f) && f.status === 'COMPLETED').length
    const completedTotal = animeCompleted + mangaCompleted

    const uniqueWorks = new Set<string>()
    const uniqueAnimeWorks = new Set<string>()
    const uniqueMangaWorks = new Set<string>()
    for (const f of favorites) {
      if (isAnimeFavorite(f)) uniqueAnimeWorks.add(favKey(f))
      else uniqueMangaWorks.add(favKey(f))
    }
    for (const a of historyList) {
      const k = activityUniqueKey(a)
      uniqueWorks.add(k)
      if (isAnimeActivity(a)) uniqueAnimeWorks.add(k)
      else uniqueMangaWorks.add(k)
    }

    const totalDna = uniqueAnimeWorks.size + uniqueMangaWorks.size
    const animeShare = totalDna > 0 ? Math.round((uniqueAnimeWorks.size / totalDna) * 100) : 50
    const mangaShare = totalDna > 0 ? 100 - animeShare : 50

    return {
      animeWatching,
      mangaWatching,
      animeCompleted,
      mangaCompleted,
      completedTotal,
      uniqueHistoryWorks: uniqueWorks.size,
      totalHistoryCount: historyList.length,
      animeWorkTotal: uniqueAnimeWorks.size,
      mangaWorkTotal: uniqueMangaWorks.size,
      animeShare,
      mangaShare,
      hasDna: totalDna > 0,
    }
  }, [favorites, historyList])

  // ── 本周追更雷达（交叉匹配在追动画与本季时刻表）──
  const todayDow = new Date().getDay() || 7
  const radarItems = useMemo(() => {
    const watchingCatalogIds = new Set(
      favorites
        .filter(f => f.type === 'catalog' && f.status !== 'COMPLETED' && f.catalogItemId)
        .map(f => f.catalogItemId as string),
    )
    const matched = calendar.filter(c => c.catalogItemId && watchingCatalogIds.has(c.catalogItemId))
    return matched.sort((a, b) => {
      const da = a.airWeekday ?? 99
      const db = b.airWeekday ?? 99
      const offA = da === 99 ? 99 : (da - todayDow + 7) % 7
      const offB = db === 99 ? 99 : (db - todayDow + 7) % 7
      if (offA !== offB) return offA - offB
      return (a.airTime ?? '99:99').localeCompare(b.airTime ?? '99:99')
    })
  }, [calendar, favorites, todayDow])
  const todayRadarCount = radarItems.filter(r => r.airWeekday === todayDow).length

  // ── 筛选结果 ──
  const needle = filterQuery.trim().toLowerCase()

  const shownFavs = useMemo(() => {
    const list = favorites.filter(f => {
      if (kind === 'anime' && !isAnimeFavorite(f)) return false
      if (kind === 'manga' && isAnimeFavorite(f)) return false
      const st = f.status ?? 'WATCHING'
      if (tab === 'completed') {
        if (st !== 'COMPLETED') return false
      } else if (favStatusFilter !== 'all' && st !== favStatusFilter) {
        return false
      }
      if (needle && !f.title.toLowerCase().includes(needle)) return false
      return true
    })
    if (favSort === 'title') {
      return [...list].sort((a, b) => a.title.localeCompare(b.title, 'zh-Hans-CN'))
    }
    return list
  }, [favorites, kind, tab, favStatusFilter, needle, favSort])

  const shownContinue = useMemo(() => {
    return continueList.filter(a => {
      if (kind === 'anime' && !isAnimeActivity(a)) return false
      if (kind === 'manga' && !isMangaActivity(a)) return false
      if (needle && !a.title.toLowerCase().includes(needle)) return false
      return true
    })
  }, [continueList, kind, needle])

  const shownHistory = useMemo(() => {
    return historyList.filter(a => {
      if (kind === 'anime' && !isAnimeActivity(a)) return false
      if (kind === 'manga' && !isMangaActivity(a)) return false
      if (needle && !a.title.toLowerCase().includes(needle)) return false
      return true
    })
  }, [historyList, kind, needle])

  const groupedHistory = useMemo(() => {
    const groups: Record<'今天' | '昨天' | '更早', Activity[]> = { 今天: [], 昨天: [], 更早: [] }
    for (const a of shownHistory) {
      groups[historyGroupLabel(a.updatedAt)].push(a)
    }
    return (['今天', '昨天', '更早'] as const)
      .map(label => ({ label, items: groups[label] }))
      .filter(g => g.items.length > 0)
  }, [shownHistory])

  const showCatchUp = kind !== 'manga' && catchUp.length > 0 && !needle
  const avatarChar = (me?.login || 'U').slice(0, 1).toUpperCase()
  const joinDate = fmtDate(me?.createdAt)

  const recentQuick = continueList.slice(0, 3)

  return (
    <div className="space-page-root">
      {/* 层 1：氛围顶图 */}
      <div className="space-banner" aria-hidden="true">
        <div className="space-banner-glow" />
      </div>

      <main className="space-shell">
        {/* 叠层身份卡 */}
        <section className="space-profile-card">
          <div className="space-id-left">
            <div className="space-avatar-ring">
              <span className="space-avatar">{avatarChar}</span>
            </div>
            <div className="space-id-meta">
              <div className="space-name-row">
                <h1>{me?.login ?? '个人空间'}</h1>
                <span className={'space-role-badge' + (me?.role === 'ADMIN' ? ' is-admin' : '')}>
                  {me?.role === 'ADMIN' ? '站点管理员' : '成员'}
                </span>
                {joinDate && (
                  <span className="space-join-date tabular-nums">{joinDate} 加入 MangaShelf</span>
                )}
              </div>
              <p className="space-bio">
                正在追 <strong>{stats.animeWatching}</strong> 部动画与 <strong>{stats.mangaWatching}</strong> 部漫画
                {stats.completedTotal > 0 ? `，累计已看完 ${stats.completedTotal} 部作品` : ''}
                {catchUp.length > 0 ? ` · ${catchUp.length} 部动画有新集更新` : '。'}
              </p>
              <div className="space-id-pills">
                <span className="space-chip is-cyan">
                  <FilmIcon size={13} />动画在追 {stats.animeWatching}
                </span>
                <span className="space-chip is-rose">
                  <BookOpenIcon size={13} />漫画在追 {stats.mangaWatching}
                </span>
                {catchUp.length > 0 && (
                  <span className="space-chip is-alert">
                    <BoltIcon size={13} />+{catchUp.length} 新集提醒
                  </span>
                )}
              </div>
            </div>
          </div>

          <div className="space-id-actions">
            <Link to="/schedule" className="space-act-btn">
              <CalendarIcon size={15} />追番时刻表
            </Link>
            <a href="/api/v1/calendar/ics?onlyMine=true" className="space-act-btn is-tint">
              <BoltIcon size={15} />订阅追番日历
            </a>
            <Link to="/uploads" className="space-act-btn">
              <UploadIcon size={15} />我的素材
            </Link>
            <Link to="/settings" className="space-act-btn">
              <SettingsIcon size={15} />账号设置
            </Link>
          </div>
        </section>

        {error && <p className="error" style={{ marginTop: 14 }}>{error}</p>}

        {/* 层 2：4 格双栖 KPI 指标条 */}
        <section className="space-metrics" aria-label="追更与阅读统计">
          <button
            type="button"
            className="space-kpi-card"
            onClick={() => { setFavStatusFilter('WATCHING'); setTab('favorites', 'anime') }}
          >
            <span className="space-kpi-ico is-cyan"><FilmIcon size={18} /></span>
            <div className="space-kpi-body">
              <span className="space-kpi-label">追番中</span>
              <strong className="space-kpi-val is-cyan tabular-nums">{loading ? '—' : stats.animeWatching}</strong>
              <span className="space-kpi-sub">
                {catchUp.length > 0 ? `含 ${catchUp.length} 部新集待看` : '连载与在看动画'}
              </span>
            </div>
          </button>

          <button
            type="button"
            className="space-kpi-card"
            onClick={() => { setFavStatusFilter('WATCHING'); setTab('favorites', 'manga') }}
          >
            <span className="space-kpi-ico is-rose"><BookOpenIcon size={18} /></span>
            <div className="space-kpi-body">
              <span className="space-kpi-label">追漫中</span>
              <strong className="space-kpi-val is-rose tabular-nums">{loading ? '—' : stats.mangaWatching}</strong>
              <span className="space-kpi-sub">站内与跨源漫画书架</span>
            </div>
          </button>

          <button
            type="button"
            className="space-kpi-card"
            onClick={() => setTab('completed', 'all')}
          >
            <span className="space-kpi-ico is-pub"><CheckCircleIcon size={18} /></span>
            <div className="space-kpi-body">
              <span className="space-kpi-label">已看完</span>
              <strong className="space-kpi-val tabular-nums">{loading ? '—' : stats.completedTotal}</strong>
              <span className="space-kpi-sub">
                动画 {stats.animeCompleted} · 漫画 {stats.mangaCompleted}
              </span>
            </div>
          </button>

          <button
            type="button"
            className="space-kpi-card"
            onClick={() => setTab('history', 'all')}
          >
            <span className="space-kpi-ico is-amber"><ClockIcon size={18} /></span>
            <div className="space-kpi-body">
              <span className="space-kpi-label">观看与阅读足迹</span>
              <strong className="space-kpi-val tabular-nums">{loading ? '—' : stats.uniqueHistoryWorks}</strong>
              <span className="space-kpi-sub">累计 {stats.totalHistoryCount} 条进度记录</span>
            </div>
          </button>
        </section>

        {/* 层 3：左主工作台 + 右侧追更雷达 & 偏好 DNA */}
        <div className="space-content-grid">
          <div className="space-main">
            {/* 主 Tab + 动漫画介质切换 + 网格/列表视图 */}
            <div className="space-tabbar">
              <div className="space-tabs" role="tablist" aria-label="个人空间视图">
                <button
                  type="button"
                  role="tab"
                  aria-selected={tab === 'continue'}
                  className={'space-tab' + (tab === 'continue' ? ' active' : '')}
                  onClick={() => setTab('continue')}
                >
                  继续追更
                  <span className="space-tab-num tabular-nums">{continueList.length}</span>
                  {catchUp.length > 0 && <span className="space-tab-dot" aria-label="有新集" />}
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={tab === 'favorites'}
                  className={'space-tab' + (tab === 'favorites' ? ' active' : '')}
                  onClick={() => setTab('favorites')}
                >
                  我的书架
                  <span className="space-tab-num tabular-nums">{favorites.length}</span>
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={tab === 'completed'}
                  className={'space-tab' + (tab === 'completed' ? ' active' : '')}
                  onClick={() => setTab('completed')}
                >
                  已看完
                  <span className="space-tab-num tabular-nums">{stats.completedTotal}</span>
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={tab === 'history'}
                  className={'space-tab' + (tab === 'history' ? ' active' : '')}
                  onClick={() => setTab('history')}
                >
                  足迹历史
                  <span className="space-tab-num tabular-nums">{historyList.length}</span>
                </button>
              </div>

              <div className="space-tabbar-right">
                <div className="space-kind-seg" role="group" aria-label="内容类型筛选">
                  <button
                    type="button"
                    className={'space-kind-btn' + (kind === 'all' ? ' active' : '')}
                    onClick={() => setKind('all')}
                  >
                    全部
                  </button>
                  <button
                    type="button"
                    className={'space-kind-btn is-anime' + (kind === 'anime' ? ' active' : '')}
                    onClick={() => setKind('anime')}
                  >
                    动画
                  </button>
                  <button
                    type="button"
                    className={'space-kind-btn is-manga' + (kind === 'manga' ? ' active' : '')}
                    onClick={() => setKind('manga')}
                  >
                    漫画
                  </button>
                </div>

                {tab !== 'history' && (
                  <div className="space-view-seg" role="group" aria-label="视图模式">
                    <button
                      type="button"
                      className={'space-view-btn' + (viewMode === 'grid' ? ' active' : '')}
                      title="网格视图"
                      aria-label="网格视图"
                      onClick={() => setViewMode('grid')}
                    >
                      <GridIcon size={15} />
                    </button>
                    <button
                      type="button"
                      className={'space-view-btn' + (viewMode === 'list' ? ' active' : '')}
                      title="列表视图"
                      aria-label="列表视图"
                      onClick={() => setViewMode('list')}
                    >
                      <ListIcon size={15} />
                    </button>
                  </div>
                )}
              </div>
            </div>

            {/* 轻量筛选条 */}
            <div className="space-filter-bar">
              <div className="space-search-box">
                <SearchIcon size={14} className="space-search-ico" />
                <input
                  value={filterQuery}
                  onChange={e => setFilterQuery(e.target.value)}
                  placeholder={`在${kind === 'anime' ? '动画' : kind === 'manga' ? '漫画' : '动画与漫画'}中快速筛选标题…`}
                  aria-label="在个人空间中筛选"
                />
                {filterQuery && (
                  <button
                    type="button"
                    className="space-search-clear"
                    aria-label="清空筛选"
                    onClick={() => setFilterQuery('')}
                  >
                    <CloseIcon size={13} />
                  </button>
                )}
              </div>

              {tab === 'favorites' && (
                <div className="space-filter-controls">
                  <div className="space-status-pills">
                    {(['all', 'WATCHING', 'COMPLETED'] as const).map(st => (
                      <button
                        key={st}
                        type="button"
                        className={'space-pill-sm' + (favStatusFilter === st ? ' active' : '')}
                        onClick={() => setFavStatusFilter(st)}
                      >
                        {st === 'all' ? '全部状态' : st === 'WATCHING' ? '在追' : '已看完'}
                      </button>
                    ))}
                  </div>
                  <button
                    type="button"
                    className="space-sort-btn"
                    onClick={() => setFavSort(s => (s === 'recent' ? 'title' : 'recent'))}
                  >
                    排序：{favSort === 'recent' ? '最近收藏' : '按名称'}
                  </button>
                </div>
              )}
            </div>

            {/* 主内容面板 */}
            {loading ? (
              <div className="space-card-grid">
                {Array.from({ length: 8 }).map((_, i) => (
                  <div key={i}>
                    <div className="skeleton-box card-skeleton-cover" />
                    <div className="skeleton-box card-skeleton-line" />
                    <div className="skeleton-box card-skeleton-line short" />
                  </div>
                ))}
              </div>
            ) : tab === 'continue' ? (
              <div className="space-panel-stack">
                {showCatchUp && (
                  <section className="space-section-card is-catchup">
                    <div className="space-sec-head">
                      <h2>
                        <span className="space-accent-bar is-rose" />
                        新集更新提醒
                        <span className="space-sec-count tabular-nums">{catchUp.length} 部</span>
                      </h2>
                      <span className="muted" style={{ fontSize: 12.5 }}>已追连载番更新了新集</span>
                    </div>
                    <div className="space-card-grid">
                      {catchUp.map(item => (
                        <Link
                          key={item.catalogItemId}
                          to={item.workId ? `/works/${item.workId}` : `/browse/${item.catalogItemId}`}
                          className="work-card"
                        >
                          <div className="cover">
                            <CoverImage src={item.coverUrl} alt={item.title} />
                            <span className="space-media-badge is-anime">动画</span>
                            <span className="space-progress-badge is-new">+{item.newEpisodes} 新集</span>
                          </div>
                          <div className="work-card-body">
                            <div className="work-card-title clamp">{item.title}</div>
                            <div className="work-card-meta">
                              <span>看到第 {item.watchedOrdinal} 集 · 更至 {item.latestOrdinal} 集</span>
                            </div>
                          </div>
                        </Link>
                      ))}
                    </div>
                  </section>
                )}

                <section className="space-section-card">
                  <div className="space-sec-head">
                    <h2>
                      <span className="space-accent-bar is-cyan" />
                      继续观看 / 阅读
                      <span className="space-sec-count tabular-nums">{shownContinue.length}</span>
                    </h2>
                  </div>

                  {shownContinue.length === 0 ? (
                    <div className="space-empty">
                      <span className="space-empty-ico"><PlayIcon size={22} /></span>
                      <h3>暂无正在追的{kind === 'anime' ? '动画' : kind === 'manga' ? '漫画' : '作品'}</h3>
                      <p>观看任意番剧或翻阅漫画后，这里会自动记录精确进度，随时一键续播续读。</p>
                      <div className="row-inline" style={{ justifyContent: 'center' }}>
                        <Link to="/" className="btn pill">逛逛番剧</Link>
                        <Link to="/manga" className="btn ghost pill">逛逛漫画</Link>
                      </div>
                    </div>
                  ) : viewMode === 'grid' ? (
                    <div className="space-card-grid">
                      {shownContinue.map(a => {
                        const pct = a.durationMs && a.positionMs
                          ? Math.min(99, Math.max(2, Math.round(a.positionMs / a.durationMs * 100)))
                          : null
                        const isAnime = isAnimeActivity(a)
                        return (
                          <Link
                            key={`${a.type}-${a.workId ?? a.catalogItemId ?? a.mangaId ?? a.animationId}`}
                            to={activityLink(a)}
                            className="work-card"
                          >
                            <div className="cover">
                              <CoverImage src={a.coverUrl} alt={a.title} />
                              <span className={'space-media-badge ' + (isAnime ? 'is-anime' : 'is-manga')}>
                                {isAnime ? '动画' : '漫画'}
                              </span>
                              <span className="space-progress-badge">
                                {isAnime ? (pct !== null ? `${pct}%` : `第 ${a.ordinal ?? 1} 集`) : `第 ${a.pageOrdinal ?? 1} 页`}
                              </span>
                              {pct !== null && (
                                <div className="card-progress" aria-hidden="true">
                                  <div className="card-progress-fill" style={{ width: `${pct}%` }} />
                                </div>
                              )}
                            </div>
                            <div className="work-card-body">
                              <div className="work-card-title clamp">{a.title}</div>
                              <div className="work-card-meta">
                                <span className="clamp">{activitySummary(a)}</span>
                              </div>
                            </div>
                          </Link>
                        )
                      })}
                    </div>
                  ) : (
                    <div className="space-list-rows">
                      {shownContinue.map(a => {
                        const pct = a.durationMs && a.positionMs
                          ? Math.min(99, Math.max(2, Math.round(a.positionMs / a.durationMs * 100)))
                          : null
                        const isAnime = isAnimeActivity(a)
                        return (
                          <div
                            key={`${a.type}-${a.workId ?? a.catalogItemId ?? a.mangaId ?? a.animationId}`}
                            className="space-list-row"
                          >
                            <Link to={activityLink(a)} className="space-row-thumb">
                              <CoverImage src={a.coverUrl} alt={a.title} />
                            </Link>
                            <div className="space-row-body">
                              <div className="space-row-top">
                                <Link to={activityLink(a)} className="space-row-title">{a.title}</Link>
                                <span className={'space-tag ' + (isAnime ? 'is-anime' : 'is-manga')}>
                                  {isAnime ? '动画' : '漫画'}
                                </span>
                              </div>
                              <div className="space-row-sub">
                                <span>{activitySummary(a)}</span>
                                <span className="tabular-nums">{fmtRelativeTime(a.updatedAt)}</span>
                              </div>
                              {pct !== null && (
                                <div className="space-row-progress">
                                  <div className="space-row-progress-fill" style={{ width: `${pct}%` }} />
                                </div>
                              )}
                            </div>
                            <Link to={activityLink(a)} className={'btn mini ' + (isAnime ? '' : 'space-btn-rose')}>
                              {isAnime ? '继续观看' : '继续阅读'}
                            </Link>
                          </div>
                        )
                      })}
                    </div>
                  )}
                </section>
              </div>
            ) : tab === 'favorites' || tab === 'completed' ? (
              <section className="space-section-card">
                <div className="space-sec-head">
                  <h2>
                    <span className={'space-accent-bar ' + (tab === 'completed' ? 'is-pub' : 'is-cyan')} />
                    {tab === 'completed' ? '已看完归档' : '我的动漫画书架'}
                    <span className="space-sec-count tabular-nums">{shownFavs.length}</span>
                  </h2>
                </div>

                {shownFavs.length === 0 ? (
                  <div className="space-empty">
                    <span className="space-empty-ico"><BookmarkIcon size={22} /></span>
                    <h3>当前筛选下暂无收藏</h3>
                    <p>在动画详情页点「追番」、或在漫画详情页点「加入书架」，就能在这里统一管理。</p>
                    <div className="row-inline" style={{ justifyContent: 'center' }}>
                      <Link to="/index" className="btn pill">发现番剧 →</Link>
                      <Link to="/manga" className="btn ghost pill">发现漫画 →</Link>
                    </div>
                  </div>
                ) : viewMode === 'grid' ? (
                  <div className="space-card-grid">
                    {shownFavs.map(f => {
                      const done = f.status === 'COMPLETED'
                      const isAnime = isAnimeFavorite(f)
                      return (
                        <div key={favKey(f)} className="shelf-card-wrap">
                          <Link to={favLink(f)} className="work-card">
                            <div className="cover">
                              <CoverImage src={f.coverUrl} alt={f.title} />
                              <span className={'space-media-badge ' + (isAnime ? 'is-anime' : 'is-manga')}>
                                {isAnime ? '动画' : '漫画'}
                              </span>
                              {done && <span className="badge-br is-pub" style={{ background: 'var(--pub)' }}>✓ 已看完</span>}
                            </div>
                            <div className="work-card-body">
                              <div className="work-card-title">{f.title}</div>
                              <div className="row-between" style={{ marginTop: 6 }}>
                                <span className="muted tabular-nums" style={{ fontSize: 11.5 }}>
                                  {fmtDate(f.createdAt) || (isAnime ? '番剧' : '漫画')}
                                </span>
                                <button
                                  type="button"
                                  className="fav-status-btn"
                                  onClick={e => { e.preventDefault(); void toggleFavoriteStatus(f) }}
                                >
                                  {done ? '改回在追' : '标已看完'}
                                </button>
                              </div>
                            </div>
                          </Link>
                          <button
                            type="button"
                            className="shelf-unfav"
                            aria-label={`取消收藏 ${f.title}`}
                            title="取消收藏"
                            onClick={() => void removeFavorite(f)}
                          >
                            <CloseIcon size={14} />
                          </button>
                        </div>
                      )
                    })}
                  </div>
                ) : (
                  <div className="space-list-rows">
                    {shownFavs.map(f => {
                      const done = f.status === 'COMPLETED'
                      const isAnime = isAnimeFavorite(f)
                      return (
                        <div key={favKey(f)} className="space-list-row">
                          <Link to={favLink(f)} className="space-row-thumb">
                            <CoverImage src={f.coverUrl} alt={f.title} />
                          </Link>
                          <div className="space-row-body">
                            <div className="space-row-top">
                              <Link to={favLink(f)} className="space-row-title">{f.title}</Link>
                              <span className={'space-tag ' + (isAnime ? 'is-anime' : 'is-manga')}>
                                {isAnime ? '动画' : '漫画'}
                              </span>
                              <span className={'space-tag ' + (done ? 'is-done' : 'is-watching')}>
                                {done ? '已看完' : '在追'}
                              </span>
                            </div>
                            <div className="space-row-sub tabular-nums">
                              <span>加入书架于 {fmtDate(f.createdAt) || '近期'}</span>
                            </div>
                          </div>
                          <div className="space-row-actions">
                            <button
                              type="button"
                              className="btn ghost mini"
                              onClick={() => void toggleFavoriteStatus(f)}
                            >
                              {done ? '改回在追' : '标已看完'}
                            </button>
                            <button
                              type="button"
                              className="space-row-del"
                              aria-label={`取消收藏 ${f.title}`}
                              title="取消收藏"
                              onClick={() => void removeFavorite(f)}
                            >
                              <CloseIcon size={14} />
                            </button>
                          </div>
                        </div>
                      )
                    })}
                  </div>
                )}
              </section>
            ) : (
              <section className="space-section-card">
                <div className="space-sec-head">
                  <h2>
                    <span className="space-accent-bar is-amber" />
                    观看与阅读足迹
                    <span className="space-sec-count tabular-nums">{shownHistory.length}</span>
                  </h2>
                </div>

                {groupedHistory.length === 0 ? (
                  <div className="space-empty">
                    <span className="space-empty-ico"><ClockIcon size={22} /></span>
                    <h3>暂无{kind === 'anime' ? '动画观看' : kind === 'manga' ? '漫画阅读' : '观看或阅读'}记录</h3>
                    <p>播放番剧或阅读漫画章节后，会按时间线自动归档在这里。</p>
                  </div>
                ) : (
                  <div className="space-history-groups">
                    {groupedHistory.map(group => (
                      <div key={group.label} className="space-history-group">
                        <div className="space-history-day">
                          <span>{group.label}</span>
                          <em className="tabular-nums">{group.items.length} 条</em>
                        </div>
                        <div className="space-list-rows">
                          {group.items.map(a => {
                            const isAnime = isAnimeActivity(a)
                            return (
                              <div
                                key={`${a.type}-${a.episodeId ?? a.chapterId ?? a.animationId}-${a.updatedAt}`}
                                className="space-list-row"
                              >
                                <Link to={activityLink(a)} className="space-row-thumb">
                                  <CoverImage src={a.coverUrl} alt={a.title} />
                                </Link>
                                <div className="space-row-body">
                                  <div className="space-row-top">
                                    <Link to={activityLink(a)} className="space-row-title">{a.title}</Link>
                                    <span className={'space-tag ' + (isAnime ? 'is-anime' : 'is-manga')}>
                                      {isAnime ? '动画' : '漫画'}
                                    </span>
                                  </div>
                                  <div className="space-row-sub">
                                    <span>{activitySummary(a)}</span>
                                    <span className="tabular-nums">{fmtRelativeTime(a.updatedAt)}</span>
                                  </div>
                                </div>
                                <Link className="btn ghost mini" to={activityLink(a)}>
                                  {isAnime ? '继续观看' : '继续阅读'}
                                </Link>
                              </div>
                            )
                          })}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </section>
            )}
          </div>

          {/* 右侧边栏（324px，3 张 100% 真实数据卡片） */}
          <aside className="space-sidebar" aria-label="追更雷达与偏好概览">
            {/* 卡 1：本周追更雷达 */}
            <section className="space-side-card">
              <div className="space-side-head">
                <h3>本周追更雷达</h3>
                <span className={'space-side-pill' + (todayRadarCount > 0 ? ' is-live' : '')}>
                  {todayRadarCount > 0 ? `今日更新 ${todayRadarCount}` : '本季排期'}
                </span>
              </div>

              {radarItems.length === 0 ? (
                <div className="space-side-empty">
                  <CalendarIcon size={22} />
                  <p>暂无已追的本季连载番剧</p>
                  <span>在新番时刻表收藏本季动画，这里会自动提醒放送日与时间。</span>
                </div>
              ) : (
                <ul className="space-radar-list">
                  {radarItems.slice(0, 6).map(r => {
                    const isToday = r.airWeekday === todayDow
                    const title = r.nameCn || r.name
                    const href = r.workId ? `/works/${r.workId}` : (r.catalogItemId ? `/catalog/${r.catalogItemId}` : '/schedule')
                    return (
                      <li key={r.subjectId} className="space-radar-item">
                        <span className={'space-radar-day' + (isToday ? ' is-today' : '')}>
                          {isToday ? '今天' : (r.airWeekday ? WEEKDAYS[r.airWeekday] : '待定')}
                        </span>
                        <div className="space-radar-info">
                          <Link to={href} className="space-radar-title">{title}</Link>
                          <span className="space-radar-sub tabular-nums">
                            {r.airTime ? `${r.airTime} 更新` : '本季放送'}
                            {r.remarks ? ` · ${r.remarks}` : ''}
                          </span>
                        </div>
                      </li>
                    )
                  })}
                </ul>
              )}

              <Link to="/schedule" className="space-side-link">
                查看完整「七日播出时刻表」 →
              </Link>
            </section>

            {/* 卡 2：动漫画偏好 DNA */}
            <section className="space-side-card">
              <div className="space-side-head">
                <h3>动漫画偏好 DNA</h3>
                <SparklesIcon size={15} className="muted" />
              </div>

              <div className="space-dna-tiles tabular-nums">
                <div className="space-dna-tile">
                  <span className="space-dna-k">动画涉猎</span>
                  <strong className="is-cyan">{stats.animeWorkTotal} 部</strong>
                </div>
                <div className="space-dna-tile">
                  <span className="space-dna-k">漫画涉猎</span>
                  <strong className="is-rose">{stats.mangaWorkTotal} 部</strong>
                </div>
              </div>

              {stats.hasDna && (
                <div className="space-dna-ratio">
                  <div className="space-dna-ratio-labels tabular-nums">
                    <span className="is-cyan">动画 {stats.animeShare}%</span>
                    <span className="is-rose">漫画 {stats.mangaShare}%</span>
                  </div>
                  <div className="space-dna-bar" aria-hidden="true">
                    <div className="space-dna-bar-anime" style={{ width: `${stats.animeShare}%` }} />
                    <div className="space-dna-bar-manga" style={{ width: `${stats.mangaShare}%` }} />
                  </div>
                </div>
              )}

              <div className="space-dna-tags-block">
                <span className="space-dna-subhead">常看题材分布 · 点击直达索引</span>
                {animePrefs.length === 0 && mangaPrefs.length === 0 ? (
                  <p className="space-side-hint">多收藏或观看/阅读几部作品后，会自动生成你的动漫画偏好标签。</p>
                ) : (
                  <div className="space-dna-pills">
                    {animePrefs.slice(0, 5).map(t => (
                      <Link key={`a-${t}`} to={`/index?genre=${encodeURIComponent(t)}`} className="space-dna-pill is-anime">
                        {t}
                      </Link>
                    ))}
                    {mangaPrefs.slice(0, 5).map(t => (
                      <Link key={`m-${t}`} to={`/manga?genre=${encodeURIComponent(t)}`} className="space-dna-pill is-manga">
                        {t}
                      </Link>
                    ))}
                  </div>
                )}
              </div>
            </section>

            {/* 卡 3：最近回到 */}
            <section className="space-side-card">
              <div className="space-side-head">
                <h3>最近回到</h3>
                <ClockIcon size={15} className="muted" />
              </div>

              {recentQuick.length === 0 ? (
                <p className="space-side-hint">开始观看或阅读后，这里会保留最近 3 部作品的一键续看入口。</p>
              ) : (
                <ul className="space-recent-list">
                  {recentQuick.map(a => {
                    const isAnime = isAnimeActivity(a)
                    return (
                      <li key={`rec-${a.type}-${a.episodeId ?? a.chapterId ?? a.workId ?? a.mangaId}`}>
                        <Link to={activityLink(a)} className="space-recent-item">
                          <div className="space-recent-thumb">
                            <CoverImage src={a.coverUrl} alt={a.title} />
                          </div>
                          <div className="space-recent-info">
                            <div className="space-recent-title">{a.title}</div>
                            <div className="space-recent-sub">
                              <span className={'space-mini-dot ' + (isAnime ? 'is-cyan' : 'is-rose')} />
                              <span>{activitySummary(a)}</span>
                            </div>
                          </div>
                        </Link>
                      </li>
                    )
                  })}
                </ul>
              )}
            </section>
          </aside>
        </div>
      </main>
    </div>
  )
}

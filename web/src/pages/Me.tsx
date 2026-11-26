import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import CoverImage from '../components/CoverImage'
import {
  BoltIcon, BookOpenIcon, BookmarkIcon, CalendarIcon, CheckCircleIcon,
  ClockIcon, CloseIcon, FilmIcon, GridIcon, LayersIcon, ListIcon, PlayIcon,
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

type SpaceChannel = 'anime' | 'manga'
type MainTab = 'continue' | 'favorites' | 'completed' | 'history'
type ViewMode = 'grid' | 'list'
type FavSort = 'recent' | 'title'

const WEEKDAYS = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']
const SOURCE_LABELS: Record<string, string> = {
  tencent: '腾讯动漫',
  copymanga: '拷贝漫画',
  bilibili: '哔哩漫画',
  baozimh: '包子漫画',
  hipmh: '嗨皮漫画',
  local: '站内上传',
}

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

function parseExplicitSpace(params: URLSearchParams): SpaceChannel | null {
  const s = params.get('space') || params.get('kind')
  if (s === 'anime' || s === 'manga') return s
  return null
}

/** 个人空间：动画空间与漫画空间完全分离展示，专属 KPI、Tab、主工作台与侧边栏各自独立。 */
export default function Me() {
  const { me } = useAuth()
  const [params, setParams] = useSearchParams()
  const explicitSpace = parseExplicitSpace(params)
  const [autoSpace, setAutoSpace] = useState<SpaceChannel>('anime')
  const userPickedSpaceRef = useRef(false)
  const space: SpaceChannel = explicitSpace ?? autoSpace
  const isManga = space === 'manga'

  const rawTab = params.get('tab')
  const tab: MainTab = rawTab === 'favorites' || rawTab === 'completed' || rawTab === 'history'
    ? rawTab : 'continue'

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

        // 未指定频道时，自动落点到最近活跃且有内容的频道，避免进空页
        if (!userPickedSpaceRef.current) {
          const latest = cont[0] ?? hist[0]
          if (latest) {
            setAutoSpace(isMangaActivity(latest) ? 'manga' : 'anime')
          } else {
            const animeFavs = favs.filter(isAnimeFavorite).length
            const mangaFavs = favs.length - animeFavs
            setAutoSpace(mangaFavs > animeFavs ? 'manga' : 'anime')
          }
        }
      } catch (e) {
        setError(e instanceof Error ? e.message : '加载失败')
      } finally {
        setLoading(false)
      }
    })()
  }, [])

  function switchSpace(nextSpace: SpaceChannel, nextTab?: MainTab) {
    userPickedSpaceRef.current = true
    setAutoSpace(nextSpace)
    setFilterQuery('')
    const next = new URLSearchParams(params)
    next.set('space', nextSpace)
    next.delete('kind')
    if (nextTab) next.set('tab', nextTab)
    setParams(next, { replace: true })
  }

  function setTab(nextTab: MainTab) {
    const next = new URLSearchParams(params)
    next.set('space', space)
    next.delete('kind')
    next.set('tab', nextTab)
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
      const doneWord = isAnimeFavorite(f) ? '已看完' : '已读完'
      showToast(nextStatus === 'COMPLETED' ? `已标记「${f.title}」为${doneWord}` : `已改回在追「${f.title}」`, 'success')
    } catch (e) {
      showToast(e instanceof Error ? e.message : '更新状态失败', 'error')
    }
  }

  // ── 彻底按动画 / 漫画拆分的独立数据桶 ──
  const split = useMemo(() => {
    const animeFavs = favorites.filter(isAnimeFavorite)
    const mangaFavs = favorites.filter(f => !isAnimeFavorite(f))
    const animeContinue = continueList.filter(isAnimeActivity)
    const mangaContinue = continueList.filter(isMangaActivity)
    const animeHistory = historyList.filter(isAnimeActivity)
    const mangaHistory = historyList.filter(isMangaActivity)

    const animeWatching = animeFavs.filter(f => f.status !== 'COMPLETED').length
    const mangaWatching = mangaFavs.filter(f => f.status !== 'COMPLETED').length
    const animeCompleted = animeFavs.filter(f => f.status === 'COMPLETED').length
    const mangaCompleted = mangaFavs.filter(f => f.status === 'COMPLETED').length

    const animeWorkSet = new Set<string>()
    animeFavs.forEach(f => animeWorkSet.add(favKey(f)))
    animeHistory.forEach(a => animeWorkSet.add(activityUniqueKey(a)))

    const mangaWorkSet = new Set<string>()
    mangaFavs.forEach(f => mangaWorkSet.add(favKey(f)))
    mangaHistory.forEach(a => mangaWorkSet.add(activityUniqueKey(a)))

    const animeHistUnique = new Set(animeHistory.map(activityUniqueKey)).size
    const mangaHistUnique = new Set(mangaHistory.map(activityUniqueKey)).size

    // 漫画书架 + 足迹来源统计（真实聚合）
    const sourceCounter = new Map<string, Set<string>>()
    for (const f of mangaFavs) {
      const code = f.type === 'work' ? 'local' : (f.source || 'other')
      if (!sourceCounter.has(code)) sourceCounter.set(code, new Set())
      sourceCounter.get(code)!.add(favKey(f))
    }
    for (const a of mangaHistory) {
      const code = a.type === 'work-read' ? 'local' : (a.source || 'other')
      if (!sourceCounter.has(code)) sourceCounter.set(code, new Set())
      sourceCounter.get(code)!.add(activityUniqueKey(a))
    }
    const mangaSources = Array.from(sourceCounter.entries())
      .map(([code, set]) => ({ code, label: SOURCE_LABELS[code] ?? code, count: set.size }))
      .sort((a, b) => b.count - a.count)

    return {
      animeFavs,
      mangaFavs,
      animeContinue,
      mangaContinue,
      animeHistory,
      mangaHistory,
      animeWatching,
      mangaWatching,
      animeCompleted,
      mangaCompleted,
      animeWorkTotal: animeWorkSet.size,
      mangaWorkTotal: mangaWorkSet.size,
      animeHistUnique,
      mangaHistUnique,
      mangaSources,
    }
  }, [favorites, continueList, historyList])

  // 当前频道的数据
  const curFavs = isManga ? split.mangaFavs : split.animeFavs
  const curContinue = isManga ? split.mangaContinue : split.animeContinue
  const curHistory = isManga ? split.mangaHistory : split.animeHistory
  const curCompletedCount = isManga ? split.mangaCompleted : split.animeCompleted

  // ── 动画侧栏：本周追更雷达 ──
  const todayDow = new Date().getDay() || 7
  const radarItems = useMemo(() => {
    const watchingCatalogIds = new Set(
      split.animeFavs
        .filter(f => f.status !== 'COMPLETED' && f.catalogItemId)
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
  }, [calendar, split.animeFavs, todayDow])
  const todayRadarCount = radarItems.filter(r => r.airWeekday === todayDow).length

  // ── 当前频道筛选结果 ──
  const needle = filterQuery.trim().toLowerCase()

  const shownFavs = useMemo(() => {
    const list = curFavs.filter(f => {
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
  }, [curFavs, tab, favStatusFilter, needle, favSort])

  const shownContinue = useMemo(() => {
    return curContinue.filter(a => !needle || a.title.toLowerCase().includes(needle))
  }, [curContinue, needle])

  const shownHistory = useMemo(() => {
    return curHistory.filter(a => !needle || a.title.toLowerCase().includes(needle))
  }, [curHistory, needle])

  const groupedHistory = useMemo(() => {
    const groups: Record<'今天' | '昨天' | '更早', Activity[]> = { 今天: [], 昨天: [], 更早: [] }
    for (const a of shownHistory) {
      groups[historyGroupLabel(a.updatedAt)].push(a)
    }
    return (['今天', '昨天', '更早'] as const)
      .map(label => ({ label, items: groups[label] }))
      .filter(g => g.items.length > 0)
  }, [shownHistory])

  const showCatchUp = !isManga && catchUp.length > 0 && !needle
  const avatarChar = (me?.login || 'U').slice(0, 1).toUpperCase()
  const joinDate = fmtDate(me?.createdAt)
  const recentQuick = curContinue.slice(0, 3)
  const maxSourceCount = split.mangaSources[0]?.count || 1

  return (
    <div className={'space-page-root ' + (isManga ? 'is-manga' : 'is-anime')}>
      {/* 层 1：无底线无缝渐晕氛围顶图 */}
      <div className="space-banner" aria-hidden="true">
        <div className="space-banner-glow" />
      </div>

      <main className="space-shell">
        {/* 叠层身份卡 + 底部一级双频道切换器 */}
        <section className="space-profile-card">
          <div className="space-profile-top">
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
                  {isManga ? (
                    <>
                      漫画空间 · 在追 <strong>{split.mangaWatching}</strong> 部，最近阅读 <strong>{split.mangaContinue.length}</strong> 部
                      {split.mangaCompleted > 0 ? `，已读完 ${split.mangaCompleted} 部` : ''}。
                    </>
                  ) : (
                    <>
                      动画空间 · 在追 <strong>{split.animeWatching}</strong> 部，最近观看 <strong>{split.animeContinue.length}</strong> 部
                      {split.animeCompleted > 0 ? `，已看完 ${split.animeCompleted} 部` : ''}
                      {catchUp.length > 0 ? ` · ${catchUp.length} 部有新集更新` : '。'}
                    </>
                  )}
                </p>
              </div>
            </div>

            <div className="space-id-actions">
              {isManga ? (
                <>
                  <Link to="/manga" className="space-act-btn is-tint">
                    <BookOpenIcon size={15} />漫画发现页
                  </Link>
                  <Link to="/uploads" className="space-act-btn">
                    <UploadIcon size={15} />我的素材
                  </Link>
                  <Link to="/settings" className="space-act-btn">
                    <SettingsIcon size={15} />账号设置
                  </Link>
                </>
              ) : (
                <>
                  <Link to="/schedule" className="space-act-btn">
                    <CalendarIcon size={15} />追番时刻表
                  </Link>
                  <a href="/api/v1/calendar/ics?onlyMine=true" className="space-act-btn is-cyan-tint">
                    <BoltIcon size={15} />订阅追番日历
                  </a>
                  <Link to="/settings" className="space-act-btn">
                    <SettingsIcon size={15} />账号设置
                  </Link>
                </>
              )}
            </div>
          </div>

          {/* 一级双频道切换器：动画空间 ⇄ 漫画空间 */}
          <div className="space-channel-bar" role="tablist" aria-label="个人空间频道切换">
            <div className="space-channel-switch">
              <button
                type="button"
                role="tab"
                aria-selected={!isManga}
                className={'space-channel-btn is-anime' + (!isManga ? ' active' : '')}
                onClick={() => switchSpace('anime')}
              >
                <FilmIcon size={16} />
                <span>动画空间</span>
                <em className="space-channel-count tabular-nums">
                  {split.animeWatching > 0 ? `在追 ${split.animeWatching}` : `${split.animeWorkTotal} 部`}
                </em>
                {catchUp.length > 0 && <span className="space-tab-dot" aria-label="有新集更新" />}
              </button>

              <button
                type="button"
                role="tab"
                aria-selected={isManga}
                className={'space-channel-btn is-manga' + (isManga ? ' active' : '')}
                onClick={() => switchSpace('manga')}
              >
                <BookOpenIcon size={16} />
                <span>漫画空间</span>
                <em className="space-channel-count tabular-nums">
                  {split.mangaWatching > 0 ? `在追 ${split.mangaWatching}` : `${split.mangaWorkTotal} 部`}
                </em>
              </button>
            </div>

            <span className="space-channel-hint">
              {isManga ? '仅展示漫画书架、阅读进度与偏好' : '仅展示动画追番、观看进度与排期'}
            </span>
          </div>
        </section>

        {error && <p className="error" style={{ marginTop: 14 }}>{error}</p>}

        {/* 层 2：4 格当前频道专属 KPI 指标条 */}
        {isManga ? (
          <section className="space-metrics" aria-label="漫画空间统计">
            <button
              type="button"
              className="space-kpi-card"
              onClick={() => { setFavStatusFilter('WATCHING'); setTab('favorites') }}
            >
              <span className="space-kpi-ico is-rose"><BookOpenIcon size={18} /></span>
              <div className="space-kpi-body">
                <span className="space-kpi-label">在追漫画</span>
                <strong className="space-kpi-val is-rose tabular-nums">{loading ? '—' : split.mangaWatching}</strong>
                <span className="space-kpi-sub">跨源与站内书架</span>
              </div>
            </button>

            <button
              type="button"
              className="space-kpi-card"
              onClick={() => setTab('continue')}
            >
              <span className="space-kpi-ico is-cyan"><BookmarkIcon size={18} /></span>
              <div className="space-kpi-body">
                <span className="space-kpi-label">最近在读</span>
                <strong className="space-kpi-val is-cyan tabular-nums">{loading ? '—' : split.mangaContinue.length}</strong>
                <span className="space-kpi-sub">可一键续读进度</span>
              </div>
            </button>

            <button
              type="button"
              className="space-kpi-card"
              onClick={() => setTab('completed')}
            >
              <span className="space-kpi-ico is-pub"><CheckCircleIcon size={18} /></span>
              <div className="space-kpi-body">
                <span className="space-kpi-label">已读完漫画</span>
                <strong className="space-kpi-val tabular-nums">{loading ? '—' : split.mangaCompleted}</strong>
                <span className="space-kpi-sub">已归档完结作品</span>
              </div>
            </button>

            <button
              type="button"
              className="space-kpi-card"
              onClick={() => setTab('history')}
            >
              <span className="space-kpi-ico is-amber"><ClockIcon size={18} /></span>
              <div className="space-kpi-body">
                <span className="space-kpi-label">阅读足迹</span>
                <strong className="space-kpi-val tabular-nums">{loading ? '—' : split.mangaHistUnique}</strong>
                <span className="space-kpi-sub">累计 {split.mangaHistory.length} 条阅读记录</span>
              </div>
            </button>
          </section>
        ) : (
          <section className="space-metrics" aria-label="动画空间统计">
            <button
              type="button"
              className="space-kpi-card"
              onClick={() => { setFavStatusFilter('WATCHING'); setTab('favorites') }}
            >
              <span className="space-kpi-ico is-cyan"><FilmIcon size={18} /></span>
              <div className="space-kpi-body">
                <span className="space-kpi-label">在追番剧</span>
                <strong className="space-kpi-val is-cyan tabular-nums">{loading ? '—' : split.animeWatching}</strong>
                <span className="space-kpi-sub">连载与在看动画</span>
              </div>
            </button>

            <button
              type="button"
              className="space-kpi-card"
              onClick={() => setTab('continue')}
            >
              <span className="space-kpi-ico is-rose"><BoltIcon size={18} /></span>
              <div className="space-kpi-body">
                <span className="space-kpi-label">新集更新</span>
                <strong className="space-kpi-val is-rose tabular-nums">{loading ? '—' : catchUp.length}</strong>
                <span className="space-kpi-sub">{catchUp.length > 0 ? '已追番剧有新集' : '当前暂无待补新集'}</span>
              </div>
            </button>

            <button
              type="button"
              className="space-kpi-card"
              onClick={() => setTab('completed')}
            >
              <span className="space-kpi-ico is-pub"><CheckCircleIcon size={18} /></span>
              <div className="space-kpi-body">
                <span className="space-kpi-label">已看完番剧</span>
                <strong className="space-kpi-val tabular-nums">{loading ? '—' : split.animeCompleted}</strong>
                <span className="space-kpi-sub">已归档补完番剧</span>
              </div>
            </button>

            <button
              type="button"
              className="space-kpi-card"
              onClick={() => setTab('history')}
            >
              <span className="space-kpi-ico is-amber"><ClockIcon size={18} /></span>
              <div className="space-kpi-body">
                <span className="space-kpi-label">观看足迹</span>
                <strong className="space-kpi-val tabular-nums">{loading ? '—' : split.animeHistUnique}</strong>
                <span className="space-kpi-sub">累计 {split.animeHistory.length} 条播放记录</span>
              </div>
            </button>
          </section>
        )}

        {/* 层 3：左主工作台 + 右专属侧边栏 */}
        <div className="space-content-grid">
          <div className="space-main">
            {/* 纯净介质 Tab 栏 + 网格/列表切换 */}
            <div className="space-tabbar">
              <div className="space-tabs" role="tablist" aria-label={isManga ? '漫画空间视图' : '动画空间视图'}>
                <button
                  type="button"
                  role="tab"
                  aria-selected={tab === 'continue'}
                  className={'space-tab' + (tab === 'continue' ? ' active' : '')}
                  onClick={() => setTab('continue')}
                >
                  {isManga ? '继续阅读' : '继续观看'}
                  <span className="space-tab-num tabular-nums">{curContinue.length}</span>
                  {!isManga && catchUp.length > 0 && <span className="space-tab-dot" aria-label="有新集" />}
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={tab === 'favorites'}
                  className={'space-tab' + (tab === 'favorites' ? ' active' : '')}
                  onClick={() => setTab('favorites')}
                >
                  {isManga ? '漫画书架' : '追番列表'}
                  <span className="space-tab-num tabular-nums">{curFavs.length}</span>
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={tab === 'completed'}
                  className={'space-tab' + (tab === 'completed' ? ' active' : '')}
                  onClick={() => setTab('completed')}
                >
                  {isManga ? '已读完' : '已看完'}
                  <span className="space-tab-num tabular-nums">{curCompletedCount}</span>
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={tab === 'history'}
                  className={'space-tab' + (tab === 'history' ? ' active' : '')}
                  onClick={() => setTab('history')}
                >
                  {isManga ? '阅读历史' : '观看历史'}
                  <span className="space-tab-num tabular-nums">{curHistory.length}</span>
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

            {/* 轻量筛选条 */}
            <div className="space-filter-bar">
              <div className="space-search-box">
                <SearchIcon size={14} className="space-search-ico" />
                <input
                  value={filterQuery}
                  onChange={e => setFilterQuery(e.target.value)}
                  placeholder={isManga ? '在我的漫画中快速筛选标题…' : '在我的动画中快速筛选标题…'}
                  aria-label={isManga ? '筛选漫画' : '筛选动画'}
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
                        {st === 'all' ? '全部状态' : st === 'WATCHING' ? '在追' : (isManga ? '已读完' : '已看完')}
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
                      <span className={'space-accent-bar ' + (isManga ? 'is-rose' : 'is-cyan')} />
                      {isManga ? '继续阅读' : '继续观看'}
                      <span className="space-sec-count tabular-nums">{shownContinue.length}</span>
                    </h2>
                  </div>

                  {shownContinue.length === 0 ? (
                    <div className="space-empty">
                      <span className="space-empty-ico">
                        {isManga ? <BookOpenIcon size={22} /> : <PlayIcon size={22} />}
                      </span>
                      <h3>{isManga ? '还没有正在读的漫画' : '还没有正在追的动画'}</h3>
                      <p>
                        {isManga
                          ? '打开任意漫画章节翻阅后，这里会自动同步章节与页码，随时一键续读。'
                          : '播放任意番剧剧集后，这里会自动记录集数与精确秒数，随时一键续播。'}
                      </p>
                      <Link to={isManga ? '/manga' : '/'} className={'btn pill ' + (isManga ? 'space-btn-rose' : '')}>
                        {isManga ? '去漫画发现页 →' : '去番剧首页 →'}
                      </Link>
                    </div>
                  ) : viewMode === 'grid' ? (
                    <div className="space-card-grid">
                      {shownContinue.map(a => {
                        const pct = a.durationMs && a.positionMs
                          ? Math.min(99, Math.max(2, Math.round(a.positionMs / a.durationMs * 100)))
                          : null
                        return (
                          <Link
                            key={`${a.type}-${a.workId ?? a.catalogItemId ?? a.mangaId ?? a.animationId}`}
                            to={activityLink(a)}
                            className="work-card"
                          >
                            <div className="cover">
                              <CoverImage src={a.coverUrl} alt={a.title} />
                              <span className="space-progress-badge">
                                {isManga ? `第 ${a.pageOrdinal ?? 1} 页` : (pct !== null ? `${pct}%` : `第 ${a.ordinal ?? 1} 集`)}
                              </span>
                              {!isManga && pct !== null && (
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
                              </div>
                              <div className="space-row-sub">
                                <span>{activitySummary(a)}</span>
                                <span className="tabular-nums">{fmtRelativeTime(a.updatedAt)}</span>
                              </div>
                              {!isManga && pct !== null && (
                                <div className="space-row-progress">
                                  <div className="space-row-progress-fill" style={{ width: `${pct}%` }} />
                                </div>
                              )}
                            </div>
                            <Link to={activityLink(a)} className={'btn mini ' + (isManga ? 'space-btn-rose' : '')}>
                              {isManga ? '继续阅读' : '继续观看'}
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
                    <span className={'space-accent-bar ' + (tab === 'completed' ? 'is-pub' : (isManga ? 'is-rose' : 'is-cyan'))} />
                    {tab === 'completed'
                      ? (isManga ? '已读完漫画' : '已看完番剧')
                      : (isManga ? '我的漫画书架' : '我的追番列表')}
                    <span className="space-sec-count tabular-nums">{shownFavs.length}</span>
                  </h2>
                </div>

                {shownFavs.length === 0 ? (
                  <div className="space-empty">
                    <span className="space-empty-ico"><BookmarkIcon size={22} /></span>
                    <h3>{isManga ? '当前筛选下暂无漫画收藏' : '当前筛选下暂无追番收藏'}</h3>
                    <p>
                      {isManga
                        ? '在漫画详情页点击「加入书架」或「标为已读完」，就能在这里分类管理。'
                        : '在番剧详情页或首页点击「追番」，就能在这里追踪排期与更新状态。'}
                    </p>
                    <Link to={isManga ? '/manga' : '/index'} className={'btn pill ' + (isManga ? 'space-btn-rose' : '')}>
                      {isManga ? '发现漫画 →' : '发现番剧 →'}
                    </Link>
                  </div>
                ) : viewMode === 'grid' ? (
                  <div className="space-card-grid">
                    {shownFavs.map(f => {
                      const done = f.status === 'COMPLETED'
                      return (
                        <div key={favKey(f)} className="shelf-card-wrap">
                          <Link to={favLink(f)} className="work-card">
                            <div className="cover">
                              <CoverImage src={f.coverUrl} alt={f.title} />
                              {done && (
                                <span className="badge-br is-pub" style={{ background: 'var(--pub)' }}>
                                  ✓ {isManga ? '已读完' : '已看完'}
                                </span>
                              )}
                            </div>
                            <div className="work-card-body">
                              <div className="work-card-title">{f.title}</div>
                              <div className="row-between" style={{ marginTop: 6 }}>
                                <span className="muted tabular-nums" style={{ fontSize: 11.5 }}>
                                  {fmtDate(f.createdAt) || (isManga ? '书架' : '追番')}
                                </span>
                                <button
                                  type="button"
                                  className="fav-status-btn"
                                  onClick={e => { e.preventDefault(); void toggleFavoriteStatus(f) }}
                                >
                                  {done ? '改回在追' : (isManga ? '标已读完' : '标已看完')}
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
                      return (
                        <div key={favKey(f)} className="space-list-row">
                          <Link to={favLink(f)} className="space-row-thumb">
                            <CoverImage src={f.coverUrl} alt={f.title} />
                          </Link>
                          <div className="space-row-body">
                            <div className="space-row-top">
                              <Link to={favLink(f)} className="space-row-title">{f.title}</Link>
                              <span className={'space-tag ' + (done ? 'is-done' : 'is-watching')}>
                                {done ? (isManga ? '已读完' : '已看完') : '在追'}
                              </span>
                            </div>
                            <div className="space-row-sub tabular-nums">
                              <span>加入于 {fmtDate(f.createdAt) || '近期'}</span>
                            </div>
                          </div>
                          <div className="space-row-actions">
                            <button
                              type="button"
                              className="btn ghost mini"
                              onClick={() => void toggleFavoriteStatus(f)}
                            >
                              {done ? '改回在追' : (isManga ? '标已读完' : '标已看完')}
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
                    {isManga ? '漫画阅读历史' : '动画观看历史'}
                    <span className="space-sec-count tabular-nums">{shownHistory.length}</span>
                  </h2>
                </div>

                {groupedHistory.length === 0 ? (
                  <div className="space-empty">
                    <span className="space-empty-ico"><ClockIcon size={22} /></span>
                    <h3>{isManga ? '暂无漫画阅读记录' : '暂无动画观看记录'}</h3>
                    <p>{isManga ? '翻阅漫画章节后，会按日期自动归档在这里。' : '观看番剧后，会按日期自动归档在这里。'}</p>
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
                          {group.items.map(a => (
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
                                </div>
                                <div className="space-row-sub">
                                  <span>{activitySummary(a)}</span>
                                  <span className="tabular-nums">{fmtRelativeTime(a.updatedAt)}</span>
                                </div>
                              </div>
                              <Link className="btn ghost mini" to={activityLink(a)}>
                                {isManga ? '继续阅读' : '继续观看'}
                              </Link>
                            </div>
                          ))}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </section>
            )}
          </div>

          {/* 右侧边栏（324px，按动画/漫画频道完全独立定制） */}
          <aside className="space-sidebar" aria-label={isManga ? '漫画书架概览与阅读偏好' : '追更雷达与追番偏好'}>
            {isManga ? (
              <>
                {/* 漫画卡 1：书架来源概览 */}
                <section className="space-side-card">
                  <div className="space-side-head">
                    <h3>书架来源概览</h3>
                    <LayersIcon size={15} className="muted" />
                  </div>

                  {split.mangaSources.length === 0 ? (
                    <div className="space-side-empty">
                      <BookOpenIcon size={22} />
                      <p>尚无漫画书架与阅读来源</p>
                      <span>去漫画发现页收藏跨源漫画，或在「我的素材」上传私人漫画。</span>
                    </div>
                  ) : (
                    <ul className="space-source-list">
                      {split.mangaSources.map(src => (
                        <li key={src.code} className="space-source-item">
                          <div className="space-source-row tabular-nums">
                            <span>{src.label}</span>
                            <strong>{src.count} 部</strong>
                          </div>
                          <div className="space-source-track" aria-hidden="true">
                            <div
                              className="space-source-fill"
                              style={{ width: `${Math.max(12, Math.round((src.count / maxSourceCount) * 100))}%` }}
                            />
                          </div>
                        </li>
                      ))}
                    </ul>
                  )}

                  <div className="space-side-links">
                    <Link to="/manga" className="space-side-link is-rose">探索全站漫画 →</Link>
                    <Link to="/uploads" className="space-side-link">管理站内素材 →</Link>
                  </div>
                </section>

                {/* 漫画卡 2：阅读偏好 DNA */}
                <section className="space-side-card">
                  <div className="space-side-head">
                    <h3>阅读偏好 DNA</h3>
                    <SparklesIcon size={15} className="muted" />
                  </div>

                  <div className="space-dna-tiles tabular-nums">
                    <div className="space-dna-tile">
                      <span className="space-dna-k">涉猎漫画</span>
                      <strong className="is-rose">{split.mangaWorkTotal} 部</strong>
                    </div>
                    <div className="space-dna-tile">
                      <span className="space-dna-k">阅读记录</span>
                      <strong>{split.mangaHistory.length} 条</strong>
                    </div>
                  </div>

                  <div className="space-dna-tags-block">
                    <span className="space-dna-subhead">常看漫画题材 · 点击直达筛选</span>
                    {mangaPrefs.length === 0 ? (
                      <p className="space-side-hint">多阅读或收藏几部漫画后，会自动提炼你的常看漫画题材。</p>
                    ) : (
                      <div className="space-dna-pills">
                        {mangaPrefs.slice(0, 8).map(t => (
                          <Link key={t} to={`/manga?genre=${encodeURIComponent(t)}`} className="space-dna-pill is-manga">
                            {t}
                          </Link>
                        ))}
                      </div>
                    )}
                  </div>
                </section>

                {/* 漫画卡 3：最近阅读 */}
                <section className="space-side-card">
                  <div className="space-side-head">
                    <h3>最近阅读</h3>
                    <ClockIcon size={15} className="muted" />
                  </div>
                  {recentQuick.length === 0 ? (
                    <p className="space-side-hint">开始阅读漫画后，这里会保留最近 3 部作品的一键续读入口。</p>
                  ) : (
                    <ul className="space-recent-list">
                      {recentQuick.map(a => (
                        <li key={`rec-m-${a.type}-${a.chapterId ?? a.workId ?? a.mangaId}`}>
                          <Link to={activityLink(a)} className="space-recent-item">
                            <div className="space-recent-thumb">
                              <CoverImage src={a.coverUrl} alt={a.title} />
                            </div>
                            <div className="space-recent-info">
                              <div className="space-recent-title">{a.title}</div>
                              <div className="space-recent-sub">
                                <span className="space-mini-dot is-rose" />
                                <span>{activitySummary(a)}</span>
                              </div>
                            </div>
                          </Link>
                        </li>
                      ))}
                    </ul>
                  )}
                </section>
              </>
            ) : (
              <>
                {/* 动画卡 1：本周追更雷达 */}
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

                {/* 动画卡 2：追番偏好 DNA */}
                <section className="space-side-card">
                  <div className="space-side-head">
                    <h3>追番偏好 DNA</h3>
                    <SparklesIcon size={15} className="muted" />
                  </div>

                  <div className="space-dna-tiles tabular-nums">
                    <div className="space-dna-tile">
                      <span className="space-dna-k">涉猎动画</span>
                      <strong className="is-cyan">{split.animeWorkTotal} 部</strong>
                    </div>
                    <div className="space-dna-tile">
                      <span className="space-dna-k">观看记录</span>
                      <strong>{split.animeHistory.length} 条</strong>
                    </div>
                  </div>

                  <div className="space-dna-tags-block">
                    <span className="space-dna-subhead">常看动画题材 · 点击直达索引</span>
                    {animePrefs.length === 0 ? (
                      <p className="space-side-hint">多收藏或观看几部番剧后，会自动提炼你的常看动画题材。</p>
                    ) : (
                      <div className="space-dna-pills">
                        {animePrefs.slice(0, 8).map(t => (
                          <Link key={t} to={`/index?genre=${encodeURIComponent(t)}`} className="space-dna-pill is-anime">
                            {t}
                          </Link>
                        ))}
                      </div>
                    )}
                  </div>
                </section>

                {/* 动画卡 3：最近观看 */}
                <section className="space-side-card">
                  <div className="space-side-head">
                    <h3>最近观看</h3>
                    <ClockIcon size={15} className="muted" />
                  </div>
                  {recentQuick.length === 0 ? (
                    <p className="space-side-hint">开始观看番剧后，这里会保留最近 3 部动画的一键续播入口。</p>
                  ) : (
                    <ul className="space-recent-list">
                      {recentQuick.map(a => (
                        <li key={`rec-a-${a.type}-${a.episodeId ?? a.animationId ?? a.workId}`}>
                          <Link to={activityLink(a)} className="space-recent-item">
                            <div className="space-recent-thumb">
                              <CoverImage src={a.coverUrl} alt={a.title} />
                            </div>
                            <div className="space-recent-info">
                              <div className="space-recent-title">{a.title}</div>
                              <div className="space-recent-sub">
                                <span className="space-mini-dot is-cyan" />
                                <span>{activitySummary(a)}</span>
                              </div>
                            </div>
                          </Link>
                        </li>
                      ))}
                    </ul>
                  )}
                </section>
              </>
            )}
          </aside>
        </div>
      </main>
    </div>
  )
}

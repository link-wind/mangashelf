import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import CoverImage from '../components/CoverImage'
import { BoltIcon, ChevronDownIcon, HeartIcon, InfoIcon, MoonIcon, PlayIcon, SunIcon } from '../components/Icons'
import { useToast } from '../components/Toast'

interface CalendarEntry {
  subjectId: number
  name: string
  nameCn: string
  coverUrl: string
  rating: number | null
  airWeekday: number | null
  covered: boolean
  catalogItemId: string | null
  workId: string | null
  summary: string | null
  remarks: string | null
  episodeCount: number | null
  categoryName: string | null
  airDate?: string | null
  airTime?: string | null
  studio?: string | null
  mediaType?: string | null
}

interface SeasonOption {
  code: string
  label: string
}

type ScheduleFilter = 'all' | 'following' | 'unwatched'
type TimeBand = 'prime' | 'midnight' | 'daytime'

const DAYS = [
  { id: 1, zh: '周一', en: 'MON' },
  { id: 2, zh: '周二', en: 'TUE' },
  { id: 3, zh: '周三', en: 'WED' },
  { id: 4, zh: '周四', en: 'THU' },
  { id: 5, zh: '周五', en: 'FRI' },
  { id: 6, zh: '周六', en: 'SAT' },
  { id: 7, zh: '周日', en: 'SUN' },
]

function weekdayName(day: number | null | undefined): string {
  return DAYS.find(d => d.id === day)?.zh ?? '待定'
}

/** 计算本周周一至周日的真实公历日期（锚定本地当前时间）。 */
function buildWeekDates(now: Date): Map<number, { longLabel: string; shortLabel: string }> {
  const dow = now.getDay() || 7
  const monday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - (dow - 1))
  const map = new Map<number, { longLabel: string; shortLabel: string }>()
  for (let i = 0; i < 7; i++) {
    const d = new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + i)
    const m = d.getMonth() + 1
    const day = d.getDate()
    map.set(i + 1, {
      longLabel: `${m}月${day}日`,
      shortLabel: `${m}/${day}`,
    })
  }
  return map
}

function parseMinutes(hhmm: string | null | undefined): number | null {
  if (!hhmm) return null
  const m = /^(\d{1,2}):(\d{2})$/.exec(hhmm.trim())
  if (!m) return null
  const h = Number(m[1])
  const min = Number(m[2])
  if (h < 0 || h > 23 || min < 0 || min > 59) return null
  return h * 60 + min
}

function classifyBand(airTime: string | null | undefined): TimeBand | null {
  const mins = parseMinutes(airTime)
  if (mins === null) return null
  if (mins >= 21 * 60) return 'prime'
  if (mins < 3 * 60) return 'midnight'
  return 'daytime'
}

function sortByTimeAndRating(list: CalendarEntry[]): CalendarEntry[] {
  return [...list].sort((a, b) => {
    const ta = parseMinutes(a.airTime)
    const tb = parseMinutes(b.airTime)
    if (ta !== null && tb !== null && ta !== tb) return ta - tb
    if (ta !== null && tb === null) return -1
    if (ta === null && tb !== null) return 1
    if (a.covered !== b.covered) return a.covered ? -1 : 1
    return (b.rating ?? 0) - (a.rating ?? 0)
  })
}

/**
 * 新番时刻表：
 * - 顶部季度切换下拉 + 「全部 / 已上线 / 我的追番 / 未看更新」筛选 + 右侧「下个更新」实时预告；
 * - 真实日期 7 列周历切换条（今日高亮微光、浅色反色选中、已追番提示圆点）；
 * - 8:4 双栏主体：左侧三档播映时段（黄金夜档大卡 / 深夜硬核档双列紧凑卡 / 日间与其他时段），封面支持悬停磨砂播放与一键追番；
 * - 右侧情报栏：本周重磅雷达 Top 3 + 我的本周追更 + 追番日历 .ics 订阅。
 */
export default function Schedule() {
  const navigate = useNavigate()
  const { me } = useAuth()
  const { showToast } = useToast()
  const [params, setParams] = useSearchParams()

  const [now, setNow] = useState(() => new Date())
  const today = now.getDay() || 7
  const weekDates = useMemo(() => buildWeekDates(now), [now.getFullYear(), now.getMonth(), now.getDate()])

  const rawDay = Number(params.get('day'))
  const activeDay = rawDay >= 1 && rawDay <= 7 ? rawDay : today
  const season = params.get('season') || 'current'

  const [seasons, setSeasons] = useState<SeasonOption[]>([{ code: 'current', label: '本季新番' }])
  const [seasonOpen, setSeasonOpen] = useState(false)
  const seasonRef = useRef<HTMLDivElement>(null)

  const [items, setItems] = useState<CalendarEntry[]>([])
  const [favCatalogIds, setFavCatalogIds] = useState<Set<string>>(new Set())
  const [unwatchedIds, setUnwatchedIds] = useState<Set<string>>(new Set())
  const [filter, setFilter] = useState<ScheduleFilter>('all')
  const [busyId, setBusyId] = useState<string | null>(null)
  const [initialLoading, setInitialLoading] = useState(true)
  const [switching, setSwitching] = useState(false)
  const [error, setError] = useState('')

  // 每分钟更新一次时钟，保持「下个更新」预告实时准确
  useEffect(() => {
    const t = window.setInterval(() => setNow(new Date()), 60_000)
    return () => window.clearInterval(t)
  }, [])

  // 点击外部或按 Esc 关闭季度下拉
  useEffect(() => {
    if (!seasonOpen) return
    const onDown = (e: MouseEvent) => {
      if (seasonRef.current && !seasonRef.current.contains(e.target as Node)) setSeasonOpen(false)
    }
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setSeasonOpen(false) }
    window.addEventListener('mousedown', onDown)
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('mousedown', onDown)
      window.removeEventListener('keydown', onKey)
    }
  }, [seasonOpen])

  // 加载可选季度列表
  useEffect(() => {
    void api.get<SeasonOption[]>('/api/v1/calendar/seasons')
      .then(list => { if (list.length > 0) setSeasons(list) })
      .catch(() => { /* 回退默认本季选项 */ })
  }, [])

  // 加载指定季度的时刻表数据（切换季度时原地柔和过渡，不闪白屏）
  useEffect(() => {
    let cancelled = false
    void (async () => {
      if (items.length === 0) setInitialLoading(true)
      else setSwitching(true)
      setError('')
      try {
        const qs = season && season !== 'current' ? `?season=${encodeURIComponent(season)}` : ''
        const cal = await api.get<CalendarEntry[]>(`/api/v1/calendar${qs}`)
        if (!cancelled) setItems(cal)
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : '时刻表加载失败')
      } finally {
        if (!cancelled) {
          setInitialLoading(false)
          setSwitching(false)
        }
      }
    })()
    return () => { cancelled = true }
  }, [season])

  // 加载当前用户的追番列表与待看更新集合
  useEffect(() => {
    if (!me) {
      setFavCatalogIds(new Set())
      setUnwatchedIds(new Set())
      return
    }
    void Promise.all([
      api.get<{ catalogItemId: string | null }[]>('/api/v1/me/favorites?type=catalog').catch(() => []),
      api.get<{ catchUp?: { catalogItemId: string }[] }>('/api/v1/recommendations/anime?limit=1').catch(() => ({ catchUp: [] })),
    ]).then(([favs, rec]) => {
      setFavCatalogIds(new Set(favs.map(f => f.catalogItemId).filter((x): x is string => Boolean(x))))
      setUnwatchedIds(new Set((rec.catchUp ?? []).map(c => c.catalogItemId)))
    })
  }, [me])

  function selectDay(day: number) {
    const next = new URLSearchParams(params)
    if (day === today) next.delete('day')
    else next.set('day', String(day))
    setParams(next, { replace: true })
  }

  function selectSeason(code: string) {
    setSeasonOpen(false)
    const next = new URLSearchParams(params)
    if (!code || code === 'current') next.delete('season')
    else next.set('season', code)
    setParams(next, { replace: true })
  }

  function playHrefOf(it: CalendarEntry): string {
    if (it.workId) return `/works/${it.workId}`
    if (it.catalogItemId) return `/browse/${it.catalogItemId}`
    return `/anime?q=${encodeURIComponent(it.nameCn || it.name)}`
  }

  function detailHrefOf(it: CalendarEntry): string {
    if (it.workId) return `/works/${it.workId}`
    if (it.catalogItemId) return `/catalog/${it.catalogItemId}`
    return `/anime?q=${encodeURIComponent(it.nameCn || it.name)}`
  }

  async function toggleFavorite(it: CalendarEntry) {
    if (!it.catalogItemId) {
      showToast('该作品暂未上线', 'info')
      return
    }
    if (!me) {
      showToast('请先登录后再追番收藏', 'info')
      return
    }
    const cid = it.catalogItemId
    const isFav = favCatalogIds.has(cid)
    setBusyId(cid)
    try {
      if (isFav) {
        await api.delete(`/api/v1/me/favorites/catalog/${cid}`)
        setFavCatalogIds(prev => { const n = new Set(prev); n.delete(cid); return n })
        showToast(`已取消追番「${it.nameCn || it.name}」`, 'info')
      } else {
        await api.put(`/api/v1/me/favorites/catalog/${cid}`)
        setFavCatalogIds(prev => new Set(prev).add(cid))
        showToast(`已追番「${it.nameCn || it.name}」`, 'success')
      }
    } catch (e) {
      showToast(e instanceof Error ? e.message : '操作失败', 'error')
    } finally {
      setBusyId(null)
    }
  }

  // 记录哪些星期有当前用户正在追的番（用于在周历条打小圆点）
  const followedDays = useMemo(() => {
    const s = new Set<number>()
    if (!me || favCatalogIds.size === 0) return s
    for (const it of items) {
      if (it.airWeekday && it.catalogItemId && favCatalogIds.has(it.catalogItemId)) {
        s.add(it.airWeekday)
      }
    }
    return s
  }, [items, favCatalogIds, me])

  const filteredAll = useMemo(() => {
    return items.filter(it => {
      if (filter === 'following') return Boolean(it.catalogItemId && favCatalogIds.has(it.catalogItemId))
      if (filter === 'unwatched') return Boolean(it.catalogItemId && unwatchedIds.has(it.catalogItemId))
      return true
    })
  }, [items, filter, favCatalogIds, unwatchedIds])

  const dayList = useMemo(() => {
    return filteredAll.filter(it => (it.airWeekday ?? 0) === activeDay && parseMinutes(it.airTime) !== null)
  }, [filteredAll, activeDay])

  // 按精确时刻分三档：黄金夜档 / 深夜硬核档 / 日间与晚间档
  const bands = useMemo(() => {
    const prime: CalendarEntry[] = []
    const midnight: CalendarEntry[] = []
    const daytime: CalendarEntry[] = []
    for (const it of dayList) {
      const b = classifyBand(it.airTime)
      if (b === 'prime') prime.push(it)
      else if (b === 'midnight') midnight.push(it)
      else if (b === 'daytime') daytime.push(it)
    }
    return {
      prime: sortByTimeAndRating(prime),
      midnight: sortByTimeAndRating(midnight),
      daytime: sortByTimeAndRating(daytime),
    }
  }, [dayList])

  // 顶部「下个更新」实时预告（从今日带精确时刻的条目中取最近即将播出的一部）
  const nextAiring = useMemo(() => {
    const todayTimed = items
      .filter(it => it.airWeekday === today && parseMinutes(it.airTime) !== null)
      .sort((a, b) => (parseMinutes(a.airTime) ?? 0) - (parseMinutes(b.airTime) ?? 0))
    if (todayTimed.length === 0) return { state: 'none' as const, item: null as CalendarEntry | null }
    const nowMin = now.getHours() * 60 + now.getMinutes()
    const upcoming = todayTimed.find(it => (parseMinutes(it.airTime) ?? 0) > nowMin)
    if (upcoming) return { state: 'upcoming' as const, item: upcoming }
    return { state: 'done' as const, item: null as CalendarEntry | null }
  }, [items, today, now])

  // 右侧重磅雷达精简为 Top 3（随当前季度联动）
  const topPicks = useMemo(() => {
    return [...items]
      .filter(it => (it.rating ?? 0) > 0)
      .sort((a, b) => {
        if (a.covered !== b.covered) return a.covered ? -1 : 1
        return (b.rating ?? 0) - (a.rating ?? 0)
      })
      .slice(0, 3)
  }, [items])

  const myWeekly = useMemo(() => {
    return items
      .filter(it => it.catalogItemId && favCatalogIds.has(it.catalogItemId))
      .sort((a, b) => (a.airWeekday ?? 9) - (b.airWeekday ?? 9))
  }, [items, favCatalogIds])

  const activeSeasonLabel = seasons.find(s => s.code === season)?.label ?? '本季新番'
  const needsLoginForFilter = !me && (filter === 'following' || filter === 'unwatched')

  function renderDetailedCard(it: CalendarEntry) {
    const title = it.nameCn || it.name
    const isFav = Boolean(it.catalogItemId && favCatalogIds.has(it.catalogItemId))
    const hasNew = Boolean(it.catalogItemId && unwatchedIds.has(it.catalogItemId))
    const detailHref = detailHrefOf(it)
    const playHref = playHrefOf(it)
    const statusText = it.remarks?.includes('完结') ? '已完结' : (it.covered ? '连载中' : '待更新')

    return (
      <article key={it.subjectId} className="sched-card card">
        <div className="sched-poster">
          <Link to={detailHref} className="sched-poster-link" aria-label={`查看 ${title}`}>
            <CoverImage src={`/api/v1/bangumi-covers/${it.subjectId}?size=large`}
                        alt={title} loading="eager" />
            <span className={'badge-tl ' + (it.covered ? 'is-pub' : '')}>{statusText}</span>
            {typeof it.rating === 'number' && it.rating > 0 && (
              <span className="badge-bl badge-score tabular-nums">★ {it.rating.toFixed(1)}</span>
            )}
          </Link>
          <div className="sched-poster-actions">
            <Link to={playHref} className="apc-play" aria-label={`播放 ${title}`} title="立即播放">
              <PlayIcon size={20} />
            </Link>
            {it.catalogItemId && (
              <button type="button"
                      className={'apc-fav' + (isFav ? ' active' : '')}
                      disabled={busyId === it.catalogItemId}
                      aria-pressed={isFav}
                      aria-label={isFav ? `取消追番 ${title}` : `追番 ${title}`}
                      title={isFav ? '取消追番' : '追番'}
                      onClick={e => { e.preventDefault(); e.stopPropagation(); void toggleFavorite(it) }}>
                <HeartIcon size={17} filled={isFav} />
              </button>
            )}
          </div>
        </div>

        <div className="sched-card-body">
          <div className="sched-card-toprow">
            <div className="sched-card-tags">
              <span className="sched-time-tag tabular-nums">
                {it.airTime || `${weekdayName(it.airWeekday)}放送`}
              </span>
              {it.remarks && <span className="sched-ep-tag">{it.remarks}</span>}
              {hasNew && <span className="sched-new-tag">有新集</span>}
            </div>
            {(it.mediaType || it.categoryName) && (
              <span className="sched-format-tag">{it.mediaType || it.categoryName}</span>
            )}
          </div>

          <Link to={detailHref} className="sched-card-title">{title}</Link>

          {(it.studio || (it.nameCn && it.name !== it.nameCn)) && (
            <p className="sched-card-credits muted">
              {it.studio ? `制作：${it.studio}` : it.name}
            </p>
          )}

          <p className="sched-card-desc">
            {it.summary || '暂无简介，点击详情查看更多信息。'}
          </p>

          <div className="sched-card-actions">
            {it.catalogItemId && (
              <button type="button" className={'pill mini ' + (isFav ? '' : 'ghost')}
                      disabled={busyId === it.catalogItemId}
                      onClick={() => void toggleFavorite(it)}>
                <HeartIcon size={13} filled={isFav} />
                {isFav ? '已追番' : '追番'}
              </button>
            )}
            <button type="button" className={'pill mini ' + (it.covered ? '' : 'ghost')}
                    onClick={() => navigate(playHref)}>
              {it.covered ? <PlayIcon size={13} /> : <InfoIcon size={13} />}
              {it.covered ? '立即播放' : '番剧详情'}
            </button>
          </div>
        </div>
      </article>
    )
  }

  function renderCompactCard(it: CalendarEntry) {
    const title = it.nameCn || it.name
    const isFav = Boolean(it.catalogItemId && favCatalogIds.has(it.catalogItemId))
    const detailHref = detailHrefOf(it)
    const playHref = playHrefOf(it)
    const statusText = it.remarks?.includes('完结') ? '已完结' : (it.covered ? '连载中' : '待更新')

    return (
      <article key={it.subjectId} className="sched-card compact card">
        <div className="sched-poster">
          <Link to={detailHref} className="sched-poster-link" aria-label={`查看 ${title}`}>
            <CoverImage src={`/api/v1/bangumi-covers/${it.subjectId}?size=large`} alt={title} />
            <span className={'badge-tl ' + (it.covered ? 'is-pub' : '')}>{statusText}</span>
            {typeof it.rating === 'number' && it.rating > 0 && (
              <span className="badge-bl badge-score tabular-nums">★ {it.rating.toFixed(1)}</span>
            )}
          </Link>
          <div className="sched-poster-actions is-compact">
            <Link to={playHref} className="apc-play is-sm" aria-label={`播放 ${title}`} title="立即播放">
              <PlayIcon size={16} />
            </Link>
            {it.catalogItemId && (
              <button type="button"
                      className={'apc-fav is-sm' + (isFav ? ' active' : '')}
                      disabled={busyId === it.catalogItemId}
                      aria-pressed={isFav}
                      aria-label={isFav ? `取消追番 ${title}` : `追番 ${title}`}
                      onClick={e => { e.preventDefault(); e.stopPropagation(); void toggleFavorite(it) }}>
                <HeartIcon size={14} filled={isFav} />
              </button>
            )}
          </div>
        </div>

        <div className="sched-card-body">
          <div className="sched-card-toprow">
            <span className="sched-time-tag tabular-nums">
              {it.airTime || (it.remarks || weekdayName(it.airWeekday))}
            </span>
            {it.mediaType && <span className="sched-format-tag">{it.mediaType}</span>}
          </div>
          <Link to={detailHref} className="sched-card-title clamp">{title}</Link>
          {it.studio && <span className="sched-compact-studio muted">{it.studio}</span>}
          <div className="sched-compact-links">
            <Link to={playHref} className="sched-inline-action">
              {it.covered ? <PlayIcon size={12} /> : <InfoIcon size={12} />}
              {it.covered ? '立即播放' : '番剧详情'}
            </Link>
            {it.catalogItemId && (
              <Link to={detailHref} className="muted sched-inline-sub">详情</Link>
            )}
          </div>
        </div>
      </article>
    )
  }

  return (
    <main className="watch-page">
      {/* 顶部标题 + 季度切换下拉 */}
      <div className="sched-header">
        <div className="sched-title-row">
          <div className="sched-season-wrap" ref={seasonRef}>
            <button type="button"
                    className={'sched-season-btn' + (seasonOpen ? ' open' : '')}
                    aria-haspopup="listbox"
                    aria-expanded={seasonOpen}
                    onClick={() => setSeasonOpen(v => !v)}>
              <span className="sched-season-badge">新番时刻表</span>
              <span className="sched-season-text">{activeSeasonLabel}</span>
              <ChevronDownIcon size={15} className="sched-season-caret" />
            </button>
            {seasonOpen && (
              <div className="sched-season-menu glass-surface" role="listbox" aria-label="选择季度">
                {seasons.map(opt => (
                  <button key={opt.code} type="button" role="option"
                          aria-selected={opt.code === season}
                          className={'sched-season-opt' + (opt.code === season ? ' active' : '')}
                          onClick={() => selectSeason(opt.code)}>
                    {opt.label}
                  </button>
                ))}
              </div>
            )}
          </div>
          <span className="sched-title-divider" aria-hidden="true" />
          <p className="sched-subtitle muted">每周与你准时相遇</p>
        </div>
      </div>

      {/* 筛选胶囊 + 右侧「下个更新」实时预告 */}
      <div className="sched-filter-bar">
        <div className="row-inline" role="group" aria-label="排期筛选">
          <button type="button" className={'mini ' + (filter === 'all' ? '' : 'ghost')}
                  onClick={() => setFilter('all')}>全部</button>
          <button type="button" className={'mini ' + (filter === 'following' ? '' : 'ghost')}
                  onClick={() => setFilter('following')}>我的追番</button>
          <button type="button" className={'mini ' + (filter === 'unwatched' ? '' : 'ghost')}
                  onClick={() => setFilter('unwatched')}>未看更新</button>
        </div>

        {nextAiring.state === 'upcoming' && nextAiring.item ? (
          <Link to={detailHrefOf(nextAiring.item)} className="sched-next-pill">
            <span className="sched-pulse-dot" aria-hidden="true" />
            <span className="muted">下个更新</span>
            <strong className="sched-next-time tabular-nums">{nextAiring.item.airTime}</strong>
            <span className="sched-next-title">{nextAiring.item.nameCn || nextAiring.item.name}</span>
          </Link>
        ) : nextAiring.state === 'done' ? (
          <div className="sched-next-pill is-done">
            <span className="sched-done-dot" aria-hidden="true" />
            <span className="muted">今日更新已全部上线</span>
          </div>
        ) : (
          <Link to="/anime?category=season" className="muted" style={{ fontSize: 13 }}>
            浏览全部本季新番 →
          </Link>
        )}
      </div>

      {/* 7 列真实日期周历切换条 */}
      <div className="sched-week-strip" role="tablist" aria-label="星期切换">
        {DAYS.map(d => {
          const isToday = d.id === today
          const isActive = d.id === activeDay
          const dateInfo = weekDates.get(d.id)
          const hasFollowed = followedDays.has(d.id)
          return (
            <button key={d.id} type="button" role="tab" aria-selected={isActive}
                    className={'sched-day-tab' + (isActive ? ' active' : '') + (isToday ? ' today' : '')}
                    onClick={() => selectDay(d.id)}>
              <span className="sched-day-micro">
                {isToday ? '今日 · TODAY' : `${d.zh} · ${d.en}`}
              </span>
              <span className="sched-day-date tabular-nums">
                {isToday ? `${d.zh} ${dateInfo?.shortLabel ?? ''}` : (dateInfo?.longLabel ?? d.zh)}
              </span>
              {hasFollowed && <span className="sched-day-dot" title="今日有你的追番更新" aria-hidden="true" />}
            </button>
          )
        })}
      </div>

      {error && <p className="error" style={{ marginTop: 14 }}>{error}</p>}

      {initialLoading ? (
        <div className="sched-body-grid" style={{ marginTop: 22 }}>
          <div className="stack">
            <div className="skeleton-box" style={{ height: 210, borderRadius: 16 }} />
            <div className="skeleton-box" style={{ height: 150, borderRadius: 16 }} />
          </div>
          <div className="skeleton-box" style={{ height: 340, borderRadius: 16 }} />
        </div>
      ) : (
        <div className={'sched-body-grid' + (switching ? ' is-switching' : '')}>
          {/* 左侧主排期流（8fr） */}
          <div className="sched-main">
            {needsLoginForFilter ? (
              <div className="card sched-empty-box">
                <p className="sched-empty-title">
                  {filter === 'following' ? '登录后查看你的追番排期' : '登录后自动筛出未看新集'}
                </p>
                <p className="muted" style={{ margin: '0 0 14px' }}>
                  同步你的收藏与观看进度，不再错过每一集更新。
                </p>
                <div className="row-inline" style={{ justifyContent: 'center' }}>
                  <Link to="/login?next=%2Fschedule" className="btn pill mini">登录账号</Link>
                  <button type="button" className="mini ghost" onClick={() => setFilter('all')}>查看全部</button>
                </div>
              </div>
            ) : dayList.length === 0 ? (
              <div className="card sched-empty-box">
                <p className="sched-empty-title">{weekdayName(activeDay)}暂无更新</p>
                <p className="muted" style={{ margin: '0 0 12px' }}>
                  这一天在当前筛选下没有番剧，可以切换其他星期或季度。
                </p>
                {filter !== 'all' && (
                  <button type="button" className="mini ghost" onClick={() => setFilter('all')}>
                    显示全部番剧
                  </button>
                )}
              </div>
            ) : (
              <>
                {bands.prime.length > 0 && (
                  <section className="sched-group">
                    <div className="sched-group-head">
                      <span className="sched-slot-icon"><MoonIcon size={15} /></span>
                      <h2>黄金夜档</h2>
                      <span className="sched-slot-range tabular-nums">21:00 — 23:59</span>
                    </div>
                    <div className="sched-featured-list">
                      {bands.prime.map(renderDetailedCard)}
                    </div>
                  </section>
                )}

                {bands.midnight.length > 0 && (
                  <section className="sched-group">
                    <div className="sched-group-head">
                      <span className="sched-slot-icon"><BoltIcon size={15} /></span>
                      <h2>深夜硬核档</h2>
                      <span className="sched-slot-range tabular-nums">00:00 — 02:59</span>
                    </div>
                    <div className="sched-compact-grid">
                      {bands.midnight.map(renderCompactCard)}
                    </div>
                  </section>
                )}

                {bands.daytime.length > 0 && (
                  <section className="sched-group">
                    <div className="sched-group-head">
                      <span className="sched-slot-icon"><SunIcon size={15} /></span>
                      <h2>日间与晚间档</h2>
                      <span className="sched-slot-range tabular-nums">03:00 — 20:59</span>
                    </div>
                    <div className="sched-featured-list">
                      {bands.daytime.map(renderDetailedCard)}
                    </div>
                  </section>
                )}
              </>
            )}
          </div>

          {/* 右侧情报侧栏（4fr） */}
          <aside className="sched-side">
            {topPicks.length > 0 && (
              <section className="card sched-side-card">
                <div className="sched-side-head">
                  <h3>本周重磅雷达</h3>
                  <span className="muted">高分精选</span>
                </div>
                <ol className="sched-radar-list">
                  {topPicks.map((it, idx) => (
                    <li key={it.subjectId} className="sched-radar-item">
                      <span className={'rank-no tabular-nums' + (idx < 3 ? ' top' : '')}>
                        {String(idx + 1).padStart(2, '0')}
                      </span>
                      <Link to={detailHrefOf(it)} className="sched-radar-thumb">
                        <CoverImage src={`/api/v1/bangumi-covers/${it.subjectId}?size=large`}
                                    alt={it.nameCn || it.name} />
                      </Link>
                      <div className="sched-radar-info">
                        <Link to={detailHrefOf(it)} className="sched-radar-title">
                          {it.nameCn || it.name}
                        </Link>
                        <span className="muted sched-radar-sub">
                          {weekdayName(it.airWeekday)}
                          {it.airTime ? ` ${it.airTime}` : ''}
                          {it.studio ? ` · ${it.studio}` : (it.remarks ? ` · ${it.remarks}` : '')}
                        </span>
                      </div>
                      <span className="sched-radar-score tabular-nums">
                        {(it.rating ?? 0).toFixed(1)} 分
                      </span>
                    </li>
                  ))}
                </ol>
              </section>
            )}

            <section className="card sched-side-card">
              <div className="sched-side-head">
                <h3>我的本周追更</h3>
                {me && <Link to="/me?tab=favorites&kind=anime" className="muted">管理 →</Link>}
              </div>
              {!me ? (
                <p className="muted" style={{ margin: 0 }}>
                  <Link to="/login?next=%2Fschedule">登录</Link>后将在此自动汇总你收藏的本季追番排期。
                </p>
              ) : myWeekly.length === 0 ? (
                <p className="muted" style={{ margin: 0 }}>
                  还没有收藏本季新番——在左侧卡片点「追番」即可加入本周追更表。
                </p>
              ) : (
                <ul className="sched-my-list">
                  {myWeekly.slice(0, 10).map(it => (
                    <li key={it.subjectId}>
                      <span className="sched-my-day">
                        {weekdayName(it.airWeekday)}{it.airTime ? ` ${it.airTime}` : ''}
                      </span>
                      <Link to={playHrefOf(it)} className="sched-my-name">
                        {it.nameCn || it.name}
                      </Link>
                      <span className="muted tabular-nums">{it.remarks || ''}</span>
                    </li>
                  ))}
                </ul>
              )}
            </section>

            {/* 追番日历订阅 */}
            <section className="card sched-side-card">
              <div className="sched-side-head">
                <h3>追番日历订阅</h3>
              </div>
              <p className="muted" style={{ margin: '0 0 12px', fontSize: 12.5 }}>
                导出周历文件，可导入 Apple 日历、Google 日历或 Outlook。
              </p>
              <div className="sched-ics-actions">
                <a className="btn mini"
                   href={me && myWeekly.length > 0 ? '/api/v1/calendar/ics?onlyMine=true' : '/api/v1/calendar/ics?coveredOnly=true'}
                   download="mangashelf-schedule.ics">
                  {me && myWeekly.length > 0 ? '下载我的追番日历' : '下载本季日历'}
                </a>
                <button type="button" className="mini ghost" onClick={() => {
                  const path = me && myWeekly.length > 0 ? '/api/v1/calendar/ics?onlyMine=true' : '/api/v1/calendar/ics?coveredOnly=true'
                  const full = `${window.location.origin}${path}`
                  void navigator.clipboard?.writeText(full)
                    .then(() => showToast('已复制日历订阅链接', 'success'))
                    .catch(() => showToast(full, 'info'))
                }}>
                  复制订阅地址
                </button>
              </div>
            </section>
          </aside>
        </div>
      )}
    </main>
  )
}

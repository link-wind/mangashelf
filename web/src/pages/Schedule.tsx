import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import CoverImage from '../components/CoverImage'
import { HeartIcon, PlayIcon } from '../components/Icons'
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
}

type ScheduleFilter = 'all' | 'covered' | 'following'

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

/**
 * 新番时刻表（借鉴 gugu.fan/schedule）：
 * - 顶部 KPI 态势卡（本季总数 / 今日更新 / 我在追的）+ 筛选胶囊；
 * - 7 列等宽周历切换条（自动高亮今日，URL 同步 ?day=）；
 * - 8:4 双栏主体：左侧两档自适应横卡（焦点 144×192 + 紧凑双列 96×128），右侧本周重磅雷达 + 我的本周追更表；
 * - 全量 Bangumi large 高清封面。
 */
export default function Schedule() {
  const navigate = useNavigate()
  const { me } = useAuth()
  const { showToast } = useToast()
  const [params, setParams] = useSearchParams()

  const today = new Date().getDay() || 7
  const rawDay = Number(params.get('day'))
  const activeDay = rawDay >= 1 && rawDay <= 7 ? rawDay : today

  const [items, setItems] = useState<CalendarEntry[]>([])
  const [favCatalogIds, setFavCatalogIds] = useState<Set<string>>(new Set())
  const [filter, setFilter] = useState<ScheduleFilter>('all')
  const [busyId, setBusyId] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    void (async () => {
      setLoading(true); setError('')
      try {
        const cal = await api.get<CalendarEntry[]>('/api/v1/calendar')
        setItems(cal)
        if (me) {
          const favs = await api.get<{ catalogItemId: string | null }[]>('/api/v1/me/favorites?type=catalog')
            .catch(() => [])
          setFavCatalogIds(new Set(favs.map(f => f.catalogItemId).filter((x): x is string => Boolean(x))))
        } else {
          setFavCatalogIds(new Set())
        }
      } catch (e) {
        setError(e instanceof Error ? e.message : '时刻表加载失败')
      } finally {
        setLoading(false)
      }
    })()
  }, [me])

  function selectDay(day: number) {
    const next = new URLSearchParams(params)
    if (day === today) next.delete('day')
    else next.set('day', String(day))
    setParams(next, { replace: true })
  }

  function play(it: CalendarEntry) {
    if (it.workId) { navigate(`/works/${it.workId}`); return }
    if (it.catalogItemId) { navigate(`/browse/${it.catalogItemId}`); return }
    navigate(`/anime?q=${encodeURIComponent(it.nameCn || it.name)}`)
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

  const filteredAll = useMemo(() => {
    return items.filter(it => {
      if (filter === 'covered') return it.covered
      if (filter === 'following') return Boolean(it.catalogItemId && favCatalogIds.has(it.catalogItemId))
      return true
    })
  }, [items, filter, favCatalogIds])

  const dayList = useMemo(() => {
    return filteredAll
      .filter(it => (it.airWeekday ?? 0) === activeDay)
      .sort((a, b) => {
        if (a.covered !== b.covered) return a.covered ? -1 : 1
        return (b.rating ?? 0) - (a.rating ?? 0)
      })
  }, [filteredAll, activeDay])

  const featured = useMemo(() => {
    const covered = dayList.filter(it => it.covered)
    return (covered.length > 0 ? covered : dayList).slice(0, Math.min(2, dayList.length))
  }, [dayList])
  const featuredIds = useMemo(() => new Set(featured.map(f => f.subjectId)), [featured])
  const compactList = useMemo(() => dayList.filter(it => !featuredIds.has(it.subjectId)), [dayList, featuredIds])

  const topPicks = useMemo(() => {
    return [...items]
      .filter(it => it.covered && (it.rating ?? 0) > 0)
      .sort((a, b) => (b.rating ?? 0) - (a.rating ?? 0))
      .slice(0, 5)
  }, [items])

  const myWeekly = useMemo(() => {
    return items
      .filter(it => it.catalogItemId && favCatalogIds.has(it.catalogItemId))
      .sort((a, b) => (a.airWeekday ?? 9) - (b.airWeekday ?? 9))
  }, [items, favCatalogIds])

  return (
    <main className="watch-page">
      <div className="sched-header">
        <div>
          <h1>新番时刻表</h1>
          <p className="muted" style={{ margin: '4px 0 0' }}>
            按放送星期追踪本季新番更新。
          </p>
        </div>
      </div>

      {/* 筛选胶囊条 */}
      <div className="sched-filter-bar">
        <div className="row-inline" role="group" aria-label="排期筛选">
          <button type="button" className={'mini ' + (filter === 'all' ? '' : 'ghost')}
                  onClick={() => setFilter('all')}>全部</button>
          <button type="button" className={'mini ' + (filter === 'covered' ? '' : 'ghost')}
                  onClick={() => setFilter('covered')}>已上线</button>
          <button type="button" className={'mini ' + (filter === 'following' ? '' : 'ghost')}
                  disabled={!me}
                  title={me ? '只看收藏的本季新番' : '登录后可用'}
                  onClick={() => setFilter('following')}>
            我的追番
          </button>
        </div>
        <Link to="/anime?category=season" className="muted" style={{ fontSize: 13 }}>
          浏览全部本季新番 →
        </Link>
      </div>

      {/* 7 列等宽周历切换条 */}
      <div className="sched-week-strip" role="tablist" aria-label="星期切换">
        {DAYS.map(d => {
          const isToday = d.id === today
          const isActive = d.id === activeDay
          return (
            <button key={d.id} type="button" role="tab" aria-selected={isActive}
                    className={'sched-day-tab' + (isActive ? ' active' : '') + (isToday ? ' today' : '')}
                    onClick={() => selectDay(d.id)}>
              <div className="sched-day-top">
                <span className="sched-day-zh">{d.zh}</span>
                <span className="sched-day-en">{isToday ? '今日' : d.en}</span>
              </div>
            </button>
          )
        })}
      </div>

      {error && <p className="error" style={{ marginTop: 14 }}>{error}</p>}

      {loading ? (
        <div className="sched-body-grid" style={{ marginTop: 22 }}>
          <div className="stack">
            <div className="skeleton-box" style={{ height: 210, borderRadius: 16 }} />
            <div className="skeleton-box" style={{ height: 150, borderRadius: 16 }} />
          </div>
          <div className="skeleton-box" style={{ height: 340, borderRadius: 16 }} />
        </div>
      ) : (
        <div className="sched-body-grid">
          {/* 左侧主排期流（8fr） */}
          <div className="sched-main">
            {dayList.length === 0 ? (
              <div className="card" style={{ padding: 28, textAlign: 'center' }}>
                <p className="muted" style={{ margin: 0 }}>
                  {weekdayName(activeDay)}在当前筛选下暂无更新——可切换其他星期或点「全部」。
                </p>
              </div>
            ) : (
              <>
                {featured.length > 0 && (
                  <section className="sched-group">
                    <div className="sched-group-head">
                      <h2>{weekdayName(activeDay)}精选</h2>
                    </div>
                    <div className="sched-featured-list">
                      {featured.map(it => {
                        const isFav = Boolean(it.catalogItemId && favCatalogIds.has(it.catalogItemId))
                        return (
                          <article key={it.subjectId} className="sched-card card">
                            <Link to={it.catalogItemId ? `/catalog/${it.catalogItemId}` : '/anime'}
                                  className="sched-poster">
                              <CoverImage src={`/api/v1/bangumi-covers/${it.subjectId}?size=large`}
                                          alt={it.nameCn || it.name} loading="eager" />
                              <span className={'badge-tl ' + (it.covered ? 'is-pub' : '')}>
                                {it.covered ? '连载中' : '待更新'}
                              </span>
                              {typeof it.rating === 'number' && it.rating > 0 && (
                                <span className="badge-bl badge-score tabular-nums">★ {it.rating.toFixed(1)}</span>
                              )}
                            </Link>
                            <div className="sched-card-body">
                              <div className="sched-card-tags">
                                <span className="sched-time-tag">{weekdayName(it.airWeekday)}放送</span>
                                {it.remarks && <span className="sched-ep-tag">{it.remarks}</span>}
                                {it.categoryName && <span className="chip ghost">{it.categoryName}</span>}
                              </div>
                              <Link to={it.catalogItemId ? `/catalog/${it.catalogItemId}` : '/anime'}
                                    className="sched-card-title">
                                {it.nameCn || it.name}
                              </Link>
                              {it.nameCn && it.name !== it.nameCn && (
                                <p className="sched-card-sub muted">{it.name}</p>
                              )}
                              <p className="sched-card-desc">
                                {it.summary || '暂无简介，点击详情查看更多信息。'}
                              </p>
                              <div className="sched-card-actions">
                                <button type="button" className="pill mini" onClick={() => play(it)}>
                                  <PlayIcon size={13} />
                                  {it.covered ? '立即播放' : '查看详情'}
                                </button>
                                {it.catalogItemId && (
                                  <button type="button" className={'pill mini ' + (isFav ? '' : 'ghost')}
                                          disabled={busyId === it.catalogItemId}
                                          onClick={() => void toggleFavorite(it)}>
                                    <HeartIcon size={13} />
                                    {isFav ? '已追番' : '追番'}
                                  </button>
                                )}
                                {it.catalogItemId && (
                                  <Link to={`/catalog/${it.catalogItemId}`} className="btn ghost mini">详情</Link>
                                )}
                              </div>
                            </div>
                          </article>
                        )
                      })}
                    </div>
                  </section>
                )}

                {compactList.length > 0 && (
                  <section className="sched-group">
                    <div className="sched-group-head">
                      <h2>更多更新</h2>
                    </div>
                    <div className="sched-compact-grid">
                      {compactList.map(it => (
                        <article key={it.subjectId} className="sched-card compact card">
                          <Link to={it.catalogItemId ? `/catalog/${it.catalogItemId}` : '/anime'}
                                className="sched-poster">
                            <CoverImage src={`/api/v1/bangumi-covers/${it.subjectId}?size=large`}
                                        alt={it.nameCn || it.name} />
                            {typeof it.rating === 'number' && it.rating > 0 && (
                              <span className="badge-bl badge-score tabular-nums">★ {it.rating.toFixed(1)}</span>
                            )}
                          </Link>
                          <div className="sched-card-body">
                            <div className="sched-card-tags">
                              <span className={'tag ' + (it.covered ? 'pub' : 'priv')}>
                                {it.covered ? (it.remarks || '连载中') : '待更新'}
                              </span>
                            </div>
                            <Link to={it.catalogItemId ? `/catalog/${it.catalogItemId}` : '/anime'}
                                  className="sched-card-title clamp">
                              {it.nameCn || it.name}
                            </Link>
                            <div className="sched-card-actions" style={{ marginTop: 'auto' }}>
                              <button type="button" className="mini" onClick={() => play(it)}>
                                {it.covered ? '立即播放' : '查看详情'}
                              </button>
                              {it.catalogItemId && (
                                <Link to={`/catalog/${it.catalogItemId}`} className="btn ghost mini">详情</Link>
                              )}
                            </div>
                          </div>
                        </article>
                      ))}
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
                  <span className="muted">本周精选</span>
                </div>
                <ol className="sched-radar-list">
                  {topPicks.map((it, idx) => (
                    <li key={it.subjectId} className="sched-radar-item">
                      <span className={'rank-no tabular-nums' + (idx < 3 ? ' top' : '')}>
                        {String(idx + 1).padStart(2, '0')}
                      </span>
                      <Link to={it.catalogItemId ? `/catalog/${it.catalogItemId}` : '/anime'}
                            className="sched-radar-thumb">
                        <CoverImage src={`/api/v1/bangumi-covers/${it.subjectId}?size=large`}
                                    alt={it.nameCn || it.name} />
                      </Link>
                      <div className="sched-radar-info">
                        <Link to={it.catalogItemId ? `/catalog/${it.catalogItemId}` : '/anime'}
                              className="sched-radar-title">
                          {it.nameCn || it.name}
                        </Link>
                        <span className="muted">
                          {weekdayName(it.airWeekday)} · {it.remarks || `${it.episodeCount ?? 0} 集`}
                        </span>
                      </div>
                      <span className="sched-radar-score tabular-nums">★ {(it.rating ?? 0).toFixed(1)}</span>
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
                  <Link to="/login?next=%2Fschedule">登录</Link>后将在此自动汇总你收藏的本季连载新番排期。
                </p>
              ) : myWeekly.length === 0 ? (
                <p className="muted" style={{ margin: 0 }}>
                  还没有收藏本季新番——在左侧卡片点「追番」即可加入本周追更表。
                </p>
              ) : (
                <ul className="sched-my-list">
                  {myWeekly.slice(0, 10).map(it => (
                    <li key={it.subjectId}>
                      <span className="sched-my-day">{weekdayName(it.airWeekday)}</span>
                      <Link to={it.catalogItemId ? `/browse/${it.catalogItemId}` : '/anime'}
                            className="sched-my-name">
                        {it.nameCn || it.name}
                      </Link>
                      <span className="muted tabular-nums">{it.remarks || ''}</span>
                    </li>
                  ))}
                </ul>
              )}
            </section>

            {/* 追番日历 .ics 订阅 */}
            <section className="card sched-side-card">
              <div className="sched-side-head">
                <h3>追番日历订阅</h3>
              </div>
              <p className="muted" style={{ margin: '0 0 12px', fontSize: 12.5 }}>
                导出周历文件，可导入 Apple 日历、Google 日历或 Outlook。
              </p>
              <div className="sched-ics-actions">
                <a className="btn mini" href={me && myWeekly.length > 0 ? '/api/v1/calendar/ics?onlyMine=true' : '/api/v1/calendar/ics?coveredOnly=true'}
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

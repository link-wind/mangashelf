import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api'
import CoverImage from '../components/CoverImage'
import { HeartIcon, InfoIcon, PlayIcon } from '../components/Icons'
import { useToast } from '../components/Toast'
import { useAuth } from '../auth'

interface CalendarEntry {
  subjectId: number; name: string; nameCn: string; coverUrl: string
  rating: number | null; airWeekday: number | null; covered: boolean
  catalogItemId: string | null; workId: string | null; summary: string | null
  remarks: string | null; episodeCount: number | null; categoryName: string | null
}
interface CatalogItem {
  id: string; name: string; coverUrl: string; remarks: string; episodeCount: number
  workId: string | null; bangumiSubjectId: number | null
}
interface ContinueRow {
  type: 'work-read' | 'manga-read' | 'animation-watch' | 'external-watch'
  title: string; coverUrl: string | null; workId: string | null; catalogItemId: string | null
  chapterId: string | null; revisionId: string | null; pageOrdinal: number | null
  episodeId: string | null; ordinal: number | null; positionMs: number | null; durationMs: number | null
}
interface AnimeCatchUpItem {
  catalogItemId: string; workId: string | null; title: string; coverUrl: string
  remarks: string; watchedOrdinal: number; latestOrdinal: number; newEpisodes: number
}
interface AnimeRecItem {
  catalogItemId: string; workId: string | null; bangumiSubjectId: number | null
  title: string; coverUrl: string; remarks: string; categoryName: string | null
  episodeCount: number; rating: number | null; matchedTags: string[]; reason: string
}
interface AnimeRecResult {
  personalized: boolean
  topPreferences: string[]
  catchUp: AnimeCatchUpItem[]
  forYou: AnimeRecItem[]
  seasonalPicks: CalendarEntry[]
  totalCandidates: number
}

const WEEKDAYS = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']

/** 首页：精选 Hero → 追更提醒与继续观看 → 为你推荐（可换一批）→ 新番时刻表速览 → 最近更新 → 本季必看榜。 */
export default function Home() {
  const { me } = useAuth()
  const [calendar, setCalendar] = useState<CalendarEntry[]>([])
  const [updates, setUpdates] = useState<CatalogItem[]>([])
  const [continueList, setContinueList] = useState<ContinueRow[]>([])
  const [rec, setRec] = useState<AnimeRecResult | null>(null)
  const [recOffset, setRecOffset] = useState(0)
  const [recLoading, setRecLoading] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const loadRecommendations = useCallback(async (offset: number) => {
    setRecLoading(true)
    try {
      const data = await api.get<AnimeRecResult>(`/api/v1/recommendations/anime?limit=12&offset=${offset}`)
      setRec(data)
    } catch { /* 推荐异常不阻断公开首页 */ }
    finally { setRecLoading(false) }
  }, [])

  useEffect(() => {
    void (async () => {
      try {
        const [cal, cat, recData] = await Promise.all([
          api.get<CalendarEntry[]>('/api/v1/calendar'),
          api.get<{ items: CatalogItem[] }>('/api/v1/catalog-items?limit=12'),
          api.get<AnimeRecResult>('/api/v1/recommendations/anime?limit=12&offset=0')
            .catch(() => null),
        ])
        setCalendar(cal)
        setUpdates(cat.items)
        setRec(recData)
        if (me) {
          try {
            const cont = await api.get<ContinueRow[]>('/api/v1/me/continue?limit=12')
            setContinueList(cont.filter(r => r.type === 'external-watch').slice(0, 8))
          } catch { /* 私有继续列表失败不影响首页 */ }
        } else {
          setContinueList([])
        }
      } catch (e) {
        setError(e instanceof Error ? e.message : '加载失败')
      } finally {
        setLoading(false)
      }
    })()
  }, [me])

  const seasonalPicks = rec?.seasonalPicks?.length
    ? rec.seasonalPicks
    : [...calendar].filter(i => (i.rating ?? 0) > 0).sort((a, b) => (b.rating ?? 0) - (a.rating ?? 0)).slice(0, 10)

  const bannerItems = seasonalPicks.slice(0, 4)

  function refreshRec() {
    const next = recOffset + 12
    setRecOffset(next)
    void loadRecommendations(next)
  }

  if (loading) {
    return (
      <main className="home-page">
        <div className="skeleton-box hero-skeleton" />
        <section style={{ marginTop: 30 }}>
          <div className="skeleton-box" style={{ width: 140, height: 22, marginBottom: 14, borderRadius: 8 }} />
          <div className="card-grid">
            {Array.from({ length: 6 }).map((_, i) => (
              <div key={i}>
                <div className="skeleton-box card-skeleton-cover" />
                <div className="skeleton-box card-skeleton-line" />
                <div className="skeleton-box card-skeleton-line short" />
              </div>
            ))}
          </div>
        </section>
      </main>
    )
  }

  const catchUp = rec?.catchUp ?? []
  const forYou = rec?.forYou ?? []

  return (
    <main className="home-page">
      <HeroBanner items={bannerItems} />

      {error && <p className="error">{error}</p>}

      {catchUp.length > 0 && (
        <section style={{ marginTop: 28 }}>
          <div className="section-head">
            <h2>
              追更提醒
              <span className="muted" style={{ fontSize: 13, fontWeight: 400, marginLeft: 8 }}>
                {catchUp.length} 部在追番剧有新集
              </span>
            </h2>
            <Link to="/me?tab=continue&kind=anime" className="muted">进入「我的」→</Link>
          </div>
          <div className="hscroll">
            {catchUp.map(item => (
              <Link key={item.catalogItemId}
                    to={item.workId ? `/works/${item.workId}` : `/browse/${item.catalogItemId}`}
                    className="work-card">
                <div className="cover">
                  <CoverImage src={item.coverUrl} alt={item.title} />
                  <span className="badge-anim badge-new">+{item.newEpisodes} 新集</span>
                </div>
                <div className="work-card-body">
                  <div className="work-card-title clamp">{item.title}</div>
                  <div className="work-card-meta">
                    <span>第 {item.watchedOrdinal} → {item.latestOrdinal} 集</span>
                  </div>
                </div>
              </Link>
            ))}
          </div>
        </section>
      )}

      {continueList.length > 0 && (
        <section style={{ marginTop: 28 }}>
          <div className="section-head">
            <h2>继续观看</h2>
            <Link to="/me?tab=continue&kind=anime" className="muted">全部记录 →</Link>
          </div>
          <div className="hscroll">
            {continueList.map(r => {
              const pct = r.durationMs && r.positionMs
                ? Math.min(99, Math.max(2, Math.round(r.positionMs / r.durationMs * 100)))
                : null
              return (
                <Link key={`${r.workId ?? r.catalogItemId}-${r.episodeId}`}
                      to={r.workId
                        ? `/works/${r.workId}/external/${r.episodeId}`
                        : `/browse/${r.catalogItemId}/${r.episodeId}`}
                      className="work-card">
                  <div className="cover">
                    <CoverImage src={r.coverUrl} alt={r.title} />
                    <span className="badge-anim">{pct !== null ? `${pct}%` : '继续'}</span>
                    {pct !== null && (
                      <div className="card-progress" aria-hidden="true">
                        <div className="card-progress-fill" style={{ width: `${pct}%` }} />
                      </div>
                    )}
                  </div>
                  <div className="work-card-body">
                    <div className="work-card-title clamp">{r.title}</div>
                    <div className="work-card-meta"><span>第 {r.ordinal} 话</span></div>
                  </div>
                </Link>
              )
            })}
          </div>
        </section>
      )}

      {forYou.length > 0 && (
        <section style={{ marginTop: 30 }}>
          <div className="section-head">
            <h2>
              {rec?.personalized ? '为你推荐' : '精选推荐'}
              {rec && rec.topPreferences.length > 0 && (
                <span className="muted" style={{ fontSize: 13, fontWeight: 400, marginLeft: 8 }}>
                  常看：{rec.topPreferences.slice(0, 3).join(' · ')}
                </span>
              )}
            </h2>
            {(rec?.totalCandidates ?? 0) > 12 && (
              <button type="button" className="mini ghost" disabled={recLoading} onClick={refreshRec}>
                {recLoading ? '刷新中…' : '换一批 ↻'}
              </button>
            )}
          </div>
          <div className="card-grid">
            {forYou.map(it => (
              <Link key={it.catalogItemId}
                    to={it.workId ? `/works/${it.workId}` : `/catalog/${it.catalogItemId}`}
                    className="work-card">
                <div className="cover">
                  <CoverImage src={it.coverUrl} alt={it.title} />
                  {typeof it.rating === 'number' && it.rating > 0 && (
                    <span className="badge-anim">★ {it.rating.toFixed(1)}</span>
                  )}
                </div>
                <div className="work-card-body">
                  <div className="work-card-title clamp">{it.title}</div>
                  <div className="work-card-meta">
                    <span className="rec-reason clamp">{it.reason}</span>
                  </div>
                </div>
              </Link>
            ))}
          </div>
        </section>
      )}

      <WeeklyPreview items={calendar} />

      <section style={{ marginTop: 30 }}>
        <div className="section-head">
          <h2>最近更新</h2>
          <Link to="/anime" className="muted">全部番剧 →</Link>
        </div>
        {updates.length === 0
          ? <p className="muted">目录为空——在设置页触发一次目录同步。</p>
          : <div className="card-grid">
              {updates.map(it => <CatalogCard key={it.id} it={it} />)}
            </div>}
      </section>

      {seasonalPicks.length > 0 && (
        <section style={{ marginTop: 30 }}>
          <div className="section-head">
            <h2>本季必看榜</h2>
            <Link to="/anime?category=season&sort=rating" className="muted">按评分浏览 →</Link>
          </div>
          <ol className="rank-list">
            {seasonalPicks.slice(0, 10).map((it, i) => (
              <li key={it.subjectId} className="rank-item">
                <span className={'rank-no' + (i < 3 ? ' top' : '')}>{i + 1}</span>
                <img src={`/api/v1/bangumi-covers/${it.subjectId}?size=small`} alt=""
                     loading="lazy" decoding="async" referrerPolicy="no-referrer"
                     onError={e => { (e.target as HTMLImageElement).style.visibility = 'hidden' }} />
                <Link className="rank-name clamp"
                      to={it.catalogItemId ? `/catalog/${it.catalogItemId}` : '/anime'}>
                  {it.nameCn || it.name}
                </Link>
                {typeof it.rating === 'number' && <span className="star">★ {it.rating.toFixed(1)}</span>}
                {it.airWeekday ? <span className="muted">{WEEKDAYS[it.airWeekday]}</span> : null}
                {it.covered
                  ? <span className="tag pub">可看</span>
                  : <span className="tag priv">未收录</span>}
              </li>
            ))}
          </ol>
        </section>
      )}
    </main>
  )
}

/** 首屏 Banner：4 张淡入轮播，优先可播 + 贝叶斯高分 + 近期更新条目。 */
function HeroBanner({ items }: { items: CalendarEntry[] }) {
  const navigate = useNavigate()
  const { showToast } = useToast()
  const [idx, setIdx] = useState(0)
  const [paused, setPaused] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)

  useEffect(() => {
    if (items.length < 2 || paused) return
    const t = setInterval(() => setIdx(i => (i + 1) % items.length), 7000)
    return () => clearInterval(t)
  }, [items.length, paused])

  function play(it: CalendarEntry) {
    if (it.workId) { navigate(`/works/${it.workId}`); return }
    if (!it.catalogItemId) { navigate('/anime'); return }
    navigate(`/browse/${it.catalogItemId}`)
  }

  async function subscribe(it: CalendarEntry) {
    if (!it.catalogItemId) {
      showToast('该条目暂未收录到资源目录', 'info')
      return
    }
    setBusy(it.catalogItemId)
    try {
      await api.put(`/api/v1/me/favorites/catalog/${it.catalogItemId}`)
      showToast(`已收藏「${it.nameCn || it.name}」`, 'success')
    } catch (e) {
      showToast(e instanceof Error ? e.message : '收藏失败', 'error')
    } finally {
      setBusy(null)
    }
  }

  if (items.length === 0) {
    return (
      <section className="hero">
        <div>
          <h1>MangaShelf</h1>
          <p className="muted">追动画、读漫画、全流检索——你的个人影音漫画空间。</p>
        </div>
        <div className="hero-search">
          <Link to="/anime" className="btn ghost pill">番剧索引 →</Link>
        </div>
      </section>
    )
  }

  const it = items[idx] ?? items[0]
  const isBusy = busy !== null && busy === it.catalogItemId
  const canPlay = Boolean(it.workId || it.catalogItemId)
  return (
    <section className="hero-slide"
             onMouseEnter={() => setPaused(true)}
             onMouseLeave={() => setPaused(false)}>
      <div key={it.subjectId} className="hero-bg"
           style={{ backgroundImage: `url(/api/v1/bangumi-covers/${it.subjectId}?size=large)` }} />
      <div className="hero-shade" />

      <div className="hero-content">
        <div className="hero-badges">
          <span className="hb hb-tv">TV</span>
          <span className="hb hb-on">本季精选</span>
          {it.airWeekday ? <span className="hb hb-time">每周{WEEKDAYS[it.airWeekday].slice(1)}放送</span> : null}
        </div>
        <h1>{it.nameCn || it.name}</h1>
        {it.nameCn && it.name !== it.nameCn && <p className="hero-sub">{it.name}</p>}
        <div className="hero-meta">
          {typeof it.rating === 'number' && <span className="star">★ Bangumi {it.rating.toFixed(1)}</span>}
          {it.categoryName && <span>{it.categoryName}</span>}
          {it.episodeCount ? <span>已更 {it.episodeCount} 集</span> : null}
          {it.remarks && <span>{it.remarks}</span>}
        </div>
        {it.summary && <p className="hero-desc">{it.summary}</p>}
        <div className="hero-actions">
          <button type="button" className="pill hero-play" disabled={isBusy}
                  onClick={() => play(it)}>
            <PlayIcon size={15} />
            {canPlay ? (it.workId ? '立即播放' : '播放') : '浏览番剧库'}
          </button>
          <button type="button" className="pill hero-sub-btn" disabled={isBusy}
                  onClick={() => void subscribe(it)}>
            <HeartIcon size={15} />
            {isBusy ? '处理中…' : '收藏'}
          </button>
          {it.catalogItemId && (
            <Link to={`/catalog/${it.catalogItemId}`} className="hero-info" title="详情" aria-label="查看详情">
              <InfoIcon size={17} />
            </Link>
          )}
        </div>
      </div>

      <div className="hero-thumbs">
        {items.map((x, i) => (
          <button type="button" key={x.subjectId} className={'hero-thumb' + (i === idx ? ' on' : '')}
                  onClick={() => setIdx(i)} title={x.nameCn || x.name}>
            <CoverImage src={`/api/v1/bangumi-covers/${x.subjectId}?size=small`}
                        alt={x.nameCn || x.name} loading="eager" />
            <span className="hero-thumb-title">{x.nameCn || x.name}</span>
          </button>
        ))}
      </div>
    </section>
  )
}

/** 新番时刻表预览：星期切换 + 当日列表。 */
function WeeklyPreview({ items }: { items: CalendarEntry[] }) {
  const today = new Date().getDay() || 7
  const [day, setDay] = useState(today)
  if (items.length === 0) return null
  const list = items
    .filter(i => (i.airWeekday ?? 0) === day)
    .sort((a, b) => (b.rating ?? 0) - (a.rating ?? 0))
  return (
    <section style={{ marginTop: 28 }}>
      <div className="section-head">
        <h2>
          新番时刻表{' '}
          <span className="muted" style={{ fontSize: 13, fontWeight: 400 }}>
            {WEEKDAYS[day]}放送 {list.length} 部
          </span>
        </h2>
        <Link to="/schedule" className="muted">完整时刻表 →</Link>
      </div>
      <div className="row-inline" style={{ marginBottom: 12 }}>
        {[1, 2, 3, 4, 5, 6, 7].map(d => (
          <button type="button" key={d} className={d === day ? 'mini' : 'mini ghost'} onClick={() => setDay(d)}>
            {WEEKDAYS[d]}
          </button>
        ))}
      </div>
      <div className="hscroll">
        {list.map(it => (
          <Link key={it.subjectId}
                to={it.workId ? `/works/${it.workId}` : (it.catalogItemId ? `/browse/${it.catalogItemId}` : '/anime')}
                className="work-card">
            <div className="cover">
              <CoverImage src={`/api/v1/bangumi-covers/${it.subjectId}?size=common`} alt={it.nameCn || it.name} />
              {it.covered && <span className="badge-anim">可看</span>}
            </div>
            <div className="work-card-body">
              <div className="work-card-title clamp">{it.nameCn || it.name}</div>
              <div className="work-card-meta">
                {typeof it.rating === 'number' && <span className="star">★ {it.rating.toFixed(1)}</span>}
                {it.remarks && <span>{it.remarks}</span>}
              </div>
            </div>
          </Link>
        ))}
        {list.length === 0 && <p className="muted">这一天暂无放送条目。</p>}
      </div>
    </section>
  )
}

function CatalogCard({ it }: { it: CatalogItem }) {
  const navigate = useNavigate()
  return (
    <button type="button" className="work-card ext-card"
            onClick={() => navigate(it.workId ? `/works/${it.workId}` : `/browse/${it.id}`)}>
      <div className="cover">
        <CoverImage
          src={it.bangumiSubjectId ? `/api/v1/bangumi-covers/${it.bangumiSubjectId}?size=common` : it.coverUrl}
          alt={it.name}
        />
        {it.workId
          ? <span className="badge-anim" style={{ background: 'var(--pub)' }}>在库</span>
          : <span className="badge-anim">可播</span>}
      </div>
      <div className="work-card-body">
        <div className="work-card-title clamp">{it.name}</div>
        <div className="work-card-meta">
          <span>{it.remarks || `${it.episodeCount} 集`}</span>
        </div>
      </div>
    </button>
  )
}

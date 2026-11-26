import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api'
import AnimePosterCard from '../components/AnimePosterCard'
import CoverImage from '../components/CoverImage'
import { HeartIcon, InfoIcon, PlayIcon } from '../components/Icons'
import { useToast } from '../components/Toast'
import { useAuth } from '../auth'
import { useCatalogFavorites } from '../useCatalogFavorites'

interface CalendarEntry {
  subjectId: number; name: string; nameCn: string; coverUrl: string
  bannerUrl?: string | null
  rating: number | null; airWeekday: number | null; covered: boolean
  catalogItemId: string | null; workId: string | null; summary: string | null
  remarks: string | null; episodeCount: number | null; categoryName: string | null
}
interface CatalogItem {
  id: string; name: string; coverUrl: string; remarks: string; episodeCount: number
  workId: string | null; bangumiSubjectId: number | null; categoryName?: string | null
}
interface AnimeRecItem {
  catalogItemId: string; workId: string | null; bangumiSubjectId: number | null
  title: string; coverUrl: string; remarks: string; categoryName: string | null
  episodeCount: number; rating: number | null; matchedTags: string[]; reason: string
}
interface AnimeRecResult {
  personalized: boolean
  topPreferences: string[]
  forYou: AnimeRecItem[]
  seasonalPicks: CalendarEntry[]
  totalCandidates: number
}
interface GenreStat { tag: string; items: number }

type RankTab = 'seasonal' | 'updated' | 'episodes'

const WEEKDAYS = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']
const FALLBACK_GENRES: GenreStat[] = [
  { tag: '热血', items: 0 }, { tag: '奇幻', items: 0 }, { tag: '冒险', items: 0 },
  { tag: '恋爱', items: 0 }, { tag: '搞笑', items: 0 }, { tag: '悬疑', items: 0 },
  { tag: '科幻', items: 0 }, { tag: '治愈', items: 0 }, { tag: '校园', items: 0 },
  { tag: '日常', items: 0 },
]

/** 首页：高清 Hero → 70/30 推荐网格+多榜侧栏 → 新番时刻表速览 → 完结补番精选 → 最近更新 → 热门题材探索。 */
export default function Home() {
  const { me } = useAuth()
  const { favIds, busyId, toggle: toggleFav } = useCatalogFavorites()
  const [calendar, setCalendar] = useState<CalendarEntry[]>([])
  const [updates, setUpdates] = useState<CatalogItem[]>([])
  const [completed, setCompleted] = useState<CatalogItem[]>([])
  const [topEpisodes, setTopEpisodes] = useState<CatalogItem[]>([])
  const [genres, setGenres] = useState<GenreStat[]>([])
  const [rec, setRec] = useState<AnimeRecResult | null>(null)
  const [recOffset, setRecOffset] = useState(0)
  const [recLoading, setRecLoading] = useState(false)
  const [rankTab, setRankTab] = useState<RankTab>('seasonal')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const loadRecommendations = useCallback(async (offset: number) => {
    setRecLoading(true)
    try {
      const data = await api.get<AnimeRecResult>(`/api/v1/recommendations/anime?limit=8&offset=${offset}`)
      setRec(data)
    } catch { /* 推荐异常不阻断公开首页 */ }
    finally { setRecLoading(false) }
  }, [])

  useEffect(() => {
    void (async () => {
      try {
        const [cal, cat, comp, epRank, recData, genreList] = await Promise.all([
          api.get<CalendarEntry[]>('/api/v1/calendar'),
          api.get<{ items: CatalogItem[] }>('/api/v1/catalog-items?limit=14&page=1'),
          api.get<{ items: CatalogItem[] }>('/api/v1/catalog-items?limit=10&page=1&status=completed&sort=rating')
            .catch(() => ({ items: [] })),
          api.get<{ items: CatalogItem[] }>('/api/v1/catalog-items?limit=8&page=1&sort=episodes')
            .catch(() => ({ items: [] })),
          api.get<AnimeRecResult>('/api/v1/recommendations/anime?limit=8&offset=0')
            .catch(() => null),
          api.get<GenreStat[]>('/api/v1/catalog-items/genres')
            .catch(() => []),
        ])
        setCalendar(cal)
        setUpdates(cat.items)
        setCompleted(comp.items)
        setTopEpisodes(epRank.items)
        setRec(recData)
        setGenres(genreList.length > 0 ? genreList : FALLBACK_GENRES)
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
    const next = recOffset + 8
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
            {Array.from({ length: 7 }).map((_, i) => (
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

  const forYou = rec?.forYou ?? []

  return (
    <main className="home-page">
      <HeroBanner items={bannerItems} favIds={favIds} onToggleFav={toggleFav} />

      {error && <p className="error">{error}</p>}

      {/* 70/30 非对称探索区：左侧 4×2 推荐网格 + 右侧多 Tab 榜单卡片 */}
      {(forYou.length > 0 || seasonalPicks.length > 0) && (
        <section className="home-split-grid" style={{ marginTop: 30 }}>
          <div className="home-split-main">
            <div className="section-head">
              <h2>
                {rec?.personalized ? '为你推荐' : '精选推荐'}
                {rec && rec.topPreferences.length > 0 && (
                  <span className="muted" style={{ fontSize: 13, fontWeight: 400, marginLeft: 8 }}>
                    常看：{rec.topPreferences.slice(0, 3).join(' · ')}
                  </span>
                )}
              </h2>
              {(rec?.totalCandidates ?? 0) > 8 && (
                <button type="button" className="mini ghost" disabled={recLoading} onClick={refreshRec}>
                  {recLoading ? '刷新中…' : '换一批 ↻'}
                </button>
              )}
            </div>
            <div className="card-grid card-grid-4">
              {forYou.map(it => (
                <AnimePosterCard
                  key={it.catalogItemId}
                  title={it.title}
                  coverUrl={it.coverUrl}
                  detailHref={it.workId ? `/works/${it.workId}` : `/catalog/${it.catalogItemId}`}
                  playHref={it.workId ? `/works/${it.workId}` : `/browse/${it.catalogItemId}`}
                  topLeft={it.remarks?.includes('完结') ? '已完结' : '连载中'}
                  rating={it.rating}
                  bottomLeft={it.categoryName}
                  bottomRight={it.remarks || `${it.episodeCount} 集`}
                  metaLeft={it.categoryName || '番剧'}
                  metaTag={it.reason}
                  favorited={favIds.has(it.catalogItemId)}
                  busy={busyId === it.catalogItemId}
                  onToggleFavorite={() => void toggleFav(it.catalogItemId, it.title)}
                />
              ))}
            </div>
          </div>

          <aside className="card home-rank-card">
            <div className="home-rank-head">
              <h2>站内榜单</h2>
              <div className="home-rank-tabs" role="tablist" aria-label="榜单切换">
                <button type="button" role="tab" aria-selected={rankTab === 'seasonal'}
                        className={'mini ' + (rankTab === 'seasonal' ? '' : 'ghost')}
                        onClick={() => setRankTab('seasonal')}>高分</button>
                <button type="button" role="tab" aria-selected={rankTab === 'updated'}
                        className={'mini ' + (rankTab === 'updated' ? '' : 'ghost')}
                        onClick={() => setRankTab('updated')}>热更</button>
                <button type="button" role="tab" aria-selected={rankTab === 'episodes'}
                        className={'mini ' + (rankTab === 'episodes' ? '' : 'ghost')}
                        onClick={() => setRankTab('episodes')}>长篇</button>
              </div>
            </div>

            {rankTab === 'seasonal' ? (
              <ol className="home-rank-list">
                {seasonalPicks.slice(0, 8).map((it, i) => (
                  <li key={it.subjectId} className="home-rank-row">
                    <span className={'rank-no tabular-nums' + (i < 3 ? ' top' : '')}>
                      {String(i + 1).padStart(2, '0')}
                    </span>
                    <Link to={it.catalogItemId ? `/catalog/${it.catalogItemId}` : '/index'} className="home-rank-thumb">
                      <CoverImage src={`/api/v1/bangumi-covers/${it.subjectId}?size=large`} alt={it.nameCn || it.name} />
                    </Link>
                    <div className="home-rank-info">
                      <Link to={it.catalogItemId ? `/catalog/${it.catalogItemId}` : '/index'} className="home-rank-title">
                        {it.nameCn || it.name}
                      </Link>
                      <span className="muted">
                        {it.airWeekday ? WEEKDAYS[it.airWeekday] : '本季'} · {it.remarks || (it.covered ? '连载中' : '待更新')}
                      </span>
                    </div>
                    {typeof it.rating === 'number' && (
                      <span className="home-rank-metric tabular-nums">★ {it.rating.toFixed(1)}</span>
                    )}
                  </li>
                ))}
              </ol>
            ) : (
              <ol className="home-rank-list">
                {(rankTab === 'updated' ? updates : topEpisodes).slice(0, 8).map((it, i) => (
                  <li key={it.id} className="home-rank-row">
                    <span className={'rank-no tabular-nums' + (i < 3 ? ' top' : '')}>
                      {String(i + 1).padStart(2, '0')}
                    </span>
                    <Link to={`/catalog/${it.id}`} className="home-rank-thumb">
                      <CoverImage
                        src={it.bangumiSubjectId ? `/api/v1/bangumi-covers/${it.bangumiSubjectId}?size=large` : it.coverUrl}
                        alt={it.name}
                      />
                    </Link>
                    <div className="home-rank-info">
                      <Link to={`/catalog/${it.id}`} className="home-rank-title">{it.name}</Link>
                      <span className="muted">{it.categoryName || '动漫'} · {it.remarks || `${it.episodeCount} 集`}</span>
                    </div>
                    <span className="home-rank-metric tabular-nums">{it.episodeCount} 集</span>
                  </li>
                ))}
              </ol>
            )}
          </aside>
        </section>
      )}

      <WeeklyPreview items={calendar} favIds={favIds} busyId={busyId} onToggleFav={toggleFav} />

      {/* 完结补番精选（一口气看完） */}
      {completed.length > 0 && (
        <section style={{ marginTop: 30 }}>
          <div className="section-head">
            <h2>
              完结补番精选
              <span className="muted" style={{ fontSize: 13, fontWeight: 400, marginLeft: 8 }}>
                一口气看完 · 高分完结
              </span>
            </h2>
            <Link to="/index?status=completed&sort=rating" className="muted">全部完结番剧 →</Link>
          </div>
          <div className="hscroll">
            {completed.map(it => (
              <AnimePosterCard
                key={it.id}
                title={it.name}
                coverUrl={it.bangumiSubjectId ? `/api/v1/bangumi-covers/${it.bangumiSubjectId}?size=large` : it.coverUrl}
                detailHref={it.workId ? `/works/${it.workId}` : `/catalog/${it.id}`}
                playHref={it.workId ? `/works/${it.workId}` : `/browse/${it.id}`}
                topLeft="已完结"
                bottomLeft={it.categoryName}
                bottomRight={it.remarks || `全 ${it.episodeCount} 集`}
                metaLeft={it.categoryName || '完结番剧'}
                metaTag="一口气看完"
                favorited={favIds.has(it.id)}
                busy={busyId === it.id}
                onToggleFavorite={() => void toggleFav(it.id, it.name)}
              />
            ))}
          </div>
        </section>
      )}

      <section style={{ marginTop: 30 }}>
        <div className="section-head">
          <h2>最近更新</h2>
          <Link to="/index" className="muted">全部番剧 →</Link>
        </div>
        {updates.length === 0
          ? <p className="muted">暂无更新内容。</p>
          : <div className="card-grid">
              {updates.map(it => (
                <AnimePosterCard
                  key={it.id}
                  title={it.name}
                  coverUrl={it.bangumiSubjectId ? `/api/v1/bangumi-covers/${it.bangumiSubjectId}?size=large` : it.coverUrl}
                  detailHref={it.workId ? `/works/${it.workId}` : `/catalog/${it.id}`}
                  playHref={it.workId ? `/works/${it.workId}` : `/browse/${it.id}`}
                  topLeft={it.remarks?.includes('完结') ? '已完结' : '连载中'}
                  topLeftVariant="dark"
                  bottomRight={it.remarks || `${it.episodeCount} 集`}
                  metaLeft={it.categoryName || '番剧'}
                  metaTag={it.episodeCount > 0 ? `${it.episodeCount} 集` : undefined}
                  favorited={favIds.has(it.id)}
                  busy={busyId === it.id}
                  onToggleFavorite={() => void toggleFav(it.id, it.name)}
                />
              ))}
            </div>}
      </section>

      {/* 热门题材探索 */}
      <section className="card home-explore-card" style={{ marginTop: 32 }}>
        <div className="section-head" style={{ marginBottom: 10 }}>
          <h2>热门题材探索</h2>
          <div className="row-inline">
            <Link to="/index?sort=rating" className="muted">高分番剧 →</Link>
            <Link to="/index?kind=manga" className="muted">漫画索引 →</Link>
          </div>
        </div>
        <div className="explore-tags">
          {genres.slice(0, 16).map(g => (
            <Link key={g.tag} to={`/index?genre=${encodeURIComponent(g.tag)}`} className="explore-tag-pill">
              <span>{g.tag}</span>
            </Link>
          ))}
        </div>
      </section>
    </main>
  )
}

/** 首屏 Banner：4 张淡入轮播，优先可播 + 贝叶斯高分 + 近期更新条目。 */
function HeroBanner({ items, favIds, onToggleFav }: {
  items: CalendarEntry[]
  favIds: Set<string>
  onToggleFav: (id: string | null | undefined, title: string) => Promise<void>
}) {
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
    if (!it.catalogItemId) { navigate('/index'); return }
    navigate(`/browse/${it.catalogItemId}`)
  }

  async function subscribe(it: CalendarEntry) {
    if (!it.catalogItemId) {
      showToast('该作品暂未上线', 'info')
      return
    }
    setBusy(it.catalogItemId)
    try {
      await onToggleFav(it.catalogItemId, it.nameCn || it.name)
    } finally {
      setBusy(null)
    }
  }

  if (items.length === 0) {
    return (
      <section className="hero">
        <div>
          <h1>MangaShelf</h1>
          <p className="muted">追动画、读漫画，简洁无广告的私人影音漫画空间。</p>
        </div>
        <div className="hero-search">
          <Link to="/index" className="btn ghost pill">浏览索引 →</Link>
        </div>
      </section>
    )
  }

  const it = items[idx] ?? items[0]
  const isBusy = busy !== null && busy === it.catalogItemId
  const isFav = Boolean(it.catalogItemId && favIds.has(it.catalogItemId))
  const canPlay = Boolean(it.workId || it.catalogItemId)
  const hasBanner = Boolean(it.bannerUrl)
  const posterUrl = `/api/v1/bangumi-covers/${it.subjectId}?size=large`
  const detailHref = it.workId ? `/works/${it.workId}` : (it.catalogItemId ? `/catalog/${it.catalogItemId}` : '/index')
  return (
    <section className={'hero-slide' + (hasBanner ? ' has-banner' : ' has-poster')}
             onMouseEnter={() => setPaused(true)}
             onMouseLeave={() => setPaused(false)}>
      <div key={it.subjectId}
           className={'hero-bg' + (hasBanner ? ' is-landscape' : ' is-ambient')}
           style={{ backgroundImage: `url(${hasBanner ? it.bannerUrl : posterUrl})` }} />
      <div className="hero-shade" />

      {!hasBanner && (
        <Link to={detailHref} className="hero-poster-showcase"
              aria-label={`查看《${it.nameCn || it.name}》详情`}>
          <CoverImage src={posterUrl} alt={it.nameCn || it.name} loading="eager" fetchPriority="high" />
        </Link>
      )}

      <div className="hero-content">
        <div className="hero-badges">
          <span className="hb hb-tv">TV</span>
          <span className="hb hb-on">本季精选</span>
          {it.airWeekday ? <span className="hb hb-time">每周{WEEKDAYS[it.airWeekday].slice(1)}放送</span> : null}
        </div>
        <h1>{it.nameCn || it.name}</h1>
        {it.nameCn && it.name !== it.nameCn && <p className="hero-sub">{it.name}</p>}
        <div className="hero-meta tabular-nums">
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
            <HeartIcon size={15} filled={isFav} />
            {isBusy ? '处理中…' : (isFav ? '已追番' : '追番')}
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
            <CoverImage src={x.bannerUrl || `/api/v1/bangumi-covers/${x.subjectId}?size=large`}
                        alt={x.nameCn || x.name} loading="eager" />
            <span className="hero-thumb-title">{x.nameCn || x.name}</span>
          </button>
        ))}
      </div>
    </section>
  )
}

/** 新番时刻表预览：星期切换 + 当日等比高清卡片（带悬浮播放+追番按钮）。 */
function WeeklyPreview({ items, favIds, busyId, onToggleFav }: {
  items: CalendarEntry[]
  favIds: Set<string>
  busyId: string | null
  onToggleFav: (id: string | null | undefined, title: string) => Promise<void>
}) {
  const today = new Date().getDay() || 7
  const [day, setDay] = useState(today)
  if (items.length === 0) return null
  const list = items
    .filter(i => (i.airWeekday ?? 0) === day)
    .sort((a, b) => (b.rating ?? 0) - (a.rating ?? 0))
  return (
    <section style={{ marginTop: 30 }}>
      <div className="section-head">
        <h2>新番时刻表</h2>
        <div className="row-inline">
          {[1, 2, 3, 4, 5, 6, 7].map(d => (
            <button type="button" key={d} className={d === day ? 'mini' : 'mini ghost'} onClick={() => setDay(d)}>
              {WEEKDAYS[d]}
            </button>
          ))}
          <Link to={`/schedule?day=${day}`} className="muted" style={{ marginLeft: 4 }}>完整时刻表 →</Link>
        </div>
      </div>
      <div className="hscroll">
        {list.map(it => {
          const title = it.nameCn || it.name
          const detailHref = it.workId ? `/works/${it.workId}` : (it.catalogItemId ? `/catalog/${it.catalogItemId}` : `/index?q=${encodeURIComponent(title)}`)
          const playHref = it.workId ? `/works/${it.workId}` : (it.catalogItemId ? `/browse/${it.catalogItemId}` : `/index?q=${encodeURIComponent(title)}`)
          return (
            <AnimePosterCard
              key={it.subjectId}
              title={title}
              coverUrl={`/api/v1/bangumi-covers/${it.subjectId}?size=large`}
              detailHref={detailHref}
              playHref={playHref}
              topLeft={WEEKDAYS[day]}
              topLeftVariant="dark"
              rating={it.rating}
              bottomRight={it.remarks || (it.episodeCount ? `更新至 ${it.episodeCount} 集` : null)}
              metaLeft={it.categoryName || WEEKDAYS[day]}
              metaTag={it.covered ? '连载中' : '待更新'}
              canFavorite={Boolean(it.catalogItemId)}
              favorited={Boolean(it.catalogItemId && favIds.has(it.catalogItemId))}
              busy={busyId === it.catalogItemId}
              onToggleFavorite={() => void onToggleFav(it.catalogItemId, title)}
            />
          )
        })}
        {list.length === 0 && <p className="muted">这一天暂无放送条目。</p>}
      </div>
    </section>
  )
}

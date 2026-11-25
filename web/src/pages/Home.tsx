import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api'
import CoverImage from '../components/CoverImage'
import { HeartIcon, InfoIcon, PlayIcon } from '../components/Icons'
import { useToast } from '../components/Toast'
import { useAuth } from '../auth'
import SeasonSection from './SeasonSection'

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
  title: string
  coverUrl: string | null
  workId: string | null
  catalogItemId: string | null
  chapterId: string | null
  revisionId: string | null
  pageOrdinal: number | null
  episodeId: string | null
  ordinal: number | null
  positionMs: number | null
  durationMs: number | null
}
interface MangaCard {
  id: string; title: string; description: string; visibility: string
  chapterCount: number; latestOrdinal: number | null; coverUrl: string | null; updatedAt: string
}
interface MangaContinue {
  chapterId: string; revisionId: string; pageOrdinal: number; workId: string; title: string; coverUrl: string | null
}
interface WorkCard {
  id: string; title: string; description: string; status: string; visibility: string
  animations: number; episodes: number; posterUrl: string | null
}

const WEEKDAYS = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']
const CATEGORIES = [
  { id: 29, label: '国产动漫' },
  { id: 30, label: '日韩动漫' },
  { id: 31, label: '欧美动漫' },
  { id: 32, label: '港台动漫' },
  { id: 33, label: '海外动漫' },
]

/** 首页：骨架屏 → 液态玻璃 Hero 轮播 → 新番时刻表 → 继续观看/阅读（含进度条）→ 最近更新 → 高分榜 → 分类。 */
export default function Home() {
  const { me } = useAuth()
  const [calendar, setCalendar] = useState<CalendarEntry[]>([])
  const [updates, setUpdates] = useState<CatalogItem[]>([])
  const [continueList, setContinueList] = useState<ContinueRow[]>([])
  const [works, setWorks] = useState<WorkCard[]>([])
  const [manga, setManga] = useState<MangaCard[]>([])
  const [mangaContinue, setMangaContinue] = useState<MangaContinue[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    void (async () => {
      try {
        const [cal, cat, page, mg] = await Promise.all([
          api.get<CalendarEntry[]>('/api/v1/calendar'),
          api.get<{ items: CatalogItem[] }>('/api/v1/catalog-items?limit=12'),
          api.get<{ items: WorkCard[] }>('/api/v1/works?limit=12'),
          api.get<{ items: MangaCard[] }>('/api/v1/manga?limit=12'),
        ])
        setCalendar(cal)
        setUpdates(cat.items)
        setWorks(page.items)
        setManga(mg.items)
        // 继续观看/阅读是私有数据；访客跳过，不影响公开首页。
        if (me) {
          try {
            const cont = await api.get<ContinueRow[]>('/api/v1/me/continue?limit=12')
            setContinueList(cont.filter(r => r.type === 'external-watch').slice(0, 6))
            setMangaContinue(cont.filter(r => r.type === 'work-read' && r.chapterId && r.revisionId)
              .slice(0, 6)
              .map(r => ({
                chapterId: r.chapterId as string, revisionId: r.revisionId as string,
                pageOrdinal: r.pageOrdinal ?? 1, workId: r.workId as string, title: r.title,
                coverUrl: r.coverUrl,
              })))
          } catch { /* 私有继续列表失败不影响首页 */ }
        }
      } catch (e) {
        setError(e instanceof Error ? e.message : '加载失败')
      } finally {
        setLoading(false)
      }
    })()
  }, [me])

  const topRated = [...calendar]
    .filter(i => (i.rating ?? 0) > 0)
    .sort((a, b) => (b.rating ?? 0) - (a.rating ?? 0))
    .slice(0, 10)

  const todayDow = new Date().getDay() || 7
  const yesterdayDow = (((todayDow) + 5) % 7) + 1
  const recentCovered = calendar
    .filter(i => i.covered && (i.rating ?? 0) > 0
                 && (i.airWeekday === yesterdayDow || i.airWeekday === todayDow))
    .sort((a, b) => (b.rating ?? 0) - (a.rating ?? 0))
  const topCovered = [...calendar]
    .filter(i => i.covered && (i.rating ?? 0) > 0)
    .sort((a, b) => (b.rating ?? 0) - (a.rating ?? 0))
  const bannerPool = recentCovered.length >= 4
    ? recentCovered
    : (topCovered.length >= 4 ? topCovered : topRated)
  const bannerItems = bannerPool.slice(0, 4)

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

  return (
    <main className="home-page">
      <HeroBanner items={bannerItems} />

      {error && <p className="error">{error}</p>}

      <WeeklyPreview items={calendar} />

      {continueList.length > 0 && (
        <section style={{ marginTop: 30 }}>
          <div className="section-head">
            <h2>继续观看</h2>
            <Link to="/history" className="muted">观看历史 →</Link>
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

      {(manga.length > 0 || mangaContinue.length > 0) && (
        <section style={{ marginTop: 30 }}>
          <div className="section-head">
            <h2>漫画{mangaContinue.length > 0 ? ' · 继续阅读' : ''}</h2>
            <Link to="/manga" className="muted">漫画专区 →</Link>
          </div>
          {mangaContinue.length > 0 && (
            <div className="hscroll" style={{ marginBottom: 12 }}>
              {mangaContinue.map(r => (
                <Link key={`${r.workId}-${r.chapterId}`}
                      to={`/read/${r.revisionId}?chapter=${r.chapterId}&resume=${r.pageOrdinal}`}
                      className="work-card">
                  <div className="cover">
                    <CoverImage src={r.coverUrl} alt={r.title} />
                    <span className="badge-anim">读到 {r.pageOrdinal} 页</span>
                    <div className="card-progress" aria-hidden="true">
                      <div className="card-progress-fill" style={{ width: `${Math.min(100, Math.max(12, r.pageOrdinal * 5))}%` }} />
                    </div>
                  </div>
                  <div className="work-card-body">
                    <div className="work-card-title clamp">{r.title}</div>
                    <div className="work-card-meta"><span>继续阅读</span></div>
                  </div>
                </Link>
              ))}
            </div>
          )}
          <div className="card-grid">
            {manga.map(m => (
              <Link key={m.id} to={`/manga/${m.id}`} className="work-card">
                <div className="cover">
                  <CoverImage src={m.coverUrl} alt={m.title} />
                </div>
                <div className="work-card-body">
                  <div className="work-card-title clamp">{m.title}</div>
                  <div className="work-card-meta">
                    <span>{m.chapterCount} 话</span>
                    {m.latestOrdinal != null && <span>更新至第 {m.latestOrdinal} 话</span>}
                  </div>
                </div>
              </Link>
            ))}
          </div>
        </section>
      )}

      <section style={{ marginTop: 30 }}>
        <div className="section-head">
          <h2>最近更新</h2>
          <Link to="/anime" className="muted">查看全部 →</Link>
        </div>
        {updates.length === 0
          ? <p className="muted">目录为空——在设置页触发一次目录同步。</p>
          : <div className="card-grid">
              {updates.map(it => <CatalogCard key={it.id} it={it} />)}
            </div>}
      </section>

      {topRated.length > 0 && (
        <section style={{ marginTop: 30 }}>
          <div className="section-head">
            <h2>本季高分榜</h2>
            <Link to="/schedule" className="muted">查看时刻表 →</Link>
          </div>
          <ol className="rank-list">
            {topRated.map((it, i) => (
              <li key={it.subjectId} className="rank-item">
                <span className={'rank-no' + (i < 3 ? ' top' : '')}>{i + 1}</span>
                <img src={`/api/v1/bangumi-covers/${it.subjectId}?size=small`} alt=""
                     loading="lazy" decoding="async" referrerPolicy="no-referrer"
                     onError={e => { (e.target as HTMLImageElement).style.visibility = 'hidden' }} />
                <Link className="rank-name clamp"
                      to={it.catalogItemId ? `/catalog/${it.catalogItemId}` : '/anime'}>
                  {it.nameCn || it.name}
                </Link>
                <span className="star">★ {(it.rating ?? 0).toFixed(1)}</span>
                {it.airWeekday ? <span className="muted">{WEEKDAYS[it.airWeekday]}</span> : null}
                {it.covered
                  ? <span className="tag pub">可看</span>
                  : <span className="tag priv">未收录</span>}
              </li>
            ))}
          </ol>
        </section>
      )}

      <section style={{ marginTop: 30 }}>
        <SeasonSection layout="scroll" limit={12} />
      </section>

      {works.length > 0 && (
        <section style={{ marginTop: 30 }}>
          <div className="section-head">
            <h2>我的作品</h2>
            <Link to="/shelf" className="muted">我的收藏 →</Link>
          </div>
          <div className="card-grid">
            {works.map(w => (
              <Link key={w.id} to={`/works/${w.id}`} className="work-card">
                <div className="cover">
                  <CoverImage src={w.posterUrl} alt={w.title} />
                </div>
                <div className="work-card-body">
                  <div className="work-card-title clamp">{w.title}</div>
                  <div className="work-card-meta">
                    {w.animations > 0 && <span>动画 {w.animations}</span>}
                    {w.episodes > 0 && <span>外部 {w.episodes} 集</span>}
                  </div>
                </div>
              </Link>
            ))}
          </div>
        </section>
      )}

      <section style={{ marginTop: 30 }}>
        <div className="section-head"><h2>按分类浏览</h2></div>
        <div className="row-inline">
          {CATEGORIES.map(c => (
            <Link key={c.id} to={`/anime?category=${c.id}`} className="btn ghost pill">{c.label}</Link>
          ))}
        </div>
      </section>
    </main>
  )
}

/** 首屏 Banner：4 张淡入轮播，液态玻璃悬浮控件与缩略图托盘。 */
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
      showToast(`已追番「${it.nameCn || it.name}」`, 'success')
    } catch (e) {
      showToast(e instanceof Error ? e.message : '追番失败', 'error')
    } finally {
      setBusy(null)
    }
  }

  if (items.length === 0) {
    return (
      <section className="hero">
        <div>
          <h1>MangaShelf</h1>
          <p className="muted">读漫画、追改编动画、按剧情找内容——你的私人收藏。</p>
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
          <span className="hb hb-on">连载中</span>
          {it.airWeekday ? <span className="hb hb-time">每周{WEEKDAYS[it.airWeekday].slice(1)}放送</span> : null}
        </div>
        <h1>{it.nameCn || it.name}</h1>
        {it.nameCn && it.name !== it.nameCn && <p className="hero-sub">{it.name}</p>}
        <div className="hero-meta">
          {typeof it.rating === 'number' && <span className="star">★ Bangumi {it.rating.toFixed(1)}</span>}
          {it.categoryName && <span>{it.categoryName}</span>}
          {it.episodeCount ? <span>全 {it.episodeCount} 集</span> : null}
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
            {isBusy ? '处理中…' : '追番'}
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

/** 点卡片直接进播放页（浏览态）：不创建作品；已入库则走作品播放页。 */
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
          ? <span className="badge-anim" style={{ background: 'var(--pub)' }}>在书库</span>
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

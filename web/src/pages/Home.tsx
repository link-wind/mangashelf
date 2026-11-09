import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api'
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
interface ProgressRow {
  workId: string; workTitle: string; ordinal: number; episodeTitle: string | null
  episodeId: string | null; lineName: string | null; coverUrl: string | null
  positionMs: number; durationMs: number | null; updatedAt: string
}
interface MangaCard {
  id: string; title: string; description: string; visibility: string
  chapterCount: number; latestOrdinal: number | null; coverUrl: string | null; updatedAt: string
}
interface MangaContinue {
  chapterId: string; revisionId: string; pageOrdinal: number; workId: string; workTitle: string
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

/** 首页（M14）：Banner 轮播 → 新番时刻表 → 继续观看 → 最近更新 → 高分榜 → 分类。 */
export default function Home() {
  const [calendar, setCalendar] = useState<CalendarEntry[]>([])
  const [updates, setUpdates] = useState<CatalogItem[]>([])
  const [continueList, setContinueList] = useState<ProgressRow[]>([])
  const [works, setWorks] = useState<WorkCard[]>([])
  const [manga, setManga] = useState<MangaCard[]>([])
  const [mangaContinue, setMangaContinue] = useState<MangaContinue[]>([])
  const [error, setError] = useState('')

  useEffect(() => {
    void (async () => {
      try {
        const [cal, cat, prog, page, mg, lib] = await Promise.all([
          api.get<CalendarEntry[]>('/api/v1/calendar'),
          api.get<{ items: CatalogItem[] }>('/api/v1/catalog-items?limit=12'),
          api.get<ProgressRow[]>('/api/v1/me/continue-watching?limit=6'),
          api.get<{ items: WorkCard[] }>('/api/v1/works?limit=12'),
          api.get<{ items: MangaCard[] }>('/api/v1/manga?limit=12'),
          api.get<{ continueReading: MangaContinue[] }>('/api/v1/me/library'),
        ])
        setCalendar(cal)
        setUpdates(cat.items)
        setContinueList(prog)
        setWorks(page.items)
        setManga(mg.items)
        setMangaContinue(lib.continueReading.slice(0, 6))
      } catch (e) {
        setError(e instanceof Error ? e.message : '加载失败')
      }
    })()
  }, [])

  const topRated = [...calendar]
    .filter(i => (i.rating ?? 0) > 0)
    .sort((a, b) => (b.rating ?? 0) - (a.rating ?? 0))
    .slice(0, 10)

  return (
    <main className="home-page">
      <HeroBanner items={topRated.slice(0, 4)} />

      {error && <p className="error">{error}</p>}

      <WeeklyPreview items={calendar} />

      {continueList.length > 0 && (
        <section style={{ marginTop: 30 }}>
          <div className="section-head">
            <h2>继续观看</h2>
            <Link to="/history" className="muted">观看历史 →</Link>
          </div>
          <div className="hscroll">
            {continueList.map(r => (
              <Link key={`${r.workId}-${r.ordinal}`}
                    to={r.episodeId ? `/works/${r.workId}/external/${r.episodeId}` : `/works/${r.workId}`}
                    className="work-card">
                <div className="cover">
                  {r.coverUrl
                    ? <img src={r.coverUrl} alt={r.workTitle} loading="lazy" decoding="async"
                           onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />
                    : <span className="cover-initial">{r.workTitle.slice(0, 1)}</span>}
                  <span className="badge-anim">{r.durationMs
                    ? Math.min(99, Math.round(r.positionMs / r.durationMs * 100)) + '%'
                    : '继续'}</span>
                </div>
                <div className="work-card-body">
                  <div className="work-card-title clamp">{r.workTitle}</div>
                  <div className="work-card-meta"><span>第 {r.ordinal} 话</span></div>
                </div>
              </Link>
            ))}
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
                    <span className="cover-initial">{r.workTitle.slice(0, 1)}</span>
                    <span className="badge-anim">读到 {r.pageOrdinal} 页</span>
                  </div>
                  <div className="work-card-body">
                    <div className="work-card-title clamp">{r.workTitle}</div>
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
                  {m.coverUrl
                    ? <img src={m.coverUrl} alt={m.title} loading="lazy" decoding="async"
                           onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />
                    : <span className="cover-initial">{m.title.slice(0, 1)}</span>}
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
                <img src={`/api/v1/bangumi-covers/${it.subjectId}?size=medium`} alt=""
                     loading="lazy" decoding="async"
                     onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />
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
            <Link to="/shelf" className="muted">我的书架 →</Link>
          </div>
          <div className="card-grid">
            {works.map(w => (
              <Link key={w.id} to={`/works/${w.id}`} className="work-card">
                <div className="cover">
                  {w.posterUrl
                    ? <img src={w.posterUrl} alt={w.title} loading="lazy" decoding="async"
                           onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />
                    : <span className="cover-initial">{w.title.slice(0, 1)}</span>}
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

/** 首屏 Banner：4 张轮播，右下缩略图切换（gugu 式）。 */
function HeroBanner({ items }: { items: CalendarEntry[] }) {
  const navigate = useNavigate()
  const [idx, setIdx] = useState(0)
  const [busy, setBusy] = useState<string | null>(null)

  useEffect(() => {
    if (items.length < 2) return
    const t = setInterval(() => setIdx(i => (i + 1) % items.length), 7000)
    return () => clearInterval(t)
  }, [items.length])

  async function play(it: CalendarEntry) {
    if (it.workId) { navigate(`/works/${it.workId}`); return }
    if (!it.catalogItemId) { navigate('/anime'); return }
    setBusy(it.catalogItemId)
    try {
      const res = await api.post<{ workId: string }>(`/api/v1/catalog-items/${it.catalogItemId}/materialize`, {})
      navigate(`/works/${res.workId}`)
    } catch { /* 保留在当前页 */ } finally { setBusy(null) }
  }

  async function subscribe(it: CalendarEntry) {
    if (!it.catalogItemId) return
    setBusy(it.catalogItemId)
    try {
      let workId = it.workId
      if (!workId) {
        const res = await api.post<{ workId: string }>(`/api/v1/catalog-items/${it.catalogItemId}/materialize`, {})
        workId = res.workId
      }
      await api.put(`/api/v1/me/library/${workId}`)
      navigate(`/works/${workId}`)
    } catch { /* 保留在当前页 */ } finally { setBusy(null) }
  }

  if (items.length === 0) {
    return (
      <section className="hero">
        <div>
          <h1>MangaShelf</h1>
          <p className="muted">读漫画、追改编动画、按剧情找内容——你的私人书架。</p>
        </div>
        <div className="hero-search">
          <Link to="/anime" className="btn ghost pill">番剧索引 →</Link>
        </div>
      </section>
    )
  }

  const it = items[idx]
  return (
    <section className="hero-slide">
      <div className="hero-bg"
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
          <button className="pill hero-play" disabled={busy === it.catalogItemId}
                  onClick={() => void play(it)}>
            ▶ {busy === it.catalogItemId ? '处理中…' : (it.workId ? '立即播放' : '播放')}
          </button>
          <button className="pill hero-sub-btn" disabled={busy === it.catalogItemId}
                  onClick={() => void subscribe(it)}>
            ♡ 追番
          </button>
          {it.catalogItemId && (
            <Link to={`/catalog/${it.catalogItemId}`} className="hero-info" title="详情">i</Link>
          )}
        </div>
      </div>

      <div className="hero-thumbs">
        {items.map((x, i) => (
          <button key={x.subjectId} className={'hero-thumb' + (i === idx ? ' on' : '')}
                  onClick={() => setIdx(i)} title={x.nameCn || x.name}>
            <img src={`/api/v1/bangumi-covers/${x.subjectId}?size=medium`} alt=""
                 onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />
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
          <button key={d} className={d === day ? 'mini' : 'mini ghost'} onClick={() => setDay(d)}>
            {WEEKDAYS[d]}
          </button>
        ))}
      </div>
      <div className="hscroll">
        {list.map(it => (
          <Link key={it.subjectId} to={it.catalogItemId ? `/catalog/${it.catalogItemId}` : '/anime'}
                className="work-card">
            <div className="cover">
              <img src={`/api/v1/bangumi-covers/${it.subjectId}?size=medium`} alt={it.nameCn || it.name} loading="lazy" decoding="async"
                   onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />
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
  return (
    <Link to={`/catalog/${it.id}`} className="work-card">
      <div className="cover">
        {it.bangumiSubjectId
          ? <img src={`/api/v1/bangumi-covers/${it.bangumiSubjectId}?size=medium`} alt={it.name} loading="lazy" decoding="async"
                 onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />
          : it.coverUrl
            ? <img src={it.coverUrl} alt={it.name} loading="lazy" decoding="async"
                   onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />
            : <span className="cover-initial">{it.name.slice(0, 1)}</span>}
        {it.workId && <span className="badge-anim" style={{ background: 'var(--pub)' }}>在书库</span>}
      </div>
      <div className="work-card-body">
        <div className="work-card-title clamp">{it.name}</div>
        <div className="work-card-meta">
          <span>{it.remarks || `${it.episodeCount} 集`}</span>
        </div>
      </div>
    </Link>
  )
}

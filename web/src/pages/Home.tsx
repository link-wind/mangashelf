import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api'
import SeasonSection from './SeasonSection'

interface ProgressRow {
  workId: string; workTitle: string; ordinal: number; episodeTitle: string | null
  episodeId: string | null; lineName: string | null; coverUrl: string | null
  positionMs: number; durationMs: number | null; updatedAt: string
}

interface WorkCard {
  id: string; title: string; description: string; status: string; visibility: string
  animations: number; episodes: number; posterUrl: string | null
}

/** 首页（gugu 式分区）：欢迎区 + 有动画的作品（横滚）+ 最新作品。 */
export default function Home() {
  const navigate = useNavigate()
  const [items, setItems] = useState<WorkCard[]>([])
  const [continueList, setContinueList] = useState<ProgressRow[]>([])
  const [error, setError] = useState('')

  useEffect(() => {
    void (async () => {
      try {
        const page = await api.get<{ items: WorkCard[] }>('/api/v1/works?limit=48')
        setItems(page.items)
        setContinueList(await api.get<ProgressRow[]>('/api/v1/me/continue-watching?limit=6'))
      } catch (e) {
        setError(e instanceof Error ? e.message : '加载失败')
      }
    })()
  }, [])

  const withAnimation = items.filter(w => w.animations > 0)
  const latest = items.slice(0, 12)

  return (
    <main className="home-page">
      <section className="hero card">
        <div>
          <h1>MangaShelf</h1>
          <p className="muted">读漫画、追改编动画、按剧情找内容——你的私人书架。</p>
        </div>
        <div className="hero-search">
          <input placeholder="搜索动画、标题、别名…" className="search"
                 onKeyDown={e => {
                   if (e.key === 'Enter') {
                     const q = (e.target as HTMLInputElement).value.trim()
                     if (q) navigate(`/search?q=${encodeURIComponent(q)}`)
                   }
                 }} />
          <Link to="/anime" className="btn ghost">番剧索引 →</Link>
        </div>
      </section>

      {error && <p className="error">{error}</p>}

      {continueList.length > 0 && (
        <section style={{ marginTop: 28 }}>
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
                    ? <img src={r.coverUrl} alt={r.workTitle} loading="lazy"
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

      <section style={{ marginTop: 28 }}>
        <SeasonSection layout="scroll" limit={8} />
      </section>

      {withAnimation.length > 0 && (
        <section style={{ marginTop: 28 }}>
          <div className="section-head">
            <h2>有动画的作品</h2>
            <Link to="/anime" className="muted">查看全部 →</Link>
          </div>
          <div className="hscroll">
            {withAnimation.map(w => <WorkCardView key={w.id} w={w} />)}
          </div>
        </section>
      )}

      <section style={{ marginTop: 28 }}>
        <div className="section-head">
          <h2>最新作品</h2>
          <Link to="/anime" className="muted">番剧索引 →</Link>
        </div>
        {latest.length === 0
          ? <p className="muted">还没有可见作品——去「我的素材」上传第一部吧。</p>
          : <div className="card-grid">{latest.map(w => <WorkCardView key={w.id} w={w} />)}</div>}
      </section>
    </main>
  )
}

export function WorkCardView({ w }: { w: WorkCard }) {
  return (
    <Link to={`/works/${w.id}`} className="work-card">
      <div className="cover">
        {w.posterUrl
          ? <img src={w.posterUrl} alt={w.title} loading="lazy" />
          : <span className="cover-initial">{w.title.slice(0, 1)}</span>}
        {w.animations > 0 && <span className="badge-anim">动画</span>}
      </div>
      <div className="work-card-body">
        <div className="work-card-title clamp">{w.title}</div>
        <div className="work-card-meta">
          {w.animations > 0 && <span>动画 {w.animations}</span>}
          {w.episodes > 0 && <span>外部 {w.episodes} 集</span>}
          {w.animations === 0 && w.episodes === 0 && <span>漫画</span>}
        </div>
      </div>
    </Link>
  )
}

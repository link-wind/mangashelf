import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import ExternalEpisodes from './ExternalEpisodes'
import { api, ApiError } from '../api'

interface Chapter {
  id: string
  ordinal: number
  title: string
  currentRevisionId: string | null
}

interface WorkExternalMeta {
  coverUrl: string | null
  catalogTitle: string | null
  rating: number | null
  summary: string | null
  airWeekday: number | null
  lines: string[]
}

interface WorkView {
  work: { id: string; title: string; description: string; status: string; visibility: string; ownerId: string }
  chapters: Chapter[]
  owner: boolean
}

export default function WorkDetail() {
  const { id } = useParams()
  const [meta, setMeta] = useState<WorkExternalMeta | null>(null)
  const [data, setData] = useState<WorkView | null>(null)
  const [error, setError] = useState('')
  const navigate = useNavigate()

  const load = useCallback(async () => {
    try {
      setData(await api.get<WorkView>(`/api/v1/works/${id}`))
      setError('')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '加载失败')
    }
  }, [id])

  useEffect(() => { void load() }, [load])

  useEffect(() => {
    if (!id) return
    void api.get<WorkExternalMeta | null>(`/api/v1/works/${id}/external-meta`)
      .then(m => setMeta(m))
      .catch(() => setMeta(null))
  }, [id])

  // 纯外部动画作品：点进作品即进播放页（与参考站一致）
  useEffect(() => {
    if (!id || !data) return
    if (data.chapters.length > 0) return
    void api.get<{ episodeId: string } | null>(`/api/v1/works/${id}/play-target`)
      .then(target => {
        if (target?.episodeId) {
          navigate(`/works/${id}/external/${target.episodeId}`, { replace: true })
        }
      })
      .catch(() => { /* 无外部集则停留详情页 */ })
  }, [id, data, navigate])

  async function addToShelf() {
    try {
      await api.put(`/api/v1/me/library/${id}`)
      setError('')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '操作失败')
    }
  }

  /** 续读入口：携带章节与上次页序（无进度则从头），DESIGN 4.1。 */
  async function readLink(chapter: Chapter): Promise<string> {
    let resume = ''
    try {
      const p = await api.get<{ pageOrdinal: number }>(`/api/v1/me/reading-progress/${chapter.id}`)
      resume = `&resume=${p.pageOrdinal}`
    } catch { /* 无进度 */ }
    return `/read/${chapter.currentRevisionId}?chapter=${chapter.id}${resume}`
  }

  async function withdraw() {
    try {
      await api.post(`/api/v1/works/${id}/withdraw`)
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '操作失败')
    }
  }

  if (error && !data) return <main className="page"><p className="error">{error}</p></main>
  if (!data) return <main className="page-center">加载中…</main>

  const { work, chapters, owner } = data
  return (
    <main className="page">
      {meta && (
        <div className="detail-hero" style={{ marginBottom: 18 }}>
          <div className="detail-cover">
            {meta.coverUrl
              ? <img src={meta.coverUrl} alt={work.title}
                     onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />
              : <span className="cover-initial">{work.title.slice(0, 1)}</span>}
          </div>
          <div className="detail-info">
            <h1>{work.title}</h1>
            <div className="watch-meta" style={{ margin: '4px 0 8px' }}>
              {typeof meta.rating === 'number' && meta.rating > 0 && <span className="star">★ {meta.rating.toFixed(1)}</span>}
              {meta.airWeekday && <span>{['', '周一', '周二', '周三', '周四', '周五', '周六', '周日'][meta.airWeekday]}放送</span>}
              {meta.lines.length > 0 && <span>线路：{meta.lines.join(' / ')}</span>}
            </div>
            {meta.summary && <p className="detail-summary">{meta.summary}</p>}
          </div>
        </div>
      )}
      <div className="row-between">
        <div>
          <h1 style={meta ? { display: 'none' } : undefined}>{work.title}</h1>
          <p className="muted">{work.description}</p>
          <span className={`tag ${work.visibility === 'PUBLIC' ? 'pub' : 'priv'}`}>
            {work.visibility === 'PUBLIC' ? '公开' : '私人'} · {work.status === 'PUBLISHED' ? '已发布' : work.status === 'WITHDRAWN' ? '已下架' : '草稿'}
          </span>
        </div>
        {!owner && <button onClick={addToShelf}>加入收藏</button>}
        {owner && work.status === 'PUBLISHED' && <button className="ghost" onClick={withdraw}>下架</button>}
      </div>

      <h2>章节</h2>
      {error && <p className="error">{error}</p>}
      <ul className="chapters">
        {chapters.map(c => (
          <li key={c.id}>
            <span>{c.ordinal}. {c.title || `第 ${c.ordinal} 话`}</span>
            {c.currentRevisionId
              ? <a className="btn" href="#" onClick={e => { e.preventDefault(); void readLink(c).then(url => navigate(url)) }}>阅读</a>
              : <span className="muted">未发布</span>}
          </li>
        ))}
        {chapters.length === 0 && <li className="muted">还没有已发布章节</li>}
      </ul>
      {owner && <button className="ghost" onClick={() => navigate('/uploads')}>管理素材 →</button>}
      {owner && <ExternalEpisodes />}
    </main>
  )
}

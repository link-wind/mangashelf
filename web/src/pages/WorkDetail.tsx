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

interface WorkView {
  work: { id: string; title: string; description: string; status: string; visibility: string; ownerId: string }
  chapters: Chapter[]
  owner: boolean
}

export default function WorkDetail() {
  const { id } = useParams()
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
      <div className="row-between">
        <div>
          <h1>{work.title}</h1>
          <p className="muted">{work.description}</p>
          <span className={`tag ${work.visibility === 'PUBLIC' ? 'pub' : 'priv'}`}>
            {work.visibility === 'PUBLIC' ? '公开' : '私人'} · {work.status === 'PUBLISHED' ? '已发布' : work.status === 'WITHDRAWN' ? '已下架' : '草稿'}
          </span>
        </div>
        {!owner && <button onClick={addToShelf}>加入书架</button>}
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

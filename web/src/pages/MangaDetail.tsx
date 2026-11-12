import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError } from '../api'

interface ChapterRow {
  id: string; ordinal: number; title: string; currentRevisionId: string | null
  readPageOrdinal: number | null
}
interface MangaDetail {
  work: { id: string; title: string; description: string; status: string; visibility: string; ownerId: string }
  chapters: ChapterRow[]
  owner: boolean
  coverUrl: string | null
  continueReading: { chapterId: string; revisionId: string; pageOrdinal: number } | null
}

/** 漫画详情（M15）：封面、简介、继续阅读与章节列表。 */
export default function MangaDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const [data, setData] = useState<MangaDetail | null>(null)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  useEffect(() => {
    void (async () => {
      if (!id) return
      try {
        setData(await api.get<MangaDetail>(`/api/v1/manga/${id}`))
      } catch (e) {
        setError(e instanceof ApiError ? e.message : '加载失败')
      }
    })()
  }, [id])

  async function addToShelf() {
    if (!id) return
    try {
      await api.put(`/api/v1/me/library/${id}`)
      setNotice('已加入收藏')
      setTimeout(() => setNotice(''), 2500)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '操作失败')
    }
  }

  if (error) return <main className="watch-page"><p className="error">{error}</p></main>
  if (!data) return <main className="watch-page page-center">加载中…</main>

  const { work, chapters, owner, coverUrl, continueReading } = data
  const readUrl = (c: ChapterRow, resume = 1) =>
    `/read/${c.currentRevisionId}?chapter=${c.id}&resume=${resume}`
  const contChapter = continueReading
    ? chapters.find(c => c.id === continueReading.chapterId) : null

  return (
    <main className="watch-page">
      <div className="detail-hero">
        <div className="detail-cover">
          {coverUrl
            ? <img src={coverUrl} alt={work.title}
                   onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />
            : <span className="cover-initial">{work.title.slice(0, 1)}</span>}
        </div>
        <div className="detail-info">
          <h1>{work.title}</h1>
          <div className="watch-meta" style={{ margin: '6px 0 10px' }}>
            <span>{chapters.length} 话</span>
            <span className={`tag ${work.visibility === 'PUBLIC' ? 'pub' : 'priv'}`}>
              {work.visibility === 'PUBLIC' ? '公开' : '私人'}
            </span>
            <span className="muted">
              {work.status === 'PUBLISHED' ? '已发布' : work.status === 'WITHDRAWN' ? '已下架' : '草稿'}
            </span>
          </div>
          {work.description && <p className="detail-summary">{work.description}</p>}
          {notice && <p className="muted">{notice}</p>}
          <div className="row-inline" style={{ marginTop: 14 }}>
            {continueReading && contChapter ? (
              <button onClick={() => navigate(readUrl(contChapter, continueReading.pageOrdinal))}>
                继续阅读（第 {contChapter.ordinal} 话 · 第 {continueReading.pageOrdinal} 页）
              </button>
            ) : chapters[0]?.currentRevisionId ? (
              <button onClick={() => navigate(readUrl(chapters[0]))}>开始阅读</button>
            ) : (
              <span className="muted">还没有已发布章节</span>
            )}
            {!owner && <button className="ghost" onClick={() => void addToShelf()}>加入收藏</button>}
            {owner && <Link className="btn ghost" to="/uploads">管理素材 →</Link>}
          </div>
        </div>
      </div>

      <section style={{ marginTop: 26 }}>
        <div className="section-head"><h2>章节（{chapters.length}）</h2></div>
        <ul className="chapters">
          {chapters.map(c => (
            <li key={c.id}>
              <span>
                第 {c.ordinal} 话 {c.title}
                {c.readPageOrdinal != null && (
                  <span className="muted"> · 读到第 {c.readPageOrdinal} 页</span>
                )}
              </span>
              {c.currentRevisionId
                ? <Link className="btn ghost" to={readUrl(c, c.readPageOrdinal ?? 1)}>
                    {c.readPageOrdinal != null ? '继续' : '阅读'}
                  </Link>
                : <span className="muted">未发布</span>}
            </li>
          ))}
          {chapters.length === 0 && <li className="muted">还没有已发布章节</li>}
        </ul>
      </section>
    </main>
  )
}

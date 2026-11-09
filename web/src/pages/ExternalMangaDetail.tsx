import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'
import { proxyImage } from '../externalManga'

interface ExtManga {
  id: string; title: string; altTitle: string; description: string
  coverUrl: string | null; status: string; year: string; tags: string[]; langs: string[]
}
interface ExtChapter {
  id: string; chapter: string; title: string; lang: string
  pages: number; publishAt: string; external: boolean
}
interface Detail { manga: ExtManga; chapters: ExtChapter[] }

const LANGS = [
  { key: 'zh', label: '中文' },
  { key: 'en', label: 'English' },
  { key: 'all', label: '全部' },
]

/** 外部漫画详情（M16）：章节列表（可按语言过滤）。 */
export default function ExternalMangaDetail() {
  const { mangaId } = useParams()
  const navigate = useNavigate()
  const [detail, setDetail] = useState<Detail | null>(null)
  const [lang, setLang] = useState('zh')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const load = useCallback(async (language: string) => {
    if (!mangaId) return
    setLoading(true); setError('')
    try {
      setDetail(await api.get<Detail>(`/api/v1/external-manga/${mangaId}?lang=${language}`))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'MangaDex 请求失败')
    } finally { setLoading(false) }
  }, [mangaId])

  useEffect(() => { void load(lang) }, [lang, load])

  if (error) return <main className="watch-page"><p className="error">{error}</p></main>
  if (!detail) return <main className="watch-page page-center">加载中…</main>

  const { manga, chapters } = detail
  const readable = chapters.filter(c => !c.external && c.pages > 0)

  return (
    <main className="watch-page">
      <div className="detail-hero">
        <div className="detail-cover">
          {manga.coverUrl
            ? <img src={proxyImage(manga.coverUrl)} alt={manga.title} />
            : <span className="cover-initial">{manga.title.slice(0, 1)}</span>}
        </div>
        <div className="detail-info">
          <h1>{manga.title}</h1>
          {manga.altTitle && <p className="muted">{manga.altTitle}</p>}
          <div className="watch-meta" style={{ margin: '8px 0 10px' }}>
            {manga.year && <span>{manga.year}</span>}
            {manga.status && <span>{manga.status}</span>}
            <span>{(manga.langs ?? []).slice(0, 6).join(' / ')}</span>
            <span className="muted">来源 MangaDex</span>
          </div>
          {manga.description && <p className="detail-summary">{manga.description}</p>}
          <div className="row-inline" style={{ marginTop: 14 }}>
            {readable[0]
              ? <button onClick={() => navigate(`/manga-ext/read/${readable[0].id}?mangaId=${manga.id}&lang=${lang}`)}>
                  开始阅读（第 {readable[0].chapter || 1} 话）
                </button>
              : <span className="muted">该语言下暂无可直接阅读的章节</span>}
            <Link className="btn ghost" to="/manga-ext">返回搜索</Link>
          </div>
        </div>
      </div>

      <section style={{ marginTop: 24 }}>
        <div className="section-head">
          <h2>章节（{readable.length}）</h2>
          <div className="row-inline">
            {LANGS.map(l => (
              <button key={l.key} className={lang === l.key ? 'mini' : 'mini ghost'}
                      onClick={() => setLang(l.key)}>{l.label}</button>
            ))}
          </div>
        </div>
        {loading && <p className="muted">加载章节…</p>}
        <ul className="chapters">
          {readable.map(c => (
            <li key={c.id}>
              <span>
                第 {c.chapter || '?'} 话 {c.title}
                <span className="muted"> · {c.pages} 页 · {c.lang}</span>
              </span>
              <button className="ghost"
                      onClick={() => navigate(`/manga-ext/read/${c.id}?mangaId=${manga.id}&lang=${lang}`)}>
                阅读
              </button>
            </li>
          ))}
          {readable.length === 0 && !loading && (
            <li className="muted">
              该语言下没有 MangaDex 托管章节（部分章节为外链，需在原站阅读）。
              可切换 English 或「全部」。
            </li>
          )}
        </ul>
      </section>
    </main>
  )
}

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
interface ProgressRow { source: string; mangaId: string; chapterId: string | null; page: number }

const LANGS = [
  { key: 'zh', label: '中文' },
  { key: 'en', label: 'English' },
  { key: 'all', label: '全部' },
]

/** 外部漫画详情（M21，参照哔哩哔哩漫画）：大封面 + 信息 + 章节格。 */
export default function ExternalMangaDetail() {
  const { mangaId } = useParams()
  const navigate = useNavigate()
  const [detail, setDetail] = useState<Detail | null>(null)
  const [lang, setLang] = useState('zh')
  const [asc, setAsc] = useState(true)
  const [expanded, setExpanded] = useState(false)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [resume, setResume] = useState<{ chapterId: string; page: number } | null>(null)

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

  useEffect(() => {
    if (!mangaId) return
    void api.get<ProgressRow[]>('/api/v1/me/manga-progress?limit=50')
      .then(rows => {
        const row = rows.find(r => r.source === 'mangadex' && r.mangaId === mangaId)
        if (row?.chapterId) setResume({ chapterId: row.chapterId, page: row.page })
      })
      .catch(() => { /* 无进度 */ })
  }, [mangaId])

  if (error) return <main className="watch-page"><p className="error">{error}</p></main>
  if (!detail) return <main className="watch-page page-center">加载中…</main>

  const { manga, chapters } = detail
  const readable = chapters.filter(c => !c.external && c.pages > 0)
  const ordered = asc ? readable : [...readable].reverse()
  const continueCh = resume ? readable.find(c => c.id === resume.chapterId) : null
  const startCh = continueCh ?? readable[0]

  const open = (c: ExtChapter) =>
    navigate(`/manga-ext/read/${c.id}?mangaId=${manga.id}&lang=${lang}&title=${encodeURIComponent(manga.title)}`)

  return (
    <main className="watch-page">
      <section className="manga-hero">
        <div className="manga-cover">
          {manga.coverUrl
            ? <img src={proxyImage(manga.coverUrl)} alt={manga.title} />
            : <span className="cover-initial">{manga.title.slice(0, 1)}</span>}
        </div>
        <div className="manga-info">
          <h1>{manga.title}</h1>
          {manga.altTitle && <p className="muted" style={{ margin: '2px 0 8px' }}>{manga.altTitle}</p>}
          <div className="manga-chips">
            {manga.year && <span className="chip">{manga.year}</span>}
            {manga.status && <span className="chip">{manga.status}</span>}
            {(manga.tags ?? []).slice(0, 4).map(t => <span key={t} className="chip ghost">{t}</span>)}
            <span className="chip ghost">MangaDex</span>
          </div>
          <p className={'manga-desc' + (expanded ? ' expanded' : '')}>{manga.description || '（暂无简介）'}</p>
          {manga.description && manga.description.length > 120 && (
            <button className="link-btn" onClick={() => setExpanded(v => !v)}>
              {expanded ? '收起' : '展开'}
            </button>
          )}
          <div className="manga-actions">
            {startCh
              ? <button className="pill manga-read-btn" onClick={() => open(startCh)}>
                  {continueCh
                    ? `继续阅读（第 ${continueCh.chapter || '?'} 话${resume ? ` · 第 ${resume.page} 页` : ''}）`
                    : `开始阅读（第 ${startCh.chapter || 1} 话）`}
                </button>
              : <span className="muted">该语言下暂无可读章节</span>}
            <Link className="btn ghost pill" to="/manga-ext">返回搜索</Link>
          </div>
        </div>
      </section>

      <section style={{ marginTop: 26 }}>
        <div className="section-head">
          <h2>
            章节 <span className="muted" style={{ fontSize: 13, fontWeight: 400 }}>{readable.length} 话</span>
          </h2>
          <div className="row-inline">
            {LANGS.map(l => (
              <button key={l.key} className={lang === l.key ? 'mini' : 'mini ghost'}
                      onClick={() => setLang(l.key)}>{l.label}</button>
            ))}
            <button className="mini ghost" onClick={() => setAsc(v => !v)}>{asc ? '倒序' : '正序'}</button>
          </div>
        </div>

        {loading && <p className="muted">加载章节…</p>}
        {readable.length > 0 ? (
          <div className="chapter-grid">
            {ordered.map(c => (
              <button key={c.id} className="chapter-cell" onClick={() => open(c)}
                      title={c.title || `第 ${c.chapter} 话`}>
                <span className="cc-no">{c.chapter || '?'}</span>
                <span className="cc-sub">{c.pages} 页</span>
              </button>
            ))}
          </div>
        ) : !loading && (
          <p className="muted">
            该语言下没有 MangaDex 托管章节（部分章节为外链，需在原站阅读）。可切换 English 或「全部」。
          </p>
        )}
      </section>
    </main>
  )
}

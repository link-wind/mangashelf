import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
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
interface SourceInfo { id: string; name: string; language: string; capabilities: string[]; note: string }
interface SwitchItem { source: string; id: string; title: string; altTitle: string; coverUrl: string | null }

const LANGS = [
  { key: 'zh', label: '中文' },
  { key: 'en', label: 'English' },
  { key: 'all', label: '全部' },
]

/** MangaDex 旧端点响应形状 → 统一 Detail。 */
function normalizeMangadex(raw: {
  manga: { id: string; title: string; altTitle: string; description: string
           coverUrl: string | null; status: string; year: string; tags: string[]; langs: string[] }
  chapters: ExtChapter[]
}): Detail {
  return raw
}

/** 规则源（/manga-sources/{s}/manga/{id}）响应形状 → 统一 Detail。 */
function normalizeRuleSource(raw: {
  item: { source: string; id: string; title: string; altTitle: string
          coverUrl: string | null; year: string | null }
  description: string; status: string; year: string | null
  tags: string[]; chapters: { id: string; label: string; title: string; lang: string
                              pages: number; external: boolean }[]
}): Detail {
  return {
    manga: {
      id: raw.item.id, title: raw.item.title, altTitle: raw.item.altTitle ?? '',
      description: raw.description ?? '', coverUrl: raw.item.coverUrl,
      status: raw.status ?? '', year: raw.year ?? raw.item.year ?? '',
      tags: raw.tags ?? [], langs: ['中文'],
    },
    chapters: (raw.chapters ?? []).map(c => ({
      id: c.id, chapter: c.label, title: c.title, lang: c.lang ?? 'zh',
      pages: c.pages, publishAt: '', external: c.external,
    })),
  }
}

/**
 * 外部漫画详情（M21/M23）：大封面 + 信息 + 章节格。
 * 按源分发端点：MangaDex 走既有聚合端点，规则源走 /manga-sources/{source}。
 */
export default function ExternalMangaDetail() {
  const { mangaId } = useParams()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const source = params.get('source') ?? 'mangadex'
  const [detail, setDetail] = useState<Detail | null>(null)
  const [lang, setLang] = useState('zh')
  const [asc, setAsc] = useState(true)
  const [showAllBiliChapters, setShowAllBiliChapters] = useState(false)
  const [expanded, setExpanded] = useState(false)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [resume, setResume] = useState<{ chapterId: string; page: number } | null>(null)
  // 数据源切换（M31）：在其它中文源按标题搜索同作
  const [switchSources, setSwitchSources] = useState<SourceInfo[]>([])
  const [switchTarget, setSwitchTarget] = useState('')
  const [switchItems, setSwitchItems] = useState<SwitchItem[]>([])
  const [switchLoading, setSwitchLoading] = useState(false)
  const [switchError, setSwitchError] = useState('')
  const [sourceNames, setSourceNames] = useState<Record<string, string>>({})

  const load = useCallback(async (language: string) => {
    if (!mangaId) return
    setLoading(true); setError('')
    try {
      if (source === 'mangadex') {
        setDetail(normalizeMangadex(
          await api.get<Detail>(`/api/v1/external-manga/${mangaId}?lang=${language}`)))
      } else {
        setDetail(normalizeRuleSource(
          await api.get(`/api/v1/manga-sources/${source}/manga/${mangaId}?lang=${language}`)))
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '详情请求失败')
    } finally { setLoading(false) }
  }, [mangaId, source])

  useEffect(() => { void load(lang) }, [lang, load])

  useEffect(() => {
    void api.get<SourceInfo[]>('/api/v1/manga-sources')
      .then(list => {
        const names: Record<string, string> = {}
        list.forEach(s => { names[s.id] = s.name })
        setSourceNames(names)
        setSwitchSources(list.filter(s => s.language === 'zh'
          && s.capabilities.includes('SEARCH') && s.id !== source))
      })
      .catch(() => { /* 源列表不可用不影响详情 */ })
  }, [source])

  async function findIn(target: SourceInfo) {
    if (!detail) return
    setSwitchTarget(target.id)
    setSwitchItems([])
    setSwitchError('')
    setSwitchLoading(true)
    try {
      const rows = await api.get<SwitchItem[]>(
        `/api/v1/manga-sources/${target.id}/search?q=${encodeURIComponent(detail.manga.title)}&limit=8`)
      setSwitchItems(rows)
      if (rows.length === 0) setSwitchError(`未在${target.name}找到同作`)
    } catch (e) {
      setSwitchError(e instanceof Error ? e.message : '搜索失败')
    } finally {
      setSwitchLoading(false)
    }
  }

  useEffect(() => {
    if (!mangaId) return
    void api.get<ProgressRow[]>('/api/v1/me/manga-progress?limit=50')
      .then(rows => {
        const row = rows.find(r => r.source === source && r.mangaId === mangaId)
        if (row?.chapterId) setResume({ chapterId: row.chapterId, page: row.page })
      })
      .catch(() => { /* 无进度 */ })
  }, [mangaId, source])

  const officialBiliUrl = source === 'bilibili' && /^\d{1,12}$/.test(mangaId ?? '')
    ? `https://manga.bilibili.com/detail/mc${mangaId}` : null
  const officialLink = officialBiliUrl
    ? <a className="btn ghost pill" href={officialBiliUrl} target="_blank" rel="noopener noreferrer">前往哔哩原站</a>
    : null

  if (error) return <main className="watch-page"><p className="error" role="alert">{error}</p>{officialLink}</main>
  if (!detail) return <main className="watch-page page-center">加载中…</main>

  const { manga, chapters } = detail
  const readable = chapters.filter(c => !c.external && c.pages !== 0)
  const shown = officialBiliUrl && showAllBiliChapters ? chapters : readable
  const ordered = asc ? shown : [...shown].reverse()
  const continueCh = resume ? readable.find(c => c.id === resume.chapterId) : null
  const startCh = continueCh ?? readable[0]

  const open = (c: ExtChapter) =>
    navigate(`/manga-ext/read/${c.id}?mangaId=${manga.id}&lang=${lang}&source=${source}` +
             `&title=${encodeURIComponent(manga.title)}`)

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
            <span className="chip ghost">{sourceNames[source] ?? (source === 'mangadex' ? 'MangaDex' : source)}</span>
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
              : <span className="muted">暂无可读章节</span>}
            {officialLink}
            <Link className="btn ghost pill" to={officialBiliUrl ? '/manga' : '/manga-ext'}>
              {officialBiliUrl ? '返回漫画首页' : '返回搜索'}
            </Link>
          </div>

          {switchSources.length > 0 && (
            <div className="manga-switch">
              <span className="muted">切换数据源：</span>
              {switchSources.map(s => (
                <button key={s.id} className="mini ghost" disabled={switchLoading}
                        onClick={() => void findIn(s)}>{s.name}</button>
              ))}
              {switchLoading && <span className="muted" style={{ fontSize: 12 }}>搜索中…</span>}
            </div>
          )}
          {(switchItems.length > 0 || switchError) && (
            <div className="switch-panel">
              <div className="switch-panel-head">
                {switchError || `在${sourceNames[switchTarget] ?? switchTarget}找到 ${switchItems.length} 个结果`}
              </div>
              {switchItems.length > 0 && (
                <div className="switch-list">
                  {switchItems.map(r => (
                    <button key={r.source + r.id} className="switch-item"
                            onClick={() => navigate(`/manga-ext/${r.id}?source=${r.source}` +
                              `&title=${encodeURIComponent(r.title)}`)}>
                      {r.coverUrl
                        ? <img src={proxyImage(r.coverUrl)} alt="" loading="lazy" decoding="async" />
                        : <span className="switch-cover-fallback">{(r.title || '?').slice(0, 1)}</span>}
                      <span className="switch-item-title clamp">{r.title}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      </section>

      <section style={{ marginTop: 26 }}>
        <div className="section-head">
          <h2>
            章节 <span className="muted" style={{ fontSize: 13, fontWeight: 400 }}>{readable.length} 话</span>
          </h2>
          <div className="row-inline">
            {source === 'mangadex' && LANGS.map(l => (
              <button key={l.key} className={lang === l.key ? 'mini' : 'mini ghost'}
                      onClick={() => setLang(l.key)}>{l.label}</button>
            ))}
            {officialBiliUrl && chapters.length > readable.length && (
              <button className="mini ghost" onClick={() => setShowAllBiliChapters(v => !v)}>
                {showAllBiliChapters ? `仅站内可读（${readable.length}）` : `显示全部（${chapters.length}）`}
              </button>
            )}
            <button className="mini ghost" onClick={() => setAsc(v => !v)}>{asc ? '倒序' : '正序'}</button>
          </div>
        </div>

        {loading && <p className="muted">加载章节…</p>}
        {ordered.length > 0 ? (
          <div className="chapter-grid">
            {ordered.map(c => c.external && officialBiliUrl ? (
              <a key={c.id} className="chapter-cell" href={`https://manga.bilibili.com/${c.id.replace('-', '/')}`}
                 target="_blank" rel="noopener noreferrer" title={c.title || `第 ${c.chapter} 话`}>
                <span className="cc-no">{c.chapter || '?'}</span>
                <span className="cc-sub">原站</span>
              </a>
            ) : (
              <button key={c.id} className="chapter-cell" onClick={() => open(c)}
                      title={c.title || `第 ${c.chapter} 话`}>
                <span className="cc-no">{c.chapter || '?'}</span>
                <span className="cc-sub">{c.pages > 0 ? `${c.pages} 页` : c.pages < 0 ? '可读' : ''}</span>
              </button>
            ))}
          </div>
        ) : !loading && (
          <p className="muted">
            {source === 'mangadex'
              ? '该语言下没有 MangaDex 托管章节（部分章节为外链，需在原站阅读）。可切换 English 或「全部」。'
              : '没有可读章节。'}
          </p>
        )}
      </section>
    </main>
  )
}

import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api } from '../api'
import CoverImage from '../components/CoverImage'
import { useToast } from '../components/Toast'
import { formatMangaLine } from '../lineLabel'


interface ExtManga {
  id: string; title: string; altTitle: string; description: string
  coverUrl: string | null; status: string; year: string; tags: string[]
}
interface ExtChapter {
  id: string; chapter: string; title: string; lang: string
  pages: number; publishAt: string; external: boolean
}
interface Detail { manga: ExtManga; chapters: ExtChapter[] }
interface ProgressRow { source: string; mangaId: string; chapterId: string | null; page: number }
interface SourceInfo { id: string; name: string; language: string; capabilities: string[]; note: string }
interface SwitchItem { source: string; id: string; title: string; altTitle: string; coverUrl: string | null }

/** 统一源响应（/manga-sources/{s}/manga/{id}）→ 内部 Detail。 */
function normalizeSource(raw: {
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
      tags: raw.tags ?? [],
    },
    chapters: (raw.chapters ?? []).map(c => ({
      id: c.id, chapter: c.label, title: c.title, lang: c.lang ?? 'zh',
      pages: c.pages, publishAt: '', external: c.external,
    })),
  }
}

/**
 * 外部漫画详情（M21/M23）：大封面 + 信息 + 章节格。
 * 详情统一走 /manga-sources/{source}（含 MangaDex）。
 */
export default function ExternalMangaDetail() {
  const { mangaId } = useParams()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const { showToast } = useToast()
  const source = params.get('source') ?? 'copymanga'
  const [detail, setDetail] = useState<Detail | null>(null)
  const [lang, setLang] = useState('zh')
  const [asc, setAsc] = useState(true)
  const [chunkIdx, setChunkIdx] = useState(0)
  const [showAllBiliChapters, setShowAllBiliChapters] = useState(false)
  const [expanded, setExpanded] = useState(false)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [resume, setResume] = useState<{ chapterId: string; page: number } | null>(null)
  const [switchSources, setSwitchSources] = useState<SourceInfo[]>([])
  const [switchLabel, setSwitchLabel] = useState('')
  const [switchItems, setSwitchItems] = useState<SwitchItem[]>([])
  const [switchLoading, setSwitchLoading] = useState(false)
  const [switchError, setSwitchError] = useState('')
  const [favorited, setFavorited] = useState(false)
  const [favStatus, setFavStatus] = useState<'WATCHING' | 'COMPLETED'>('WATCHING')

  const load = useCallback(async (language: string) => {
    if (!mangaId) return
    setLoading(true); setError('')
    try {
      setDetail(normalizeSource(
        await api.get(`/api/v1/manga-sources/${source}/manga/${mangaId}?lang=${language}`)))
    } catch (e) {
      setError(e instanceof Error ? e.message : '详情请求失败')
    } finally { setLoading(false) }
  }, [mangaId, source])

  useEffect(() => { void load(lang) }, [lang, load])

  useEffect(() => {
    if (!mangaId) return
    void api.get<{ source: string | null; mangaId: string | null; status?: 'WATCHING' | 'COMPLETED' }[]>('/api/v1/me/favorites?type=manga')
      .then(list => {
        const hit = list.find(f => f.source === source && f.mangaId === mangaId)
        setFavorited(Boolean(hit))
        setFavStatus(hit?.status === 'COMPLETED' ? 'COMPLETED' : 'WATCHING')
      })
      .catch(() => { /* 收藏状态不可用不影响详情 */ })
  }, [source, mangaId])

  async function toggleFavorite() {
    if (!mangaId) return
    const base = `/api/v1/me/favorites/manga?source=${encodeURIComponent(source)}`
      + `&mangaId=${encodeURIComponent(mangaId)}`
    try {
      if (favorited) {
        await api.delete(base)
        setFavorited(false)
        setFavStatus('WATCHING')
        showToast('已取消收藏', 'info')
      } else {
        await api.put(`${base}&title=${encodeURIComponent(detail?.manga.title ?? '')}`)
        setFavorited(true)
        setFavStatus('WATCHING')
        showToast('已加入「我的」收藏', 'success')
      }
    } catch (e) {
      const msg = e instanceof Error ? e.message : '收藏操作失败'
      setError(msg)
      showToast(msg, 'error')
    }
  }

  async function toggleCompleted() {
    if (!mangaId) return
    const nextStatus = favStatus === 'COMPLETED' ? 'WATCHING' : 'COMPLETED'
    const qs = new URLSearchParams({
      source, mangaId, title: detail?.manga.title ?? '', status: nextStatus,
    })
    try {
      await api.put(`/api/v1/me/favorites/manga/status?${qs}`)
      setFavorited(true)
      setFavStatus(nextStatus)
      showToast(nextStatus === 'COMPLETED' ? '已标记为「已读完」' : '已改回「在追」', 'success')
    } catch (e) {
      showToast(e instanceof Error ? e.message : '更新阅读状态失败', 'error')
    }
  }

  useEffect(() => {
    void api.get<SourceInfo[]>('/api/v1/manga-sources')
      .then(list => {
        setSwitchSources(list.filter(s => s.language === 'zh'
          && s.capabilities.includes('SEARCH') && s.id !== source))
      })
      .catch(() => { /* 线路列表不可用不影响详情 */ })
  }, [source])

  async function findIn(target: SourceInfo, label: string) {
    if (!detail) return
    setSwitchLabel(label)
    setSwitchItems([])
    setSwitchError('')
    setSwitchLoading(true)
    try {
      const rows = await api.get<SwitchItem[]>(
        `/api/v1/manga-sources/${target.id}/search?q=${encodeURIComponent(detail.manga.title)}&limit=8`)
      setSwitchItems(rows)
      if (rows.length === 0) setSwitchError(`${label}暂未找到同作`)
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
    ? <a className="btn ghost pill" href={officialBiliUrl} target="_blank" rel="noopener noreferrer">查看正版页面</a>
    : null

  if (error) return <main className="watch-page"><p className="error" role="alert">{error}</p>{officialLink}</main>
  if (!detail) return <main className="watch-page page-center">加载中…</main>

  const { manga, chapters } = detail
  const readable = chapters.filter(c => !c.external && c.pages !== 0)
  const shown = officialBiliUrl && showAllBiliChapters ? chapters : readable
  const ordered = asc ? shown : [...shown].reverse()
  const CHUNK = 50
  const useChunks = ordered.length > CHUNK
  const safeChunkIdx = useChunks ? Math.min(chunkIdx, Math.floor((ordered.length - 1) / CHUNK)) : 0
  const displayedChapters = useChunks
    ? ordered.slice(safeChunkIdx * CHUNK, (safeChunkIdx + 1) * CHUNK)
    : ordered
  const continueCh = resume ? readable.find(c => c.id === resume.chapterId) : null
  const startCh = continueCh ?? readable[0]

  const open = (c: ExtChapter) =>
    navigate(`/manga-ext/read/${c.id}?mangaId=${manga.id}&lang=${lang}&source=${source}` +
             `&title=${encodeURIComponent(manga.title)}`)

  return (
    <main className="watch-page">
      <section className="manga-hero">
        <div className="manga-cover">
          <CoverImage src={manga.coverUrl} alt={manga.title} loading="eager" fetchPriority="high" />
        </div>
        <div className="manga-info">
          <h1>{manga.title}</h1>
          {manga.altTitle && <p className="muted" style={{ margin: '2px 0 8px' }}>{manga.altTitle}</p>}
          <div className="manga-chips">
            {manga.year && <span className="chip">{manga.year}</span>}
            {manga.status && <span className="chip">{manga.status}</span>}
            {(manga.tags ?? []).slice(0, 4).map(t => <span key={t} className="chip ghost">{t}</span>)}
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
              : <span className="muted">暂无章节</span>}
            <button className="btn ghost pill" onClick={() => void toggleFavorite()}>
              {favorited ? '已收藏' : '收藏'}
            </button>
            <button className={'pill ' + (favStatus === 'COMPLETED' ? '' : 'ghost')}
                    onClick={() => void toggleCompleted()}>
              {favStatus === 'COMPLETED' ? '✓ 已读完' : '标记已读完'}
            </button>
            {officialLink}
            <Link className="btn ghost pill" to="/manga">
              返回漫画页
            </Link>
          </div>

          {switchSources.length > 0 && (
            <div className="manga-switch">
              <span className="muted">切换线路：</span>
              {switchSources.map((s, idx) => {
                const label = formatMangaLine(idx)
                return (
                  <button key={s.id} className="mini ghost" disabled={switchLoading}
                          onClick={() => void findIn(s, label)}>{label}</button>
                )
              })}
              {switchLoading && <span className="muted" style={{ fontSize: 12 }}>加载中…</span>}
            </div>
          )}
          {(switchItems.length > 0 || switchError) && (
            <div className="switch-panel">
              <div className="switch-panel-head">
                {switchError || `${switchLabel}匹配结果`}
              </div>
              {switchItems.length > 0 && (
                <div className="switch-list">
                  {switchItems.map(r => (
                    <button key={r.source + r.id} className="switch-item"
                            onClick={() => navigate(`/manga-ext/${r.id}?source=${r.source}` +
                              `&title=${encodeURIComponent(r.title)}`)}>
                      {r.coverUrl
                        ? <img src={r.coverUrl ?? ''} alt="" loading="lazy" decoding="async" referrerPolicy="no-referrer" />
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
          <h2>章节</h2>
          <div className="row-inline">
            {officialBiliUrl && chapters.length > readable.length && (
              <button className="mini ghost" onClick={() => setShowAllBiliChapters(v => !v)}>
                {showAllBiliChapters ? '仅看可免费阅读' : '显示全部章节'}
              </button>
            )}
            <button className="mini ghost" onClick={() => { setAsc(v => !v); setChunkIdx(0) }}>{asc ? '倒序 ↓' : '正序 ↑'}</button>
          </div>
        </div>

        {useChunks && (
          <div className="row-inline ep-chunks" style={{ marginBottom: 12 }}>
            {Array.from({ length: Math.ceil(ordered.length / CHUNK) }, (_, idx) => {
              const s = idx * CHUNK + 1
              const e = Math.min(ordered.length, (idx + 1) * CHUNK)
              return (
                <button key={idx} type="button"
                        className={'mini tabular-nums ' + (idx === safeChunkIdx ? '' : 'ghost')}
                        onClick={() => setChunkIdx(idx)}>
                  {s}–{e} 话
                </button>
              )
            })}
          </div>
        )}

        {loading && <p className="muted">加载章节…</p>}
        {displayedChapters.length > 0 ? (
          <div className="chapter-grid">
            {displayedChapters.map(c => c.external && officialBiliUrl ? (
              <a key={c.id} className="chapter-cell" href={`https://manga.bilibili.com/${c.id.replace('-', '/')}`}
                 target="_blank" rel="noopener noreferrer" title={c.title || `第 ${c.chapter} 话`}>
                <span className="cc-no">{c.chapter || '?'}</span>
                <span className="cc-sub">原站</span>
              </a>
            ) : (
              <button key={c.id} className="chapter-cell" onClick={() => open(c)}
                      title={c.title || `第 ${c.chapter} 话`}>
                <span className="cc-no">{c.chapter || '?'}</span>
                <span className="cc-sub">{c.pages > 0 ? `${c.pages} 页` : ''}</span>
              </button>
            ))}
          </div>
        ) : !loading && (
          <p className="muted">
            暂无章节。
          </p>
        )}
      </section>
    </main>
  )
}

import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import AnimePosterCard from '../components/AnimePosterCard'
import CoverImage from '../components/CoverImage'
import { BookOpenIcon, HeartIcon } from '../components/Icons'
import { useToast } from '../components/Toast'
import { hdCover } from '../externalManga'
import { formatMangaLine } from '../lineLabel'
import { mangaFavKey, useMangaFavorites } from '../useMangaFavorites'

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
interface MangaRecItem {
  source: string; id: string; title: string; coverUrl: string | null; remarks: string; reason: string
}

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
 * 漫画详情页（对齐番剧详情页 CatalogDetail）：
 * 面包屑 + 吸顶锚点条 + 双栏卡片头图 + 题材胶囊 + 3 KPI 信息条 + 收藏/已读完 + 线路切换 + 卡片化章节格 + 更多推荐。
 */
export default function ExternalMangaDetail() {
  const { mangaId } = useParams()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const { me } = useAuth()
  const { showToast } = useToast()
  const recFav = useMangaFavorites()

  const source = params.get('source') ?? 'copymanga'
  const lang = 'zh'
  const [detail, setDetail] = useState<Detail | null>(null)
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
  const [recommended, setRecommended] = useState<MangaRecItem[]>([])

  const load = useCallback(async (language: string) => {
    if (!mangaId) return
    setLoading(true); setError(''); setSwitchItems([]); setSwitchError('')
    try {
      setDetail(normalizeSource(
        await api.get(`/api/v1/manga-sources/${source}/manga/${mangaId}?lang=${language}`)))
    } catch (e) {
      setError(e instanceof Error ? e.message : '详情请求失败')
    } finally { setLoading(false) }
  }, [mangaId, source])

  useEffect(() => { void load(lang) }, [lang, load])

  useEffect(() => {
    if (!mangaId || !me) { setFavorited(false); setFavStatus('WATCHING'); return }
    void api.get<{ source: string | null; mangaId: string | null; status?: 'WATCHING' | 'COMPLETED' }[]>('/api/v1/me/favorites?type=manga')
      .then(list => {
        const hit = list.find(f => f.source === source && f.mangaId === mangaId)
        setFavorited(Boolean(hit))
        setFavStatus(hit?.status === 'COMPLETED' ? 'COMPLETED' : 'WATCHING')
      })
      .catch(() => { /* 收藏状态不可用不影响详情 */ })
  }, [source, mangaId, me])

  useEffect(() => {
    void api.get<{ forYou?: MangaRecItem[] }>('/api/v1/recommendations/manga?limit=10')
      .then(res => {
        const list = (res.forYou ?? [])
          .filter(r => !(r.source === source && r.id === mangaId))
          .slice(0, 7)
        setRecommended(list)
      })
      .catch(() => setRecommended([]))
  }, [source, mangaId])

  async function toggleFavorite() {
    if (!mangaId) return
    if (!me) { showToast('请先登录后再收藏漫画', 'info'); return }
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
    if (!me) { showToast('请先登录后再标记阅读状态', 'info'); return }
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
    if (!mangaId || !me) { setResume(null); return }
    void api.get<ProgressRow[]>('/api/v1/me/manga-progress?limit=50')
      .then(rows => {
        const row = rows.find(r => r.source === source && r.mangaId === mangaId)
        if (row?.chapterId) setResume({ chapterId: row.chapterId, page: row.page })
        else setResume(null)
      })
      .catch(() => { /* 无进度 */ })
  }, [mangaId, source, me])

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
  const cleanTags = (manga.tags ?? []).filter(t => t && t.length >= 2 && t.length <= 12).slice(0, 8)
  const statusLabel = manga.status?.includes('完结') ? '已完结' : (manga.status || '连载中')

  const open = (c: ExtChapter) =>
    navigate(`/manga-ext/read/${c.id}?mangaId=${manga.id}&lang=${lang}&source=${source}` +
             `&title=${encodeURIComponent(manga.title)}`)

  return (
    <main className="watch-page">
      {/* 面包屑 */}
      <nav className="breadcrumb" aria-label="面包屑">
        <Link to="/">首页</Link>
        <span>/</span>
        <Link to="/manga">漫画</Link>
        <span>/</span>
        <span className="breadcrumb-cur">{manga.title}</span>
      </nav>

      {/* 吸顶分区锚点条 */}
      <div className="detail-anchor-bar">
        <a href="#overview" className="mini ghost btn">简介</a>
        <a href="#chapters" className="mini ghost btn">章节目录</a>
        {recommended.length > 0 && <a href="#recommend" className="mini ghost btn">更多推荐</a>}
      </div>

      {/* 双栏头图卡（对齐番剧详情页） */}
      <section id="overview" className="detail-hero card" style={{ padding: 20, borderRadius: 16 }}>
        <div className="detail-cover-col">
          <div className="detail-cover">
            <CoverImage src={hdCover(manga.coverUrl)} alt={manga.title} loading="eager" fetchPriority="high" />
          </div>
        </div>

        <div className="detail-info">
          <div className="row-inline" style={{ marginBottom: 6 }}>
            <span className="tag pub">漫画</span>
            <span className="sched-ep-tag">{statusLabel}</span>
            {favStatus === 'COMPLETED' && <span className="tag pub">✓ 已读完</span>}
          </div>
          <h1>{manga.title}</h1>
          {manga.altTitle && (
            <p className="muted" style={{ margin: '4px 0 10px' }}>{manga.altTitle}</p>
          )}

          {cleanTags.length > 0 && (
            <div className="explore-tags" style={{ margin: '10px 0 12px' }}>
              {cleanTags.map(t => (
                <Link key={t} to={`/manga?tab=index&genre=${encodeURIComponent(t)}`} className="explore-tag-pill">
                  #{t}
                </Link>
              ))}
            </div>
          )}

          <div className="detail-kpi-strip cols-3">
            <div className="detail-kpi">
              <span className="muted">连载状态</span>
              <strong>{statusLabel}</strong>
            </div>
            <div className="detail-kpi">
              <span className="muted">年份</span>
              <strong className="tabular-nums">{manga.year || '—'}</strong>
            </div>
            <div className="detail-kpi">
              <span className="muted">总话数</span>
              <strong className="tabular-nums">{readable.length > 0 ? `${readable.length} 话` : '—'}</strong>
            </div>
          </div>

          <p className={'manga-desc' + (expanded ? ' expanded' : '')} style={{ marginTop: 14 }}>
            {manga.description || '暂无简介，可直接点击下方「开始阅读」浏览章节。'}
          </p>
          {manga.description && manga.description.length > 120 && (
            <button type="button" className="link-btn" onClick={() => setExpanded(v => !v)}>
              {expanded ? '收起' : '展开'}
            </button>
          )}

          <div className="row-inline" style={{ marginTop: 16 }}>
            {startCh
              ? <button type="button" className="pill" onClick={() => open(startCh)}>
                  <BookOpenIcon size={15} />
                  {continueCh
                    ? `继续阅读（第 ${continueCh.chapter || '?'} 话${resume ? ` · 第 ${resume.page} 页` : ''}）`
                    : `开始阅读（第 ${startCh.chapter || 1} 话）`}
                </button>
              : <span className="muted">暂无章节</span>}
            <button type="button" className={'pill ' + (favorited ? '' : 'ghost')}
                    onClick={() => void toggleFavorite()}>
              <HeartIcon size={15} filled={favorited} />
              {favorited ? '已收藏' : '收藏'}
            </button>
            <button type="button" className={'pill ' + (favStatus === 'COMPLETED' ? '' : 'ghost')}
                    onClick={() => void toggleCompleted()}>
              {favStatus === 'COMPLETED' ? '✓ 已读完' : '标记已读完'}
            </button>
            {officialLink}
          </div>

          {switchSources.length > 0 && (
            <div className="manga-switch">
              <span className="muted">切换线路：</span>
              {switchSources.map((s, idx) => {
                const label = formatMangaLine(idx)
                return (
                  <button key={s.id} type="button" className="mini ghost" disabled={switchLoading}
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
                    <button key={r.source + r.id} type="button" className="switch-item"
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

      {/* 卡片化章节目录 */}
      <section id="chapters" className="card" style={{ marginTop: 24, padding: 18, borderRadius: 16 }}>
        <div className="section-head">
          <h2>章节目录</h2>
          <div className="row-inline">
            {officialBiliUrl && chapters.length > readable.length && (
              <button type="button" className="mini ghost" onClick={() => setShowAllBiliChapters(v => !v)}>
                {showAllBiliChapters ? '仅看可免费阅读' : '显示全部章节'}
              </button>
            )}
            <button type="button" className="mini ghost" onClick={() => { setAsc(v => !v); setChunkIdx(0) }}>
              {asc ? '倒序 ↓' : '正序 ↑'}
            </button>
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
                <span className="cc-sub">正版</span>
              </a>
            ) : (
              <button key={c.id} type="button" className="chapter-cell" onClick={() => open(c)}
                      title={c.title || `第 ${c.chapter} 话`}>
                <span className="cc-no">{c.chapter || '?'}</span>
                <span className="cc-sub">{c.pages > 0 ? `${c.pages} 页` : ''}</span>
              </button>
            ))}
          </div>
        ) : !loading && (
          <p className="muted" style={{ margin: 0 }}>暂无章节。</p>
        )}
      </section>

      {/* 底部更多推荐（7 列网格闭环） */}
      {recommended.length > 0 && (
        <section id="recommend" style={{ marginTop: 28 }}>
          <div className="section-head">
            <h2>更多推荐</h2>
            <Link to="/manga" className="muted">浏览更多 →</Link>
          </div>
          <div className="card-grid">
            {recommended.map(r => {
              const href = `/manga-ext/${r.id}?${new URLSearchParams({ lang: 'all', source: r.source, title: r.title })}`
              const key = mangaFavKey(r.source, r.id)
              return (
                <AnimePosterCard
                  key={`${r.source}:${r.id}`}
                  actionKind="read"
                  title={r.title}
                  coverUrl={hdCover(r.coverUrl)}
                  detailHref={href}
                  playHref={href}
                  bottomRight={r.remarks || null}
                  metaLeft="推荐漫画"
                  metaTag={r.reason}
                  favorited={recFav.isFavorited(r.source, r.id)}
                  busy={recFav.busyKey === key}
                  onToggleFavorite={() => void recFav.toggle(r.source, r.id, r.title)}
                />
              )
            })}
          </div>
        </section>
      )}
    </main>
  )
}

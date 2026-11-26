import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import AnimePosterCard from '../components/AnimePosterCard'
import CoverImage from '../components/CoverImage'
import {
  BookOpenIcon, CalendarIcon, CheckCircleIcon, HeartIcon, LayersIcon, TagIcon,
} from '../components/Icons'
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

  useEffect(() => {
    if (detail?.manga.title) {
      document.title = `${detail.manga.title} · MangaShelf`
    }
  }, [detail?.manga.title])

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
  const isCompleted = Boolean(manga.status?.includes('完结'))
  const statusLabel = isCompleted ? '已完结' : (manga.status || '连载中')
  const totalLines = switchSources.length + 1

  const open = (c: ExtChapter) =>
    navigate(`/manga-ext/read/${c.id}?mangaId=${manga.id}&lang=${lang}&source=${source}` +
             `&title=${encodeURIComponent(manga.title)}`)

  return (
    <main className="watch-page">
      {/* 面包屑 + 状态胶囊 */}
      <div className="detail-crumb-row">
        <nav className="breadcrumb" aria-label="面包屑">
          <Link to="/manga">漫画</Link>
          <span>/</span>
          <Link to="/index?kind=manga">索引</Link>
          <span>/</span>
          <span className="breadcrumb-cur">{manga.title}</span>
        </nav>
        <div className="detail-crumb-pills">
          {readable.length > 0 && (
            <span className="detail-crumb-pill is-live tabular-nums">
              <span className="detail-crumb-dot" aria-hidden="true" />
              共 {readable.length} 话可阅读
            </span>
          )}
          <span className="detail-crumb-pill">{statusLabel}</span>
        </div>
      </div>

      {/* 吸顶分区锚点条 */}
      <div className="detail-anchor-bar">
        <a href="#overview" className="mini btn active-anchor">总览介绍</a>
        <a href="#synopsis" className="mini ghost btn">剧情简介</a>
        <a href="#chapters" className="mini ghost btn">章节目录</a>
        {recommended.length > 0 && <a href="#recommend" className="mini ghost btn">更多推荐</a>}
      </div>

      {/* gugu.fan 分层总览区：左 312px 融合角标大海报 + 阅读概览副卡；右 身份/行动/KPI/足迹与线路 */}
      <section id="overview" className="detail-overview-grid">
        <div className="detail-left-col">
          <div className="detail-poster-card">
            <CoverImage src={hdCover(manga.coverUrl)} alt={manga.title} loading="eager" fetchPriority="high" />
            <div className="dpc-top-left">
              {manga.year && <span className="dpc-pill tabular-nums">{manga.year}</span>}
              <span className={'dpc-pill ' + (isCompleted ? 'is-pub' : 'is-cyan')}>{statusLabel}</span>
            </div>
            {readable.length > 0 && (
              <span className="dpc-top-right">{readable.length} 话</span>
            )}
            <div className="dpc-bottom">
              <div className="dpc-bottom-meta">
                <span className="dpc-bottom-k">{continueCh ? '上次读到' : '连载进度'}</span>
                <span className="dpc-bottom-v">
                  {continueCh
                    ? `第 ${continueCh.chapter || '?'} 话${resume ? ` · 第 ${resume.page} 页` : ''}`
                    : (readable.length > 0 ? `已收录 ${readable.length} 话` : '站内直读')}
                </span>
              </div>
              <span className="dpc-bottom-chip tabular-nums">{totalLines} 条线路</span>
            </div>
          </div>

          <div className="detail-score-card">
            <div className="detail-score-card-title">阅读概览</div>
            <div className="detail-score-tiles">
              <div className="detail-score-tile">
                <span className="detail-score-tile-k">总话数</span>
                <span className="detail-score-tile-v is-rose">
                  {readable.length > 0 ? readable.length : '—'}
                </span>
                <span className="detail-score-tile-sub">{statusLabel}</span>
              </div>
              <div className="detail-score-tile">
                <span className="detail-score-tile-k">阅读进度</span>
                <span className="detail-score-tile-v">
                  {continueCh ? `第${continueCh.chapter || '?'}话` : '未读'}
                </span>
                <span className="detail-score-tile-sub">
                  {resume ? `第 ${resume.page} 页` : '从头开始'}
                </span>
              </div>
            </div>
          </div>
        </div>

        <div className="detail-right-col">
          {/* 1. 身份头部 */}
          <div className="detail-identity">
            <div className="detail-micro-row">
              <span className="detail-micro-pill is-rose">{statusLabel}</span>
              <span className="detail-micro-pill">漫画</span>
              {manga.year && <span className="detail-micro-pill is-cyan tabular-nums">{manga.year}</span>}
              {favStatus === 'COMPLETED' && <span className="tag pub">✓ 已读完</span>}
            </div>
            <h1>{manga.title}</h1>
            {manga.altTitle && (
              <p className="detail-subtitle">作者：{manga.altTitle}</p>
            )}
            {cleanTags.length > 0 && (
              <div className="explore-tags" style={{ marginTop: 2 }}>
                {cleanTags.map(t => (
                  <Link key={t} to={`/index?kind=manga&genre=${encodeURIComponent(t)}`} className="explore-tag-pill">
                    #{t}
                  </Link>
                ))}
              </div>
            )}
          </div>

          {/* 2. 行动卡 */}
          <div className="detail-action-card">
            <div className="detail-action-status">
              <div className="detail-action-status-main">
                <span className="detail-action-dot" aria-hidden="true" />
                <span>
                  {continueCh
                    ? `上次读到 第 ${continueCh.chapter || '?'} 话${resume ? ` · 第 ${resume.page} 页` : ''}`
                    : (readable.length > 0 ? `共 ${readable.length} 话可在线阅读` : '暂无可阅读章节')}
                </span>
              </div>
              <span className="detail-action-status-sub">
                {statusLabel} · 支持单页滚动 / 双页翻页与自动下一话
              </span>
            </div>
            <div className="detail-action-btns">
              {startCh ? (
                <button type="button" className="detail-cta-primary" onClick={() => open(startCh)}>
                  <BookOpenIcon size={16} />
                  {continueCh
                    ? `继续阅读（第 ${continueCh.chapter || '?'} 话）`
                    : `开始阅读（第 ${startCh.chapter || 1} 话）`}
                </button>
              ) : (
                <span className="muted">暂无章节</span>
              )}
              <button type="button" className={'detail-cta-fav' + (favorited ? ' active' : '')}
                      onClick={() => void toggleFavorite()}>
                <HeartIcon size={16} filled={favorited} />
                {favorited ? '已收藏' : '收藏'}
              </button>
              <button type="button"
                      className={'detail-cta-circle' + (favStatus === 'COMPLETED' ? ' active' : '')}
                      title={favStatus === 'COMPLETED' ? '已读完（点击改回在追）' : '标记已读完'}
                      aria-label={favStatus === 'COMPLETED' ? '已读完' : '标记已读完'}
                      aria-pressed={favStatus === 'COMPLETED'}
                      onClick={() => void toggleCompleted()}>
                <CheckCircleIcon size={19} />
              </button>
              {officialLink}
            </div>
          </div>

          {/* 3. 四格彩色图标 KPI 卡片 */}
          <div className="detail-kpi-card">
            <div className="detail-kpi-item">
              <span className="detail-kpi-icon is-cyan"><BookOpenIcon size={20} /></span>
              <div className="detail-kpi-body">
                <span className="detail-kpi-val tabular-nums">{readable.length > 0 ? `${readable.length} 话` : '—'}</span>
                <span className="detail-kpi-lbl">可读话数</span>
              </div>
            </div>
            <div className="detail-kpi-item">
              <span className="detail-kpi-icon is-rose"><TagIcon size={19} /></span>
              <div className="detail-kpi-body">
                <span className="detail-kpi-val">{cleanTags[0] || '漫画'}</span>
                <span className="detail-kpi-lbl">{cleanTags.length > 1 ? `共 ${cleanTags.length} 个题材` : '作品分类'}</span>
              </div>
            </div>
            <div className="detail-kpi-item">
              <span className="detail-kpi-icon is-slate"><LayersIcon size={19} /></span>
              <div className="detail-kpi-body">
                <span className="detail-kpi-val tabular-nums">{totalLines} 条</span>
                <span className="detail-kpi-lbl">可用线路</span>
              </div>
            </div>
            <div className="detail-kpi-item">
              <span className="detail-kpi-icon is-amber"><CalendarIcon size={19} /></span>
              <div className="detail-kpi-body">
                <span className="detail-kpi-val tabular-nums">{manga.year || statusLabel}</span>
                <span className="detail-kpi-lbl">{manga.year ? '首发年份' : '连载状态'}</span>
              </div>
            </div>
          </div>

          {/* 4. 阅读足迹与线路切换条 */}
          <div className="detail-footprint-bar">
            <div className="detail-footprint-left">
              <span className="detail-footprint-title">阅读足迹与线路切换</span>
              <span className="detail-footprint-sub">
                {favStatus === 'COMPLETED'
                  ? '已标记为「已读完」· 阅读进度自动云端同步'
                  : favorited
                    ? '已加入收藏 · 跨设备自动同步阅读位置'
                    : '如章节不全或加载慢，可点击右侧切换其他阅读线路'}
              </span>
            </div>
            <div className="row-inline" style={{ gap: 8 }}>
              {switchSources.map((s, idx) => {
                const label = formatMangaLine(idx)
                return (
                  <button key={s.id} type="button" className="mini ghost" disabled={switchLoading}
                          onClick={() => void findIn(s, label)}>{label}</button>
                )
              })}
              {switchLoading && <span className="muted" style={{ fontSize: 12 }}>查找中…</span>}
              <a href="#chapters" className="detail-footprint-link">章节目录 →</a>
            </div>
          </div>

          {(switchItems.length > 0 || switchError) && (
            <div className="switch-panel" style={{ marginTop: 0 }}>
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

      {/* 下方 8:4 剧情简介 + 作品资料分栏 */}
      <section id="synopsis" className="detail-synopsis-grid">
        <div>
          <div className="detail-sec-head">
            <h2 className="detail-sec-title">
              <span className="detail-sec-bar" aria-hidden="true" />
              剧情简介
            </h2>
          </div>
          <div className="detail-synopsis-card">
            <p className="detail-synopsis-text">
              {manga.description || '暂无简介，可直接点击上方「开始阅读」或下方章节目录浏览。'}
            </p>
          </div>
        </div>

        <div>
          <div className="detail-sec-head">
            <h2 className="detail-sec-title">
              <span className="detail-sec-bar is-rose" aria-hidden="true" />
              作品资料
            </h2>
          </div>
          <div className="detail-facts-card">
            <dl className="detail-facts-dl">
              <div className="detail-facts-row">
                <dt>类型</dt>
                <dd>漫画</dd>
              </div>
              <div className="detail-facts-row">
                <dt>状态</dt>
                <dd>{statusLabel}</dd>
              </div>
              <div className="detail-facts-row">
                <dt>作者</dt>
                <dd title={manga.altTitle || ''}>{manga.altTitle || '—'}</dd>
              </div>
              <div className="detail-facts-row">
                <dt>年份</dt>
                <dd className="tabular-nums">{manga.year || '—'}</dd>
              </div>
              <div className="detail-facts-row">
                <dt>总话数</dt>
                <dd className="tabular-nums">{readable.length > 0 ? `共 ${readable.length} 话` : '—'}</dd>
              </div>
              {cleanTags.length > 0 && (
                <div className="detail-facts-row">
                  <dt>题材</dt>
                  <dd title={cleanTags.join(' / ')}>{cleanTags.slice(0, 4).join(' / ')}</dd>
                </div>
              )}
            </dl>
          </div>
        </div>
      </section>

      {/* 卡片化章节目录 */}
      <section id="chapters" style={{ marginTop: 32 }}>
        <div className="detail-sec-head">
          <h2 className="detail-sec-title">
            <span className="detail-sec-bar" aria-hidden="true" />
            章节目录
          </h2>
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
        <div className="card" style={{ padding: 20, borderRadius: 16 }}>
          {useChunks && (
            <div className="row-inline ep-chunks" style={{ marginBottom: 14 }}>
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
        </div>
      </section>

      {/* 底部更多推荐 */}
      {recommended.length > 0 && (
        <section id="recommend" style={{ marginTop: 32 }}>
          <div className="detail-sec-head">
            <h2 className="detail-sec-title">
              <span className="detail-sec-bar is-rose" aria-hidden="true" />
              更多推荐
            </h2>
            <Link to="/manga" className="detail-footprint-link">浏览更多 →</Link>
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

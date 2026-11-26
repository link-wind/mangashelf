import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import AnimePosterCard from '../components/AnimePosterCard'
import CoverImage from '../components/CoverImage'
import { HeartIcon, PlayIcon } from '../components/Icons'
import { useToast } from '../components/Toast'
import { formatAnimeLine } from '../lineLabel'
import { useCatalogFavorites } from '../useCatalogFavorites'

interface CatalogItem {
  id: string; sourceId: string; name: string; coverUrl: string; remarks: string
  categoryId: number; categoryName: string; episodeCount: number; workId: string | null
  bangumiSubjectId?: number | null
}
interface CatalogDetailData {
  item: CatalogItem
  bangumiName: string | null
  bangumiNameCn: string | null
  rating: number | null
  summary: string | null
  airWeekday: number | null
  airDate: string | null
  tags: string[]
  lines: { name: string; episodeCount: number }[]
}
interface BrowseEpisode {
  id: string; title: string; ordinal: number; lineName: string
  lineRank: number; episodeType: 'MAIN' | 'EXTRA'
}
interface BrowseLine { name: string; rank: number; episodes: BrowseEpisode[] }
interface SimilarAnime {
  catalogItemId: string; workId: string | null; title: string
  coverUrl: string; rating: number | null; reason: string; remarks: string
}

const WEEKDAYS = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']
const NOISE_TAGS = new Set(['TV', '日本', '中国', 'OVA', 'WEB', '动画', '番剧'])
const EP_CHUNK = 24

/**
 * 番剧资料详情页（P2 重构）：
 * 面包屑 + 高清双栏头图 + 可点击题材标签 + 4 KPI 指标 + 收藏/已看完状态 + 吸顶分区锚点 + 内嵌选集 + 相似推荐。
 */
export default function CatalogDetail() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { me } = useAuth()
  const { showToast } = useToast()
  const { favIds, busyId: simBusyId, toggle: toggleSimFav } = useCatalogFavorites()

  const [detail, setDetail] = useState<CatalogDetailData | null>(null)
  const [browseLines, setBrowseLines] = useState<BrowseLine[]>([])
  const [activeLine, setActiveLine] = useState('')
  const [epReverse, setEpReverse] = useState(false)
  const [epChunk, setEpChunk] = useState(0)
  const [similar, setSimilar] = useState<SimilarAnime[]>([])
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [favorited, setFavorited] = useState(false)
  const [favStatus, setFavStatus] = useState<'WATCHING' | 'COMPLETED'>('WATCHING')
  const [expanded, setExpanded] = useState(false)

  const load = useCallback(async () => {
    if (!id) return
    setError('')
    try {
      const d = await api.get<CatalogDetailData>(`/api/v1/catalog-items/${id}`)
      setDetail(d)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败')
    }
  }, [id])

  useEffect(() => { void load() }, [load])

  useEffect(() => {
    if (!id) return
    void api.get<{ lines: BrowseLine[] }>(`/api/v1/catalog-items/${id}/episodes`)
      .then(res => {
        setBrowseLines(res.lines ?? [])
        if (res.lines?.length) setActiveLine(res.lines[0].name)
      })
      .catch(() => setBrowseLines([]))
    void api.get<SimilarAnime[]>(`/api/v1/recommendations/anime/${id}/similar?limit=7`)
      .then(setSimilar)
      .catch(() => setSimilar([]))
  }, [id])

  useEffect(() => {
    if (!id || !me) { setFavorited(false); setFavStatus('WATCHING'); return }
    void api.get<{ catalogItemId: string | null; status?: 'WATCHING' | 'COMPLETED' }[]>('/api/v1/me/favorites?type=catalog')
      .then(list => {
        const hit = list.find(f => f.catalogItemId === id)
        setFavorited(Boolean(hit))
        setFavStatus(hit?.status === 'COMPLETED' ? 'COMPLETED' : 'WATCHING')
      })
      .catch(() => { /* 收藏状态不可用不影响详情 */ })
  }, [id, me])

  async function toggleFavorite() {
    if (!id) return
    if (!me) { showToast('请先登录后再收藏', 'info'); return }
    try {
      if (favorited) {
        await api.delete(`/api/v1/me/favorites/catalog/${id}`)
        setFavorited(false)
        setFavStatus('WATCHING')
        showToast('已取消收藏', 'info')
      } else {
        await api.put(`/api/v1/me/favorites/catalog/${id}`)
        setFavorited(true)
        setFavStatus('WATCHING')
        showToast('已加入「我的」追番', 'success')
      }
    } catch (e) {
      const msg = e instanceof Error ? e.message : '收藏操作失败'
      setError(msg)
      showToast(msg, 'error')
    }
  }

  async function toggleCompleted() {
    if (!id) return
    if (!me) { showToast('请先登录后再标记观看状态', 'info'); return }
    const nextStatus = favStatus === 'COMPLETED' ? 'WATCHING' : 'COMPLETED'
    try {
      await api.put(`/api/v1/me/favorites/catalog/${id}/status?status=${nextStatus}`)
      setFavorited(true)
      setFavStatus(nextStatus)
      showToast(nextStatus === 'COMPLETED' ? '已标记为「已看完」' : '已改回「在追」', 'success')
    } catch (e) {
      const msg = e instanceof Error ? e.message : '更新状态失败'
      showToast(msg, 'error')
    }
  }

  async function materialize() {
    if (!id) return
    setBusy(true)
    try {
      const res = await api.post<{ workId: string }>(`/api/v1/catalog-items/${id}/materialize`, {})
      navigate(`/works/${res.workId}`)
    } catch (e) {
      setError(e instanceof Error ? e.message : '导入失败')
    } finally { setBusy(false) }
  }

  const lineEpisodes = useMemo(() => {
    const raw = browseLines.find(l => l.name === activeLine)?.episodes ?? []
    const sorted = [...raw].sort((a, b) => a.ordinal - b.ordinal)
    return epReverse ? sorted.reverse() : sorted
  }, [browseLines, activeLine, epReverse])

  const useChunks = lineEpisodes.length > 30
  const safeChunk = useChunks ? Math.min(epChunk, Math.floor((lineEpisodes.length - 1) / EP_CHUNK)) : 0
  const visibleEpisodes = useChunks
    ? lineEpisodes.slice(safeChunk * EP_CHUNK, (safeChunk + 1) * EP_CHUNK)
    : lineEpisodes

  useEffect(() => {
    const t = detail?.bangumiNameCn || detail?.item.name
    if (t) document.title = `${t} · MangaShelf`
  }, [detail?.bangumiNameCn, detail?.item.name])

  if (error) return <main className="watch-page"><p className="error">{error}</p></main>
  if (!detail) return <main className="watch-page page-center">加载中…</main>

  const { item } = detail
  const displayTitle = detail.bangumiNameCn || item.name
  const heroCoverSrc = item.bangumiSubjectId
    ? `/api/v1/bangumi-covers/${item.bangumiSubjectId}?size=large`
    : item.coverUrl
  const cleanTags = (detail.tags ?? []).filter(t => t && t.length >= 2 && t.length <= 10 && !NOISE_TAGS.has(t) && !/^\d/.test(t)).slice(0, 10)

  return (
    <main className="watch-page">
      {/* 面包屑 */}
      <nav className="breadcrumb" aria-label="面包屑">
        <Link to="/">番剧</Link>
        <span>/</span>
        <Link to="/index">索引</Link>
        {item.categoryId ? (
          <>
            <span>/</span>
            <Link to={`/index?category=${item.categoryId}`}>{item.categoryName}</Link>
          </>
        ) : null}
        <span>/</span>
        <span className="breadcrumb-cur">{displayTitle}</span>
      </nav>

      {/* 吸顶分区锚点条 */}
      <div className="detail-anchor-bar">
        <a href="#overview" className="mini ghost btn">简介</a>
        <a href="#episodes" className="mini ghost btn">选集播放</a>
        {similar.length > 0 && <a href="#similar" className="mini ghost btn">相似推荐</a>}
      </div>

      {/* gugufan 式总览头卡：左侧放大竖版大海报 + 柔和氛围背景 + 右侧结构化信息 */}
      <section id="overview" className="detail-hero card">
        {heroCoverSrc && (
          <div className="detail-hero-bg" style={{ backgroundImage: `url(${heroCoverSrc})` }} aria-hidden="true" />
        )}
        <div className="detail-cover-col">
          <div className="detail-cover">
            <CoverImage
              src={heroCoverSrc}
              alt={displayTitle}
              loading="eager"
              fetchPriority="high"
            />
          </div>
        </div>

        <div className="detail-info">
          <div className="row-inline" style={{ marginBottom: 8 }}>
            <span className="tag pub">{item.categoryName || '番剧'}</span>
            {detail.airWeekday && <span className="sched-ep-tag">每{WEEKDAYS[detail.airWeekday]}放送</span>}
            {favStatus === 'COMPLETED' && <span className="tag pub">✓ 已看完</span>}
          </div>
          <div className="detail-title-row">
            <h1>{displayTitle}</h1>
            {typeof detail.rating === 'number' && detail.rating > 0 && (
              <span className="detail-score-pill" title="Bangumi 评分">
                ★ {detail.rating.toFixed(1)}
              </span>
            )}
          </div>
          {detail.bangumiName && detail.bangumiName !== displayTitle && (
            <p className="muted" style={{ margin: '6px 0 0', fontSize: 13.5 }}>{detail.bangumiName}</p>
          )}

          {cleanTags.length > 0 && (
            <div className="explore-tags" style={{ margin: '12px 0 2px' }}>
              {cleanTags.map(t => (
                <Link key={t} to={`/index?genre=${encodeURIComponent(t)}`} className="explore-tag-pill">
                  #{t}
                </Link>
              ))}
            </div>
          )}

          <div className="detail-meta-grid">
            <div className="detail-meta-item">
              <span className="detail-meta-k">状态</span>
              <span className="detail-meta-v">{item.remarks || `共 ${item.episodeCount} 集`}</span>
            </div>
            <div className="detail-meta-item">
              <span className="detail-meta-k">首播</span>
              <span className="detail-meta-v tabular-nums">
                {detail.airDate || (detail.airWeekday ? `每${WEEKDAYS[detail.airWeekday]}` : '—')}
              </span>
            </div>
            <div className="detail-meta-item">
              <span className="detail-meta-k">集数</span>
              <span className="detail-meta-v tabular-nums">{item.episodeCount > 0 ? `共 ${item.episodeCount} 集` : '—'}</span>
            </div>
            <div className="detail-meta-item">
              <span className="detail-meta-k">线路</span>
              <span className="detail-meta-v tabular-nums">{detail.lines.length || browseLines.length} 条可用</span>
            </div>
          </div>

          <div className="detail-intro-box">
            <div className="detail-intro-label">简介</div>
            <p className={'manga-desc detail-summary' + (expanded ? ' expanded' : '')}>
              {detail.summary || '暂无简介，可直接点击下方「立即播放」观看。'}
            </p>
            {detail.summary && detail.summary.length > 140 && (
              <button type="button" className="link-btn" onClick={() => setExpanded(v => !v)}>
                {expanded ? '收起简介' : '展开完整简介'}
              </button>
            )}
          </div>

          <div className="detail-actions">
            <Link className="btn pill" to={`/browse/${item.id}`}>
              <PlayIcon size={15} />立即播放
            </Link>
            <button type="button" className={'pill ' + (favorited ? '' : 'ghost')}
                    onClick={() => void toggleFavorite()}>
              <HeartIcon size={15} filled={favorited} />{favorited ? '已收藏' : '收藏'}
            </button>
            <button type="button" className={'pill ' + (favStatus === 'COMPLETED' ? '' : 'ghost')}
                    onClick={() => void toggleCompleted()}>
              {favStatus === 'COMPLETED' ? '✓ 已看完' : '标记已看完'}
            </button>
            {me?.role === 'admin' && (item.workId
              ? <Link className="btn ghost pill" to={`/works/${item.workId}`}>管理已导入作品</Link>
              : (
                <button type="button" className="ghost pill" disabled={busy} onClick={() => void materialize()}
                        title="管理员操作：创建本地作品并关联剧集">
                  {busy ? '处理中…' : '导入作品'}
                </button>
              ))}
          </div>
        </div>
      </section>

      {/* 内嵌多线路分集选集 */}
      <section id="episodes" className="card" style={{ marginTop: 24, padding: 18, borderRadius: 16 }}>
        <div className="section-head">
          <h2>选集播放</h2>
          {lineEpisodes.length > 1 && (
            <button type="button" className="mini ghost"
                    onClick={() => { setEpReverse(v => !v); setEpChunk(0) }}>
              {epReverse ? '倒序 ↓' : '正序 ↑'}
            </button>
          )}
        </div>
        {browseLines.length > 0 && (
          <div className="row-inline" style={{ marginBottom: 12 }}>
            {browseLines.map((l, idx) => (
              <button key={l.name} type="button"
                      className={'mini ' + (l.name === activeLine ? '' : 'ghost')}
                      onClick={() => { setActiveLine(l.name); setEpChunk(0) }}>
                {formatAnimeLine(idx)}
              </button>
            ))}
          </div>
        )}
        {useChunks && (
          <div className="row-inline ep-chunks" style={{ marginBottom: 12 }}>
            {Array.from({ length: Math.ceil(lineEpisodes.length / EP_CHUNK) }, (_, idx) => {
              const slice = lineEpisodes.slice(idx * EP_CHUNK, (idx + 1) * EP_CHUNK)
              return (
                <button key={idx} type="button"
                        className={'mini tabular-nums ' + (idx === safeChunk ? '' : 'ghost')}
                        onClick={() => setEpChunk(idx)}>
                  {String(slice[0].ordinal).padStart(2, '0')}–{String(slice[slice.length - 1].ordinal).padStart(2, '0')}
                </button>
              )
            })}
          </div>
        )}
        {visibleEpisodes.length > 0 ? (
          <div className="episode-grid">
            {visibleEpisodes.map(e => (
              <Link key={e.id} to={`/browse/${item.id}/${e.id}`} className="episode-cell" style={{ textDecoration: 'none' }}>
                <div className="no tabular-nums">{String(e.ordinal).padStart(2, '0')}</div>
                <div className="name">{e.title}</div>
              </Link>
            ))}
          </div>
        ) : (
          <p className="muted" style={{ margin: 0 }}>暂无剧集，点击上方「立即播放」尝试加载。</p>
        )}
      </section>

      {/* 相似推荐 */}
      {similar.length > 0 && (
        <section id="similar" style={{ marginTop: 28 }}>
          <div className="section-head">
            <h2>相似推荐</h2>
            <Link to="/index" className="muted">浏览更多 →</Link>
          </div>
          <div className="card-grid">
            {similar.map(s => (
              <AnimePosterCard
                key={s.catalogItemId}
                title={s.title}
                coverUrl={s.coverUrl}
                detailHref={s.workId ? `/works/${s.workId}` : `/catalog/${s.catalogItemId}`}
                playHref={s.workId ? `/works/${s.workId}` : `/browse/${s.catalogItemId}`}
                rating={s.rating}
                bottomRight={s.remarks}
                metaLeft="相似推荐"
                metaTag={s.reason}
                favorited={favIds.has(s.catalogItemId)}
                busy={simBusyId === s.catalogItemId}
                onToggleFavorite={() => void toggleSimFav(s.catalogItemId, s.title)}
              />
            ))}
          </div>
        </section>
      )}
    </main>
  )
}

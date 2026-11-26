import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import AnimePosterCard from '../components/AnimePosterCard'
import CoverImage from '../components/CoverImage'
import {
  CalendarIcon, CheckCircleIcon, FilmIcon, HeartIcon, LayersIcon, PlayIcon, StarIcon,
} from '../components/Icons'
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
  const isCompleted = Boolean(item.remarks && item.remarks.includes('完结') && !item.remarks.includes('更新'))
  const statusLabel = isCompleted ? '已完结' : '连载中'
  const airQuarter = detail.airDate ? detail.airDate.slice(0, 7) : ''
  const lineCount = detail.lines.length || browseLines.length

  return (
    <main className="watch-page">
      {/* 面包屑 + 右侧播映状态胶囊 */}
      <div className="detail-crumb-row">
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
        <div className="detail-crumb-pills">
          {detail.airWeekday && (
            <span className="detail-crumb-pill is-live">
              <span className="detail-crumb-dot" aria-hidden="true" />
              每{WEEKDAYS[detail.airWeekday]}更新
            </span>
          )}
          <span className="detail-crumb-pill">{statusLabel}</span>
        </div>
      </div>

      {/* 吸顶分区锚点条 */}
      <div className="detail-anchor-bar">
        <a href="#overview" className="mini btn active-anchor">总览介绍</a>
        <a href="#synopsis" className="mini ghost btn">剧情简介</a>
        <a href="#episodes" className="mini ghost btn">选集播放</a>
        {similar.length > 0 && <a href="#similar" className="mini ghost btn">相似推荐</a>}
      </div>

      {/* gugu.fan 分层总览区：左 312px 融合角标大海报 + 评分副卡；右 身份/行动/KPI/足迹 */}
      <section id="overview" className="detail-overview-grid">
        <div className="detail-left-col">
          <div className="detail-poster-card">
            <CoverImage
              src={heroCoverSrc}
              alt={displayTitle}
              loading="eager"
              fetchPriority="high"
            />
            <div className="dpc-top-left">
              {airQuarter && <span className="dpc-pill tabular-nums">{airQuarter}</span>}
              <span className={'dpc-pill ' + (isCompleted ? 'is-pub' : 'is-cyan')}>{statusLabel}</span>
            </div>
            {typeof detail.rating === 'number' && detail.rating > 0 && (
              <span className="dpc-top-right" title="Bangumi 评分">★ {detail.rating.toFixed(1)}</span>
            )}
            <div className="dpc-bottom">
              <div className="dpc-bottom-meta">
                <span className="dpc-bottom-k">更新进度</span>
                <span className="dpc-bottom-v">{item.remarks || (item.episodeCount > 0 ? `共 ${item.episodeCount} 集` : '可在线播放')}</span>
              </div>
              {lineCount > 0 && <span className="dpc-bottom-chip tabular-nums">{lineCount} 条线路</span>}
            </div>
          </div>

          <div className="detail-score-card">
            <div className="detail-score-card-title">作品概览</div>
            <div className="detail-score-tiles">
              <div className="detail-score-tile">
                <span className="detail-score-tile-k">Bangumi</span>
                <span className={'detail-score-tile-v' + (detail.rating ? ' is-rose' : '')}>
                  {typeof detail.rating === 'number' && detail.rating > 0 ? detail.rating.toFixed(1) : '—'}
                </span>
                <span className="detail-score-tile-sub">{detail.rating ? '作品评分' : '暂无评分'}</span>
              </div>
              <div className="detail-score-tile">
                <span className="detail-score-tile-k">剧集数</span>
                <span className="detail-score-tile-v">
                  {item.episodeCount > 0 ? item.episodeCount : '—'}
                </span>
                <span className="detail-score-tile-sub">{isCompleted ? '全季完结' : '持续更新'}</span>
              </div>
            </div>
          </div>
        </div>

        <div className="detail-right-col">
          {/* 1. 身份头部 */}
          <div className="detail-identity">
            <div className="detail-micro-row">
              <span className="detail-micro-pill is-rose">{statusLabel}</span>
              <span className="detail-micro-pill">{item.categoryName || '番剧'}</span>
              {detail.airWeekday && (
                <span className="detail-micro-pill is-cyan">每{WEEKDAYS[detail.airWeekday]}更新</span>
              )}
              {favStatus === 'COMPLETED' && <span className="tag pub">✓ 已看完</span>}
            </div>
            <h1>{displayTitle}</h1>
            {detail.bangumiName && detail.bangumiName !== displayTitle && (
              <p className="detail-subtitle">{detail.bangumiName}</p>
            )}
            {cleanTags.length > 0 && (
              <div className="explore-tags" style={{ marginTop: 2 }}>
                {cleanTags.map(t => (
                  <Link key={t} to={`/index?genre=${encodeURIComponent(t)}`} className="explore-tag-pill">
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
                <span>{item.remarks || (item.episodeCount > 0 ? `共 ${item.episodeCount} 集可观看` : '可在线播放')}</span>
              </div>
              <span className="detail-action-status-sub">
                {lineCount > 0 ? `${lineCount} 条可用播放线路` : '点击立即开始观看'}
                {item.episodeCount > 0 ? ` · 共 ${item.episodeCount} 集` : ''}
              </span>
            </div>
            <div className="detail-action-btns">
              <Link className="detail-cta-primary" to={`/browse/${item.id}`}>
                <PlayIcon size={16} />立即前往播放
              </Link>
              <button type="button" className={'detail-cta-fav' + (favorited ? ' active' : '')}
                      onClick={() => void toggleFavorite()}>
                <HeartIcon size={16} filled={favorited} />
                {favorited ? '已追番' : '追番'}
              </button>
              <button type="button"
                      className={'detail-cta-circle' + (favStatus === 'COMPLETED' ? ' active' : '')}
                      title={favStatus === 'COMPLETED' ? '已看完（点击改回在追）' : '标记已看完'}
                      aria-label={favStatus === 'COMPLETED' ? '已看完' : '标记已看完'}
                      aria-pressed={favStatus === 'COMPLETED'}
                      onClick={() => void toggleCompleted()}>
                <CheckCircleIcon size={19} />
              </button>
            </div>
          </div>

          {/* 3. 四格彩色图标 KPI 卡片 */}
          <div className="detail-kpi-card">
            <div className="detail-kpi-item">
              <span className="detail-kpi-icon is-cyan"><FilmIcon size={20} /></span>
              <div className="detail-kpi-body">
                <span className="detail-kpi-val tabular-nums">{item.episodeCount > 0 ? `${item.episodeCount} 集` : '—'}</span>
                <span className="detail-kpi-lbl">全季集数</span>
              </div>
            </div>
            <div className="detail-kpi-item">
              <span className="detail-kpi-icon is-rose"><StarIcon size={19} /></span>
              <div className="detail-kpi-body">
                <span className="detail-kpi-val tabular-nums">
                  {typeof detail.rating === 'number' && detail.rating > 0 ? detail.rating.toFixed(1) : '—'}
                </span>
                <span className="detail-kpi-lbl">作品评分</span>
              </div>
            </div>
            <div className="detail-kpi-item">
              <span className="detail-kpi-icon is-slate"><LayersIcon size={19} /></span>
              <div className="detail-kpi-body">
                <span className="detail-kpi-val tabular-nums">{lineCount > 0 ? `${lineCount} 条` : '—'}</span>
                <span className="detail-kpi-lbl">可用线路</span>
              </div>
            </div>
            <div className="detail-kpi-item">
              <span className="detail-kpi-icon is-amber"><CalendarIcon size={19} /></span>
              <div className="detail-kpi-body">
                <span className="detail-kpi-val tabular-nums">
                  {detail.airDate ? detail.airDate.slice(0, 7) : (detail.airWeekday ? `每${WEEKDAYS[detail.airWeekday]}` : '—')}
                </span>
                <span className="detail-kpi-lbl">首播 / 放送</span>
              </div>
            </div>
          </div>

          {/* 4. 追番足迹条 */}
          <div className="detail-footprint-bar">
            <div className="detail-footprint-left">
              <span className="detail-footprint-title">我的追番足迹</span>
              <span className="detail-footprint-sub">
                {favStatus === 'COMPLETED'
                  ? '已标记为「已看完」· 与个人空间实时同步'
                  : favorited
                    ? '已加入「在追」· 更新与观看进度自动同步'
                    : '点击「追番」后，观看进度与更新提醒与个人空间实时同步'}
              </span>
            </div>
            <div className="row-inline" style={{ gap: 12 }}>
              {me?.role === 'admin' && (item.workId
                ? <Link className="mini ghost btn" to={`/works/${item.workId}`}>管理已导入作品</Link>
                : (
                  <button type="button" className="mini ghost" disabled={busy} onClick={() => void materialize()}
                          title="管理员操作：创建本地作品并关联剧集">
                    {busy ? '处理中…' : '导入作品'}
                  </button>
                ))}
              <a href="#episodes" className="detail-footprint-link">查看分集列表 →</a>
            </div>
          </div>
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
              {detail.summary || '暂无简介，可直接点击上方「立即前往播放」或下方分集列表观看。'}
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
                <dt>分区</dt>
                <dd>{item.categoryName || '番剧'}</dd>
              </div>
              <div className="detail-facts-row">
                <dt>状态</dt>
                <dd>{item.remarks || statusLabel}</dd>
              </div>
              <div className="detail-facts-row">
                <dt>首播时间</dt>
                <dd className="tabular-nums">{detail.airDate || '—'}</dd>
              </div>
              <div className="detail-facts-row">
                <dt>更新周期</dt>
                <dd>{detail.airWeekday ? `每${WEEKDAYS[detail.airWeekday]}` : '—'}</dd>
              </div>
              <div className="detail-facts-row">
                <dt>总集数</dt>
                <dd className="tabular-nums">{item.episodeCount > 0 ? `共 ${item.episodeCount} 集` : '—'}</dd>
              </div>
              <div className="detail-facts-row">
                <dt>可用线路</dt>
                <dd className="tabular-nums">{lineCount > 0 ? `${lineCount} 条` : '—'}</dd>
              </div>
              {detail.bangumiName && detail.bangumiName !== displayTitle && (
                <div className="detail-facts-row">
                  <dt>原名</dt>
                  <dd title={detail.bangumiName}>{detail.bangumiName}</dd>
                </div>
              )}
            </dl>
          </div>
        </div>
      </section>

      {/* 内嵌多线路分集选集 */}
      <section id="episodes" style={{ marginTop: 32 }}>
        <div className="detail-sec-head">
          <h2 className="detail-sec-title">
            <span className="detail-sec-bar" aria-hidden="true" />
            选集播放
          </h2>
          {lineEpisodes.length > 1 && (
            <button type="button" className="mini ghost"
                    onClick={() => { setEpReverse(v => !v); setEpChunk(0) }}>
              {epReverse ? '倒序 ↓' : '正序 ↑'}
            </button>
          )}
        </div>
        <div className="card" style={{ padding: 20, borderRadius: 16 }}>
          {browseLines.length > 0 && (
            <div className="row-inline" style={{ marginBottom: 14 }}>
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
            <div className="row-inline ep-chunks" style={{ marginBottom: 14 }}>
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
            <p className="muted" style={{ margin: 0 }}>暂无剧集，点击上方「立即前往播放」尝试加载。</p>
          )}
        </div>
      </section>

      {/* 相似推荐 */}
      {similar.length > 0 && (
        <section id="similar" style={{ marginTop: 32 }}>
          <div className="detail-sec-head">
            <h2 className="detail-sec-title">
              <span className="detail-sec-bar is-rose" aria-hidden="true" />
              相似推荐
            </h2>
            <Link to="/index" className="detail-footprint-link">浏览更多 →</Link>
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

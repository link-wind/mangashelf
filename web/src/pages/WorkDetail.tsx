import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import ExternalEpisodes from './ExternalEpisodes'
import { api, ApiError } from '../api'
import CoverImage from '../components/CoverImage'
import { BookOpenIcon, HeartIcon, UploadIcon } from '../components/Icons'
import { useToast } from '../components/Toast'
import EmptyState from '../components/ui/EmptyState'
import { localizeWorkStatus, splitBilingualSummary } from '../lib/mediaFormat'
import { formatAnimeLine } from '../lineLabel'

interface Chapter {
  id: string
  ordinal: number
  title: string
  currentRevisionId: string | null
}

interface WorkExternalMeta {
  coverUrl: string | null
  catalogTitle: string | null
  rating: number | null
  summary: string | null
  airWeekday: number | null
  lines: string[]
}

interface WorkView {
  work: { id: string; title: string; description: string; status: string; visibility: string; ownerId: string }
  chapters: Chapter[]
  owner: boolean
}

export default function WorkDetail() {
  const { id } = useParams()
  const { showToast } = useToast()
  const [meta, setMeta] = useState<WorkExternalMeta | null>(null)
  const [data, setData] = useState<WorkView | null>(null)
  const [saved, setSaved] = useState(false)
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

  useEffect(() => {
    if (!id) return
    void api.get<WorkExternalMeta | null>(`/api/v1/works/${id}/external-meta`)
      .then(m => setMeta(m))
      .catch(() => setMeta(null))
  }, [id])

  useEffect(() => {
    if (data?.work.title) {
      document.title = `${data.work.title} · MangaShelf`
    }
  }, [data?.work.title])

  // 纯外部动画作品：点进作品即进播放页（与参考站一致）
  useEffect(() => {
    if (!id || !data) return
    if (data.chapters.length > 0) return
    void api.get<{ episodeId: string } | null>(`/api/v1/works/${id}/play-target`)
      .then(target => {
        if (target?.episodeId) {
          navigate(`/works/${id}/external/${target.episodeId}`, { replace: true })
        }
      })
      .catch(() => { /* 无外部集则停留详情页 */ })
  }, [id, data, navigate])

  async function addToShelf() {
    try {
      await api.put(`/api/v1/me/favorites/work/${id}`)
      setSaved(true)
      showToast('已加入收藏', 'success')
      setError('')
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : '操作失败'
      setError(msg)
      showToast(msg, 'error')
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
      showToast('作品已下架', 'info')
      await load()
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : '操作失败'
      setError(msg)
      showToast(msg, 'error')
    }
  }

  if (error && !data) return <main className="watch-page"><p className="error">{error}</p></main>
  if (!data) return <main className="watch-page page-center">加载中…</main>

  const { work, chapters, owner } = data
  const statusInfo = localizeWorkStatus(work.status)
  const zhSummary = splitBilingualSummary(meta?.summary || work.description).zh
  const firstReadable = chapters.find(c => Boolean(c.currentRevisionId))

  return (
    <main className="watch-page">
      <div className="detail-crumb-row">
        <nav className="breadcrumb" aria-label="面包屑">
          <Link to="/">首页</Link>
          <span>/</span>
          <Link to="/uploads">我的素材</Link>
          <span>/</span>
          <span className="breadcrumb-cur">{work.title}</span>
        </nav>
      </div>

      <section className="detail-overview-grid">
        <div className="detail-left-col">
          <div className="detail-poster-card">
            <CoverImage src={meta?.coverUrl ?? null} alt={work.title} loading="eager" fetchPriority="high" />
            <div className="dpc-top-left">
              <span className={'dpc-pill ' + (work.visibility === 'PUBLIC' ? 'is-pub' : 'is-cyan')}>
                {work.visibility === 'PUBLIC' ? '公开' : '私人'}
              </span>
            </div>
            {typeof meta?.rating === 'number' && meta.rating > 0 && (
              <span className="dpc-top-right tabular-nums">★ {meta.rating.toFixed(1)}</span>
            )}
            <div className="dpc-bottom">
              <div className="dpc-bottom-meta">
                <span className="dpc-bottom-k">作品状态</span>
                <span className="dpc-bottom-v">{statusInfo.label}</span>
              </div>
              {chapters.length > 0 && <span className="dpc-bottom-chip tabular-nums">{chapters.length} 话</span>}
            </div>
          </div>
        </div>

        <div className="detail-right-col">
          <div className="detail-identity">
            <div className="detail-micro-row">
              <span className="detail-micro-pill is-cyan">{statusInfo.label}</span>
              <span className="detail-micro-pill">{work.visibility === 'PUBLIC' ? '公开' : '私人'}</span>
              {meta?.airWeekday && (
                <span className="detail-micro-pill is-rose">
                  每{['', '周一', '周二', '周三', '周四', '周五', '周六', '周日'][meta.airWeekday]}放送
                </span>
              )}
              {meta && meta.lines.length > 0 && (
                <span className="detail-micro-pill">
                  {meta.lines.map((_, i) => formatAnimeLine(i)).join(' / ')}
                </span>
              )}
            </div>
            <h1>{work.title}</h1>
            {zhSummary && <p className="detail-synopsis-text" style={{ marginTop: 6 }}>{zhSummary}</p>}
          </div>

          <div className="detail-action-card">
            <div className="detail-action-status">
              <div className="detail-action-status-main">
                <span className="detail-action-dot" aria-hidden="true" />
                <span>{chapters.length > 0 ? `共 ${chapters.length} 话章节` : '暂无本地章节'}</span>
              </div>
              <span className="detail-action-status-sub">
                {owner ? '你是此作品的创建者，可在下方管理关联剧集或前往素材中心编辑' : '点击章节即可开始阅读'}
              </span>
            </div>
            <div className="detail-action-btns">
              {firstReadable && (
                <button type="button" className="detail-cta-primary"
                        onClick={() => void readLink(firstReadable).then(url => navigate(url))}>
                  <BookOpenIcon size={16} />
                  开始阅读（第 {firstReadable.ordinal} 话）
                </button>
              )}
              {!owner && (
                <button type="button" className={'detail-cta-fav' + (saved ? ' active' : '')}
                        onClick={() => void addToShelf()}>
                  <HeartIcon size={16} filled={saved} />
                  {saved ? '已收藏' : '加入收藏'}
                </button>
              )}
              {owner && (
                <button type="button" className="ghost pill" onClick={() => navigate('/uploads')}>
                  <UploadIcon size={15} />
                  管理素材
                </button>
              )}
              {owner && work.status === 'PUBLISHED' && (
                <button type="button" className="ghost pill" onClick={() => void withdraw()}>下架作品</button>
              )}
            </div>
          </div>
        </div>
      </section>

      {error && <p className="error" style={{ marginTop: 14 }}>{error}</p>}

      <section style={{ marginTop: 28 }}>
        <div className="detail-sec-head">
          <h2 className="detail-sec-title">
            <span className="detail-sec-bar" aria-hidden="true" />
            章节目录（{chapters.length}）
          </h2>
        </div>
        <div className="card" style={{ padding: 20, borderRadius: 16 }}>
          {chapters.length === 0 ? (
            <EmptyState
              compact
              icon={<BookOpenIcon size={20} />}
              title="还没有已发布章节"
              description={owner ? '你可以在「我的素材」上传章节，或在下方挂载外部动画分集。' : '暂未发布可读章节。'}
            />
          ) : (
            <div className="chapter-grid">
              {chapters.map(c => c.currentRevisionId ? (
                <button
                  key={c.id}
                  type="button"
                  className="chapter-cell"
                  title={c.title || `第 ${c.ordinal} 话`}
                  onClick={() => void readLink(c).then(url => navigate(url))}
                >
                  <span className="cc-no">第 {c.ordinal} 话</span>
                  <span className="cc-sub">{c.title || '阅读'}</span>
                </button>
              ) : (
                <div key={c.id} className="chapter-cell" style={{ opacity: 0.5, cursor: 'default' }}>
                  <span className="cc-no">第 {c.ordinal} 话</span>
                  <span className="cc-sub">未发布</span>
                </div>
              ))}
            </div>
          )}
        </div>
      </section>

      {owner && <ExternalEpisodes />}
    </main>
  )
}

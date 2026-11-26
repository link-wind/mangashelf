import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError } from '../api'
import CoverImage from '../components/CoverImage'
import { BookOpenIcon, HeartIcon } from '../components/Icons'
import { useToast } from '../components/Toast'
import EmptyState from '../components/ui/EmptyState'
import { localizeWorkStatus } from '../lib/mediaFormat'

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

/** 站内本地漫画详情：统一双栏总览卡 + 卡片化章节格 + 阅读续点高亮。 */
export default function MangaDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { showToast } = useToast()
  const [data, setData] = useState<MangaDetail | null>(null)
  const [reverse, setReverse] = useState(false)
  const [chunkIdx, setChunkIdx] = useState(0)
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)

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

  useEffect(() => {
    if (data?.work.title) {
      document.title = `${data.work.title} · GemiNeko`
    }
  }, [data?.work.title])

  async function addToShelf() {
    if (!id) return
    try {
      await api.put(`/api/v1/me/favorites/work/${id}`)
      setSaved(true)
      showToast('已加入我的漫画书架', 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.message : '收藏操作失败', 'error')
    }
  }

  if (error) return <main className="watch-page"><p className="error">{error}</p></main>
  if (!data) return <main className="watch-page page-center">加载中…</main>

  const { work, chapters, owner, coverUrl, continueReading } = data
  const ordered = reverse ? [...chapters].reverse() : chapters
  const CHUNK = 50
  const useChunks = ordered.length > CHUNK
  const safeChunkIdx = useChunks ? Math.min(chunkIdx, Math.floor((ordered.length - 1) / CHUNK)) : 0
  const displayed = useChunks ? ordered.slice(safeChunkIdx * CHUNK, (safeChunkIdx + 1) * CHUNK) : ordered
  const readUrl = (c: ChapterRow, resume = 1) =>
    `/read/${c.currentRevisionId}?chapter=${c.id}&resume=${resume}`
  const contChapter = continueReading
    ? chapters.find(c => c.id === continueReading.chapterId) : null
  const firstReadable = chapters.find(c => Boolean(c.currentRevisionId))
  const statusInfo = localizeWorkStatus(work.status)

  return (
    <main className="watch-page">
      <div className="detail-crumb-row">
        <nav className="breadcrumb" aria-label="面包屑">
          <Link to="/manga">漫画</Link>
          <span>/</span>
          <span className="breadcrumb-cur">{work.title}</span>
        </nav>
      </div>

      <section className="detail-overview-grid">
        <div className="detail-left-col">
          <div className="detail-poster-card">
            <CoverImage src={coverUrl} alt={work.title} loading="eager" fetchPriority="high" />
            <div className="dpc-top-left">
              <span className={'dpc-pill ' + (work.visibility === 'PUBLIC' ? 'is-pub' : 'is-cyan')}>
                {work.visibility === 'PUBLIC' ? '公开' : '私人'}
              </span>
            </div>
            {chapters.length > 0 && (
              <span className="dpc-top-right tabular-nums">{chapters.length} 话</span>
            )}
            <div className="dpc-bottom">
              <div className="dpc-bottom-meta">
                <span className="dpc-bottom-k">{contChapter ? '上次读到' : '作品来源'}</span>
                <span className="dpc-bottom-v">
                  {contChapter
                    ? `第 ${contChapter.ordinal} 话 · 第 ${continueReading?.pageOrdinal ?? 1} 页`
                    : '站内上传书架'}
                </span>
              </div>
              <span className="dpc-bottom-chip">{statusInfo.label}</span>
            </div>
          </div>
        </div>

        <div className="detail-right-col">
          <div className="detail-identity">
            <div className="detail-micro-row">
              <span className="detail-micro-pill is-rose">站内漫画</span>
              <span className="detail-micro-pill">{work.visibility === 'PUBLIC' ? '公开可见' : '仅自己可见'}</span>
              <span className="detail-micro-pill is-cyan">{statusInfo.label}</span>
            </div>
            <h1>{work.title}</h1>
            {work.description && (
              <p className="detail-synopsis-text" style={{ marginTop: 6 }}>{work.description}</p>
            )}
          </div>

          <div className="detail-action-card">
            <div className="detail-action-status">
              <div className="detail-action-status-main">
                <span className="detail-action-dot" aria-hidden="true" />
                <span>
                  {continueReading && contChapter
                    ? `上次读到 第 ${contChapter.ordinal} 话 · 第 ${continueReading.pageOrdinal} 页`
                    : (chapters.length > 0 ? `已发布 ${chapters.filter(c => c.currentRevisionId).length} 话可阅读` : '还没有已发布章节')}
                </span>
              </div>
              <span className="detail-action-status-sub">
                站内原生阅读器 · 支持键盘翻页、目录跳转与自动下一话
              </span>
            </div>
            <div className="detail-action-btns">
              {continueReading && contChapter ? (
                <button type="button" className="detail-cta-primary"
                        onClick={() => navigate(readUrl(contChapter, continueReading.pageOrdinal))}>
                  <BookOpenIcon size={16} />
                  继续阅读（第 {contChapter.ordinal} 话）
                </button>
              ) : firstReadable ? (
                <button type="button" className="detail-cta-primary"
                        onClick={() => navigate(readUrl(firstReadable))}>
                  <BookOpenIcon size={16} />
                  开始阅读（第 {firstReadable.ordinal} 话）
                </button>
              ) : null}
              {!owner && (
                <button type="button" className={'detail-cta-fav' + (saved ? ' active' : '')}
                        onClick={() => void addToShelf()}>
                  <HeartIcon size={16} filled={saved} />
                  {saved ? '已加入书架' : '加入书架'}
                </button>
              )}
            </div>
          </div>
        </div>
      </section>

      <section style={{ marginTop: 30 }}>
        <div className="detail-sec-head">
          <h2 className="detail-sec-title">
            <span className="detail-sec-bar is-rose" aria-hidden="true" />
            章节目录（{chapters.length} 话）
          </h2>
          {chapters.length > 1 && (
            <button type="button" className="mini ghost"
                    onClick={() => { setReverse(v => !v); setChunkIdx(0) }}>
              {reverse ? '倒序 ↓' : '正序 ↑'}
            </button>
          )}
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

          {displayed.length === 0 ? (
            <EmptyState
              compact
              tone="rose"
              icon={<BookOpenIcon size={22} />}
              title="还没有已发布章节"
              description="当前作品尚未发布可读章节。"
            />
          ) : (
            <div className="chapter-grid">
              {displayed.map(c => {
                const isResume = Boolean(continueReading && c.id === continueReading.chapterId)
                return c.currentRevisionId ? (
                  <button
                    key={c.id}
                    type="button"
                    className={'chapter-cell' + (isResume ? ' is-resume' : '')}
                    title={c.title || `第 ${c.ordinal} 话`}
                    onClick={() => navigate(readUrl(c, c.readPageOrdinal ?? 1))}
                  >
                    <span className="cc-no">第 {c.ordinal} 话</span>
                    <span className={'cc-sub' + (isResume ? ' is-resume-lbl' : '')}>
                      {isResume
                        ? `上次第 ${continueReading?.pageOrdinal ?? 1} 页`
                        : (c.readPageOrdinal != null ? `第 ${c.readPageOrdinal} 页` : (c.title || '阅读'))}
                    </span>
                  </button>
                ) : (
                  <div key={c.id} className="chapter-cell" style={{ opacity: 0.5, cursor: 'default' }}>
                    <span className="cc-no">第 {c.ordinal} 话</span>
                    <span className="cc-sub">未发布</span>
                  </div>
                )
              })}
            </div>
          )}
        </div>
      </section>
    </main>
  )
}

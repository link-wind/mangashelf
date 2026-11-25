import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, mediaUrl } from '../api'
import { useAuth } from '../auth'

interface PageView {
  id: string
  ordinal: number
  width: number
  height: number
  imageUrl: string
  thumbnailUrl?: string | null
  imageExpiresAt?: string
}

/** 窗口拉取大小与有限预加载页数（DESIGN 14.1：不一次请求整本原图）。 */
const WINDOW_LIMIT = 10
const PREFETCH_AHEAD = 2
const REFRESH_MARGIN_MS = 60_000

interface PagesPage {
  revisionId: string
  status: string
  pageCount: number
  editVersion: number
  nextCursor: string | null
  pages: PageView[]
}

interface Progress {
  chapterId: string
  revisionId: string
  pageOrdinal: number
  version: number
}

interface Adaptation {
  animationId: string
  title: string
  revisionId: string
  pageStart: number
  pageEnd: number
}

interface WorkChapter {
  id: string; ordinal: number; title: string; currentRevisionId: string | null
}

/** 阅读器（DESIGN 14.1/14.3/4.1）：固定版本页面窗口复用 + 有限预加载 + 进度防覆盖同步 + 续读 + 页级动画入口。 */
export default function Reader() {
  const { me } = useAuth()
  const { revisionId } = useParams()
  const [pagesByOrdinal, setPagesByOrdinal] = useState<Record<number, PageView>>({})
  const [pageCount, setPageCount] = useState(0)
  const [current, setCurrent] = useState(0) // 1 起页序
  const [version, setVersion] = useState(0)
  const [chapterId, setChapterId] = useState<string | null>(null)
  const [adaptations, setAdaptations] = useState<Adaptation[]>([])
  const [titles, setTitles] = useState<{ work: string; chapter: string }>({ work: '本地漫画', chapter: '' })
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  const [workId, setWorkId] = useState<string | null>(null)
  const [chapters, setChapters] = useState<WorkChapter[]>([])
  const [tocOpen, setTocOpen] = useState(false)
  const [tocAsc, setTocAsc] = useState(true)
  const loadedRef = useRef(false)
  const wheelLockRef = useRef(0)
  const pagesRef = useRef<Record<number, PageView>>({})
  const prefetchedUrlsRef = useRef<Set<string>>(new Set())
  const inflightCursorsRef = useRef<Set<number>>(new Set())

  const isFresh = useCallback((p: PageView | undefined) => {
    if (!p) return false
    if (!p.imageExpiresAt) return true
    const exp = Date.parse(p.imageExpiresAt)
    return Number.isNaN(exp) || exp - Date.now() > REFRESH_MARGIN_MS
  }, [])

  const fetchWindow = useCallback(async (startOrdinal: number) => {
    if (!revisionId) return
    const cursor = Math.max(0, startOrdinal - 1)
    if (inflightCursorsRef.current.has(cursor)) return
    inflightCursorsRef.current.add(cursor)
    try {
      const page = await api.get<PagesPage>(
        `/api/v1/chapter-revisions/${revisionId}/pages?limit=${WINDOW_LIMIT}&cursor=${cursor}`)
      setPageCount(page.pageCount)
      setPagesByOrdinal(prev => {
        const next = { ...prev }
        for (const item of page.pages) {
          next[item.ordinal] = item
        }
        pagesRef.current = next
        return next
      })
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败')
    } finally {
      inflightCursorsRef.current.delete(cursor)
    }
  }, [revisionId])

  // 按需补齐窗口：当前页或前方 PREFETCH_AHEAD 页未缓存/临近过期时才请求 API
  const ensureWindow = useCallback(async (targetOrdinal: number, totalCount?: number) => {
    const knownTotal = totalCount ?? pageCount
    const cache = pagesRef.current
    if (!isFresh(cache[targetOrdinal])) {
      await fetchWindow(targetOrdinal)
      return
    }
    const maxLookahead = knownTotal > 0 ? Math.min(knownTotal, targetOrdinal + PREFETCH_AHEAD) : targetOrdinal + PREFETCH_AHEAD
    for (let ord = targetOrdinal + 1; ord <= maxLookahead; ord++) {
      if (!isFresh(cache[ord])) {
        void fetchWindow(ord)
        break
      }
    }
  }, [fetchWindow, isFresh, pageCount])

  // 拉取首页窗口（支持 URL ?resume= 与 ?chapter=）
  useEffect(() => {
    let cancelled = false
    pagesRef.current = {}
    prefetchedUrlsRef.current.clear()
    setPagesByOrdinal({})
    const params = new URLSearchParams(window.location.search)
    const resume = Number(params.get('resume') || '0')
    const chapter = params.get('chapter')
    if (chapter) setChapterId(chapter)
    const startOrdinal = resume > 0 ? resume : 1
    ;(async () => {
      try {
        const page = await api.get<PagesPage>(
          `/api/v1/chapter-revisions/${revisionId}/pages?limit=${WINDOW_LIMIT}&cursor=${Math.max(0, startOrdinal - 1)}`)
        if (cancelled) return
        const map: Record<number, PageView> = {}
        for (const item of page.pages) {
          map[item.ordinal] = item
        }
        pagesRef.current = map
        setPagesByOrdinal(map)
        setPageCount(page.pageCount)
        const initial = resume > 0 && resume <= page.pageCount
          ? resume
          : (page.pages[0]?.ordinal ?? 1)
        loadedRef.current = true
        setCurrent(initial)
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : '加载失败')
      }
    })()
    return () => { cancelled = true }
  }, [revisionId])

  // 有限预加载下一页/下两页（DESIGN 14.1：不预加载整本原图）
  useEffect(() => {
    if (!current) return
    const maxOrd = pageCount > 0 ? Math.min(pageCount, current + PREFETCH_AHEAD) : current + PREFETCH_AHEAD
    for (let ord = current + 1; ord <= maxOrd; ord++) {
      const p = pagesByOrdinal[ord]
      if (!p || !isFresh(p)) continue
      const url = mediaUrl(p.imageUrl)
      if (prefetchedUrlsRef.current.has(url)) continue
      prefetchedUrlsRef.current.add(url)
      const img = new Image()
      img.decoding = 'async'
      img.src = url
    }
  }, [current, pageCount, pagesByOrdinal, isFresh])

  // 作品名与章节名（阅读器顶栏）
  useEffect(() => {
    if (!chapterId) return
    void api.get<{ work: { id: string; title: string }; chapter: { ordinal: number; title: string } }>(
      `/api/v1/chapters/${chapterId}`)
      .then(v => {
        setTitles({
          work: v.work.title,
          chapter: `第 ${v.chapter.ordinal} 话 ${v.chapter.title || ''}`.trim(),
        })
        setWorkId(v.work.id)
      })
      .catch(() => { /* 标题失败不影响阅读 */ })
  }, [chapterId])

  // 章节目录（mobile-ui-index-and-reader）：作品可见章节 + 当前修订
  useEffect(() => {
    if (!workId) return
    void api.get<{ chapters: WorkChapter[] }>(`/api/v1/works/${workId}`)
      .then(v => setChapters(v.chapters))
      .catch(() => setChapters([]))
  }, [workId])

  // 键盘与滚轮导航（←→↑↓ 与滚轮）：单页翻页器，方向键语义一致
  useEffect(() => {
    const onNavKey = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return
      if (tocOpen) return
      if (e.key === 'ArrowRight' || e.key === 'ArrowDown') { e.preventDefault(); void go(1) }
      if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') { e.preventDefault(); void go(-1) }
    }
    const onWheel = (e: WheelEvent) => {
      if (tocOpen) return
      e.preventDefault()
      const now = Date.now()
      if (now - wheelLockRef.current < 150) return
      wheelLockRef.current = now
      void go(e.deltaY > 0 ? 1 : -1)
    }
    window.addEventListener('keydown', onNavKey)
    window.addEventListener('wheel', onWheel, { passive: false })
    return () => {
      window.removeEventListener('keydown', onNavKey)
      window.removeEventListener('wheel', onWheel)
    }
  })

  // 页级关联动画（DESIGN 4.1：存在已发布动画时显示入口，不自动播放）
  useEffect(() => {
    if (!current || !revisionId) return
    void (async () => {
      try {
        setAdaptations(await api.get<Adaptation[]>(
          `/api/v1/chapter-revisions/${revisionId}/animations?page=${current}`))
      } catch {
        setAdaptations([])
      }
    })()
  }, [current, revisionId])

  // 进度保存：页面变更时提交（客户端防抖简化为直接提交，DESIGN 14.3 冲突时刷新版本号）
  const saveProgress = useCallback(async (ordinal: number) => {
    if (!me) return // 访客不保存阅读进度
    if (!chapterId || !revisionId) return
    try {
      const snap = await api.put<Progress>(`/api/v1/me/reading-progress/${chapterId}`, {
        chapterRevisionId: revisionId, pageOrdinal: ordinal, expectedVersion: version,
      })
      setVersion(snap.version)
      setNotice('')
    } catch (e) {
      const err = e as { code?: string; details?: { current?: Progress } }
      if (err.code === 'PROGRESS_CONFLICT' && err.details?.current) {
        const current = err.details.current
        setVersion(current.version)
        setNotice('进度已在其他设备更新，已同步最新版本号')
      }
    }
  }, [chapterId, revisionId, version])

  async function jumpTo(target: number) {
    const next = Math.min(Math.max(1, target), pageCount)
    if (next === current) return
    setCurrent(next)
    await ensureWindow(next)
    void saveProgress(next)
  }

  async function go(delta: number) {
    await jumpTo(current + delta)
  }

  const currentUrl = pagesByOrdinal[current]?.imageUrl
    ?? Object.values(pagesByOrdinal)[0]?.imageUrl

  // 阅读时隐藏站点导航（与外部漫画阅读器一致）
  useEffect(() => {
    document.body.classList.add('reader-open')
    return () => document.body.classList.remove('reader-open')
  }, [])

  return (
    <div className="reader-full local-reader show-ui">
      <header className="rf-top">
        <Link to="/manga" className="rf-icon" aria-label="返回">←</Link>
        <div className="rf-title">
          <span className="rf-comic">{titles.work}</span>
          <span className="rf-chapter">{titles.chapter} · 第 {current} / {pageCount} 页</span>
        </div>
        {notice && <span className="rf-chapter">{notice}</span>}
        <button className="rf-icon" aria-label="目录"
                onClick={() => setTocOpen(v => !v)}>☰</button>
      </header>

      {tocOpen && (
        <div className="rf-sheet rf-toc" onClick={e => e.stopPropagation()}>
          <div className="rf-sheet-row">
            <span>目录（{chapters.length} 话）</span>
            <button className="mini ghost" onClick={() => setTocAsc(v => !v)}>
              {tocAsc ? '正序' : '倒序'}
            </button>
          </div>
          <div className="rf-toc-list">
            {(tocAsc ? chapters : [...chapters].reverse()).map(c => (
              <button key={c.id}
                      className={'rf-toc-item' + (c.currentRevisionId ? '' : ' disabled')}
                      disabled={!c.currentRevisionId}
                      onClick={() => {
                        if (!c.currentRevisionId) return
                        setTocOpen(false)
                        window.location.href =
                          `/read/${c.currentRevisionId}?chapter=${c.id}`
                      }}>
                第 {c.ordinal} 话 {c.title}
              </button>
            ))}
          </div>
        </div>
      )}

      {error && <p className="error" style={{ padding: '80px 24px 0' }}>{error}</p>}

      <div className="local-stage" onClick={() => void go(1)}>
        <button className="rf-hit rf-hit-left" onClick={e => { e.stopPropagation(); void go(-1) }}
                disabled={current <= 1} aria-label="上一页">‹</button>
        {currentUrl
          ? <img className="local-page" src={mediaUrl(currentUrl)} alt={`第 ${current} 页`} />
          : <p className="muted">加载中…</p>}
        <button className="rf-hit rf-hit-right" onClick={e => { e.stopPropagation(); void go(1) }}
                disabled={current >= pageCount} aria-label="下一页">›</button>
      </div>

      <footer className="rf-bottom">
        <button className="rf-text-btn" onClick={() => void go(-1)} disabled={current <= 1}>上一页</button>
        <div className="rf-progress">
          <span className="rf-page">{current} / {pageCount}</span>
          <input type="range" min={1} max={Math.max(pageCount, 1)} value={current}
                 onChange={e => { void jumpTo(Number(e.target.value)) }} />
        </div>
        <button className="rf-text-btn" onClick={() => void go(1)} disabled={current >= pageCount}>下一页</button>
      </footer>

      {adaptations.length > 0 && (
        <div className="local-adaptations">
          {adaptations.map(a => (
            <Link key={a.animationId} className="btn pill" to={`/watch/${a.animationId}`}>
              本页有改编动画：观看（{a.pageStart}–{a.pageEnd} 页）
            </Link>
          ))}
        </div>
      )}
    </div>
  )
}

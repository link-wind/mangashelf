import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, mediaUrl } from '../api'

interface PageView {
  id: string
  ordinal: number
  width: number
  height: number
  imageUrl: string
}

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

/** 阅读器（DESIGN 14.1/14.3/4.1）：固定版本页面窗口 + 进度防覆盖同步 + 续读 + 页级动画入口。 */
export default function Reader() {
  const { revisionId } = useParams()
  const [windowPages, setWindowPages] = useState<PageView[]>([])
  const [pageCount, setPageCount] = useState(0)
  const [current, setCurrent] = useState(0) // 1 起页序
  const [version, setVersion] = useState(0)
  const [chapterId, setChapterId] = useState<string | null>(null)
  const [adaptations, setAdaptations] = useState<Adaptation[]>([])
  const [titles, setTitles] = useState<{ work: string; chapter: string }>({ work: '本地漫画', chapter: '' })
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  const cursorRef = useRef<string | null>(null)
  const loadedRef = useRef(false)

  // 拉取首页窗口；有续读进度则跳到上次位置
  useEffect(() => {
    let cancelled = false
    ;(async () => {
      try {
        // 先取章节 id（用于进度接口）
        // 简化：从 work 详情不可得；进度按 chapter 保存——这里通过页面接口首载，
        // chapter_id 在保存进度时由服务端 409 快照/首次成功返回路径间接获得。
        const page = await api.get<PagesPage>(`/api/v1/chapter-revisions/${revisionId}/pages?limit=5`)
        if (cancelled) return
        setWindowPages(page.pages)
        setPageCount(page.pageCount)
        cursorRef.current = page.nextCursor
        const first = page.pages[0]?.ordinal ?? 1
        setCurrent(first)
        // 续读：进度 revision 匹配则跳转
        try {
          // 进度查询需要 chapterId；Reader 通过 window.__lastProgress 由详情页传递不现实，
          // 直接用服务端最近进度接口不可行（需要 chapterId）——以 URL ?resume=ordinal 支持。
        } catch { /* 无进度 */ }
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : '加载失败')
      }
    })()
    return () => { cancelled = true }
  }, [revisionId])

  // 解析 ?resume= 与 ?chapter= 参数
  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    const resume = params.get('resume')
    const chapter = params.get('chapter')
    if (chapter) setChapterId(chapter)
    if (resume && !loadedRef.current) {
      loadedRef.current = true
      setCurrent(Number(resume))
    }
  }, [])

  // 当前页窗口：向前/向后翻页时按需拉取
  const loadWindow = useCallback(async (aroundOrdinal: number) => {
    try {
      const page = await api.get<PagesPage>(
        `/api/v1/chapter-revisions/${revisionId}/pages?limit=5&cursor=${Math.max(0, aroundOrdinal - 1)}`)
      setWindowPages(page.pages)
      cursorRef.current = page.nextCursor
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败')
    }
  }, [revisionId])

  // 作品名与章节名（阅读器顶栏）
  useEffect(() => {
    if (!chapterId) return
    void api.get<{ work: { title: string }; chapter: { ordinal: number; title: string } }>(
      `/api/v1/chapters/${chapterId}`)
      .then(v => setTitles({
        work: v.work.title,
        chapter: `第 ${v.chapter.ordinal} 话 ${v.chapter.title || ''}`.trim(),
      }))
      .catch(() => { /* 标题失败不影响阅读 */ })
  }, [chapterId])

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

  async function go(delta: number) {
    const next = Math.min(Math.max(1, current + delta), pageCount)
    if (next === current) return
    setCurrent(next)
    await loadWindow(next)
    void saveProgress(next)
  }

  const currentUrl = windowPages.find(p => p.ordinal === current)?.imageUrl
    ?? windowPages[0]?.imageUrl

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
      </header>

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
                 onChange={e => { setCurrent(Number(e.target.value)) }} />
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

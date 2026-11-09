import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api } from '../api'
import { onImgError, proxyImage } from '../externalManga'

interface Pages {
  chapterId: string
  baseUrl: string
  hash: string
  pages: string[]
  dataSaver: string[]
  external: boolean
}
interface Chapter {
  id: string; chapter: string; title: string; lang: string; pages: number; external: boolean
}
interface Detail { manga: { title: string }; chapters: Chapter[] }
interface ProgressRow { source: string; mangaId: string; chapterId: string | null; page: number }

type Mode = 'scroll' | 'paged'

/**
 * 外部漫画阅读器（M21，参照哔哩哔哩漫画）：
 * 全屏黑底、点击中央切换浮层、上下滚动/左右翻页两种模式、上一话/下一话、页码滑杆。
 */
export default function ExternalMangaReader() {
  const { chapterId } = useParams()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const mangaId = params.get('mangaId') ?? ''
  const lang = params.get('lang') ?? 'zh'
  const titleParam = params.get('title') ?? ''

  const [data, setData] = useState<Pages | null>(null)
  const [detail, setDetail] = useState<Detail | null>(null)
  const [error, setError] = useState('')
  const [mode, setMode] = useState<Mode>(() =>
    (localStorage.getItem('ms-manga-mode') as Mode) || 'scroll')
  const [hd, setHd] = useState(false)
  const [uiVisible, setUiVisible] = useState(true)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [page, setPage] = useState(1)
  const [resumePage, setResumePage] = useState<number | null>(null)
  // 续读/跳页时强制预加载到目标页（否则懒加载与 0 高度互相锁死）
  const [eagerUntil, setEagerUntil] = useState(3)

  const stageRef = useRef<HTMLDivElement>(null)
  const lastSaveRef = useRef(0)
  const pageRef = useRef(1)
  pageRef.current = page
  const hideTimer = useRef<number | null>(null)

  useEffect(() => {
    void (async () => {
      if (!chapterId) return
      try {
        setData(await api.get<Pages>(`/api/v1/external-manga/chapter/${chapterId}/pages`))
      } catch (e) {
        setError(e instanceof Error ? e.message : '章节加载失败')
      }
    })()
  }, [chapterId])

  // 章节列表（用于上一话/下一话与标题）
  useEffect(() => {
    if (!mangaId) return
    void api.get<Detail>(`/api/v1/external-manga/${mangaId}?lang=${lang}`)
      .then(setDetail)
      .catch(() => { /* 章节导航不可用不影响阅读 */ })
  }, [mangaId, lang])

  const chapter = detail?.chapters.find(c => c.id === chapterId) ?? null
  const readable = (detail?.chapters ?? []).filter(c => !c.external && c.pages > 0)
  const idx = readable.findIndex(c => c.id === chapterId)
  const prevCh = idx > 0 ? readable[idx - 1] : null
  const nextCh = idx >= 0 && idx < readable.length - 1 ? readable[idx + 1] : null
  const comicTitle = titleParam || detail?.manga.title || '外部漫画'

  const jumpChapter = (id: string) =>
    navigate(`/manga-ext/read/${id}?mangaId=${mangaId}&lang=${lang}&title=${encodeURIComponent(comicTitle)}`)

  // 续读位置
  useEffect(() => {
    if (!mangaId || !chapterId) return
    void api.get<ProgressRow[]>('/api/v1/me/manga-progress?limit=50')
      .then(rows => {
        const row = rows.find(r => r.source === 'mangadex' && r.mangaId === mangaId)
        if (row && row.chapterId === chapterId && row.page > 1) {
          setResumePage(row.page)
          setPage(row.page)
          setEagerUntil(Math.min(row.page + 2, 200))
        } else {
          const local = Number(localStorage.getItem(`ms-ext-manga:${chapterId}`) ?? '0')
          if (local > 0) setPage(local)
        }
      })
      .catch(() => { /* 无进度不影响阅读 */ })
  }, [mangaId, chapterId])

  const saveProgress = useCallback((p: number, total: number) => {
    if (!mangaId) return
    void api.put('/api/v1/me/manga-progress', {
      source: 'mangadex', mangaId, chapterId, page: p, totalPages: total, title: comicTitle,
    }).catch(() => { /* 上报失败不打断 */ })
  }, [mangaId, chapterId, comicTitle])

  const rawList = data ? (hd ? data.pages : (data.dataSaver.length ? data.dataSaver : data.pages)) : []
  const list = rawList.map(proxyImage)
  const total = list.length

  // 滚动模式：换算页码 + 节流上报
  useEffect(() => {
    if (!data || mode !== 'scroll') return
    const onScroll = () => {
      const stage = stageRef.current
      if (!stage) return
      const imgs = Array.from(stage.querySelectorAll('img'))
      const mid = window.scrollY + window.innerHeight / 2
      let i = 0
      imgs.forEach((img, k) => {
        if (img.getBoundingClientRect().top + window.scrollY < mid) i = k
      })
      const p = i + 1
      if (p !== pageRef.current) {
        setPage(p)
        localStorage.setItem(`ms-ext-manga:${chapterId}`, String(p))
        if (Date.now() - lastSaveRef.current >= 3000) {
          lastSaveRef.current = Date.now()
          saveProgress(p, imgs.length)
        }
      }
    }
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      window.removeEventListener('scroll', onScroll)
      saveProgress(pageRef.current, stageRef.current?.querySelectorAll('img').length ?? 0)
    }
  }, [data, mode, chapterId, saveProgress])

  // 续读滚动
  useEffect(() => {
    if (!resumePage || !data || mode !== 'scroll') return
    const stage = stageRef.current
    const img = stage?.querySelectorAll('img')[resumePage - 1]
    if (img) {
      const go = () => window.scrollTo({ top: (img as HTMLElement).offsetTop - 20 })
      if (img.complete && img.naturalWidth > 0) go()
      else img.addEventListener('load', go, { once: true })
    }
    setResumePage(null)
  }, [resumePage, data, mode])

  // 翻页模式键盘
  useEffect(() => {
    if (mode !== 'paged' || !total) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'ArrowRight') setPage(p => Math.min(total, p + 1))
      if (e.key === 'ArrowLeft') setPage(p => Math.max(1, p - 1))
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [mode, total])

  function pokeUi() {
    setUiVisible(true)
    if (hideTimer.current) window.clearTimeout(hideTimer.current)
    hideTimer.current = window.setTimeout(() => setUiVisible(false), 3000)
  }
  useEffect(() => {
    pokeUi()
    document.body.classList.add('reader-open')
    return () => {
      document.body.classList.remove('reader-open')
      if (hideTimer.current) window.clearTimeout(hideTimer.current)
    }
  }, [])

  function switchMode(m: Mode) {
    setMode(m)
    localStorage.setItem('ms-manga-mode', m)
    setSettingsOpen(false)
  }

  if (error) {
    return (
      <div className="reader-full">
        <div className="reader-full-inner"><p className="error">{error}</p></div>
      </div>
    )
  }
  if (!data) {
    return <div className="reader-full"><div className="reader-full-inner muted">加载中…</div></div>
  }

  const backTo = `/manga-ext/${mangaId}?lang=${lang}`

  if (data.external || total === 0) {
    return (
      <div className="reader-full">
        <div className="reader-full-inner">
          <p className="muted">该章节由 MangaDex 外链到原站，无法在此阅读。</p>
          <Link className="btn ghost" to={backTo}>返回章节列表</Link>
        </div>
      </div>
    )
  }

  return (
    <div className={'reader-full' + (uiVisible ? ' show-ui' : '')}
         onClick={pokeUi} onMouseMove={pokeUi}>
      {/* 顶栏 */}
      <header className="rf-top">
        <Link to={backTo} className="rf-icon" aria-label="返回">←</Link>
        <div className="rf-title">
          <span className="rf-comic">{comicTitle}</span>
          <span className="rf-chapter">
            {chapter ? `第 ${chapter.chapter || '?'} 话` : ''} {chapter?.title ?? ''}
          </span>
        </div>
        <button className="rf-icon" aria-label="设置" onClick={() => setSettingsOpen(v => !v)}>⚙</button>
      </header>

      {/* 内容 */}
      {mode === 'scroll' ? (
        <div ref={stageRef} className="rf-stage">
          {list.map((src, i) => (
            <img key={i} src={src} alt={`第 ${i + 1} 页`}
                 loading={i < Math.max(3, eagerUntil) ? 'eager' : 'lazy'} decoding="async"
                 onError={onImgError} />
          ))}
        </div>
      ) : (
        <div className="rf-paged" onClick={e => {
          const r = (e.currentTarget as HTMLElement).getBoundingClientRect()
          const x = e.clientX - r.left
          if (x < r.width / 3) setPage(p => Math.max(1, p - 1))
          else if (x > r.width * 2 / 3) setPage(p => Math.min(total, p + 1))
        }}>
          <img src={list[page - 1]} alt={`第 ${page} 页`} onError={onImgError} />
        </div>
      )}

      {/* 底栏 */}
      <footer className="rf-bottom">
        <button className="rf-text-btn" disabled={!prevCh} onClick={() => prevCh && jumpChapter(prevCh.id)}>
          上一话
        </button>
        <div className="rf-progress">
          <span className="rf-page">{page} / {total}</span>
          <input type="range" min={1} max={total} value={page}
                 onChange={e => {
                   const p = Number(e.target.value)
                   setPage(p)
                   if (mode === 'scroll') {
                     setEagerUntil(Math.min(p + 2, 200))
                     const img = stageRef.current?.querySelectorAll('img')[p - 1]
                     if (img) {
                       const go = () => window.scrollTo({ top: (img as HTMLElement).offsetTop - 20 })
                       if (img.complete && img.naturalWidth > 0) go()
                       else img.addEventListener('load', go, { once: true })
                     }
                   }
                 }} onMouseUp={() => saveProgress(page, total)} />
        </div>
        <button className="rf-text-btn" disabled={!nextCh} onClick={() => nextCh && jumpChapter(nextCh.id)}>
          下一话
        </button>
      </footer>

      {settingsOpen && (
        <div className="rf-sheet" onClick={e => e.stopPropagation()}>
          <div className="rf-sheet-row">
            <span>翻页方式</span>
            <div className="row-inline">
              <button className={mode === 'scroll' ? 'mini' : 'mini ghost'} onClick={() => switchMode('scroll')}>上下滚动</button>
              <button className={mode === 'paged' ? 'mini' : 'mini ghost'} onClick={() => switchMode('paged')}>左右翻页</button>
            </div>
          </div>
          <div className="rf-sheet-row">
            <span>清晰度</span>
            <div className="row-inline">
              <button className={!hd ? 'mini' : 'mini ghost'} onClick={() => { setHd(false); setSettingsOpen(false) }}>省流</button>
              <button className={hd ? 'mini' : 'mini ghost'} onClick={() => { setHd(true); setSettingsOpen(false) }}>高清</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

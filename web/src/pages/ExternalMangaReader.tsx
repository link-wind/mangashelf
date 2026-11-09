import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { api } from '../api'
import { onImgError } from '../externalManga'

interface Pages {
  chapterId: string
  baseUrl: string
  hash: string
  pages: string[]
  dataSaver: string[]
  external: boolean
}
interface ProgressRow {
  source: string; mangaId: string; chapterId: string | null; page: number
}

/**
 * 外部漫画阅读器（M16/M18）：竖向滚动 + 懒加载；服务端进度续读与上报。
 * 默认 dataSaver 图（省流量），可切高清。
 */
export default function ExternalMangaReader() {
  const { chapterId } = useParams()
  const [params] = useSearchParams()
  const mangaId = params.get('mangaId') ?? ''
  const lang = params.get('lang') ?? 'zh'
  const title = params.get('title') ?? '外部漫画'

  const [data, setData] = useState<Pages | null>(null)
  const [error, setError] = useState('')
  const [hd, setHd] = useState(false)
  const [progress, setProgress] = useState(1)
  const [resumePage, setResumePage] = useState<number | null>(null)

  const stageRef = useRef<HTMLDivElement>(null)
  const lastSaveRef = useRef(0)
  const progressRef = useRef(1)
  progressRef.current = progress

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

  // 续读位置：服务端优先，本地兜底
  useEffect(() => {
    if (!mangaId || !chapterId) return
    void api.get<ProgressRow[]>('/api/v1/me/manga-progress?limit=50')
      .then(rows => {
        const row = rows.find(r => r.source === 'mangadex' && r.mangaId === mangaId)
        if (row && row.chapterId === chapterId && row.page > 1) {
          setResumePage(row.page)
          setProgress(row.page)
        } else {
          const local = Number(localStorage.getItem(`ms-ext-manga:${chapterId}`) ?? '0')
          if (local > 0) setProgress(local)
        }
      })
      .catch(() => { /* 无进度不影响阅读 */ })
  }, [mangaId, chapterId])

  const saveProgress = useCallback((page: number, total: number) => {
    if (!mangaId) return
    void api.put('/api/v1/me/manga-progress', {
      source: 'mangadex', mangaId, chapterId, page, totalPages: total, title,
    }).catch(() => { /* 上报失败不打断阅读 */ })
  }, [mangaId, chapterId, title])

  // 滚动：换算当前页；节流上报
  useEffect(() => {
    if (!chapterId || !data) return
    const onScroll = () => {
      const stage = stageRef.current
      if (!stage) return
      const imgs = Array.from(stage.querySelectorAll('img'))
      const mid = window.innerHeight / 2
      let idx = 0
      imgs.forEach((img, i) => {
        if (img.getBoundingClientRect().top < mid) idx = i
      })
      const page = idx + 1
      if (page !== progressRef.current) {
        setProgress(page)
        localStorage.setItem(`ms-ext-manga:${chapterId}`, String(page))
        if (Date.now() - lastSaveRef.current >= 3000) {
          lastSaveRef.current = Date.now()
          saveProgress(page, imgs.length)
        }
      }
    }
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      window.removeEventListener('scroll', onScroll)
      saveProgress(progressRef.current, stageRef.current?.querySelectorAll('img').length ?? 0)
    }
  }, [chapterId, data, saveProgress])

  // 续读时滚到目标页
  useEffect(() => {
    if (!resumePage || !data) return
    const stage = stageRef.current
    if (!stage) return
    const img = stage.querySelectorAll('img')[resumePage - 1]
    if (img) window.scrollTo({ top: (img as HTMLElement).offsetTop - 60 })
    setResumePage(null)
  }, [resumePage, data])

  if (error) return <main className="reader"><p className="error">{error}</p></main>
  if (!data) return <main className="reader page-center">加载中…</main>

  const backTo = `/manga-ext/${mangaId}?lang=${lang}`
  if (data.external || data.pages.length === 0) {
    return (
      <main className="reader">
        <p className="muted">该章节由 MangaDex 外链到原站，无法在此阅读。</p>
        <Link className="btn ghost" to={backTo}>返回章节列表</Link>
      </main>
    )
  }

  const list = hd ? data.pages : (data.dataSaver.length > 0 ? data.dataSaver : data.pages)

  return (
    <main className="reader">
      <div className="reader-toolbar">
        <Link to={backTo} className="btn ghost pill">← 章节列表</Link>
        <span className="muted">{progress} / {list.length} 页</span>
        <button className="ghost pill" onClick={() => setHd(v => !v)}>
          {hd ? '省流模式' : '高清模式'}
        </button>
      </div>
      <div ref={stageRef} className="ext-reader-stage">
        {list.map((src, i) => (
          <img key={i} src={src} alt={`第 ${i + 1} 页`}
               loading={i < 3 ? 'eager' : 'lazy'} decoding="async"
               onError={onImgError} />
        ))}
      </div>
      <div style={{ textAlign: 'center', margin: '18px 0 40px' }}>
        <Link className="btn ghost pill" to={backTo}>本章读完，返回章节列表</Link>
      </div>
    </main>
  )
}

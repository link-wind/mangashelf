import { useEffect, useRef, useState } from 'react'
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

/** 外部漫画阅读器（M16）：竖向滚动 + 懒加载；默认用 dataSaver 图（更省流量更快）。 */
export default function ExternalMangaReader() {
  const { chapterId } = useParams()
  const [params] = useSearchParams()
  const mangaId = params.get('mangaId') ?? ''
  const lang = params.get('lang') ?? 'zh'
  const [data, setData] = useState<Pages | null>(null)
  const [error, setError] = useState('')
  const [hd, setHd] = useState(false)
  const [progress, setProgress] = useState(1)
  const stageRef = useRef<HTMLDivElement>(null)

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

  // 阅读进度（页序号）存本地
  useEffect(() => {
    if (!chapterId) return
    const saved = Number(localStorage.getItem(`ms-ext-manga:${chapterId}`) ?? '0')
    if (saved > 0) setProgress(saved)
  }, [chapterId])

  useEffect(() => {
    if (!chapterId) return
    const onScroll = () => {
      const stage = stageRef.current
      if (!stage) return
      const imgs = Array.from(stage.querySelectorAll('img'))
      const mid = window.innerHeight / 2
      let idx = 0
      imgs.forEach((img, i) => {
        const r = img.getBoundingClientRect()
        if (r.top < mid) idx = i
      })
      if (idx + 1 !== progress) {
        setProgress(idx + 1)
        localStorage.setItem(`ms-ext-manga:${chapterId}`, String(idx + 1))
      }
    }
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [chapterId, progress, data])

  if (error) return <main className="reader"><p className="error">{error}</p></main>
  if (!data) return <main className="reader page-center">加载中…</main>

  if (data.external || data.pages.length === 0) {
    return (
      <main className="reader">
        <p className="muted">该章节由 MangaDex 外链到原站，无法在此阅读。</p>
        <Link className="btn ghost" to={`/manga-ext/${mangaId}?lang=${lang}`}>返回章节列表</Link>
      </main>
    )
  }

  const list = hd ? data.pages : (data.dataSaver.length > 0 ? data.dataSaver : data.pages)

  return (
    <main className="reader">
      <div className="reader-toolbar">
        <Link to={`/manga-ext/${mangaId}?lang=${lang}`} className="btn ghost pill">← 章节列表</Link>
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
        <Link className="btn ghost pill" to={`/manga-ext/${mangaId}?lang=${lang}`}>本章读完，返回章节列表</Link>
      </div>
    </main>
  )
}

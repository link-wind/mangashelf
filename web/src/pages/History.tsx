import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'

type ActivityType = 'work-read' | 'manga-read' | 'animation-watch' | 'external-watch'

interface Activity {
  type: ActivityType
  title: string
  subtitle: string | null
  coverUrl: string | null
  workId: string | null
  catalogItemId: string | null
  chapterId: string | null
  revisionId: string | null
  pageOrdinal: number | null
  animationId: string | null
  episodeId: string | null
  ordinal: number | null
  positionMs: number | null
  durationMs: number | null
  source: string | null
  mangaId: string | null
  updatedAt: string
}

function fmt(ms: number | null | undefined): string {
  if (!ms || ms <= 0) return '00:00'
  const s = Math.floor(ms / 1000)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  const mm = String(m).padStart(2, '0')
  const ss = String(sec).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}

function summary(a: Activity): string {
  if (a.type === 'work-read') return `${a.subtitle ?? '章节'} · 第 ${a.pageOrdinal ?? 1} 页`
  if (a.type === 'manga-read') {
    return `${a.chapterId ? `第 ${a.subtitle ?? '?'} 话 · ` : ''}读到第 ${a.pageOrdinal ?? 1} 页 · ${a.source}`
  }
  if (a.type === 'animation-watch') return `看到 ${fmt(a.positionMs)}`
  return `第 ${a.ordinal ?? '?'} 话${a.subtitle ? ` ${a.subtitle}` : ''} · 看到 ${fmt(a.positionMs)}`
}

function linkOf(a: Activity): string {
  if (a.type === 'work-read') {
    return `/read/${a.revisionId}?chapter=${a.chapterId}&resume=${a.pageOrdinal ?? 1}`
  }
  if (a.type === 'manga-read') {
    const params = new URLSearchParams({ mangaId: a.mangaId ?? '', lang: 'all', source: a.source ?? '', title: a.title })
    return a.chapterId ? `/manga-ext/read/${a.chapterId}?${params}` : `/manga-ext/${a.mangaId}?${params}`
  }
  if (a.type === 'animation-watch') return `/watch/${a.animationId}`
  return a.workId
    ? `/works/${a.workId}/external/${a.episodeId}`
    : `/browse/${a.catalogItemId}/${a.episodeId}`
}

function actionOf(a: Activity): string {
  if (a.type === 'work-read') return '继续阅读'
  if (a.type === 'manga-read') return a.chapterId ? '继续阅读' : '打开'
  return '继续观看'
}

/** 观看历史（separate-favorites-and-history）：按章节/集展开的最近活动，收藏不影响历史。 */
export default function History() {
  const [rows, setRows] = useState<Activity[]>([])
  const [error, setError] = useState('')

  useEffect(() => {
    void api.get<Activity[]>('/api/v1/me/history?limit=100')
      .then(setRows)
      .catch(e => setError(e instanceof Error ? e.message : '加载失败'))
  }, [])

  const reading = rows.filter(a => a.type === 'work-read' || a.type === 'manga-read')
  const watching = rows.filter(a => a.type === 'animation-watch' || a.type === 'external-watch')

  return (
    <main className="watch-page">
      <div className="watch-title"><h1>观看历史</h1></div>
      <div className="watch-meta"><span>共 {rows.length} 条 · 进度保存在服务端</span></div>
      {error && <p className="error">{error}</p>}
      {rows.length === 0 && !error && <p className="muted">还没有阅读或观看记录。</p>}

      {reading.length > 0 && (
        <section style={{ marginBottom: 24 }}>
          <div className="section-head"><h2>漫画阅读</h2></div>
          <ul className="chapters">
            {reading.map(a => (
              <li key={`${a.type}-${a.chapterId}-${a.updatedAt}`}>
                <span className="row-inline" style={{ gap: 12 }}>
                  {a.coverUrl && (
                    <img src={a.coverUrl} alt="" className="history-thumb" referrerPolicy="no-referrer"
                         onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />
                  )}
                  <span>
                    <strong>{a.title}</strong>
                    <br />
                    <span className="muted">{summary(a)}</span>
                  </span>
                </span>
                <Link className="btn ghost" to={linkOf(a)}>{actionOf(a)}</Link>
              </li>
            ))}
          </ul>
        </section>
      )}

      {watching.length > 0 && (
        <section>
          <div className="section-head"><h2>动画观看</h2></div>
          <ul className="chapters">
            {watching.map(a => (
              <li key={`${a.type}-${a.episodeId ?? a.animationId}-${a.updatedAt}`}>
                <span className="row-inline" style={{ gap: 12 }}>
                  {a.coverUrl && (
                    <img src={a.coverUrl} alt="" className="history-thumb" referrerPolicy="no-referrer"
                         onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />
                  )}
                  <span>
                    <strong>{a.title}</strong>
                    <br />
                    <span className="muted">{summary(a)}</span>
                  </span>
                </span>
                <Link className="btn ghost" to={linkOf(a)}>{actionOf(a)}</Link>
              </li>
            ))}
          </ul>
        </section>
      )}
    </main>
  )
}

import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'

interface MangaProgress {
  source: string; mangaId: string; chapterId: string | null; chapterLabel: string | null
  page: number; totalPages: number | null; title: string; coverUrl: string | null
  updatedAt: string
}

interface ProgressRow {
  workId: string; workTitle: string; ordinal: number; episodeTitle: string | null
  episodeId: string | null; lineName: string | null; coverUrl: string | null
  positionMs: number; durationMs: number | null; updatedAt: string
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

function percent(row: ProgressRow): number {
  if (!row.durationMs || row.durationMs <= 0) return 0
  return Math.min(100, Math.round(row.positionMs / row.durationMs * 100))
}

/** 观看历史（M10）：外部集服务端进度，点击直接续播。 */
export default function History() {
  const [rows, setRows] = useState<ProgressRow[]>([])
  const [mangaRows, setMangaRows] = useState<MangaProgress[]>([])
  const [error, setError] = useState('')

  useEffect(() => {
    void (async () => {
      try {
        setRows(await api.get<ProgressRow[]>('/api/v1/me/watch-history?limit=100'))
        setMangaRows(await api.get<MangaProgress[]>('/api/v1/me/manga-progress?limit=50'))
      } catch (e) {
        setError(e instanceof Error ? e.message : '加载失败')
      }
    })()
  }, [])

  return (
    <main className="watch-page">
      <div className="watch-title"><h1>观看历史</h1></div>
      <div className="watch-meta"><span>共 {rows.length} 条 · 进度保存在服务端</span></div>
      {error && <p className="error">{error}</p>}
      {rows.length === 0 && !error && <p className="muted">还没有观看记录。</p>}

      {mangaRows.length > 0 && (
        <section style={{ marginBottom: 24 }}>
          <div className="section-head"><h2>漫画阅读</h2></div>
          <ul className="chapters">
            {mangaRows.map(m => (
              <li key={`${m.source}-${m.mangaId}`}>
                <span>
                  <strong>{m.title}</strong>
                  <br />
                  <span className="muted">
                    {m.chapterLabel ? `第 ${m.chapterLabel} 话 · ` : ''}
                    读到第 {m.page} 页{m.totalPages ? ` / ${m.totalPages}` : ''} · {m.source}
                  </span>
                </span>
                {m.chapterId ? (
                  <Link className="btn ghost"
                        to={`/manga-ext/read/${m.chapterId}?mangaId=${m.mangaId}&lang=all&title=${encodeURIComponent(m.title)}`}>
                    继续阅读
                  </Link>
                ) : (
                  <Link className="btn ghost" to={`/manga-ext/${m.mangaId}?lang=all`}>打开</Link>
                )}
              </li>
            ))}
          </ul>
        </section>
      )}

      <div className="section-head"><h2>动画观看</h2></div>
      <ul className="chapters">
        {rows.map(r => (
          <li key={`${r.workId}-${r.ordinal}`}>
            <span className="row-inline" style={{ gap: 12 }}>
              {r.coverUrl && <img src={r.coverUrl} alt="" className="history-thumb"
                                  onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />}
              <span>
                <strong>{r.workTitle}</strong>
                <br />
                <span className="muted">
                  第 {r.ordinal} 话{r.episodeTitle ? ` ${r.episodeTitle}` : ''}
                  {r.lineName ? ` · ${r.lineName}` : ''} · 看到 {fmt(r.positionMs)}
                  {r.durationMs ? ` / ${fmt(r.durationMs)}` : ''} · {percent(r)}%
                </span>
              </span>
            </span>
            {r.episodeId
              ? <Link className="btn ghost" to={`/works/${r.workId}/external/${r.episodeId}`}>继续观看</Link>
              : <Link className="btn ghost" to={`/works/${r.workId}`}>打开作品</Link>}
          </li>
        ))}
      </ul>
    </main>
  )
}

import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'

interface ShelfItem {
  workId: string
  title: string
  workStatus: string
  visibility: string
  lastChapterId: string | null
  lastPageOrdinal: number | null
}

interface ContinueRow {
  chapterId: string
  revisionId: string
  pageOrdinal: number
  workId: string
  workTitle: string
}

interface ShelfView {
  items: ShelfItem[]
  continueReading: ContinueRow[]
}

interface MangaProgress {
  source: string
  mangaId: string
  chapterId: string | null
  chapterLabel: string | null
  page: number
  totalPages: number | null
  title: string
  coverUrl: string | null
  updatedAt: string
}

interface AnimeProgress {
  workId: string
  workTitle: string
  ordinal: number
  episodeTitle: string | null
  episodeId: string | null
  lineName: string | null
  coverUrl: string | null
  positionMs: number
  durationMs: number | null
  updatedAt: string
}

type Tab = 'anime' | 'manga'

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

function percent(row: AnimeProgress): number {
  if (!row.durationMs || row.durationMs <= 0) return 0
  return Math.min(100, Math.round(row.positionMs / row.durationMs * 100))
}

function mangaReaderLink(row: MangaProgress): string {
  if (!row.chapterId) {
    return `/manga-ext/${row.mangaId}?${new URLSearchParams({ lang: 'all', source: row.source })}`
  }
  return `/manga-ext/read/${row.chapterId}?${new URLSearchParams({
    mangaId: row.mangaId, lang: 'all', source: row.source, title: row.title,
  })}`
}

/** 我的收藏：动漫与漫画分开管理，复用本地作品、外部阅读与观看进度。 */
export default function Shelf() {
  const [tab, setTab] = useState<Tab>('manga')
  const [shelf, setShelf] = useState<ShelfView>({ items: [], continueReading: [] })
  const [mangaRows, setMangaRows] = useState<MangaProgress[]>([])
  const [animeRows, setAnimeRows] = useState<AnimeProgress[]>([])
  const [error, setError] = useState('')

  useEffect(() => {
    void (async () => {
      const [shelfResult, mangaResult, animeResult] = await Promise.allSettled([
        api.get<ShelfView>('/api/v1/me/library'),
        api.get<MangaProgress[]>('/api/v1/me/manga-progress?limit=50'),
        api.get<AnimeProgress[]>('/api/v1/me/watch-history?limit=100'),
      ])
      if (shelfResult.status === 'fulfilled') setShelf(shelfResult.value)
      if (mangaResult.status === 'fulfilled') setMangaRows(mangaResult.value)
      if (animeResult.status === 'fulfilled') setAnimeRows(animeResult.value)
      const failed = [shelfResult, mangaResult, animeResult]
        .find(result => result.status === 'rejected')
      if (failed?.status === 'rejected') {
        setError(failed.reason instanceof Error ? failed.reason.message : '加载失败')
      }
    })()
  }, [])

  async function remove(workId: string) {
    await api.delete(`/api/v1/me/library/${workId}`)
    setShelf(current => ({
      ...current,
      items: current.items.filter(item => item.workId !== workId),
      continueReading: current.continueReading.filter(row => row.workId !== workId),
    }))
  }

  const mangaCount = shelf.items.length + mangaRows.length

  return (
    <main className="page">
      <div className="row-between">
        <div>
          <h1>我的收藏</h1>
          <p className="muted">动漫与漫画分开管理；阅读和观看进度会自动同步。</p>
        </div>
      </div>
      {error && <p className="error">{error}</p>}

      <div className="row-inline" role="tablist" aria-label="收藏分类"
           style={{ margin: '18px 0 22px' }}>
        <button role="tab" aria-selected={tab === 'anime'}
                className={tab === 'anime' ? '' : 'ghost'}
                onClick={() => setTab('anime')}>
          动漫 · {animeRows.length}
        </button>
        <button role="tab" aria-selected={tab === 'manga'}
                className={tab === 'manga' ? '' : 'ghost'}
                onClick={() => setTab('manga')}>
          漫画 · {mangaCount}
        </button>
      </div>

      {tab === 'anime' ? (
        <section>
          <div className="section-head">
            <h2>最近观看</h2>
            <Link to="/anime" className="muted">去番剧索引 →</Link>
          </div>
          <ul className="chapters">
            {animeRows.map(row => (
              <li key={`${row.workId}-${row.ordinal}`}>
                <span className="row-inline" style={{ gap: 12 }}>
                  {row.coverUrl && (
                    <img src={row.coverUrl} alt="" className="history-thumb"
                         onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />
                  )}
                  <span>
                    <strong>{row.workTitle}</strong>
                    <br />
                    <span className="muted">
                      第 {row.ordinal} 话{row.episodeTitle ? ` ${row.episodeTitle}` : ''}
                      {row.lineName ? ` · ${row.lineName}` : ''} · 看到 {fmt(row.positionMs)}
                      {row.durationMs ? ` / ${fmt(row.durationMs)}` : ''} · {percent(row)}%
                    </span>
                  </span>
                </span>
                {row.episodeId
                  ? <Link className="btn ghost" to={`/works/${row.workId}/external/${row.episodeId}`}>继续观看</Link>
                  : <Link className="btn ghost" to={`/works/${row.workId}`}>打开作品</Link>}
              </li>
            ))}
            {animeRows.length === 0 && (
              <li className="muted">还没有动漫收藏——去番剧索引追一部吧。</li>
            )}
          </ul>
        </section>
      ) : (
        <section>
          <div className="section-head">
            <h2>收藏的作品</h2>
            <Link to="/manga" className="muted">去漫画首页 →</Link>
          </div>
          <div className="grid">
            {shelf.items.map(item => (
              <div key={item.workId} className="card work">
                <div className="cover-ph">📖</div>
                <h3><Link to={`/works/${item.workId}`}>{item.title}</Link></h3>
                <span className={`tag ${item.visibility === 'PUBLIC' ? 'pub' : 'priv'}`}>
                  {item.visibility === 'PUBLIC' ? '公开' : '私人'}
                </span>
                <button className="ghost" onClick={() => void remove(item.workId)}>取消收藏</button>
              </div>
            ))}
            {shelf.items.length === 0 && (
              <p className="muted">还没有收藏作品——在漫画详情页点「加入收藏」。</p>
            )}
          </div>

          <div className="section-head" style={{ marginTop: 28 }}>
            <h2>外部漫画阅读</h2>
            <Link to="/manga" className="muted">继续找漫画 →</Link>
          </div>
          <ul className="chapters">
            {mangaRows.map(row => (
              <li key={`${row.source}-${row.mangaId}`}>
                <span className="row-inline" style={{ gap: 12 }}>
                  {row.coverUrl && (
                    <img src={row.coverUrl} alt="" className="history-thumb"
                         onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />
                  )}
                  <span>
                    <strong>{row.title}</strong>
                    <br />
                    <span className="muted">
                      {row.chapterLabel ? `第 ${row.chapterLabel} 话 · ` : ''}
                      读到第 {row.page} 页{row.totalPages ? ` / ${row.totalPages}` : ''} · {row.source}
                    </span>
                  </span>
                </span>
                <Link className="btn ghost" to={mangaReaderLink(row)}>
                  {row.chapterId ? '继续阅读' : '打开'}
                </Link>
              </li>
            ))}
            {mangaRows.length === 0 && (
              <li className="muted">还没有外部漫画阅读记录。</li>
            )}
          </ul>

          {shelf.continueReading.length > 0 && (
            <>
              <div className="section-head" style={{ marginTop: 28 }}>
                <h2>本地阅读进度</h2>
              </div>
              <ul className="chapters">
                {shelf.continueReading.map(row => (
                  <li key={row.chapterId}>
                    <span>{row.workTitle} · 上次到第 {row.pageOrdinal} 页</span>
                    <Link className="btn" to={`/read/${row.revisionId}?chapter=${row.chapterId}&resume=${row.pageOrdinal}`}>
                      继续
                    </Link>
                  </li>
                ))}
              </ul>
            </>
          )}
        </section>
      )}
    </main>
  )
}

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

export default function Shelf() {
  const [items, setItems] = useState<ShelfItem[]>([])
  const [continueReading, setContinueReading] = useState<ContinueRow[]>([])
  const [error, setError] = useState('')

  useEffect(() => {
    void (async () => {
      try {
        const shelf = await api.get<{ items: ShelfItem[]; continueReading: ContinueRow[] }>('/api/v1/me/library')
        setItems(shelf.items)
        setContinueReading(shelf.continueReading)
      } catch (e) {
        setError(e instanceof Error ? e.message : '加载失败')
      }
    })()
  }, [])

  async function remove(workId: string) {
    await api.delete(`/api/v1/me/library/${workId}`)
    setItems(items.filter(i => i.workId !== workId))
  }

  return (
    <main className="page">
      <h1>我的书架</h1>
      {error && <p className="error">{error}</p>}

      <h2>继续阅读</h2>
      <ul className="chapters">
        {continueReading.map(c => (
          <li key={c.chapterId}>
            <span>{c.workTitle} · 上次到第 {c.pageOrdinal} 页</span>
            <Link className="btn" to={`/read/${c.revisionId}?chapter=${c.chapterId}&resume=${c.pageOrdinal}`}>继续</Link>
          </li>
        ))}
        {continueReading.length === 0 && <li className="muted">还没有阅读记录</li>}
      </ul>

      <h2>收藏</h2>
      <div className="grid">
        {items.map(i => (
          <div key={i.workId} className="card work">
            <div className="cover-ph">📖</div>
            <h3><Link to={`/works/${i.workId}`}>{i.title}</Link></h3>
            <button className="ghost" onClick={() => void remove(i.workId)}>取消收藏</button>
          </div>
        ))}
        {items.length === 0 && <p className="muted">书架是空的——去书库收藏一些作品。</p>}
      </div>
    </main>
  )
}

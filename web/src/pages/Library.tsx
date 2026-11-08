import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'

interface WorkItem {
  id: string
  title: string
  description: string
  status: string
  visibility: string
  ownerId: string
}

export default function Library() {
  const [items, setItems] = useState<WorkItem[]>([])
  const [q, setQ] = useState('')
  const [error, setError] = useState('')

  useEffect(() => {
    const t = setTimeout(async () => {
      try {
        const page = await api.get<{ items: WorkItem[] }>(
          `/api/v1/works?limit=20${q ? `&q=${encodeURIComponent(q)}` : ''}`)
        setItems(page.items)
        setError('')
      } catch (e) {
        setError(e instanceof Error ? e.message : '加载失败')
      }
    }, q ? 300 : 0)
    return () => clearTimeout(t)
  }, [q])

  return (
    <main className="page">
      <div className="row-between">
        <h1>书库</h1>
        <input className="search" placeholder="按标题搜索…" value={q} onChange={e => setQ(e.target.value)} />
      </div>
      {error && <p className="error">{error}</p>}
      <div className="grid">
        {items.map(w => (
          <Link key={w.id} to={`/works/${w.id}`} className="card work">
            <div className="cover-ph">📖</div>
            <h3>{w.title}</h3>
            <p className="muted clamp">{w.description || '（无简介）'}</p>
            <span className={`tag ${w.visibility === 'PUBLIC' ? 'pub' : 'priv'}`}>
              {w.visibility === 'PUBLIC' ? '公开' : '私人'}
            </span>
          </Link>
        ))}
        {items.length === 0 && <p className="muted">书库还是空的——到「我的素材」上传第一部作品。</p>}
      </div>
    </main>
  )
}

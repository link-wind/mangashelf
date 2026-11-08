import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'

interface Hit {
  kind: 'work' | 'page'
  workId: string
  workTitle: string
  chapterId: string | null
  revisionId: string | null
  pageOrdinal: number
  chapterTitle: string | null
  snippet: string
  hasAnimation: boolean
}

/** 搜索页（DESIGN 15.3）：标题/对白/语义混合结果 + 页级定位 + "有动画"过滤。 */
export default function Search() {
  const [q, setQ] = useState('')
  const [hits, setHits] = useState<Hit[]>([])
  const [semanticEnabled, setSemanticEnabled] = useState(true)
  const [hasAnimation, setHasAnimation] = useState(false)
  const [searched, setSearched] = useState(false)
  const [error, setError] = useState('')

  const run = useCallback(async (query: string) => {
    if (!query.trim()) { setHits([]); setSearched(false); return }
    try {
      const res = await api.post<{ semanticEnabled: boolean; hits: Hit[] }>('/api/v1/search', {
        q: query, hasAnimation, limit: 15,
      })
      setHits(res.hits)
      setSemanticEnabled(res.semanticEnabled)
      setSearched(true)
      setError('')
    } catch (e) {
      setError(e instanceof Error ? e.message : '搜索失败')
    }
  }, [hasAnimation])

  useEffect(() => {
    const t = setTimeout(() => void run(q), 350)
    return () => clearTimeout(t)
  }, [q, hasAnimation, run])

  return (
    <main className="page">
      <h1>搜索</h1>
      <div className="row-inline">
        <input className="search" style={{ width: 420 }} placeholder="搜索标题或对白…"
               value={q} onChange={e => setQ(e.target.value)} autoFocus />
        <label className="scope-item">
          <input type="checkbox" checked={hasAnimation} onChange={e => setHasAnimation(e.target.checked)} />
          只看有动画的
        </label>
      </div>
      {!semanticEnabled && <p className="muted">语义检索未启用（本地嵌入模型缺失），字面检索可用。</p>}
      {error && <p className="error">{error}</p>}

      {searched && hits.length === 0 && <p className="muted">没有找到相关内容。</p>}
      <div className="stack">
        {hits.map((h, i) => (
          <div key={i} className="card">
            {h.kind === 'work' ? (
              <>
                <div className="row-between">
                  <Link to={`/works/${h.workId}`}><strong>《{h.workTitle}》</strong></Link>
                  {h.hasAnimation && <span className="tag pub">有动画</span>}
                </div>
                <p className="muted">{h.snippet || '（作品）'}</p>
              </>
            ) : (
              <>
                <div className="row-between">
                  <Link to={`/read/${h.revisionId}?chapter=${h.chapterId}&resume=${h.pageOrdinal}`}>
                    <strong>《{h.workTitle}》{h.chapterTitle} · 第 {h.pageOrdinal} 页</strong>
                  </Link>
                  {h.hasAnimation && <span className="tag pub">有动画</span>}
                </div>
                <p className="muted clamp">{h.snippet}</p>
              </>
            )}
          </div>
        ))}
      </div>
    </main>
  )
}

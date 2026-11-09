import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'

interface CatalogItem {
  id: string; sourceId: string; name: string; coverUrl: string; remarks: string
  categoryId: number; categoryName: string; episodeCount: number; workId: string | null
}
interface CatalogDetail {
  item: CatalogItem
  bangumiNameCn: string | null
  rating: number | null
  summary: string | null
  airWeekday: number | null
  lines: { name: string; episodeCount: number }[]
}

const WEEKDAYS = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']

/** 目录条目详情（M11）：封面/Bangumi 简介/线路概览 + 加入书库或播放。 */
export default function CatalogDetail() {
  const { id } = useParams()
  const navigate = useNavigate()
  const [detail, setDetail] = useState<CatalogDetail | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    if (!id) return
    try {
      setDetail(await api.get<CatalogDetail>(`/api/v1/catalog-items/${id}`))
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败')
    }
  }, [id])
  useEffect(() => { void load() }, [load])

  async function materialize() {
    if (!id) return
    setBusy(true)
    try {
      const res = await api.post<{ workId: string }>(`/api/v1/catalog-items/${id}/materialize`, {})
      navigate(`/works/${res.workId}`)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加入书库失败')
    } finally { setBusy(false) }
  }

  if (error) return <main className="watch-page"><p className="error">{error}</p></main>
  if (!detail) return <main className="watch-page page-center">加载中…</main>

  const { item } = detail
  return (
    <main className="watch-page">
      <div className="detail-hero">
        <div className="detail-cover">
          {item.coverUrl
            ? <img src={item.coverUrl} alt={item.name} onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />
            : <span className="cover-initial">{item.name.slice(0, 1)}</span>}
        </div>
        <div className="detail-info">
          <h1>{detail.bangumiNameCn || item.name}</h1>
          <div className="watch-meta" style={{ margin: '4px 0 10px' }}>
            {typeof detail.rating === 'number' && detail.rating > 0 && <span className="star">★ {detail.rating.toFixed(1)}</span>}
            {detail.airWeekday && <span>{WEEKDAYS[detail.airWeekday]}放送</span>}
            <span>{item.categoryName}</span>
            <span>{item.remarks || `${item.episodeCount} 集`}</span>
          </div>
          {detail.lines.length > 0 && (
            <p className="muted">
              线路：{detail.lines.map(l => `${l.name}（${l.episodeCount} 集）`).join(' · ')}
            </p>
          )}
          {detail.summary && <p className="detail-summary">{detail.summary}</p>}
          <div className="row-inline" style={{ marginTop: 14 }}>
            {item.workId
              ? <Link className="btn" to={`/works/${item.workId}`}>打开作品</Link>
              : <>
                  <Link className="btn" to={`/browse/${item.id}`}>立即播放</Link>
                  <button className="ghost" disabled={busy} onClick={() => void materialize()}>
                    {busy ? '处理中…' : '加入书库'}
                  </button>
                </>}
            <Link className="btn ghost" to="/anime">返回索引</Link>
          </div>
        </div>
      </div>

      <p className="muted" style={{ marginTop: 18 }}>
        资源标题：{item.name}
      </p>
    </main>
  )
}

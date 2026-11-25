import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'
import CoverImage from '../components/CoverImage'

interface CatalogItem {
  id: string; sourceId: string; name: string; coverUrl: string; remarks: string
  categoryId: number; categoryName: string; episodeCount: number; workId: string | null
  bangumiSubjectId?: number | null
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

/** 目录条目详情（M11）：播放不创建作品；收藏是独立操作，导入仅用于编辑或发布。 */
export default function CatalogDetail() {
  const { id } = useParams()
  const navigate = useNavigate()
  const [detail, setDetail] = useState<CatalogDetail | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [favorited, setFavorited] = useState(false)

  const load = useCallback(async () => {
    if (!id) return
    try {
      setDetail(await api.get<CatalogDetail>(`/api/v1/catalog-items/${id}`))
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败')
    }
  }, [id])
  useEffect(() => { void load() }, [load])

  useEffect(() => {
    if (!id) return
    void api.get<{ catalogItemId: string | null }[]>('/api/v1/me/favorites?type=catalog')
      .then(list => setFavorited(list.some(f => f.catalogItemId === id)))
      .catch(() => { /* 收藏状态不可用不影响详情 */ })
  }, [id])

  async function toggleFavorite() {
    if (!id) return
    try {
      if (favorited) {
        await api.delete(`/api/v1/me/favorites/catalog/${id}`)
        setFavorited(false)
      } else {
        await api.put(`/api/v1/me/favorites/catalog/${id}`)
        setFavorited(true)
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '收藏操作失败')
    }
  }

  async function materialize() {
    if (!id) return
    setBusy(true)
    try {
      const res = await api.post<{ workId: string }>(`/api/v1/catalog-items/${id}/materialize`, {})
      navigate(`/works/${res.workId}`)
    } catch (e) {
      setError(e instanceof Error ? e.message : '导入失败')
    } finally { setBusy(false) }
  }

  if (error) return <main className="watch-page"><p className="error">{error}</p></main>
  if (!detail) return <main className="watch-page page-center">加载中…</main>

  const { item } = detail
  return (
    <main className="watch-page">
      <div className="detail-hero">
        <div className="detail-cover">
          <CoverImage
            src={item.bangumiSubjectId ? `/api/v1/bangumi-covers/${item.bangumiSubjectId}?size=large` : item.coverUrl}
            alt={item.name}
            loading="eager"
          />
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
            <Link className="btn" to={`/browse/${item.id}`}>立即播放</Link>
            <button className="ghost" onClick={() => void toggleFavorite()}>
              {favorited ? '已收藏' : '收藏'}
            </button>
            {item.workId && <Link className="btn ghost" to={`/works/${item.workId}`}>打开作品</Link>}
            {!item.workId && (
              <button className="ghost" disabled={busy} onClick={() => void materialize()}
                      title="仅用于编辑或发布：创建作品并收编剧集">
                {busy ? '处理中…' : '导入为我的作品'}
              </button>
            )}
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

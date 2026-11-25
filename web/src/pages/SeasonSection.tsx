import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'
import CoverImage from '../components/CoverImage'

export interface CalendarEntry {
  subjectId: number
  name: string
  nameCn: string
  coverUrl: string
  rating: number | null
  airWeekday: number | null
  covered: boolean
  catalogItemId: string | null
  workId: string | null
}

const WEEKDAYS = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']

/** 本季新番（Bangumi 日历 + 资源覆盖）：可横向或网格展示（M9）。 */
export default function SeasonSection({ layout = 'grid', limit }: {
  layout?: 'grid' | 'scroll'
  limit?: number
}) {
  const navigate = useNavigate()
  const [items, setItems] = useState<CalendarEntry[]>([])
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    try {
      setItems(await api.get<CalendarEntry[]>('/api/v1/calendar'))
    } catch (e) {
      setError(e instanceof Error ? e.message : '日历加载失败')
    }
  }, [])
  useEffect(() => { void load() }, [load])

  function play(it: CalendarEntry) {
    if (it.workId) { navigate(`/works/${it.workId}`); return }
    if (it.catalogItemId) navigate(`/browse/${it.catalogItemId}`)
  }

  const shown = limit ? items.slice(0, limit) : items
  const covered = items.filter(i => i.covered).length

  return (
    <div>
      <div className="section-head">
        <h2 style={{ display: 'flex', alignItems: 'baseline', gap: 10 }}>
          本季新番
          <span className="muted" style={{ fontSize: 13, fontWeight: 400 }}>
            Bangumi 本季 {items.length} 部 · 资源已覆盖 {covered} 部
          </span>
        </h2>
      </div>
      {error && <p className="error">{error}</p>}
      <div className={layout === 'scroll' ? 'hscroll' : 'card-grid'}>
        {shown.map(it => (
          <div key={it.subjectId} className="work-card"
               onClick={() => navigate(it.workId ? `/works/${it.workId}`
                 : (it.catalogItemId ? `/browse/${it.catalogItemId}` : '/anime'))}
               style={{ cursor: 'pointer' }}>
            <div className="cover">
              <CoverImage src={`/api/v1/bangumi-covers/${it.subjectId}?size=common`} alt={it.nameCn || it.name} />
              {typeof it.rating === 'number' && it.rating > 0 && (
                <span className="badge-anim" style={{ left: 'auto', right: 8, background: '#f0a020' }}>
                  ★ {it.rating.toFixed(1)}
                </span>
              )}
              {it.covered && <span className="badge-anim">可看</span>}
            </div>
            <div className="work-card-body">
              <div className="work-card-title clamp">{it.nameCn || it.name}</div>
              <div className="work-card-meta">
                <span>{it.airWeekday ? WEEKDAYS[it.airWeekday] : '放送中'}</span>
              </div>
              <div className="row-inline" style={{ marginTop: 8 }}>
                {it.covered
                  ? <button style={{ fontSize: 12, padding: '4px 10px' }}
                            onClick={() => play(it)}>
                      {it.workId ? '打开' : '播放'}
                    </button>
                  : <span className="muted" style={{ fontSize: 12 }}>资源未收录</span>}
              </div>
            </div>
          </div>
        ))}
        {items.length === 0 && !error && (
          <p className="muted">日历为空——在设置页触发一次 Bangumi 同步。</p>
        )}
      </div>
    </div>
  )
}

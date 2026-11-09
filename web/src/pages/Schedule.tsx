import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'

interface CalendarEntry {
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

/** 新番时刻表（M9 周视图）：按放送星期分栏，可直接播放入库。 */
export default function Schedule() {
  const navigate = useNavigate()
  const [items, setItems] = useState<CalendarEntry[]>([])
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    try {
      setItems(await api.get<CalendarEntry[]>('/api/v1/calendar'))
    } catch (e) {
      setError(e instanceof Error ? e.message : '日历加载失败')
    }
  }, [])
  useEffect(() => { void load() }, [load])

  async function play(it: CalendarEntry) {
    if (it.workId) { navigate(`/works/${it.workId}`); return }
    if (!it.catalogItemId) return
    setBusy(it.catalogItemId); setError('')
    try {
      const res = await api.post<{ workId: string }>(`/api/v1/catalog-items/${it.catalogItemId}/materialize`, {})
      navigate(`/works/${res.workId}`)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加入书库失败')
    } finally { setBusy(null) }
  }

  const byDay = (day: number) => items
    .filter(i => (i.airWeekday ?? 0) === day)
    .sort((a, b) => (b.rating ?? 0) - (a.rating ?? 0))
  const unscheduled = items.filter(i => !i.airWeekday)

  return (
    <main className="watch-page">
      <div className="watch-title"><h1>新番时刻表</h1></div>
      <div className="watch-meta">
        <span>Bangumi 本季 {items.length} 部 · 资源已覆盖 {items.filter(i => i.covered).length} 部</span>
        <span>按日本放送日（周一～周日）</span>
      </div>
      {error && <p className="error">{error}</p>}

      <div className="schedule-grid">
        {[1, 2, 3, 4, 5, 6, 7].map(day => {
          const list = byDay(day)
          return (
            <section key={day} className="schedule-col">
              <header>
                {WEEKDAYS[day]}
                <span className="muted"> {list.length}</span>
              </header>
              {list.map(it => (
                <ScheduleCard key={it.subjectId} it={it} busy={busy} onPlay={play} />
              ))}
              {list.length === 0 && <p className="muted" style={{ fontSize: 12 }}>—</p>}
            </section>
          )
        })}
      </div>

      {unscheduled.length > 0 && (
        <section style={{ marginTop: 26 }}>
          <div className="section-head"><h2>放送日未定（{unscheduled.length}）</h2></div>
          <div className="card-grid">
            {unscheduled.map(it => (
              <div key={it.subjectId} className="work-card">
                <div className="cover">
                  <img src={`/api/v1/bangumi-covers/${it.subjectId}`} alt={it.nameCn || it.name} loading="lazy"
                       onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />
                  {it.covered && <span className="badge-anim">可看</span>}
                </div>
                <div className="work-card-body">
                  <div className="work-card-title clamp">{it.nameCn || it.name}</div>
                  <div className="row-inline" style={{ marginTop: 8 }}>
                    {it.covered
                      ? <button style={{ fontSize: 12, padding: '4px 10px' }}
                                disabled={busy === it.catalogItemId}
                                onClick={() => void play(it)}>
                          {busy === it.catalogItemId ? '处理中…' : (it.workId ? '打开' : '播放')}
                        </button>
                      : <span className="muted" style={{ fontSize: 12 }}>资源未收录</span>}
                  </div>
                </div>
              </div>
            ))}
          </div>
        </section>
      )}
    </main>
  )
}

function ScheduleCard({ it, busy, onPlay }: {
  it: CalendarEntry
  busy: string | null
  onPlay: (it: CalendarEntry) => Promise<void>
}) {
  return (
    <div className="schedule-item">
      <img src={`/api/v1/bangumi-covers/${it.subjectId}`} alt="" loading="lazy"
           onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />
      <div className="schedule-body">
        <div className="schedule-name clamp">{it.nameCn || it.name}</div>
        <div className="schedule-meta">
          {typeof it.rating === 'number' && it.rating > 0 && <span className="star">★ {it.rating.toFixed(1)}</span>}
          {it.covered
            ? <button className="mini" disabled={busy === it.catalogItemId}
                      onClick={() => void onPlay(it)}>
                {busy === it.catalogItemId ? '…' : (it.workId ? '打开' : '播放')}
              </button>
            : <span className="muted">未收录</span>}
        </div>
      </div>
    </div>
  )
}

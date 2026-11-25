import { useState } from 'react'

interface PaginationProps {
  page: number
  totalPages: number
  total: number
  unit?: string
  loading?: boolean
  onChange: (nextPage: number) => void
}

/** 通用页码分页组件：上一页/下一页、省略号页码、总数统计与页码直达跳转。 */
export default function Pagination({
  page, totalPages, total, loading = false, onChange,
}: PaginationProps) {
  const [jumpInput, setJumpInput] = useState('')

  if (totalPages <= 1 && total === 0) return null

  const pages = buildPageWindow(page, totalPages)

  function go(p: number) {
    if (loading || p < 1 || p > totalPages || p === page) return
    onChange(p)
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  function submitJump(e: React.FormEvent) {
    e.preventDefault()
    const target = Number.parseInt(jumpInput.trim(), 10)
    if (Number.isFinite(target) && target >= 1 && target <= totalPages) {
      setJumpInput('')
      go(target)
    }
  }

  return (
    <nav className="pagination-bar" aria-label="分页导航">
      <span className="pagination-summary muted tabular-nums">
        第 {page} / {totalPages} 页
      </span>
      {totalPages > 1 && (
        <div className="pagination-controls">
          <button type="button" className="mini ghost" disabled={loading || page <= 1}
                  onClick={() => go(page - 1)}>
            ← 上一页
          </button>
          {pages.map((p, idx) =>
            p === 'ellipsis' ? (
              <span key={`e-${idx}`} className="pagination-ellipsis muted" aria-hidden="true">…</span>
            ) : (
              <button key={p} type="button"
                      className={'mini pagination-num tabular-nums' + (p === page ? '' : ' ghost')}
                      aria-current={p === page ? 'page' : undefined}
                      disabled={loading}
                      onClick={() => go(p)}>
                {p}
              </button>
            )
          )}
          <button type="button" className="mini ghost" disabled={loading || page >= totalPages}
                  onClick={() => go(page + 1)}>
            下一页 →
          </button>
          {totalPages > 5 && (
            <form className="pagination-jump" onSubmit={submitJump}>
              <span className="muted">跳至</span>
              <input type="number" min={1} max={totalPages} value={jumpInput}
                     onChange={e => setJumpInput(e.target.value)}
                     placeholder={String(page)} aria-label="跳转页码" />
              <button type="submit" className="mini ghost" disabled={loading || !jumpInput.trim()}>前往</button>
            </form>
          )}
        </div>
      )}
    </nav>
  )
}

function buildPageWindow(current: number, total: number): Array<number | 'ellipsis'> {
  if (total <= 7) {
    return Array.from({ length: total }, (_, i) => i + 1)
  }
  const out: Array<number | 'ellipsis'> = [1]
  const start = Math.max(2, current - 1)
  const end = Math.min(total - 1, current + 1)
  if (start > 2) out.push('ellipsis')
  for (let i = start; i <= end; i++) out.push(i)
  if (end < total - 1) out.push('ellipsis')
  out.push(total)
  return out
}

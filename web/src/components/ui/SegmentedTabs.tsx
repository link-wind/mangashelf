import { motion } from 'motion/react'
import type { ReactNode } from 'react'

export interface SegmentedTabItem<T extends string = string> {
  value: T
  label: string
  icon?: ReactNode
  count?: number | string
  dot?: boolean
}

interface SegmentedTabsProps<T extends string = string> {
  items: SegmentedTabItem<T>[]
  value: T
  onChange: (value: T) => void
  layoutId: string
  ariaLabel?: string
  tone?: 'cyan' | 'rose' | 'neutral'
  size?: 'sm' | 'md'
  className?: string
}

/** 基于 Motion layoutId 的共享滑动指示器分段切换栏（统一全站 Tab / 视图 / 榜单切换）。 */
export default function SegmentedTabs<T extends string = string>({
  items, value, onChange, layoutId, ariaLabel, tone = 'cyan', size = 'md', className = '',
}: SegmentedTabsProps<T>) {
  return (
    <div
      className={`ui-seg-tabs is-${tone} is-${size} ${className}`.trim()}
      role="tablist"
      aria-label={ariaLabel}
    >
      {items.map(it => {
        const active = it.value === value
        return (
          <button
            key={it.value}
            type="button"
            role="tab"
            aria-selected={active}
            className={`ui-seg-tab${active ? ' is-active' : ''}`}
            onClick={() => onChange(it.value)}
          >
            {active && (
              <motion.span
                layoutId={layoutId}
                className="ui-seg-pill"
                transition={{ type: 'spring', stiffness: 460, damping: 34 }}
                aria-hidden="true"
              />
            )}
            <span className="ui-seg-content">
              {it.icon && <span className="ui-seg-ico">{it.icon}</span>}
              <span>{it.label}</span>
              {it.count !== undefined && (
                <em className="ui-seg-count tabular-nums">{it.count}</em>
              )}
              {it.dot && <span className="ui-seg-dot" aria-label="有更新" />}
            </span>
          </button>
        )
      })}
    </div>
  )
}

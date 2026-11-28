import { motion } from 'motion/react'
import { useCallback, useLayoutEffect, useRef, useState, type ReactNode } from 'react'

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
  layoutId?: string
  ariaLabel?: string
  tone?: 'cyan' | 'rose' | 'neutral'
  size?: 'sm' | 'md'
  className?: string
}

interface PillRect {
  x: number
  y: number
  width: number
  height: number
}

/**
 * 基于 ResizeObserver 实时测量 + Motion 弹簧过渡的分段切换栏：
 * - 用户切换选中项时播放顺滑弹簧动画；
 * - 窗口缩放或异步计数变更触发 ResizeObserver 时通过 rAF 合并并 0ms 瞬时贴合，彻底避免缩窗闪烁抖动。
 */
export default function SegmentedTabs<T extends string = string>({
  items, value, onChange, ariaLabel, tone = 'cyan', size = 'md', className = '',
}: SegmentedTabsProps<T>) {
  const containerRef = useRef<HTMLDivElement>(null)
  const tabRefs = useRef<Map<string, HTMLButtonElement>>(new Map())
  const prevValueRef = useRef<T | null>(null)
  const rafRef = useRef<number | null>(null)
  const [pill, setPill] = useState<PillRect | null>(null)
  const [animatePill, setAnimatePill] = useState(false)

  const measure = useCallback((shouldAnimate: boolean) => {
    const activeBtn = tabRefs.current.get(value)
    if (!activeBtn) return
    const next: PillRect = {
      x: activeBtn.offsetLeft,
      y: activeBtn.offsetTop,
      width: activeBtn.offsetWidth,
      height: activeBtn.offsetHeight,
    }
    setAnimatePill(shouldAnimate)
    setPill(prev => {
      if (prev && prev.x === next.x && prev.y === next.y && prev.width === next.width && prev.height === next.height) {
        return prev
      }
      return next
    })
  }, [value])

  // 每次选中项、计数或文案变化后同步测量
  const signature = items.map(it => `${it.value}:${it.label}:${it.count ?? ''}`).join('|')

  useLayoutEffect(() => {
    const isValueSwitch = prevValueRef.current !== null && prevValueRef.current !== value
    prevValueRef.current = value
    measure(isValueSwitch)

    const el = containerRef.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(() => {
      if (rafRef.current !== null) cancelAnimationFrame(rafRef.current)
      rafRef.current = requestAnimationFrame(() => {
        rafRef.current = null
        measure(false)
      })
    })
    ro.observe(el)
    tabRefs.current.forEach(btn => ro.observe(btn))
    return () => {
      ro.disconnect()
      if (rafRef.current !== null) {
        cancelAnimationFrame(rafRef.current)
        rafRef.current = null
      }
    }
  }, [measure, signature, value])

  return (
    <div
      ref={containerRef}
      className={`ui-seg-tabs is-${tone} is-${size} ${className}`.trim()}
      role="tablist"
      aria-label={ariaLabel}
    >
      {pill && (
        <motion.span
          className="ui-seg-pill"
          aria-hidden="true"
          initial={false}
          animate={{ x: pill.x, y: pill.y, width: pill.width, height: pill.height }}
          transition={animatePill
            ? { type: 'spring', stiffness: 480, damping: 36 }
            : { duration: 0 }}
        />
      )}
      {items.map(it => {
        const active = it.value === value
        return (
          <button
            key={it.value}
            ref={node => {
              if (node) tabRefs.current.set(it.value, node)
              else tabRefs.current.delete(it.value)
            }}
            type="button"
            role="tab"
            aria-selected={active}
            className={`ui-seg-tab${active ? ' is-active' : ''}`}
            onClick={() => onChange(it.value)}
          >
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

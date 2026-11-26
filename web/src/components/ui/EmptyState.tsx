import type { ReactNode } from 'react'

interface EmptyStateProps {
  icon: ReactNode
  title: string
  description?: string
  action?: ReactNode
  compact?: boolean
  tone?: 'cyan' | 'rose' | 'neutral'
}

/** 全站统一空态卡片（圆章图标 + 标题 + 说明 + 引导 CTA）。 */
export default function EmptyState({
  icon, title, description, action, compact = false, tone = 'cyan',
}: EmptyStateProps) {
  return (
    <div className={`ui-empty${compact ? ' is-compact' : ''}`}>
      <span className={`ui-empty-ico is-${tone}`} aria-hidden="true">{icon}</span>
      <h3>{title}</h3>
      {description && <p>{description}</p>}
      {action && <div className="ui-empty-actions">{action}</div>}
    </div>
  )
}

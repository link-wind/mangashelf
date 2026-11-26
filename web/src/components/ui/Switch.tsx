interface SwitchProps {
  checked: boolean
  onChange: (checked: boolean) => void
  label?: string
  disabled?: boolean
  tone?: 'cyan' | 'rose'
  ariaLabel?: string
}

/** 现代开关控件（替代全站原生操作系统蓝方块复选框）。 */
export default function Switch({
  checked, onChange, label, disabled = false, tone = 'cyan', ariaLabel,
}: SwitchProps) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={ariaLabel ?? label}
      disabled={disabled}
      className={`ui-switch-wrap${label ? ' has-label' : ''}`}
      onClick={() => !disabled && onChange(!checked)}
    >
      <span className={`ui-switch-track is-${tone}${checked ? ' is-on' : ''}`} aria-hidden="true">
        <span className="ui-switch-thumb" />
      </span>
      {label && <span className="ui-switch-label">{label}</span>}
    </button>
  )
}

interface BrandLogoProps {
  size?: number
  className?: string
}

/** 统一品牌徽标（React SVG，替代失效的 CSS data-URI，亮暗双主题高清发光）。 */
export default function BrandLogo({ size = 28, className = '' }: BrandLogoProps) {
  const gradId = `ms-brand-grad-${size}`
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 32 32"
      fill="none"
      aria-hidden="true"
      className={`brand-logo-svg ${className}`.trim()}
    >
      <defs>
        <linearGradient id={gradId} x1="2" y1="2" x2="30" y2="30" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stopColor="#35d0ff" />
          <stop offset="55%" stopColor="#00aeec" />
          <stop offset="100%" stopColor="#0077c8" />
        </linearGradient>
      </defs>
      <rect x="1" y="1" width="30" height="30" rx="9" fill={`url(#${gradId})`} />
      <rect x="1.5" y="1.5" width="29" height="29" rx="8.5" stroke="rgba(255,255,255,0.32)" strokeWidth="1" />
      <path
        d="M12.2 10.2C12.2 9.45 13.02 8.98 13.67 9.36L22.65 14.66C23.29 15.04 23.29 15.96 22.65 16.34L13.67 21.64C13.02 22.02 12.2 21.55 12.2 20.8V10.2Z"
        fill="#FFFFFF"
      />
      <path
        d="M8.5 11.2V20.8"
        stroke="rgba(255,255,255,0.75)"
        strokeWidth="2.1"
        strokeLinecap="round"
      />
    </svg>
  )
}

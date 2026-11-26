import { BRAND_ICON_URL } from '../../lib/brand'

interface BrandLogoProps {
  size?: number
  className?: string
}

/** GemiNeko（杰米猫）统一品牌徽标图标。 */
export default function BrandLogo({ size = 28, className = '' }: BrandLogoProps) {
  return (
    <img
      src={BRAND_ICON_URL}
      width={size}
      height={size}
      alt=""
      aria-hidden="true"
      decoding="async"
      className={`brand-logo-img ${className}`.trim()}
    />
  )
}

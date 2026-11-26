import type { SVGProps } from 'react'

type IconProps = SVGProps<SVGSVGElement> & { size?: number }

function base(size = 16, props: SVGProps<SVGSVGElement>) {
  return {
    width: size,
    height: size,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 1.8,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    'aria-hidden': true,
    ...props,
  }
}

export function SearchIcon({ size = 15, ...p }: IconProps) {
  return (
    <svg {...base(size, p)}>
      <circle cx="11" cy="11" r="7" />
      <path d="m20 20-3.5-3.5" />
    </svg>
  )
}

export function SunIcon({ size = 16, ...p }: IconProps) {
  return (
    <svg {...base(size, p)}>
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2m0 16v2M4.93 4.93l1.41 1.41m11.32 11.32 1.41 1.41M2 12h2m16 0h2M4.93 19.07l1.41-1.41m11.32-11.32 1.41-1.41" />
    </svg>
  )
}

export function MoonIcon({ size = 16, ...p }: IconProps) {
  return (
    <svg {...base(size, p)}>
      <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z" />
    </svg>
  )
}

export function MenuIcon({ size = 20, ...p }: IconProps) {
  return (
    <svg {...base(size, p)}>
      <path d="M4 7h16M4 12h16M4 17h16" />
    </svg>
  )
}

export function CloseIcon({ size = 18, ...p }: IconProps) {
  return (
    <svg {...base(size, p)}>
      <path d="M18 6 6 18M6 6l12 12" />
    </svg>
  )
}

export function SettingsIcon({ size = 15, ...p }: IconProps) {
  return (
    <svg {...base(size, p)}>
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h.08a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v.08a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1Z" />
    </svg>
  )
}

export function LogOutIcon({ size = 15, ...p }: IconProps) {
  return (
    <svg {...base(size, p)}>
      <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
      <polyline points="16 17 21 12 16 7" />
      <line x1="21" y1="12" x2="9" y2="12" />
    </svg>
  )
}

export function UploadIcon({ size = 15, ...p }: IconProps) {
  return (
    <svg {...base(size, p)}>
      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
      <polyline points="17 8 12 3 7 8" />
      <line x1="12" y1="3" x2="12" y2="15" />
    </svg>
  )
}

export function PlayIcon({ size = 15, ...p }: IconProps) {
  return (
    <svg {...base(size, p)} fill="currentColor" stroke="none">
      <path d="M7 5.5v13l11-6.5z" />
    </svg>
  )
}

export function HeartIcon({ size = 15, filled = false, ...p }: IconProps & { filled?: boolean }) {
  return (
    <svg {...base(size, p)} fill={filled ? 'currentColor' : 'none'}>
      <path d="M12 20.5s-7.5-4.8-9.3-9.2C1.4 8.2 3.3 5 6.6 5c1.9 0 3.4 1 4.4 2.4C12 6 13.5 5 15.4 5c3.3 0 5.2 3.2 3.9 6.3-1.8 4.4-9.3 9.2-9.3 9.2Z" />
    </svg>
  )
}

export function InfoIcon({ size = 16, ...p }: IconProps) {
  return (
    <svg {...base(size, p)}>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 16v-4m0-4h.01" />
    </svg>
  )
}

export function EyeIcon({ size = 16, off = false, ...p }: IconProps & { off?: boolean }) {
  return (
    <svg {...base(size, p)}>
      <path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6-10-6-10-6Z" />
      <circle cx="12" cy="12" r="2.8" />
      {off && <path d="M3 3l18 18" />}
    </svg>
  )
}

export function ChevronDownIcon({ size = 14, ...p }: IconProps) {
  return (
    <svg {...base(size, p)}>
      <path d="m6 9 6 6 6-6" />
    </svg>
  )
}

export function BoltIcon({ size = 15, ...p }: IconProps) {
  return (
    <svg {...base(size, p)}>
      <path d="M13 2 4.5 13.5h6L9.5 22 19.5 10.5h-6L13 2Z" />
    </svg>
  )
}

export function ClockIcon({ size = 15, ...p }: IconProps) {
  return (
    <svg {...base(size, p)}>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" />
    </svg>
  )
}

export function BookOpenIcon({ size = 16, ...p }: IconProps) {
  return (
    <svg {...base(size, p)}>
      <path d="M2 5.5A2.5 2.5 0 0 1 4.5 3H10a2 2 0 0 1 2 2v14a2 2 0 0 0-2-2H4.5A2.5 2.5 0 0 0 2 19.5v-14Z" />
      <path d="M22 5.5A2.5 2.5 0 0 0 19.5 3H14a2 2 0 0 0-2 2v14a2 2 0 0 1 2-2h5.5a2.5 2.5 0 0 1 2.5 2.5v-14Z" />
    </svg>
  )
}

export function CheckCircleIcon({ size = 18, ...p }: IconProps) {
  return (
    <svg {...base(size, p)}>
      <circle cx="12" cy="12" r="9" />
      <path d="m8.5 12.2 2.3 2.3 4.7-4.8" />
    </svg>
  )
}

export function StarIcon({ size = 16, filled = true, ...p }: IconProps & { filled?: boolean }) {
  return (
    <svg {...base(size, p)} fill={filled ? 'currentColor' : 'none'}>
      <path d="m12 3.2 2.65 5.37 5.93.86-4.29 4.18 1.01 5.9L12 16.72l-5.3 2.79 1.01-5.9-4.29-4.18 5.93-.86L12 3.2Z" />
    </svg>
  )
}

export function FilmIcon({ size = 18, ...p }: IconProps) {
  return (
    <svg {...base(size, p)}>
      <rect x="3" y="4" width="18" height="16" rx="2.5" />
      <path d="M7 4v16M17 4v16M3 9h4M17 9h4M3 15h4M17 15h4" />
    </svg>
  )
}

export function LayersIcon({ size = 18, ...p }: IconProps) {
  return (
    <svg {...base(size, p)}>
      <path d="m12 3 9 4.5-9 4.5-9-4.5L12 3Z" />
      <path d="m3 12 9 4.5 9-4.5" />
      <path d="m3 16.5 9 4.5 9-4.5" />
    </svg>
  )
}

export function CalendarIcon({ size = 18, ...p }: IconProps) {
  return (
    <svg {...base(size, p)}>
      <rect x="3" y="5" width="18" height="16" rx="2.5" />
      <path d="M16 3v4M8 3v4M3 10h18" />
    </svg>
  )
}

export function TagIcon({ size = 18, ...p }: IconProps) {
  return (
    <svg {...base(size, p)}>
      <path d="M20.6 13.4 13.4 20.6a2 2 0 0 1-2.8 0L3 13V4h9l8.6 8.6a2 2 0 0 1 0 2.8Z" />
      <circle cx="8" cy="8.5" r="1.2" fill="currentColor" stroke="none" />
    </svg>
  )
}

export function BookmarkIcon({ size = 16, ...p }: IconProps) {
  return (
    <svg {...base(size, p)}>
      <path d="M6.5 4h11A1.5 1.5 0 0 1 19 5.5v15l-7-4.2L5 20.5v-15A1.5 1.5 0 0 1 6.5 4Z" />
    </svg>
  )
}

export function UserIcon({ size = 16, ...p }: IconProps) {
  return (
    <svg {...base(size, p)}>
      <circle cx="12" cy="8.5" r="4" />
      <path d="M4.5 20a7.5 7.5 0 0 1 15 0" />
    </svg>
  )
}

export function GridIcon({ size = 15, ...p }: IconProps) {
  return (
    <svg {...base(size, p)}>
      <rect x="3.5" y="3.5" width="7" height="7" rx="1.5" />
      <rect x="13.5" y="3.5" width="7" height="7" rx="1.5" />
      <rect x="3.5" y="13.5" width="7" height="7" rx="1.5" />
      <rect x="13.5" y="13.5" width="7" height="7" rx="1.5" />
    </svg>
  )
}

export function ListIcon({ size = 15, ...p }: IconProps) {
  return (
    <svg {...base(size, p)}>
      <path d="M9 6h11M9 12h11M9 18h11" />
      <circle cx="4.5" cy="6" r="1" fill="currentColor" stroke="none" />
      <circle cx="4.5" cy="12" r="1" fill="currentColor" stroke="none" />
      <circle cx="4.5" cy="18" r="1" fill="currentColor" stroke="none" />
    </svg>
  )
}

export function SparklesIcon({ size = 15, ...p }: IconProps) {
  return (
    <svg {...base(size, p)}>
      <path d="m12 3 1.7 5.3L19 10l-5.3 1.7L12 17l-1.7-5.3L5 10l5.3-1.7L12 3Z" />
      <path d="M19 3v3m-1.5-1.5h3M5 17v3m-1.5-1.5h3" />
    </svg>
  )
}

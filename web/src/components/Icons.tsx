import type { SVGProps } from 'react'
import {
  BookOpen,
  Bookmark,
  Calendar,
  CheckCircle2,
  ChevronDown,
  Clock,
  Eye,
  EyeOff,
  Film,
  Heart,
  Info,
  Layers,
  LayoutGrid,
  List,
  LogOut,
  Menu,
  Moon,
  Play,
  Search,
  Settings,
  Sparkles,
  Star,
  Sun,
  Tag,
  Upload,
  User,
  X,
  Zap,
} from 'lucide-react'

type IconProps = SVGProps<SVGSVGElement> & { size?: number }

/** 全站图标统一由 lucide-react 驱动（1.85px 一致描边），保持原导出名兼容。 */
export function SearchIcon({ size = 15, ...p }: IconProps) {
  return <Search size={size} strokeWidth={1.9} aria-hidden="true" {...p} />
}

export function SunIcon({ size = 16, ...p }: IconProps) {
  return <Sun size={size} strokeWidth={1.85} aria-hidden="true" {...p} />
}

export function MoonIcon({ size = 16, ...p }: IconProps) {
  return <Moon size={size} strokeWidth={1.85} aria-hidden="true" {...p} />
}

export function MenuIcon({ size = 20, ...p }: IconProps) {
  return <Menu size={size} strokeWidth={1.9} aria-hidden="true" {...p} />
}

export function CloseIcon({ size = 18, ...p }: IconProps) {
  return <X size={size} strokeWidth={2} aria-hidden="true" {...p} />
}

export function SettingsIcon({ size = 15, ...p }: IconProps) {
  return <Settings size={size} strokeWidth={1.85} aria-hidden="true" {...p} />
}

export function LogOutIcon({ size = 15, ...p }: IconProps) {
  return <LogOut size={size} strokeWidth={1.85} aria-hidden="true" {...p} />
}

export function UploadIcon({ size = 15, ...p }: IconProps) {
  return <Upload size={size} strokeWidth={1.85} aria-hidden="true" {...p} />
}

export function PlayIcon({ size = 15, ...p }: IconProps) {
  return <Play size={size} strokeWidth={0} fill="currentColor" aria-hidden="true" {...p} />
}

export function HeartIcon({ size = 15, filled = false, ...p }: IconProps & { filled?: boolean }) {
  return <Heart size={size} strokeWidth={1.85} fill={filled ? 'currentColor' : 'none'} aria-hidden="true" {...p} />
}

export function InfoIcon({ size = 16, ...p }: IconProps) {
  return <Info size={size} strokeWidth={1.85} aria-hidden="true" {...p} />
}

export function EyeIcon({ size = 16, off = false, ...p }: IconProps & { off?: boolean }) {
  return off
    ? <EyeOff size={size} strokeWidth={1.85} aria-hidden="true" {...p} />
    : <Eye size={size} strokeWidth={1.85} aria-hidden="true" {...p} />
}

export function ChevronDownIcon({ size = 14, ...p }: IconProps) {
  return <ChevronDown size={size} strokeWidth={2} aria-hidden="true" {...p} />
}

export function BoltIcon({ size = 15, ...p }: IconProps) {
  return <Zap size={size} strokeWidth={1.85} aria-hidden="true" {...p} />
}

export function ClockIcon({ size = 15, ...p }: IconProps) {
  return <Clock size={size} strokeWidth={1.85} aria-hidden="true" {...p} />
}

export function BookOpenIcon({ size = 16, ...p }: IconProps) {
  return <BookOpen size={size} strokeWidth={1.85} aria-hidden="true" {...p} />
}

export function CheckCircleIcon({ size = 18, ...p }: IconProps) {
  return <CheckCircle2 size={size} strokeWidth={1.85} aria-hidden="true" {...p} />
}

export function StarIcon({ size = 16, filled = true, ...p }: IconProps & { filled?: boolean }) {
  return <Star size={size} strokeWidth={1.85} fill={filled ? 'currentColor' : 'none'} aria-hidden="true" {...p} />
}

export function FilmIcon({ size = 18, ...p }: IconProps) {
  return <Film size={size} strokeWidth={1.85} aria-hidden="true" {...p} />
}

export function LayersIcon({ size = 18, ...p }: IconProps) {
  return <Layers size={size} strokeWidth={1.85} aria-hidden="true" {...p} />
}

export function CalendarIcon({ size = 18, ...p }: IconProps) {
  return <Calendar size={size} strokeWidth={1.85} aria-hidden="true" {...p} />
}

export function TagIcon({ size = 18, ...p }: IconProps) {
  return <Tag size={size} strokeWidth={1.85} aria-hidden="true" {...p} />
}

export function BookmarkIcon({ size = 16, ...p }: IconProps) {
  return <Bookmark size={size} strokeWidth={1.85} aria-hidden="true" {...p} />
}

export function UserIcon({ size = 16, ...p }: IconProps) {
  return <User size={size} strokeWidth={1.85} aria-hidden="true" {...p} />
}

export function GridIcon({ size = 15, ...p }: IconProps) {
  return <LayoutGrid size={size} strokeWidth={1.85} aria-hidden="true" {...p} />
}

export function ListIcon({ size = 15, ...p }: IconProps) {
  return <List size={size} strokeWidth={1.9} aria-hidden="true" {...p} />
}

export function SparklesIcon({ size = 15, ...p }: IconProps) {
  return <Sparkles size={size} strokeWidth={1.85} aria-hidden="true" {...p} />
}

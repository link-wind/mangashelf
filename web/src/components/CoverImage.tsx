import { useEffect, useRef, useState } from 'react'
import { normalizeCoverUrl } from '../externalManga'

interface CoverImageProps {
  src: string | null | undefined
  alt: string
  fallbackText?: string
  className?: string
  loading?: 'lazy' | 'eager'
  fetchPriority?: 'high' | 'low' | 'auto'
}

/** 统一封面图：切换 src 自动重置状态，缓存命中瞬间显示，URL 缺失或失败时展示渐变首字兜底。 */
export default function CoverImage({
  src,
  alt,
  fallbackText,
  className = '',
  loading = 'lazy',
  fetchPriority,
}: CoverImageProps) {
  const resolvedSrc = normalizeCoverUrl(src)
  const [failed, setFailed] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const imgRef = useRef<HTMLImageElement>(null)
  const label = (fallbackText || alt || '?').trim().slice(0, 1) || '?'

  useEffect(() => {
    setFailed(false)
    setLoaded(false)
    const el = imgRef.current
    if (el && el.complete && el.naturalWidth > 0) {
      setLoaded(true)
    }
  }, [resolvedSrc])

  if (!resolvedSrc || failed) {
    return (
      <div className={`cover-fallback ${className}`.trim()} aria-hidden="true">
        <span className="cover-initial">{label}</span>
      </div>
    )
  }

  return (
    <img
      ref={imgRef}
      src={resolvedSrc}
      alt={alt}
      loading={loading}
      decoding="async"
      referrerPolicy="no-referrer"
      fetchPriority={fetchPriority}
      className={`cover-img ${loaded ? 'loaded' : ''} ${className}`.trim()}
      onLoad={() => setLoaded(true)}
      onError={() => setFailed(true)}
    />
  )
}

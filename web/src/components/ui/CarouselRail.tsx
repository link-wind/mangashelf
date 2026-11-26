import useEmblaCarousel from 'embla-carousel-react'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { useCallback, useEffect, useState, type ReactNode } from 'react'

interface CarouselRailProps {
  children: ReactNode
  itemWidth?: number
  gap?: number
  className?: string
  ariaLabel?: string
}

/**
 * 基于 Embla Carousel 的丝滑横滑卡片轨：
 * 彻底替代暴露浏览器原生水平滚动条的 .hscroll，带悬浮玻璃左右箭头与平滑渐隐边缘。
 */
export default function CarouselRail({
  children, itemWidth = 186, gap = 14, className = '', ariaLabel = '横向浏览',
}: CarouselRailProps) {
  const [emblaRef, emblaApi] = useEmblaCarousel({
    align: 'start',
    dragFree: true,
    containScroll: 'trimSnaps',
  })
  const [canPrev, setCanPrev] = useState(false)
  const [canNext, setCanNext] = useState(false)

  const syncState = useCallback(() => {
    if (!emblaApi) return
    setCanPrev(emblaApi.canScrollPrev())
    setCanNext(emblaApi.canScrollNext())
  }, [emblaApi])

  useEffect(() => {
    if (!emblaApi) return
    syncState()
    emblaApi.on('select', syncState)
    emblaApi.on('reInit', syncState)
    emblaApi.on('scroll', syncState)
    return () => {
      emblaApi.off('select', syncState)
      emblaApi.off('reInit', syncState)
      emblaApi.off('scroll', syncState)
    }
  }, [emblaApi, syncState])

  return (
    <div
      className={`ui-rail${canPrev ? ' has-prev' : ''}${canNext ? ' has-next' : ''} ${className}`.trim()}
      style={{ '--rail-item-w': `${itemWidth}px`, '--rail-gap': `${gap}px` } as React.CSSProperties}
      aria-label={ariaLabel}
    >
      <div className="ui-rail-viewport" ref={emblaRef}>
        <div className="ui-rail-track">{children}</div>
      </div>

      {canPrev && (
        <button
          type="button"
          className="ui-rail-btn is-prev"
          aria-label="向左滚动"
          onClick={() => emblaApi?.scrollPrev()}
        >
          <ChevronLeft size={18} />
        </button>
      )}
      {canNext && (
        <button
          type="button"
          className="ui-rail-btn is-next"
          aria-label="向右滚动"
          onClick={() => emblaApi?.scrollNext()}
        >
          <ChevronRight size={18} />
        </button>
      )}
    </div>
  )
}

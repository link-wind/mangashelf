import { useEffect, useRef, useState } from 'react'

interface Props {
  src: string
  ordinal: number
  width: number
  height: number
  active: boolean
  paged?: boolean
  fetchImage?: boolean
  onDimensions: (ordinal: number, width: number, height: number) => void
}

export default function ExternalMangaPage({
  src, ordinal, width, height, active, paged = false, fetchImage = false, onDimensions,
}: Props) {
  const container = useRef<HTMLDivElement>(null)
  const [nearby, setNearby] = useState(false)
  const [imageUrl, setImageUrl] = useState('')
  const [state, setState] = useState<'idle' | 'loading' | 'ready' | 'failed'>('idle')
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)
  const shouldLoad = active && (paged || nearby)

  useEffect(() => {
    if (state !== 'ready') return
    // 等 React 把 data-page-ready 写入 DOM 后再通知阅读器。
    window.dispatchEvent(new CustomEvent('rf-page-ready', { detail: { ordinal } }))
  }, [state, ordinal])

  useEffect(() => {
    if (!active || paged || !container.current) return
    const observer = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) {
        setNearby(true)
        observer.disconnect()
      }
    }, { rootMargin: '600px 0px' })
    observer.observe(container.current)
    return () => observer.disconnect()
  }, [active, paged])

  useEffect(() => {
    if (!shouldLoad) return
    const controller = new AbortController()
    let objectUrl = ''
    setState('loading')
    setError('')
    setImageUrl('')
    if (!fetchImage) {
      setImageUrl(src)
      return
    }
    void (async () => {
      try {
        const response = await fetch(src, {
          credentials: 'same-origin', cache: 'no-store', signal: controller.signal,
        })
        if (!response.ok) {
          const failure = await response.json().catch(() => ({}))
          throw new Error(failure.message || `页图请求失败（${response.status}）`)
        }
        const blob = await response.blob()
        if (controller.signal.aborted) return
        objectUrl = URL.createObjectURL(blob)
        setImageUrl(objectUrl)
      } catch (failure) {
        if (controller.signal.aborted) return
        setError(failure instanceof Error ? failure.message : '页图加载失败')
        setState('failed')
      }
    })()
    return () => {
      controller.abort()
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [shouldLoad, src, attempt, fetchImage])

  return (
    <div ref={container} className={'rf-image-page' + (paged ? ' rf-image-paged' : '')}
         data-reader-page={ordinal} data-page-ready={state === 'ready'}
         style={{ aspectRatio: `${width} / ${height}` }}
         aria-busy={state === 'loading'}>
      {imageUrl && (
        <img src={imageUrl} data-original={src} alt={`第 ${ordinal} 页`}
             width={width} height={height} decoding="async" referrerPolicy="no-referrer"
             onLoad={event => {
               const image = event.currentTarget
               onDimensions(ordinal, image.naturalWidth, image.naturalHeight)
               setState('ready')
             }}
             onError={() => {
               setState('failed')
               setError(fetchImage ? '页图未能解码，请重试' : '图片读取失败，请重试')
             }} />
      )}
      {state !== 'ready' && (
        <div className="rf-image-state">
          {state === 'failed' ? (
            <div className="rf-image-error" role="alert">
              <p>第 {ordinal} 页加载失败</p>
              <p>{error}</p>
              <button type="button" className="rf-text-btn"
                      onClick={event => {
                        event.stopPropagation()
                        setImageUrl('')
                        setAttempt(value => value + 1)
                      }}>
                重试第 {ordinal} 页
              </button>
            </div>
          ) : (
            <span role={state === 'loading' ? 'status' : undefined}>
              第 {ordinal} 页{state === 'loading' ? ' · 加载中…' : ''}
            </span>
          )}
        </div>
      )}
    </div>
  )
}

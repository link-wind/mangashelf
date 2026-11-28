import { useCallback, useEffect, useRef, useState } from 'react'

export interface UseReaderChromeOptions {
  /** 章节标识变化时重置为短暂显示并在延迟后自动收起 */
  resetKey?: string
  /** 抽屉（目录 / 设置）打开时锁定浮层常驻 */
  locked?: boolean
  /** 竖向滚动阅读模式下，用户滚动超过阈值自动收起浮层 */
  scrollAutoHide?: boolean
  /** 进章后自动收起延迟（毫秒），默认 1800ms */
  initialAutoHideMs?: number
}

export interface ReaderChrome {
  uiVisible: boolean
  showUi: boolean
  show: () => void
  hide: () => void
  toggle: () => void
  suppressScrollHide: (ms?: number) => void
}

/**
 * 漫画阅读器顶栏/底栏统一显隐状态机：
 * - 单一 React 状态驱动 `.show-ui`，彻底避免 DOM classList 与重渲染冲突；
 * - 进章短暂展示后自动收起进入沉浸阅读；
 * - 抽屉打开时通过 `locked` 强制常驻；
 * - 滚动模式下用户真实滑动超过阈值自动收起，程序化跳转滚动可通过 `suppressScrollHide` 跳过。
 */
export function useReaderChrome({
  resetKey = '',
  locked = false,
  scrollAutoHide = false,
  initialAutoHideMs = 1800,
}: UseReaderChromeOptions): ReaderChrome {
  const [uiVisible, setUiVisible] = useState(true)
  const autoHideTimerRef = useRef<number | null>(null)
  const suppressUntilRef = useRef(0)
  const lockedRef = useRef(locked)
  lockedRef.current = locked
  const visibleRef = useRef(uiVisible)
  visibleRef.current = uiVisible

  const clearAutoHide = useCallback(() => {
    if (autoHideTimerRef.current !== null) {
      window.clearTimeout(autoHideTimerRef.current)
      autoHideTimerRef.current = null
    }
  }, [])

  const suppressScrollHide = useCallback((ms = 360) => {
    suppressUntilRef.current = Math.max(suppressUntilRef.current, performance.now() + ms)
  }, [])

  const show = useCallback(() => {
    clearAutoHide()
    suppressScrollHide(320)
    setUiVisible(true)
  }, [clearAutoHide, suppressScrollHide])

  const hide = useCallback(() => {
    clearAutoHide()
    setUiVisible(false)
  }, [clearAutoHide])

  const toggle = useCallback(() => {
    clearAutoHide()
    setUiVisible(prev => {
      if (!prev) suppressScrollHide(320)
      return !prev
    })
  }, [clearAutoHide, suppressScrollHide])

  // 进章时短暂展示顶栏/底栏，随后自动收起
  useEffect(() => {
    clearAutoHide()
    setUiVisible(true)
    suppressScrollHide(450)
    if (initialAutoHideMs > 0) {
      autoHideTimerRef.current = window.setTimeout(() => {
        autoHideTimerRef.current = null
        if (!lockedRef.current) setUiVisible(false)
      }, initialAutoHideMs)
    }
    return clearAutoHide
  }, [resetKey, initialAutoHideMs, clearAutoHide, suppressScrollHide])

  // 抽屉打开期间取消挂起的进章自动收起
  useEffect(() => {
    if (locked) clearAutoHide()
  }, [locked, clearAutoHide])

  // 滚动模式下：用户滚动超过 24px 时自动收起浮层
  useEffect(() => {
    if (!scrollAutoHide) return
    let anchorY = window.scrollY
    let idleTimer: number | null = null

    const onScroll = () => {
      const y = window.scrollY
      const now = performance.now()
      if (now < suppressUntilRef.current || lockedRef.current) {
        anchorY = y
        return
      }
      if (!visibleRef.current) {
        anchorY = y
        return
      }
      if (Math.abs(y - anchorY) >= 24) {
        clearAutoHide()
        setUiVisible(false)
        anchorY = y
      }
      if (idleTimer !== null) window.clearTimeout(idleTimer)
      idleTimer = window.setTimeout(() => {
        anchorY = window.scrollY
      }, 240)
    }

    window.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      window.removeEventListener('scroll', onScroll)
      if (idleTimer !== null) window.clearTimeout(idleTimer)
    }
  }, [scrollAutoHide, clearAutoHide])

  return {
    uiVisible,
    showUi: uiVisible || locked,
    show,
    hide,
    toggle,
    suppressScrollHide,
  }
}

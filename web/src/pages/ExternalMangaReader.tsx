import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api, ApiError } from '../api'
import ExternalMangaPage from '../components/ExternalMangaPage'
import { loadPageSizes, savePageSizes, type PageSizes } from '../readerPageSizes'

interface Pages {
  chapterId?: string
  baseUrl?: string
  hash?: string
  pages: (string | null)[]
  dataSaver: string[]
  external: boolean
  dimensions?: { width: number; height: number }[]
}
interface Chapter {
  id: string; chapter: string; title: string; lang: string; pages: number; external: boolean
}
interface Detail { manga: { title: string; coverUrl: string | null }; chapters: Chapter[] }
interface ProgressRow { source: string; mangaId: string; chapterId: string | null; page: number; version: number }

type Dir = 'ltr' | 'rtl'
type PageMode = 'single' | 'double' | 'double-cover'
type Anim = 'fade' | 'none'

interface ReaderSettings {
  dir: Dir
  pageMode: PageMode
  anim: Anim
  hd: boolean
  autoNext: boolean
}

const SETTINGS_KEY = 'ms-manga-reader-settings'
const AUTO_NEXT_SECONDS = 3

function loadSettings(): ReaderSettings {
  try {
    const raw = localStorage.getItem(SETTINGS_KEY)
    if (raw) return { dir: 'ltr', pageMode: 'single', anim: 'fade', hd: false, autoNext: true, ...JSON.parse(raw) }
  } catch { /* 忽略损坏配置 */ }
  return { dir: 'ltr', pageMode: 'single', anim: 'fade', hd: false, autoNext: true }
}

/* ── 手势判定（阈值规则移植 HOMURA-reader readerGestures）── */
const TAP_MAX_DURATION = 300
const TAP_MAX_DISTANCE = 8

function tapZone(x: number, width: number): 'left' | 'right' | 'center' {
  const ratio = x / Math.max(1, width)
  return ratio < 0.3 ? 'left' : ratio >= 0.7 ? 'right' : 'center'
}

type Gesture =
  | { type: 'tap'; zone: 'left' | 'right' | 'center' }
  | { type: 'swipe'; zone: 'left' | 'right' }
  | { type: 'cancel'; zone: null }

function classifyGesture(args: { dx: number; dy: number; duration: number; width: number; x: number }): Gesture {
  const { dx, dy, duration, width, x } = args
  const absX = Math.abs(dx)
  const absY = Math.abs(dy)
  const distance = Math.max(absX, absY)
  if (distance < TAP_MAX_DISTANCE && duration < TAP_MAX_DURATION) {
    return { type: 'tap', zone: tapZone(x, width) }
  }
  const horizontal = absX > 24 && absX > absY * 1.2
  const threshold = Math.max(48, width * 0.14)
  if (horizontal && (absX > threshold || (absX / Math.max(1, duration) > 0.45 && absX > 24))) {
    return { type: 'swipe', zone: dx > 0 ? 'left' : 'right' }
  }
  return { type: 'cancel', zone: null }
}

/** 双页 / 首页单排展开为页组（0 基下标）。double-cover 时第 1 页（封面）单排。 */
function buildSpreads(total: number, mode: 'single' | 'double' | 'double-cover'): number[][] {
  if (mode === 'single') return Array.from({ length: total }, (_, i) => [i])
  const spreads: number[][] = []
  let i = 0
  if (mode === 'double-cover' && total > 0) {
    spreads.push([0])
    i = 1
  }
  while (i < total) {
    spreads.push([i, i + 1].filter(x => x < total))
    i += 2
  }
  return spreads
}

/**
 * 外部漫画阅读器（M22，参照哔哩哔哩漫画与 HOMURA-reader）：
 * 阅读方向（日漫 RTL）、单双页（首页单排）、点击分区与横滑翻页、
 * 翻页动画、Esc 逐层退出、服务端进度续读。
 */
export default function ExternalMangaReader() {
  const { chapterId } = useParams()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const mangaId = params.get('mangaId') ?? ''
  const lang = params.get('lang') ?? 'zh'
  const title = params.get('title') ?? '外部漫画'
  const source = params.get('source') ?? 'copymanga'
  const sizeKey = `${source}:${chapterId ?? ''}`

  const [settings, setSettings] = useState<ReaderSettings>(loadSettings)
  const [resolved, setResolved] = useState<{ key: string; pages: Pages } | null>(null)
  const data = resolved?.key === sizeKey ? resolved.pages : null
  const [detail, setDetail] = useState<Detail | null>(null)
  const [error, setError] = useState('')
  const [status, setStatus] = useState<ResolveStatus | null>(null)
  const [uiVisible, setUiVisible] = useState(true)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [spread, setSpread] = useState(0)
  const [page, setPage] = useState(1)
  const [restoredData, setRestoredData] = useState<Pages | null>(null)
  const [resolving, setResolving] = useState(false)
  // 长条漫检测（M30）：首图高/宽 ≥3 视为竖向长条，强制滚动模式；用户手动切换可覆盖
  const [longStrip, setLongStrip] = useState(false)
  const [manualMode, setManualMode] = useState<PageMode | null>(null)
  const [measured, setMeasured] = useState<{ key: string; sizes: PageSizes }>(
    () => ({ key: sizeKey, sizes: loadPageSizes(sizeKey) }))
  const pageSizes = measured.key === sizeKey ? measured.sizes : {}

  const stageRef = useRef<HTMLDivElement>(null)
  const gestureRef = useRef<{ x: number; y: number; t: number } | null>(null)
  const lastSaveRef = useRef(0)
  const lastSavedPageRef = useRef(0)
  // 进度版本（unify-progress-conflict-and-search）：单飞行写入 + 最新位置合并。
  const versionRef = useRef(0)
  const pendingSaveRef = useRef<{ page: number; total: number } | null>(null)
  const savingRef = useRef(false)
  const lastConflictAtRef = useRef(0)
  const [progressNotice, setProgressNotice] = useState('')
  const [tocOpen, setTocOpen] = useState(false)
  const [tocAsc, setTocAsc] = useState(true)
  const [autoNextCountdown, setAutoNextCountdown] = useState<number | null>(null)
  const [autoNextCancelled, setAutoNextCancelled] = useState(false)
  const [endInView, setEndInView] = useState(false)
  const pendingScroll = useRef<number | null>(null)
  const layoutAnchor = useRef<{ ordinal: number; top: number } | null>(null)
  const endSentinelRef = useRef<HTMLDivElement>(null)
  const chapterEnteredAtRef = useRef(Date.now())
  const prefetchedNextRef = useRef<string | null>(null)
  const pageRef = useRef(1)
  pageRef.current = page
  const spreadRef = useRef(0)
  spreadRef.current = spread

  function patchSettings(patch: Partial<ReaderSettings>) {
    setSettings(prev => {
      const next = { ...prev, ...patch }
      localStorage.setItem(SETTINGS_KEY, JSON.stringify(next))
      return next
    })
  }

  // 长条漫 → 滚动模式；手动选择优先
  const effectiveMode: PageMode = manualMode ?? (longStrip ? 'single' : settings.pageMode)

  // 沉浸模式：隐藏站点导航与页脚
  useEffect(() => {
    document.body.classList.add('reader-open')
    return () => document.body.classList.remove('reader-open')
  }, [])

  const startChapter = useCallback(async (id: string, sourceId: string, signal: { cancelled: boolean }) => {
    return api.get<Pages>(`/api/v1/manga-sources/${sourceId}/chapter/${id}/pages`).then(pages => {
      if (!signal.cancelled) setResolved({ key: `${sourceId}:${id}`, pages })
    }).catch(e => {
      if (!signal.cancelled) setError(e instanceof Error ? e.message : '章节加载失败')
    })
  }, [])

  useEffect(() => {
    const signal = { cancelled: false }
    if (!chapterId) return
    setError('')
    setResolved(null)
    setRestoredData(null)
    setSpread(0)
    setPage(1)
    setAutoNextCountdown(null)
    setAutoNextCancelled(false)
    setEndInView(false)
    chapterEnteredAtRef.current = Date.now()
    window.scrollTo({ top: 0 })
    layoutAnchor.current = null
    pendingScroll.current = null
    setMeasured({ key: `${source}:${chapterId}`, sizes: loadPageSizes(`${source}:${chapterId}`) })
    void (async () => {
      await Promise.resolve()
      if (signal.cancelled) return
      await startChapter(chapterId, source, signal)
    })()
    return () => { signal.cancelled = true }
  }, [chapterId, source, startChapter])

  const isRestored = restoredData === data

  useEffect(() => {
    setManualMode(null)
    const sizes = data?.dimensions?.length ? data.dimensions : Object.values(loadPageSizes(sizeKey))
    setLongStrip(sizes.some(size => size.height / Math.max(1, size.width) >= 3))
  }, [data, sizeKey])

  useEffect(() => {
    if (measured.key === sizeKey) savePageSizes(sizeKey, measured.sizes)
  }, [measured, sizeKey])

  useEffect(() => {
    let cancelled = false
    setDetail(null)
    if (!mangaId) return
    void (async () => {
      try {
        const raw = await api.get<{ item: { title: string; coverUrl: string | null };
          chapters: { id: string; label: string; title: string; lang: string;
                      pages: number; external: boolean }[] }>(
          `/api/v1/manga-sources/${source}/manga/${mangaId}?lang=${lang}`)
        if (!cancelled) setDetail({
          manga: { title: raw.item.title, coverUrl: raw.item.coverUrl ?? null },
          chapters: (raw.chapters ?? []).map(c => ({
            id: c.id, chapter: c.label, title: c.title, lang: c.lang ?? 'zh',
            pages: c.pages, external: c.external,
          })),
        })
      } catch { /* 章节导航不可用不影响阅读 */ }
    })()
    return () => { cancelled = true }
  }, [mangaId, lang, source])

  const chapter = detail?.chapters.find(c => c.id === chapterId) ?? null
  const readable = (detail?.chapters ?? []).filter(c => !c.external && c.pages !== 0)
  const chapterIdx = readable.findIndex(c => c.id === chapterId)
  const prevCh = chapterIdx > 0 ? readable[chapterIdx - 1] : null
  const nextCh = chapterIdx >= 0 && chapterIdx < readable.length - 1 ? readable[chapterIdx + 1] : null
  const comicTitle = detail?.manga.title || title || '漫画'
  const comicCoverUrl = detail?.manga.coverUrl || undefined
  const currentChapterLabel = chapter
    ? `第 ${chapter.chapter || '?'} 话${chapter.title ? ' ' + chapter.title : ''}`.trim()
    : undefined

  useEffect(() => {
    document.title = currentChapterLabel
      ? `${comicTitle} · ${currentChapterLabel} · MangaShelf`
      : `${comicTitle} · MangaShelf`
  }, [comicTitle, currentChapterLabel])

  const notifyConflict = useCallback(() => {
    const now = Date.now()
    if (now - lastConflictAtRef.current < 30_000) return
    lastConflictAtRef.current = now
    setProgressNotice('其他设备已更新进度，已对齐版本继续保存')
    window.setTimeout(() => setProgressNotice(''), 4000)
  }, [])

  const sendProgress = useCallback(async (next: { page: number; total: number }) => {
    if (!mangaId) return
    try {
      const res = await api.put<{ version: number }>('/api/v1/me/manga-progress', {
        source, mangaId, chapterId, chapterLabel: currentChapterLabel,
        page: next.page, totalPages: next.total,
        title: comicTitle, coverUrl: comicCoverUrl, expectedVersion: versionRef.current,
      })
      versionRef.current = res.version
    } catch (e) {
      if (e instanceof ApiError && e.code === 'PROGRESS_CONFLICT') {
        const current = (e.details?.current ?? {}) as { version?: number }
        if (typeof current.version === 'number') versionRef.current = current.version
        notifyConflict()
      }
      // 其余错误不打断阅读；下一位置继续尝试。
    }
  }, [mangaId, chapterId, currentChapterLabel, comicTitle, comicCoverUrl, source, notifyConflict])

  const saveProgress = useCallback((p: number, total: number) => {
    pendingSaveRef.current = { page: p, total }
    if (savingRef.current) return
    savingRef.current = true
    void (async () => {
      try {
        while (pendingSaveRef.current) {
          const next = pendingSaveRef.current
          pendingSaveRef.current = null
          await sendProgress(next)
        }
      } finally { savingRef.current = false }
    })()
  }, [sendProgress])

  const resolve = useCallback(async (id: string) => {
    setResolving(true)
    try {
      let st = await api.get<ResolveStatus>(`/api/v1/external-episodes/${id}/resolve`)
      if (!st.playUrl) {
        await api.post(`/api/v1/external-episodes/${id}/resolve`, {})
        const started = Date.now()
        for (;;) {
          if (Date.now() - started > RESOLVE_MAX_MS) {
            st = { jobId: null, jobState: 'FAILED', playUrl: null, playKind: null,
                   errorCode: 'RESOLVE_TIMEOUT', errorMessage: '解析超时，请稍后重试' }
            break
          }
          st = await api.get<ResolveStatus>(`/api/v1/external-episodes/${id}/resolve`)
          if (st.playUrl || st.jobState === 'FAILED' || st.errorCode) break
          await new Promise(r => setTimeout(r, RESOLVE_POLL_MS))
        }
      }
      setStatus(st)
    } catch (e) {
      setStatus({ jobId: null, jobState: 'FAILED', playUrl: null, playKind: null,
                  errorCode: 'RESOLVE_ERROR',
                  errorMessage: e instanceof ApiError ? e.message : '解析请求失败' })
    } finally { setResolving(false) }
  }, [])

  // 滚动模式：窗口滚动换算页码（图片列表缓存 + 100ms 节流，避免长章节每帧遍历所有图）
  useEffect(() => {
    if (!data || effectiveMode !== 'single' || !isRestored) return
    let imgs: HTMLElement[] = []
    const refresh = () => {
      imgs = Array.from(stageRef.current?.querySelectorAll<HTMLElement>('[data-reader-page]') ?? [])
    }
    refresh()
    let lastMeasure = 0
    const measure = () => {
      const mid = window.scrollY + window.innerHeight / 2
      let i = 0
      for (let k = 0; k < imgs.length; k++) {
        if (imgs[k].getBoundingClientRect().top + window.scrollY < mid) i = k
      }
      const p = i + 1
      if (p !== pageRef.current) {
        setPage(p)
        localStorage.setItem(`ms-ext-manga:${chapterId}`, String(p))
      }
      // 页码可能先于图片加载完成变化；图片就绪后会再次触发 scroll，
      // 因此这里按“当前可见页已就绪”保存，而不是只在页码变化时保存。
      if (imgs[i]?.dataset.pageReady === 'true'
          && (p !== lastSavedPageRef.current || Date.now() - lastSaveRef.current >= 3000)) {
        lastSaveRef.current = Date.now()
        lastSavedPageRef.current = p
        saveProgress(p, imgs.length)
      }
    }
    const onScroll = () => {
      const now = performance.now()
      if (now - lastMeasure < 100) return
      lastMeasure = now
      measure()
    }
    window.addEventListener('scroll', onScroll, { passive: true })
    // 图片就绪事件不受滚动节流影响：跳到末页时页码可能先变化，图片随后才加载完成。
    window.addEventListener('rf-page-ready', measure)
    return () => {
      window.removeEventListener('scroll', onScroll)
      window.removeEventListener('rf-page-ready', measure)
      saveProgress(pageRef.current, imgs.length)
    }
  }, [data, isRestored, effectiveMode, chapterId, saveProgress, source])

  // 打开/恢复到章节后先记录当前位置；单页或尚未滚动时也能保存续读点。
  useEffect(() => {
    if (!data || !isRestored) return
    saveProgress(pageRef.current, data.pages.length)
  }, [data, isRestored, chapterId, saveProgress, source])

  // 续读位置（双页模式换算到所在 spread）
  useEffect(() => {
    if (!chapterId || !data || isRestored) return
    let cancelled = false
    const resume = (rows: ProgressRow[]) => {
      if (cancelled) return
      const row = rows.find(r => r.source === source && r.mangaId === mangaId && r.chapterId === chapterId)
      versionRef.current = row?.version ?? 0
      const stored = Number(localStorage.getItem(`ms-ext-manga:${chapterId}`) ?? '1')
      const p = Math.max(1, Math.min(data.pages.length, row?.page ?? (stored || 1)))
      setPage(p)
      const base = effectiveMode === 'double-cover' ? 1 : 0
      setSpread(Math.max(0, Math.floor((p - 1 - base) / 2) + base))
      if (effectiveMode === 'single' && p > 1) {
        stageRef.current?.querySelectorAll('[data-reader-page]')[p - 1]?.scrollIntoView()
      }
      setRestoredData(data)
    }
    void api.get<ProgressRow[]>('/api/v1/me/manga-progress?limit=50')
      .then(resume)
      .catch(() => resume([]))
    return () => { cancelled = true }
  }, [mangaId, chapterId, data, effectiveMode, source, isRestored])

  const backTo = source === 'bilibili' && !detail ? '/history' : `/manga-ext/${mangaId}?lang=${lang}&source=${source}`

  // Esc 逐层退出：设置 → 浮层 → 返回章节列表；F 全屏
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return
      if (e.key === 'Escape') {
        if (settingsOpen) { setSettingsOpen(false); setUiVisible(true) }
        else if (tocOpen) { setTocOpen(false); setUiVisible(true) }
        else if (!uiVisible) setUiVisible(true)
        else navigate(backTo)
      }
      if (e.key === 'f' || e.key === 'F') {
        const el = document.querySelector('.reader-full')
        if (!document.fullscreenElement) void el?.requestFullscreen().catch(() => {})
        else void document.exitFullscreen().catch(() => {})
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [settingsOpen, tocOpen, uiVisible, navigate, backTo])


  const list: (string | null)[] = data
    ? (settings.hd ? data.pages : (data.dataSaver.length ? data.dataSaver : data.pages))
    : []
  const total = list.length


  const spreads = buildSpreads(total, effectiveMode)
  const spreadCount = spreads.length
  const effSpread = Math.min(spread, Math.max(0, spreadCount - 1))
  const spreadPages = spreads[effSpread] ?? []
  // RTL 时组内页序反转（右页在前）
  const displayPages = settings.dir === 'rtl' ? [...spreadPages].reverse() : spreadPages

  useLayoutEffect(() => {
    if (effectiveMode !== 'single') return
    const anchor = layoutAnchor.current
    if (anchor) {
      const target = stageRef.current?.querySelector(`[data-reader-page="${anchor.ordinal}"]`)
      if (target) window.scrollBy(0, target.getBoundingClientRect().top - anchor.top)
      layoutAnchor.current = null
    }
    if (pendingScroll.current !== null) {
      stageRef.current?.querySelectorAll('[data-reader-page]')[pendingScroll.current - 1]?.scrollIntoView()
      pendingScroll.current = null
    }
  }, [effectiveMode, measured, longStrip])

  function rememberDimensions(ordinal: number, width: number, height: number) {
    if (width < 1 || height < 1) return
    const previous = pageSizes[ordinal]
    if (previous?.width === width && previous.height === height) return
    if (effectiveMode === 'single' && !layoutAnchor.current) {
      const target = stageRef.current?.querySelector(`[data-reader-page="${pageRef.current}"]`)
      if (target) layoutAnchor.current = { ordinal: pageRef.current, top: target.getBoundingClientRect().top }
    }
    setMeasured(current => ({
      key: sizeKey,
      sizes: { ...(current.key === sizeKey ? current.sizes : {}), [ordinal]: { width, height } },
    }))
    if (height / width >= 3 && !longStrip) {
      if (effectiveMode !== 'single' && manualMode === null) {
        const current = spreadPages.includes(pageRef.current - 1) ? pageRef.current : (spreadPages[0] ?? 0) + 1
        pendingScroll.current = current
        setPage(current)
      }
      setLongStrip(true)
    }
  }

  function pageSize(index: number) {
    return data?.dimensions?.[index] ?? pageSizes[index + 1]
      ?? { width: 800, height: longStrip ? 2400 : 1200 }
  }

  function changePageMode(next: PageMode) {
    const current = effectiveMode === 'single' || spreadPages.includes(page - 1)
      ? page : (spreadPages[0] ?? 0) + 1
    setPage(current)
    setSpread(Math.max(0, buildSpreads(total, next).findIndex(group => group.includes(current - 1))))
    if (next === 'single') pendingScroll.current = current
    setManualMode(next)
    patchSettings({ pageMode: next })
  }

  useEffect(() => {
    if (!isRestored || effectiveMode === 'single') return
    const next = spreads[effSpread + 1] ?? []
    const probes = next.map(index => {
      const image = new Image()
      image.src = list[index] ?? ''
      return image
    })
    return () => { probes.forEach(image => { image.onerror = null; image.src = '' }) }
  }, [source, isRestored, effectiveMode, effSpread, data])

  const jumpChapter = useCallback((id: string, autoNotice?: string) => {
    setAutoNextCountdown(null)
    setEndInView(false)
    if (autoNotice) {
      setProgressNotice(autoNotice)
      window.setTimeout(() => setProgressNotice(cur => (cur === autoNotice ? '' : cur)), 2500)
    }
    navigate(`/manga-ext/read/${id}?mangaId=${mangaId}&lang=${lang}&source=${source}` +
             `&title=${encodeURIComponent(comicTitle)}`)
  }, [navigate, mangaId, lang, source, comicTitle])

  const formatChapterLabel = useCallback((c: Chapter) => {
    const base = `第 ${c.chapter || '?'} 话`
    return c.title ? `${base} ${c.title}` : base
  }, [])

  const triggerAutoNextNow = useCallback(() => {
    if (!nextCh) return
    jumpChapter(nextCh.id, `已自动切换至 ${formatChapterLabel(nextCh)}`)
  }, [nextCh, jumpChapter, formatChapterLabel])

  // 临近末页时后台预热下一话页列表，切话更顺滑（每话最多一次）
  useEffect(() => {
    if (!isRestored || !settings.autoNext || !nextCh || total <= 0) return
    const currentPos = effectiveMode === 'single' ? page : ((spreadPages[0] ?? 0) + 1)
    if (currentPos < Math.max(1, total - 1)) return
    const key = `${source}:${nextCh.id}`
    if (prefetchedNextRef.current === key) return
    prefetchedNextRef.current = key
    void api.get<Pages>(`/api/v1/manga-sources/${source}/chapter/${nextCh.id}/pages`).catch(() => {})
  }, [isRestored, settings.autoNext, nextCh, total, effectiveMode, page, spreadPages, source])

  // 滚动模式：监听章末卡片进入视口，触发可取消的 3 秒自动下一话倒计时
  useEffect(() => {
    if (!isRestored || effectiveMode !== 'single' || !nextCh || !settings.autoNext) {
      setEndInView(false)
      return
    }
    const el = endSentinelRef.current
    if (!el) return
    const obs = new IntersectionObserver(entries => {
      const visible = entries.some(entry => entry.isIntersecting)
      setEndInView(visible)
    }, { threshold: 0.45 })
    obs.observe(el)
    return () => obs.disconnect()
  }, [isRestored, effectiveMode, nextCh, settings.autoNext, chapterId, total])

  // 仅当真正滚动到章末（且非刚进章节 1.2 秒内的初始闪现、非打开抽屉时）才启动倒计时
  useEffect(() => {
    const shouldRun = effectiveMode === 'single'
      && isRestored
      && settings.autoNext
      && Boolean(nextCh)
      && !autoNextCancelled
      && !tocOpen
      && !settingsOpen
      && endInView
      && total > 0
      && page >= Math.max(1, total - 1)

    if (!shouldRun) {
      setAutoNextCountdown(null)
      return
    }
    const elapsed = Date.now() - chapterEnteredAtRef.current
    const startDelay = Math.max(0, 1200 - elapsed)
    let tickTimer: number | null = null
    const armTimer = window.setTimeout(() => {
      setAutoNextCountdown(AUTO_NEXT_SECONDS)
      let left = AUTO_NEXT_SECONDS
      tickTimer = window.setInterval(() => {
        left -= 1
        if (left <= 0) {
          if (tickTimer !== null) window.clearInterval(tickTimer)
          setAutoNextCountdown(null)
          triggerAutoNextNow()
        } else {
          setAutoNextCountdown(left)
        }
      }, 1000)
    }, startDelay)

    return () => {
      window.clearTimeout(armTimer)
      if (tickTimer !== null) window.clearInterval(tickTimer)
    }
  }, [
    effectiveMode, isRestored, settings.autoNext, nextCh,
    autoNextCancelled, tocOpen, settingsOpen, endInView, total, page, triggerAutoNextNow,
  ])

  function jumpSpread(index: number) {
    if (!isRestored) return
    if (index >= spreadCount) {
      if (settings.autoNext && nextCh) triggerAutoNextNow()
      return
    }
    const next = Math.max(0, Math.min(spreadCount - 1, index))
    if (next === effSpread || spreads[next]?.some(i => !list[i])) return
    setSpread(next)
    setPage((spreads[next]?.[0] ?? 0) + 1)
    saveProgress((spreads[next]?.[0] ?? 0) + 1, total)
  }

  function jumpPage(index: number) {
    if (!isRestored) return
    if (index > total) {
      if (settings.autoNext && nextCh) triggerAutoNextNow()
      return
    }
    const next = Math.max(1, Math.min(total, index))
    if (next === page || !list[next - 1]) return
    stageRef.current?.querySelectorAll('[data-reader-page]')[next - 1]?.scrollIntoView({ block: 'center' })
    setPage(next)
    saveProgress(next, total)
  }

  function flip(delta: number) {
    if (effectiveMode !== 'single') {
      if (delta > 0 && effSpread + delta >= spreadCount) {
        if (settings.autoNext && nextCh) triggerAutoNextNow()
        return
      }
      if (spreads[effSpread + delta]?.some(i => !list[i])) return
      jumpSpread(effSpread + delta)
      return
    }
    if (delta > 0 && page + delta > total) {
      if (settings.autoNext && nextCh) triggerAutoNextNow()
      return
    }
    if (!list[Math.max(0, Math.min(total - 1, page - 1 + delta))]) return
    jumpPage(page + delta)
  }

  function tapAction(zone: 'left' | 'right' | 'center') {
    if (zone === 'center') { setUiVisible(v => !v); return }
    // LTR：右 = 前进；RTL：左 = 前进
    flip(settings.dir === 'rtl' ? (zone === 'left' ? 1 : -1) : (zone === 'right' ? 1 : -1))
  }

  // 指针手势（tap / swipe 判定移植 HOMURA 阈值）
  function onPointerDown(e: React.PointerEvent) {
    gestureRef.current = { x: e.clientX, y: e.clientY, t: Date.now() }
  }
  function onPointerUp(e: React.PointerEvent) {
    const g = gestureRef.current
    gestureRef.current = null
    if (!g) return
    const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
    const result = classifyGesture({
      dx: e.clientX - g.x, dy: e.clientY - g.y, duration: Date.now() - g.t,
      width: rect.width, x: e.clientX - rect.left,
    })
    if (result.type === 'tap') {
      if (result.zone === 'center') { setUiVisible(v => !v); return }
      tapAction(result.zone)
      return
    }
    if (result.type === 'swipe' && (result.zone === 'left' || result.zone === 'right')) {
      const z = result.zone
      // 向左滑 = 前进（LTR）；RTL 反转
      flip(settings.dir === 'rtl' ? (z === 'right' ? 1 : -1) : (z === 'left' ? 1 : -1))
    }
  }

  // 翻页动画：key 变化触发重挂载播放淡入
  const animKey = `${effSpread}-${page}-${settings.anim}`

  // 键盘与滚轮导航（mobile-ui-index-and-reader）：↑↓ 逐页（滚动/单页），←→ 与滚轮翻组（双页）。
  // 方向语义遵循阅读方向设置（RTL 相反）；Esc/F 不受影响。
  // 闭包经 navRef 取最新值——effect 只注册一次，避免过期状态（page/isRestored）卡死翻页。
  const navRef = useRef({
    jumpPage, jumpSpread, effSpread, total,
    dir: settings.dir, mode: effectiveMode, open: settingsOpen || tocOpen,
  })
  navRef.current = {
    jumpPage, jumpSpread, effSpread, total,
    dir: settings.dir, mode: effectiveMode, open: settingsOpen || tocOpen,
  }

  useEffect(() => {
    // delta>0 = 阅读方向上的「前进」；RTL 在此处统一反转，调用方传原始语义。
    const flip = (delta: number) => {
      const nav = navRef.current
      const step = nav.dir === 'rtl' ? -delta : delta
      if (nav.mode === 'single') nav.jumpPage(pageRef.current + step)
      else nav.jumpSpread(nav.effSpread + step)
    }
    const onNavKey = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return
      const nav = navRef.current
      if (nav.open) return
      if (e.key === 'ArrowDown') { e.preventDefault(); nav.jumpPage(pageRef.current + 1) }
      if (e.key === 'ArrowUp') { e.preventDefault(); nav.jumpPage(pageRef.current - 1) }
      if (e.key === 'ArrowRight') { e.preventDefault(); flip(1) }
      if (e.key === 'ArrowLeft') { e.preventDefault(); flip(-1) }
    }
    let lastWheel = 0
    const onWheel = (e: WheelEvent) => {
      const nav = navRef.current
      if (nav.mode === 'single' || nav.open) return
      e.preventDefault()
      const now = Date.now()
      if (now - lastWheel < 150) return
      lastWheel = now
      flip(e.deltaY > 0 ? 1 : -1)
    }
    window.addEventListener('keydown', onNavKey)
    window.addEventListener('wheel', onWheel, { passive: false })
    return () => {
      window.removeEventListener('keydown', onNavKey)
      window.removeEventListener('wheel', onWheel)
    }
  }, [])

  // jumpChapter 已上移至自动下一话回调区域

  if (error) {
    return (
      <div className="reader-full">
        <div className="reader-full-inner">
          <p className="error" role="alert">{error}</p>
          <Link className="btn ghost" to={backTo}>返回</Link>
        </div>
      </div>
    )
  }
  if (!data || total === 0) {
    const slowSource = source === 'baozimh' || source === 'hipmh' || source === 'copymanga'
    return (
      <div className="reader-full">
        <div className="reader-full-inner muted">
          {source === 'bilibili' ? '正在确认哔哩章节页数…'
            : slowSource ? '章节加载中，请稍候…' : '加载中…'}
        </div>
      </div>
    )
  }

  const backExternal = data.external
  if (backExternal) {
    return (
      <div className="reader-full">
        <div className="reader-full-inner">
          <p className="muted">该章节由 MangaDex 外链到原站，无法在此阅读。</p>
          <Link className="btn ghost" to={backTo}>返回章节列表</Link>
        </div>
      </div>
    )
  }

  const singleMode = effectiveMode === 'single'
  const displayList = singleMode
    ? [Math.max(0, Math.min(total - 1, page - 1))]
    : displayPages
  const progressNo = singleMode ? page : ((spreadPages[0] ?? 0) + 1)

  return (
    <div className={'reader-full' + (uiVisible ? ' show-ui' : '')} onClick={pokeUi}>
      {progressNotice && (
        <div role="status" style={{
          position: 'fixed', top: 78, left: '50%', transform: 'translateX(-50%)',
          background: 'rgba(20,22,28,.92)', color: '#fff', padding: '7px 16px',
          borderRadius: 999, fontSize: 13, zIndex: 130, whiteSpace: 'nowrap',
          border: '1px solid rgba(255,255,255,.16)', boxShadow: '0 8px 24px rgba(0,0,0,.4)',
        }}>{progressNotice}</div>
      )}
      <header className="rf-top">
        <Link to={backTo} className="rf-icon" aria-label="返回">←</Link>
        <div className="rf-title">
          <span className="rf-comic">{comicTitle}</span>
          <span className="rf-chapter">
            {chapter ? `第 ${chapter.chapter || '?'} 话` : ''} {chapter?.title ?? ''}
          </span>
          {longStrip && <span className="rf-strip-hint">长条漫 · 滚动</span>}
        </div>
        <button className="rf-icon" aria-label="目录"
                onClick={e => { e.stopPropagation(); setTocOpen(v => !v); pokeUi() }}>☰</button>
        <button className="rf-icon" aria-label="设置"
                onClick={e => { e.stopPropagation(); setSettingsOpen(v => !v); pokeUi() }}>⚙</button>
      </header>

      {singleMode ? (
        <div ref={stageRef} className="rf-stage">
          {list.map((src, i) => src ? (
            <ExternalMangaPage key={`${source}-${chapterId}-${i}`} src={src} ordinal={i + 1}
                               {...pageSize(i)} active={isRestored}
                               fetchImage={source === 'bilibili'} onDimensions={rememberDimensions} />
          ) : (
            <div key={i} className="rf-page-placeholder" aria-label={`第 ${i + 1} 页加载中`}>
              <span>第 {i + 1} 页 · 加载中…</span>
            </div>
          ))}

          <div ref={endSentinelRef} className="rf-chapter-end" onClick={e => e.stopPropagation()}>
            <span className="rf-chapter-end-meta">
              {chapter ? `本话已读完 · ${formatChapterLabel(chapter)}` : '本话已读完'}
            </span>
            {nextCh ? (
              <>
                <div className="rf-chapter-end-title">
                  {autoNextCountdown !== null
                    ? `${autoNextCountdown} 秒后自动进入下一话：${formatChapterLabel(nextCh)}`
                    : `下一话：${formatChapterLabel(nextCh)}`}
                </div>
                {autoNextCountdown !== null && (
                  <div className="rf-chapter-end-bar" aria-hidden="true">
                    <div className="rf-chapter-end-bar-fill"
                         style={{ width: `${((AUTO_NEXT_SECONDS - autoNextCountdown + 1) / AUTO_NEXT_SECONDS) * 100}%` }} />
                  </div>
                )}
                <div className="rf-chapter-end-actions">
                  <button type="button" className="btn pill" onClick={() => jumpChapter(nextCh.id)}>
                    立即下一话
                  </button>
                  {autoNextCountdown !== null ? (
                    <button type="button" className="rf-text-btn"
                            onClick={() => { setAutoNextCancelled(true); setAutoNextCountdown(null) }}>
                      取消自动连播
                    </button>
                  ) : (
                    <button type="button" className="rf-text-btn"
                            onClick={() => {
                              patchSettings({ autoNext: !settings.autoNext })
                              setAutoNextCancelled(false)
                            }}>
                      自动下一话：{settings.autoNext ? '已开启' : '已关闭'}
                    </button>
                  )}
                </div>
              </>
            ) : (
              <>
                <div className="rf-chapter-end-title">已是最新一话</div>
                <div className="rf-chapter-end-actions">
                  <Link className="rf-text-btn" to={backTo}>返回章节列表</Link>
                </div>
              </>
            )}
          </div>
        </div>
      ) : (
        <div className={'rf-paged' + (settings.anim === 'fade' ? ' rf-fade' : '')}
             key={animKey}
             onPointerDown={onPointerDown} onPointerUp={onPointerUp}>
          {displayPages.map(pageNo => list[pageNo] ? (
            <ExternalMangaPage key={`${source}-${chapterId}-${pageNo}`} src={list[pageNo] as string}
                               ordinal={pageNo + 1} {...pageSize(pageNo)} active={isRestored} paged
                               fetchImage={source === 'bilibili'} onDimensions={rememberDimensions} />
          ) : (
            <div key={pageNo} className="rf-page-placeholder" aria-label={`第 ${pageNo + 1} 页加载中`}>
              <span>第 {pageNo + 1} 页 · 加载中…</span>
            </div>
          ))}
        </div>
      )}

      {!singleMode && effSpread >= spreadCount - 1 && nextCh && !tocOpen && !settingsOpen && (
        <div className="rf-auto-pill" onClick={e => e.stopPropagation()}>
          <span>
            {settings.autoNext
              ? `已到末页 · 继续翻页自动进入 ${formatChapterLabel(nextCh)}`
              : `已到末页 · ${formatChapterLabel(nextCh)}`}
          </span>
          <button type="button" className="rf-auto-pill-btn" onClick={() => jumpChapter(nextCh.id)}>
            下一话
          </button>
        </div>
      )}

      {tocOpen && detail && (
        <div className="rf-sheet rf-toc" onClick={e => e.stopPropagation()}>
          <div className="rf-sheet-row">
            <span>目录（{detail.chapters.length} 话）</span>
            <button className="mini ghost" onClick={() => setTocAsc(v => !v)}>
              {tocAsc ? '正序' : '倒序'}
            </button>
          </div>
          <div className="rf-toc-list">
            {(tocAsc ? detail.chapters : [...detail.chapters].reverse()).map(c => (
              <button key={c.id}
                      className={'rf-toc-item' + (c.id === chapterId ? ' active' : '') + (c.external || c.pages === 0 ? ' disabled' : '')}
                      disabled={c.external || c.pages === 0}
                      onClick={() => { setTocOpen(false); jumpChapter(c.id) }}>
                第 {c.chapter || '?'} 话 {c.title}
                {c.external ? '（外链）' : ''}
              </button>
            ))}
          </div>
        </div>
      )}

      {settingsOpen && (
        <div className="rf-sheet" onClick={e => e.stopPropagation()}>
          <div className="rf-sheet-row">
            <span>阅读方向</span>
            <div className="row-inline">
              <button className={settings.dir === 'ltr' ? 'mini' : 'mini ghost'}
                      onClick={() => patchSettings({ dir: 'ltr' })}>左到右</button>
              <button className={settings.dir === 'rtl' ? 'mini' : 'mini ghost'}
                      onClick={() => patchSettings({ dir: 'rtl' })}>右到左（日漫）</button>
            </div>
          </div>
          <div className="rf-sheet-row">
            <span>页模式</span>
            <div className="row-inline">
              <button className={effectiveMode === 'single' ? 'mini' : 'mini ghost'}
                      onClick={() => changePageMode('single')}>单页</button>
              <button className={effectiveMode === 'double' ? 'mini' : 'mini ghost'}
                      onClick={() => changePageMode('double')}>双页</button>
              <button className={effectiveMode === 'double-cover' ? 'mini' : 'mini ghost'}
                      onClick={() => changePageMode('double-cover')}>首页单排</button>
            </div>
          </div>
          <div className="rf-sheet-row">
            <span>翻页动画</span>
            <div className="row-inline">
              <button className={settings.anim === 'fade' ? 'mini' : 'mini ghost'}
                      onClick={() => patchSettings({ anim: 'fade' })}>柔和淡入</button>
              <button className={settings.anim === 'none' ? 'mini' : 'mini ghost'}
                      onClick={() => patchSettings({ anim: 'none' })}>极速无动画</button>
            </div>
          </div>
          <div className="rf-sheet-row">
            <span>清晰度</span>
            <div className="row-inline">
              <button className={!settings.hd ? 'mini' : 'mini ghost'}
                      onClick={() => patchSettings({ hd: false })}>省流</button>
              <button className={settings.hd ? 'mini' : 'mini ghost'}
                      onClick={() => patchSettings({ hd: true })}>高清</button>
            </div>
          </div>
          <div className="rf-sheet-row">
            <span>自动下一话</span>
            <div className="row-inline">
              <button className={settings.autoNext ? 'mini' : 'mini ghost'}
                      onClick={() => { patchSettings({ autoNext: true }); setAutoNextCancelled(false) }}>开启</button>
              <button className={!settings.autoNext ? 'mini' : 'mini ghost'}
                      onClick={() => { patchSettings({ autoNext: false }); setAutoNextCountdown(null) }}>关闭</button>
            </div>
          </div>
          <div className="rf-sheet-row muted" style={{ fontSize: 12 }}>
            点击左右 30% 区域翻页，中央切换浮层；Esc 逐层退出；F 全屏
          </div>
        </div>
      )}

      <footer className="rf-bottom">
        <button className="rf-text-btn" disabled={!prevCh} onClick={() => prevCh && jumpChapter(prevCh.id)}>
          上一话
        </button>
        <div className="rf-progress">
          <span className="rf-page">
            {singleMode ? `${page} / ${total}` : `${effSpread + 1} / ${spreadCount} 组`}
          </span>
          {singleMode ? (
            <input type="range" min={1} max={Math.max(total, 1)} value={page}
                   onPointerUp={e => e.currentTarget.blur()}
                   onChange={e => jumpPage(Number(e.target.value))} />
          ) : (
            <input type="range" min={1} max={Math.max(spreadCount, 1)} value={effSpread + 1}
                   onPointerUp={e => e.currentTarget.blur()}
                   onChange={e => jumpSpread(Number(e.target.value) - 1)} />
          )}
        </div>
        <button className="rf-text-btn" disabled={!nextCh} onClick={() => nextCh && jumpChapter(nextCh.id)}>
          下一话
        </button>
      </footer>
    </div>
  )
}

interface ResolveStatus {
  jobId: string | null; jobState: string
  playUrl: string | null; playKind: string | null
  errorCode: string | null; errorMessage: string | null
}

const RESOLVE_POLL_MS = 2000
const RESOLVE_MAX_MS = 90_000

function pokeUi() {
  const root = document.querySelector('.reader-full')
  root?.classList.add('show-ui')
  window.clearTimeout((root as unknown as { _hideTimer?: number })._hideTimer)
  ;(root as unknown as { _hideTimer?: number })._hideTimer =
    window.setTimeout(() => root?.classList.remove('show-ui'), 2200)
}

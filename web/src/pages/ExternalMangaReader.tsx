import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api, ApiError } from '../api'
import { proxyImage } from '../externalManga'

interface Pages {
  chapterId?: string
  baseUrl?: string
  hash?: string
  pages: string[]
  dataSaver: string[]
  external: boolean
}
interface Chapter {
  id: string; chapter: string; title: string; lang: string; pages: number; external: boolean
}
interface Detail { manga: { title: string }; chapters: Chapter[] }
interface ProgressRow { source: string; mangaId: string; chapterId: string | null; page: number }

type Dir = 'ltr' | 'rtl'
type PageMode = 'single' | 'double' | 'double-cover'
type Anim = 'fade' | 'none'

interface ReaderSettings {
  dir: Dir
  pageMode: PageMode
  anim: Anim
  hd: boolean
}

const SETTINGS_KEY = 'ms-manga-reader-settings'

function loadSettings(): ReaderSettings {
  try {
    const raw = localStorage.getItem(SETTINGS_KEY)
    if (raw) return { dir: 'ltr', pageMode: 'single', anim: 'fade', hd: false, ...JSON.parse(raw) }
  } catch { /* 忽略损坏配置 */ }
  return { dir: 'ltr', pageMode: 'single', anim: 'fade', hd: false }
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
  const source = params.get('source') ?? 'mangadex'

  const [settings, setSettings] = useState<ReaderSettings>(loadSettings)
  const [data, setData] = useState<Pages | null>(null)
  const [detail, setDetail] = useState<{ manga: { title: string }; chapters: Chapter[] } | null>(null)
  const [error, setError] = useState('')
  const [status, setStatus] = useState<ResolveStatus | null>(null)
  const [uiVisible, setUiVisible] = useState(true)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [spread, setSpread] = useState(0)
  const [page, setPage] = useState(1)
  const [resumeSpread, setResumeSpread] = useState<number | null>(null)
  const [resolving, setResolving] = useState(false)

  const stageRef = useRef<HTMLDivElement>(null)
  const gestureRef = useRef<{ x: number; y: number; t: number } | null>(null)
  const lastSaveRef = useRef(0)
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

  // 沉浸模式：隐藏站点导航与页脚
  useEffect(() => {
    document.body.classList.add('reader-open')
    return () => document.body.classList.remove('reader-open')
  }, [])

  useEffect(() => {
    void (async () => {
      if (!chapterId) return
      try {
        if (source === 'mangadex') {
          setData(await api.get<Pages>(`/api/v1/external-manga/chapter/${chapterId}/pages`))
        } else {
          setData(await api.get<Pages>(`/api/v1/manga-sources/${source}/chapter/${chapterId}/pages`))
        }
      } catch (e) {
        setError(e instanceof Error ? e.message : '章节加载失败')
      }
    })()
  }, [chapterId, source])

  useEffect(() => {
    if (!mangaId) return
    void (async () => {
      try {
        if (source === 'mangadex') {
          setDetail(await api.get<{ manga: { title: string }; chapters: Chapter[] }>(
            `/api/v1/external-manga/${mangaId}?lang=${lang}`))
        } else {
          const raw = await api.get<{ item: { title: string };
            chapters: { id: string; label: string; title: string; lang: string;
                        pages: number; external: boolean }[] }>(
            `/api/v1/manga-sources/${source}/manga/${mangaId}?lang=${lang}`)
          setDetail({
            manga: { title: raw.item.title },
            chapters: (raw.chapters ?? []).map(c => ({
              id: c.id, chapter: c.label, title: c.title, lang: c.lang ?? 'zh',
              pages: c.pages, external: c.external,
            })),
          })
        }
      } catch { /* 章节导航不可用不影响阅读 */ }
    })()
  }, [mangaId, lang, source])

  const chapter = detail?.chapters.find(c => c.id === chapterId) ?? null
  const readable = (detail?.chapters ?? []).filter(c => !c.external && c.pages !== 0)
  const chapterIdx = readable.findIndex(c => c.id === chapterId)
  const prevCh = chapterIdx > 0 ? readable[chapterIdx - 1] : null
  const nextCh = chapterIdx >= 0 && chapterIdx < readable.length - 1 ? readable[chapterIdx + 1] : null
  const comicTitle = title || detail?.manga.title || '外部漫画'

  const saveProgress = useCallback((p: number, total: number) => {
    if (!mangaId) return
    void api.put('/api/v1/me/manga-progress', {
      source, mangaId, chapterId, page: p, totalPages: total, title: comicTitle,
    }).catch(() => { /* 上报失败不打断 */ })
  }, [mangaId, chapterId, comicTitle, source])

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

  // 滚动模式：窗口滚动换算页码 + 节流上报
  useEffect(() => {
    if (!data || settings.pageMode !== 'single') return
    const onScroll = () => {
      const stage = stageRef.current
      if (!stage) return
      const imgs = Array.from(stage.querySelectorAll('img'))
      const mid = window.scrollY + window.innerHeight / 2
      let i = 0
      imgs.forEach((img, k) => {
        if (img.getBoundingClientRect().top + window.scrollY < mid) i = k
      })
      const p = i + 1
      if (p !== pageRef.current) {
        setPage(p)
        localStorage.setItem(`ms-ext-manga:${chapterId}`, String(p))
        if (Date.now() - lastSaveRef.current >= 3000) {
          lastSaveRef.current = Date.now()
          saveProgress(p, imgs.length)
        }
      }
    }
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      window.removeEventListener('scroll', onScroll)
      saveProgress(pageRef.current, stageRef.current?.querySelectorAll('img').length ?? 0)
    }
  }, [data, settings.pageMode, chapterId, saveProgress])

  // 续读位置（双页模式换算到所在 spread）
  useEffect(() => {
    if (!mangaId || !chapterId || !data) return
    void api.get<ProgressRow[]>('/api/v1/me/manga-progress?limit=50')
      .then(rows => {
        const row = rows.find(r => r.source === source && r.mangaId === mangaId)
        let p = 1
        if (row && row.chapterId === chapterId && row.page > 1) {
          p = row.page
        } else {
          p = Number(localStorage.getItem(`ms-ext-manga:${chapterId}`) ?? '1')
        }
        setPage(Math.max(1, p))
        if (settings.pageMode !== 'single') {
          const base = settings.pageMode === 'double-cover' ? 1 : 0
          setSpread(Math.floor((Math.max(1, p) - 1 - base) / 2))
        }
      })
      .catch(() => { /* 无进度不影响阅读 */ })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mangaId, chapterId, data, settings.pageMode])

  // Esc 逐层退出：设置 → 浮层 → 返回章节列表；F 全屏
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return
      if (e.key === 'Escape') {
        if (settingsOpen) { setSettingsOpen(false); setUiVisible(true) }
        else if (!uiVisible) setUiVisible(true)
        else navigate(`/manga-ext/${mangaId}?lang=${lang}&source=${source}`)
      }
      if (e.key === 'f' || e.key === 'F') {
        const el = document.querySelector('.reader-full')
        if (!document.fullscreenElement) void el?.requestFullscreen().catch(() => {})
        else void document.exitFullscreen().catch(() => {})
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [settingsOpen, uiVisible, navigate, mangaId, lang])

  const list = data ? (settings.hd ? data.pages : (data.dataSaver.length ? data.dataSaver : data.pages)) : []
  const total = list.length

  const spreads = buildSpreads(total, settings.pageMode)
  const spreadCount = spreads.length
  const effSpread = Math.min(spread, Math.max(0, spreadCount - 1))
  const spreadPages = spreads[effSpread] ?? []
  // RTL 时组内页序反转（右页在前）
  const displayPages = settings.dir === 'rtl' ? [...spreadPages].reverse() : spreadPages

  // 双页模式进度上报：spread 变化换算页码（单页模式由滚动处理器负责）
  const pagedMountedRef = useRef(false)
  useEffect(() => {
    if (!data || settings.pageMode === 'single' || !chapterId) return
    if (!pagedMountedRef.current) { pagedMountedRef.current = true; return }
    const first = (spreads[effSpread] ?? [0])[0] + 1
    if (Date.now() - lastSaveRef.current >= 3000) {
      lastSaveRef.current = Date.now()
      saveProgress(first, total)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [effSpread, data, settings.pageMode, chapterId, saveProgress])

  function flip(delta: number) {
    if (settings.pageMode === 'single') {
      setPage(p => Math.max(1, Math.min(total, p + delta)))
    } else {
      setSpread(s => Math.max(0, Math.min(spreadCount - 1, s + delta)))
    }
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

  const jumpChapter = (id: string) =>
    navigate(`/manga-ext/read/${id}?mangaId=${mangaId}&lang=${lang}&source=${source}` +
             `&title=${encodeURIComponent(comicTitle)}`)

  const backTo = `/manga-ext/${mangaId}?lang=${lang}&source=${source}`

  if (error) {
    return (
      <div className="reader-full">
        <div className="reader-full-inner"><p className="error">{error}</p></div>
      </div>
    )
  }
  if (!data) {
    return <div className="reader-full"><div className="reader-full-inner muted">加载中…</div></div>
  }

  const backExternal = data.external || total === 0
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

  const singleMode = settings.pageMode === 'single'
  const displayList = singleMode
    ? [Math.max(0, Math.min(total - 1, page - 1))]
    : displayPages
  const progressNo = singleMode ? page : ((spreadPages[0] ?? 0) + 1)

  return (
    <div className={'reader-full' + (uiVisible ? ' show-ui' : '')} onClick={pokeUi}>
      <header className="rf-top">
        <Link to={backTo} className="rf-icon" aria-label="返回">←</Link>
        <div className="rf-title">
          <span className="rf-comic">{comicTitle}</span>
          <span className="rf-chapter">
            {chapter ? `第 ${chapter.chapter || '?'} 话` : ''} {chapter?.title ?? ''}
          </span>
        </div>
        <button className="rf-icon" aria-label="设置"
                onClick={e => { e.stopPropagation(); setSettingsOpen(v => !v); pokeUi() }}>⚙</button>
      </header>

      {singleMode ? (
        <div ref={stageRef} className="rf-stage">
          {list.map((src, i) => (
            <img key={i} src={src} data-original={src} alt={`第 ${i + 1} 页`}
                 loading={i < 3 ? 'eager' : 'lazy'} decoding="async"
                 onError={onImgError} />
          ))}
        </div>
      ) : (
        <div className={'rf-paged' + (settings.anim === 'fade' ? ' rf-fade' : '')}
             key={animKey}
             onPointerDown={onPointerDown} onPointerUp={onPointerUp}>
          {displayPages.map(pageNo => (
            <img key={pageNo} src={list[pageNo]} data-original={list[pageNo]} alt={`第 ${pageNo + 1} 页`}
                 onError={onImgError} />
          ))}
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
              <button className={settings.pageMode === 'single' ? 'mini' : 'mini ghost'}
                      onClick={() => patchSettings({ pageMode: 'single' })}>单页</button>
              <button className={settings.pageMode === 'double' ? 'mini' : 'mini ghost'}
                      onClick={() => { patchSettings({ pageMode: 'double' }); setSpread(0) }}>双页</button>
              <button className={settings.pageMode === 'double-cover' ? 'mini' : 'mini ghost'}
                      onClick={() => { patchSettings({ pageMode: 'double-cover' }); setSpread(0) }}>首页单排</button>
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
                   onChange={e => setPage(Number(e.target.value))} />
          ) : (
            <input type="range" min={1} max={Math.max(spreadCount, 1)} value={effSpread + 1}
                   onChange={e => setSpread(Number(e.target.value) - 1)} />
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

function onImgError(e: React.SyntheticEvent<HTMLImageElement>) {
  const img = e.currentTarget
  // 直连失败 → 代理回退一次（原始地址记在 data-original，避免对代理地址再代理）
  const original = img.dataset.original || img.getAttribute('src') || ''
  if (!original) return
  if (!img.dataset.proxied && !original.startsWith('/api/')) {
    img.dataset.proxied = '1'
    img.src = proxyImage(original)
    return
  }
  img.style.visibility = 'hidden'
}

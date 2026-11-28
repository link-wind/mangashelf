import Hls from 'hls.js'
import {
  Maximize, Minimize, Pause, PictureInPicture2, Play, RectangleHorizontal,
  SkipForward, Volume1, Volume2, VolumeX,
} from 'lucide-react'
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import {
  loadSrMode,
  saveSrMode,
  startSuperResolution,
  type SrController,
  type SrMode,
  type SrStats,
} from '../lib/superResolution'

export interface DanmakuItem {
  id: string
  timeMs: number
  content: string
  color: string
  userName?: string
}

export type DanmakuDensity = 'all' | 'lite' | 'off'

export interface DanmakuSettings {
  mode: DanmakuDensity
  opacity: number   // 0.3 ~ 1.0
  fontSize: number  // 14 ~ 24 px
  speed: number     // 5 ~ 12 s（越小越快）
  area: number      // 0.25 | 0.5 | 0.75 | 1.0
}

export const DEFAULT_DANMAKU_SETTINGS: DanmakuSettings = {
  mode: 'all',
  opacity: 0.88,
  fontSize: 18,
  speed: 8,
  area: 0.75,
}

const DM_SETTINGS_KEY = 'ms-danmaku-settings:v1'
const VOL_KEY = 'ms-player-vol'
const SPEED_KEY = 'ms-player-speed'
const SPEEDS = [2, 1.5, 1.25, 1, 0.75, 0.5]
const SR_OPTIONS: { value: SrMode; label: string }[] = [
  { value: 'off', label: '关闭' },
  { value: 'fast', label: '效率' },
  { value: 'quality', label: '质量' },
]
const LANES = 8

export function loadDanmakuSettings(): DanmakuSettings {
  try {
    const legacyOff = localStorage.getItem('ms-danmaku') === 'off'
    const raw = localStorage.getItem(DM_SETTINGS_KEY)
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<DanmakuSettings>
      return {
        ...DEFAULT_DANMAKU_SETTINGS,
        ...parsed,
        mode: legacyOff ? 'off' : (parsed.mode ?? 'all'),
      }
    }
    return { ...DEFAULT_DANMAKU_SETTINGS, mode: legacyOff ? 'off' : 'all' }
  } catch {
    return DEFAULT_DANMAKU_SETTINGS
  }
}

export function saveDanmakuSettings(next: DanmakuSettings) {
  try {
    localStorage.setItem(DM_SETTINGS_KEY, JSON.stringify(next))
    localStorage.setItem('ms-danmaku', next.mode === 'off' ? 'off' : 'on')
  } catch { /* ignore */ }
}

function loadSavedVol(): number {
  try {
    const n = Number(localStorage.getItem(VOL_KEY))
    if (Number.isFinite(n) && n >= 0 && n <= 1) return n
  } catch { /* ignore */ }
  return 0.9
}

function loadSavedSpeed(): number {
  try {
    const n = Number(localStorage.getItem(SPEED_KEY))
    if (SPEEDS.includes(n)) return n
  } catch { /* ignore */ }
  return 1
}

function fmt(sec: number): string {
  if (!Number.isFinite(sec) || sec < 0) return '00:00'
  const s = Math.floor(sec)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const ss = String(s % 60).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${String(m).padStart(2, '0')}:${ss}`
}

interface PlayerProps {
  src: string | null
  kind: 'hls' | 'mp4' | null
  resumeMs?: number
  poster?: string | null
  subtitleUrl?: string | null
  /** 解析中 / 失败 / 登录拦截覆盖层 */
  overlay?: ReactNode
  widescreen?: boolean
  lightsOff?: boolean
  onToggleWidescreen?: () => void
  onToggleLightsOff?: () => void
  onPrev?: (() => void) | null
  onNext?: (() => void) | null
  onTimeTick?: (currentSec: number) => void
  onProgress?: (positionMs: number, durationMs: number) => void
  onEnded?: () => void
  onFatal?: () => void
  danmaku?: {
    items: DanmakuItem[]
    settings: DanmakuSettings
    onCycleMode: () => void
  }
}

/**
 * 影院级自定义视频播放器：
 * - HLS / MP4 + 续播 + 模糊高清封面氛围底；
 * - 拖拽进度条 + 悬停时间预览气泡 + 音量/静音 + 倍速直选菜单 + 画中画 PiP + 宽屏 + 全屏；
 * - 全局键盘快捷键（Space / ←→ / ↑↓ / M / T / L / F / D / Shift+P/N）与瞬时 HUD 提示；
 * - 可定制弹幕层（全量/精简/关闭、透明度、字号、速度、区域）。
 */
export default function Player({
  src, kind, resumeMs = 0, poster, subtitleUrl, overlay,
  widescreen = false, lightsOff = false,
  onToggleWidescreen, onToggleLightsOff, onPrev, onNext,
  onTimeTick, onProgress, onEnded, onFatal, danmaku,
}: PlayerProps) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const srCanvasRef = useRef<HTMLCanvasElement>(null)
  const srCtrlRef = useRef<SrController | null>(null)
  const shellRef = useRef<HTMLDivElement>(null)
  const progressRef = useRef<HTMLDivElement>(null)
  const hlsRef = useRef<Hls | null>(null)
  const hideTimer = useRef<number | null>(null)
  const hudTimer = useRef<number | null>(null)
  const tapTimer = useRef<number | null>(null)
  const lastTouchTapRef = useRef(0)
  const draggingRef = useRef(false)
  const splitDraggingRef = useRef(false)
  const controlsHoverRef = useRef(false)
  const lastPointerTypeRef = useRef<string>('mouse')
  const onEndedRef = useRef(onEnded)
  const onProgressRef = useRef(onProgress)
  const onTimeTickRef = useRef(onTimeTick)
  onEndedRef.current = onEnded
  onProgressRef.current = onProgress
  onTimeTickRef.current = onTimeTick

  const [playing, setPlaying] = useState(false)
  const [hasFrame, setHasFrame] = useState(false)
  const [current, setCurrent] = useState(0)
  const [duration, setDuration] = useState(0)
  const [buffered, setBuffered] = useState(0)
  const [volume, setVolume] = useState<number>(loadSavedVol)
  const [muted, setMuted] = useState(false)
  const [volMenuOpen, setVolMenuOpen] = useState(false)
  const [volReadonly, setVolReadonly] = useState(false)
  const [speed, setSpeed] = useState<number>(loadSavedSpeed)
  const [speedMenuOpen, setSpeedMenuOpen] = useState(false)
  const [srMode, setSrMode] = useState<SrMode>(loadSrMode)
  const [srMenuOpen, setSrMenuOpen] = useState(false)
  const [srStats, setSrStats] = useState<SrStats | null>(null)
  const [srCompare, setSrCompare] = useState(false)
  const [srSplit, setSrSplit] = useState(0.5)
  const [srFailedSrc, setSrFailedSrc] = useState<string | null>(null)
  const [inPip, setInPip] = useState(false)
  const [subtitleCues, setSubtitleCues] = useState<string[]>([])
  const [controlsVisible, setControlsVisible] = useState(true)
  const [fullscreen, setFullscreen] = useState(false)
  const [pseudoFullscreen, setPseudoFullscreen] = useState(false)
  const [hoverSeek, setHoverSeek] = useState<{ ratio: number; time: number } | null>(null)
  const [hudText, setHudText] = useState('')

  const showHud = useCallback((msg: string) => {
    setHudText(msg)
    if (hudTimer.current) window.clearTimeout(hudTimer.current)
    hudTimer.current = window.setTimeout(() => setHudText(''), 950)
  }, [])

  // 同步音量与倍速到 <video>
  useEffect(() => {
    const v = videoRef.current
    if (!v) return
    v.volume = volume
    v.muted = muted
  }, [volume, muted, src])

  useEffect(() => {
    const v = videoRef.current
    if (!v) return
    v.playbackRate = speed
  }, [speed, src])

  // 加载媒体源（HLS 用 hls.js，Safari 原生回退）
  useEffect(() => {
    const v = videoRef.current
    setPlaying(false)
    setHasFrame(false)
    setCurrent(0)
    setDuration(0)
    setBuffered(0)
    if (!v || !src) return
    let hls: Hls | null = null
    let triedCors = false
    let loaded = false
    const onCorsFallback = () => {
      if (!triedCors || loaded) return
      triedCors = false
      v.removeAttribute('crossorigin')
      v.src = src
      v.load()
    }
    if (kind === 'hls' && Hls.isSupported()) {
      v.removeAttribute('crossorigin')
      hls = new Hls({ enableWorker: true })
      hlsRef.current = hls
      hls.loadSource(src)
      hls.attachMedia(v)
      hls.on(Hls.Events.ERROR, (_e, data) => {
        if (!data.fatal) return
        onFatal?.()
      })
    } else {
      // 优先以 anonymous CORS 加载直链 MP4/原生 HLS 以支持 WebGPU/WebGL 超清；
      // 若上游未返回 CORS 头导致加载报错，自动去掉 crossOrigin 无感回退原画播放。
      triedCors = true
      v.crossOrigin = 'anonymous'
      v.addEventListener('error', onCorsFallback)
      v.src = src
    }
    const tryPlay = () => {
      loaded = true
      setHasFrame(true)
      v.playbackRate = speed
      v.volume = volume
      v.muted = muted
      void v.play().catch(() => { /* 自动播放被浏览器拦截时等用户点击 */ })
    }
    v.addEventListener('loadeddata', tryPlay, { once: true })
    return () => {
      v.removeEventListener('loadeddata', tryPlay)
      v.removeEventListener('error', onCorsFallback)
      hls?.destroy()
      hlsRef.current = null
      v.removeAttribute('crossorigin')
      v.removeAttribute('src')
      v.load()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [src, kind, onFatal])

  // 续播
  useEffect(() => {
    const v = videoRef.current
    if (!v) return
    const onLoaded = () => {
      if (resumeMs > 3000 && resumeMs < (v.duration || Infinity) * 1000 - 5000) {
        v.currentTime = resumeMs / 1000
      }
    }
    v.addEventListener('loadedmetadata', onLoaded)
    return () => v.removeEventListener('loadedmetadata', onLoaded)
  }, [resumeMs, src])

  // 进度、缓冲与播放状态监听
  useEffect(() => {
    const v = videoRef.current
    if (!v) return
    let lastSave = 0
    const onTime = () => {
      if (!draggingRef.current) setCurrent(v.currentTime)
      onTimeTickRef.current?.(v.currentTime)
      if (v.buffered.length > 0) setBuffered(v.buffered.end(v.buffered.length - 1))
      const now = Date.now()
      if (now - lastSave > 5000) {
        lastSave = now
        onProgressRef.current?.(Math.floor(v.currentTime * 1000), Math.floor((v.duration || 0) * 1000))
      }
    }
    const onMeta = () => setDuration(v.duration || 0)
    const onPlay = () => { setPlaying(true); setHasFrame(true) }
    const onPause = () => {
      setPlaying(false)
      if (v.currentTime > 0) onProgressRef.current?.(Math.floor(v.currentTime * 1000), Math.floor((v.duration || 0) * 1000))
    }
    const handleEnded = () => onEndedRef.current?.()
    v.addEventListener('timeupdate', onTime)
    v.addEventListener('durationchange', onMeta)
    v.addEventListener('play', onPlay)
    v.addEventListener('pause', onPause)
    v.addEventListener('ended', handleEnded)
    return () => {
      v.removeEventListener('timeupdate', onTime)
      v.removeEventListener('durationchange', onMeta)
      v.removeEventListener('play', onPlay)
      v.removeEventListener('pause', onPause)
      v.removeEventListener('ended', handleEnded)
      if (v.currentTime > 0) onProgressRef.current?.(Math.floor(v.currentTime * 1000), Math.floor((v.duration || 0) * 1000))
    }
  }, [src])

  // 全屏与画中画状态同步（兼容标准与 WebKit 全屏事件）
  useEffect(() => {
    const getFsEl = () =>
      document.fullscreenElement
      ?? (document as Document & { webkitFullscreenElement?: Element | null }).webkitFullscreenElement
      ?? null
    const onFs = () => {
      controlsHoverRef.current = false
      setFullscreen(Boolean(getFsEl()))
    }
    document.addEventListener('fullscreenchange', onFs)
    document.addEventListener('webkitfullscreenchange', onFs)
    return () => {
      document.removeEventListener('fullscreenchange', onFs)
      document.removeEventListener('webkitfullscreenchange', onFs)
    }
  }, [])

  // 伪全屏时锁定背景滚动，退出时恢复
  useEffect(() => {
    if (!pseudoFullscreen) return
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => { document.body.style.overflow = prev }
  }, [pseudoFullscreen])

  useEffect(() => {
    const v = videoRef.current
    if (!v) return
    const onEnterPip = () => setInPip(true)
    const onLeavePip = () => setInPip(false)
    v.addEventListener('enterpictureinpicture', onEnterPip)
    v.addEventListener('leavepictureinpicture', onLeavePip)
    return () => {
      v.removeEventListener('enterpictureinpicture', onEnterPip)
      v.removeEventListener('leavepictureinpicture', onLeavePip)
    }
  }, [])

  const srActive = srMode !== 'off' && Boolean(src) && hasFrame && !inPip && srFailedSrc !== src

  // 实时超清管线生命周期（WebGPU Anime4K + WebGL2 CAS 降级）
  useEffect(() => {
    if (srMode === 'off' || !srActive) {
      srCtrlRef.current?.destroy()
      srCtrlRef.current = null
      setSrStats(null)
      return
    }
    const video = videoRef.current
    const canvas = srCanvasRef.current
    if (!video || !canvas) return

    let cancelled = false
    let localCtrl: SrController | null = null

    void startSuperResolution({
      video,
      canvas,
      mode: srMode,
      splitRatio: srCompare ? srSplit : null,
      onStats: (stats) => {
        if (!cancelled) setSrStats(stats)
      },
      onError: (code, msg) => {
        if (cancelled) return
        if (code === 'cors' && src) setSrFailedSrc(src)
        else {
          setSrMode('off')
          saveSrMode('off')
        }
        setSrStats(null)
        showHud(msg)
      },
      onAutoDowngrade: (msg) => {
        if (cancelled) return
        setSrMode('fast')
        saveSrMode('fast')
        showHud(msg)
      },
    }).then((ctrl) => {
      if (cancelled) {
        ctrl.destroy()
        return
      }
      localCtrl = ctrl
      srCtrlRef.current = ctrl
    }).catch(() => {
      if (!cancelled && src) setSrFailedSrc(src)
    })

    return () => {
      cancelled = true
      localCtrl?.destroy()
      if (srCtrlRef.current === localCtrl) srCtrlRef.current = null
    }
    // splitRatio 通过 setSplitRatio 热更新，不重建 GPU 管线
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [srActive, srMode, src])

  // 热更新分屏对比比例（零重建、暂停时也可实时拖动）
  useEffect(() => {
    srCtrlRef.current?.setSplitRatio(srCompare ? srSplit : null)
  }, [srCompare, srSplit])

  // 超清 Canvas 覆在 <video> 上时同步渲染外置 WebVTT 字幕，避免遮挡原生字幕轨
  useEffect(() => {
    const v = videoRef.current
    if (!v || !subtitleUrl || !srActive) {
      setSubtitleCues([])
      return
    }
    const track = v.textTracks[0]
    if (!track) return
    const prevMode = track.mode
    track.mode = 'hidden'
    const syncCues = () => {
      const list = track.activeCues
      if (!list || list.length === 0) {
        setSubtitleCues([])
        return
      }
      const lines: string[] = []
      for (let i = 0; i < list.length; i++) {
        const cue = list[i] as VTTCue
        if (cue?.text) lines.push(cue.text)
      }
      setSubtitleCues(lines)
    }
    syncCues()
    track.addEventListener('cuechange', syncCues)
    return () => {
      track.removeEventListener('cuechange', syncCues)
      track.mode = prevMode === 'hidden' ? 'showing' : prevMode
      setSubtitleCues([])
    }
  }, [subtitleUrl, srActive, src])

  const clearHideTimer = useCallback(() => {
    if (hideTimer.current) {
      window.clearTimeout(hideTimer.current)
      hideTimer.current = null
    }
  }, [])

  const scheduleHide = useCallback(() => {
    clearHideTimer()
    hideTimer.current = window.setTimeout(() => {
      if (
        !videoRef.current?.paused
        && !speedMenuOpen
        && !srMenuOpen
        && !volMenuOpen
        && !controlsHoverRef.current
        && !draggingRef.current
      ) {
        setControlsVisible(false)
      }
    }, 2500)
  }, [clearHideTimer, speedMenuOpen, srMenuOpen, volMenuOpen])

  const poke = useCallback(() => {
    setControlsVisible(true)
    scheduleHide()
  }, [scheduleHide])

  const anyMenuOpen = speedMenuOpen || srMenuOpen || volMenuOpen
  const isFs = fullscreen || pseudoFullscreen

  // 播放状态 / 全屏 / 弹层变化时同步控制条自动隐藏
  useEffect(() => {
    if (!playing) {
      clearHideTimer()
      setControlsVisible(true)
      return
    }
    if (anyMenuOpen) {
      clearHideTimer()
      setControlsVisible(true)
      return
    }
    scheduleHide()
    return clearHideTimer
  }, [playing, isFs, anyMenuOpen, scheduleHide, clearHideTimer])

  useEffect(() => () => {
    if (tapTimer.current) window.clearTimeout(tapTimer.current)
  }, [])

  const togglePlay = useCallback(() => {
    const v = videoRef.current
    if (!v || !src) return
    if (v.paused) {
      void v.play().catch(() => {})
      showHud('继续播放')
    } else {
      v.pause()
      showHud('已暂停')
    }
  }, [src, showHud])

  const seekTo = useCallback((sec: number, hudLabel?: string) => {
    const v = videoRef.current
    if (!v) return
    const target = Math.max(0, Math.min(sec, v.duration || sec))
    v.currentTime = target
    setCurrent(target)
    if (hudLabel) showHud(hudLabel)
  }, [showHud])

  const changeVolume = useCallback((nextVol: number) => {
    const clamped = Math.max(0, Math.min(1, Math.round(nextVol * 100) / 100))
    const v = videoRef.current
    if (v) {
      try {
        v.volume = clamped
        v.muted = clamped === 0
        // iOS Safari 中 video.volume 恒为只读 1.0，检测后提示使用侧边音量键
        if (clamped < 0.99 && clamped > 0 && Math.abs(v.volume - clamped) > 0.05) {
          setVolReadonly(true)
        }
      } catch {
        setVolReadonly(true)
      }
    }
    setVolume(clamped)
    setMuted(clamped === 0)
    try { localStorage.setItem(VOL_KEY, String(clamped)) } catch { /* ignore */ }
    showHud(clamped === 0 ? '已静音' : `音量 ${Math.round(clamped * 100)}%`)
  }, [showHud])

  const toggleMute = useCallback(() => {
    setMuted(prev => {
      const next = !prev
      if (!next && volume === 0) {
        setVolume(0.7)
        try { localStorage.setItem(VOL_KEY, '0.7') } catch { /* ignore */ }
      }
      showHud(next ? '已静音' : `音量 ${Math.round((volume || 0.7) * 100)}%`)
      return next
    })
  }, [volume, showHud])

  const handleVolumeBtnClick = useCallback(() => {
    const isNarrow = typeof window !== 'undefined' && window.innerWidth <= 767
    const isTouch = lastPointerTypeRef.current === 'touch' || lastPointerTypeRef.current === 'pen'
    if (isNarrow || isTouch) {
      setVolMenuOpen(v => !v)
      setSpeedMenuOpen(false)
      setSrMenuOpen(false)
      return
    }
    toggleMute()
  }, [toggleMute])

  const pickSpeed = useCallback((nextSpeed: number) => {
    setSpeed(nextSpeed)
    setSpeedMenuOpen(false)
    try { localStorage.setItem(SPEED_KEY, String(nextSpeed)) } catch { /* ignore */ }
    showHud(`倍速 ${nextSpeed}x`)
  }, [showHud])

  const pickSrMode = useCallback((nextMode: SrMode) => {
    setSrMode(nextMode)
    saveSrMode(nextMode)
    setSrFailedSrc(null)
    if (nextMode === 'off') setSrCompare(false)
    setSrMenuOpen(false)
    const label = nextMode === 'off' ? '超清：关' : nextMode === 'fast' ? '超清：效率' : '超清：质量'
    showHud(label)
  }, [showHud])

  const cycleSrMode = useCallback(() => {
    const order: SrMode[] = ['off', 'fast', 'quality']
    const next = order[(order.indexOf(srMode) + 1) % order.length]
    pickSrMode(next)
  }, [srMode, pickSrMode])

  const toggleFullscreen = useCallback(() => {
    const el = shellRef.current as (HTMLDivElement & { webkitRequestFullscreen?: () => Promise<void> | void }) | null
    if (!el) return
    controlsHoverRef.current = false
    const doc = document as Document & {
      webkitFullscreenElement?: Element | null
      webkitExitFullscreen?: () => Promise<void> | void
    }
    if (pseudoFullscreen) {
      setPseudoFullscreen(false)
      return
    }
    if (doc.fullscreenElement || doc.webkitFullscreenElement) {
      if (doc.exitFullscreen) void doc.exitFullscreen().catch(() => {})
      else doc.webkitExitFullscreen?.()
      return
    }
    if (typeof el.requestFullscreen === 'function') {
      void el.requestFullscreen().catch(() => {
        setPseudoFullscreen(true)
      })
    } else if (typeof el.webkitRequestFullscreen === 'function') {
      try {
        const ret = el.webkitRequestFullscreen()
        if (ret && typeof (ret as Promise<void>).catch === 'function') {
          void (ret as Promise<void>).catch(() => setPseudoFullscreen(true))
        }
      } catch {
        setPseudoFullscreen(true)
      }
    } else {
      // iPhone Safari 等不支持容器 Fullscreen API 的环境使用页面内全屏，保留完整控制栏与超清
      setPseudoFullscreen(true)
    }
  }, [pseudoFullscreen])

  const togglePip = useCallback(() => {
    const v = videoRef.current
    if (!v || !document.pictureInPictureEnabled) return
    if (document.pictureInPictureElement) {
      void document.exitPictureInPicture().catch(() => {})
    } else {
      void v.requestPictureInPicture().catch(() => {})
    }
  }, [])

  // 全局键盘快捷键（输入框及组合键 Cmd/Ctrl/Alt 下不拦截）
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.metaKey || e.ctrlKey || e.altKey) return
      const target = e.target as HTMLElement | null
      if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT' || target.isContentEditable)) {
        return
      }
      const v = videoRef.current
      if (e.code === 'Space') {
        e.preventDefault()
        togglePlay()
      } else if (e.code === 'ArrowLeft' && v) {
        e.preventDefault()
        seekTo(v.currentTime - 5, '快退 -5s')
      } else if (e.code === 'ArrowRight' && v) {
        e.preventDefault()
        seekTo(v.currentTime + 5, '快进 +5s')
      } else if (e.code === 'ArrowUp') {
        e.preventDefault()
        changeVolume((muted ? 0 : volume) + 0.1)
      } else if (e.code === 'ArrowDown') {
        e.preventDefault()
        changeVolume((muted ? 0 : volume) - 0.1)
      } else if (e.key === 'm' || e.key === 'M') {
        e.preventDefault()
        toggleMute()
      } else if (e.key === 'f' || e.key === 'F') {
        e.preventDefault()
        toggleFullscreen()
      } else if ((e.key === 't' || e.key === 'T') && onToggleWidescreen) {
        e.preventDefault()
        onToggleWidescreen()
        showHud(widescreen ? '退出宽屏' : '宽屏模式')
      } else if ((e.key === 'l' || e.key === 'L') && onToggleLightsOff) {
        e.preventDefault()
        onToggleLightsOff()
        showHud(lightsOff ? '已开灯' : '关灯沉浸模式')
      } else if (e.key === 'Escape' && pseudoFullscreen) {
        setPseudoFullscreen(false)
      } else if (e.key === 'Escape' && lightsOff && onToggleLightsOff) {
        onToggleLightsOff()
      } else if ((e.key === 'd' || e.key === 'D') && danmaku) {
        e.preventDefault()
        danmaku.onCycleMode()
      } else if (e.key === 'u' || e.key === 'U') {
        e.preventDefault()
        cycleSrMode()
      } else if (e.shiftKey && (e.key === 'N' || e.key === 'n') && onNext) {
        e.preventDefault()
        onNext()
      } else if (e.shiftKey && (e.key === 'P' || e.key === 'p') && onPrev) {
        e.preventDefault()
        onPrev()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [
    togglePlay, seekTo, changeVolume, toggleMute, toggleFullscreen,
    onToggleWidescreen, onToggleLightsOff, widescreen, lightsOff, pseudoFullscreen,
    danmaku, cycleSrMode, onNext, onPrev, volume, muted, showHud,
  ])

  // 拖拽进度条
  function calcRatio(clientX: number): number {
    const rect = progressRef.current?.getBoundingClientRect()
    if (!rect || rect.width <= 0) return 0
    return Math.max(0, Math.min(1, (clientX - rect.left) / rect.width))
  }

  function handleProgressPointerDown(e: React.PointerEvent<HTMLDivElement>) {
    if (!duration) return
    e.preventDefault()
    draggingRef.current = true
    const r = calcRatio(e.clientX)
    setCurrent(r * duration)

    const onMove = (ev: PointerEvent) => {
      const ratio = calcRatio(ev.clientX)
      setCurrent(ratio * duration)
      setHoverSeek({ ratio, time: ratio * duration })
    }
    const onUp = (ev: PointerEvent) => {
      draggingRef.current = false
      const ratio = calcRatio(ev.clientX)
      seekTo(ratio * duration)
      setHoverSeek(null)
      if (!videoRef.current?.paused) scheduleHide()
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
    }
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
  }

  // 拖拽原画 / 超清分屏滑块
  function handleSplitPointerDown(e: React.PointerEvent<HTMLDivElement>) {
    e.preventDefault()
    e.stopPropagation()
    const canvas = srCanvasRef.current ?? shellRef.current
    if (!canvas) return
    splitDraggingRef.current = true
    const updateFromClientX = (clientX: number) => {
      const rect = canvas.getBoundingClientRect()
      if (rect.width <= 0) return
      const ratio = Math.max(0.05, Math.min(0.95, (clientX - rect.left) / rect.width))
      setSrSplit(ratio)
    }
    updateFromClientX(e.clientX)
    const onMove = (ev: PointerEvent) => updateFromClientX(ev.clientX)
    const onUp = () => {
      splitDraggingRef.current = false
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
    }
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
  }

  const pct = duration > 0 ? Math.min(100, Math.max(0, (current / duration) * 100)) : 0
  const bufPct = duration > 0 ? Math.min(100, Math.max(0, (buffered / duration) * 100)) : 0
  const canPip = typeof document !== 'undefined' && Boolean(document.pictureInPictureEnabled) && Boolean(src)
  const dmMode = danmaku?.settings.mode ?? 'off'
  const showPosterBg = Boolean(poster && (!hasFrame || !src || overlay))
  const srBtnLabel = srMode === 'quality' ? '超清+' : '超清'
  const srTitle = srStats
    ? `超清：${srMode === 'quality' ? '质量' : '效率'} · ${srStats.sourceHeight}p→${srStats.outputHeight}p (U)`
    : srFailedSrc === src && src
      ? '当前线路受跨域限制'
      : '超清增强 (U)'
  // 区分鼠标与触屏点击画面：
  // - 触屏：单点切换控制条显隐（若菜单打开先关菜单），双击播放/暂停；
  // - 鼠标：单击播放/暂停（若菜单打开先关菜单），双击全屏。
  const handleVideoClick = useCallback(() => {
    if (anyMenuOpen) {
      setSpeedMenuOpen(false)
      setSrMenuOpen(false)
      setVolMenuOpen(false)
      return
    }
    const isTouch = lastPointerTypeRef.current === 'touch' || lastPointerTypeRef.current === 'pen'
    if (!isTouch) {
      togglePlay()
      return
    }
    const now = Date.now()
    if (now - lastTouchTapRef.current < 280) {
      lastTouchTapRef.current = 0
      if (tapTimer.current) {
        window.clearTimeout(tapTimer.current)
        tapTimer.current = null
      }
      togglePlay()
      return
    }
    lastTouchTapRef.current = now
    if (tapTimer.current) window.clearTimeout(tapTimer.current)
    tapTimer.current = window.setTimeout(() => {
      tapTimer.current = null
      setControlsVisible(prev => {
        const next = !prev
        if (next) scheduleHide()
        else clearHideTimer()
        return next
      })
    }, 220)
  }, [anyMenuOpen, togglePlay, scheduleHide, clearHideTimer])

  const handleVideoDoubleClick = useCallback(() => {
    const isTouch = lastPointerTypeRef.current === 'touch' || lastPointerTypeRef.current === 'pen'
    if (isTouch) return
    toggleFullscreen()
  }, [toggleFullscreen])

  const showUi = controlsVisible || anyMenuOpen || !playing

  return (
    <div
      ref={shellRef}
      className={
        'ms-player'
        + (showUi ? ' show-ui' : '')
        + (isFs ? ' is-fullscreen' : '')
        + (pseudoFullscreen ? ' is-pseudo-fs' : '')
      }
      tabIndex={0}
      onPointerDown={e => { lastPointerTypeRef.current = e.pointerType || 'mouse' }}
      onPointerMove={e => {
        if (e.pointerType === 'mouse') poke()
      }}
      onPointerLeave={e => {
        if (e.pointerType !== 'mouse') return
        controlsHoverRef.current = false
        clearHideTimer()
        setSpeedMenuOpen(false)
        setSrMenuOpen(false)
        setVolMenuOpen(false)
        setHoverSeek(null)
        if (playing) setControlsVisible(false)
      }}
    >
      {showPosterBg && poster && (
        <div
          className="mp-poster-bg"
          style={{ backgroundImage: `url(${poster})` }}
          aria-hidden="true"
        />
      )}

      <video
        ref={videoRef}
        playsInline
        onClick={handleVideoClick}
        onDoubleClick={handleVideoDoubleClick}
      >
        {subtitleUrl && (
          <track kind="subtitles" label="中文字幕" srcLang="zh-CN" src={subtitleUrl} default />
        )}
      </video>

      {srActive && (
        <canvas
          key={`${srMode}:${src ?? ''}`}
          ref={srCanvasRef}
          className="mp-sr-canvas"
          aria-hidden="true"
        />
      )}

      {srActive && srCompare && (
        <>
          <span className="mp-sr-badge is-left">原画</span>
          <span className="mp-sr-badge is-right">超清</span>
          <div
            className="mp-sr-split"
            style={{ left: `${srSplit * 100}%` }}
            onPointerDown={handleSplitPointerDown}
            onClick={e => e.stopPropagation()}
            role="slider"
            aria-label="拖动对比原画与超清"
            aria-valuemin={5}
            aria-valuemax={95}
            aria-valuenow={Math.round(srSplit * 100)}
          >
            <span className="mp-sr-split-knob">⇆</span>
          </div>
        </>
      )}

      {srActive && subtitleCues.length > 0 && (
        <div className="mp-sr-subtitles" aria-live="polite">
          {subtitleCues.map((line, idx) => <span key={idx}>{line}</span>)}
        </div>
      )}

      {danmaku && dmMode !== 'off' && src && (
        <DanmakuLayer items={danmaku.items} current={current} settings={danmaku.settings} />
      )}

      {hudText && (
        <div className="mp-hud" role="status" aria-live="polite">{hudText}</div>
      )}

      {!playing && src && !overlay && (
        <button type="button" className="mp-bigplay" onClick={togglePlay} aria-label="播放">
          <Play size={28} fill="currentColor" style={{ transform: 'translateX(2px)' }} />
        </button>
      )}

      {overlay}

      <div
        className="mp-controls"
        onClick={e => e.stopPropagation()}
        onPointerEnter={e => {
          if (e.pointerType !== 'mouse') return
          controlsHoverRef.current = true
          clearHideTimer()
        }}
        onPointerLeave={e => {
          if (e.pointerType !== 'mouse') return
          controlsHoverRef.current = false
          if (playing) scheduleHide()
        }}
      >
        <div
          ref={progressRef}
          className="mp-progress"
          onPointerDown={handleProgressPointerDown}
          onMouseMove={e => {
            if (!duration) return
            const ratio = calcRatio(e.clientX)
            setHoverSeek({ ratio, time: ratio * duration })
          }}
          onMouseLeave={() => { if (!draggingRef.current) setHoverSeek(null) }}
        >
          <div className="mp-buffer" style={{ width: `${bufPct}%` }} />
          <div className="mp-played" style={{ width: `${pct}%` }} />
          <span className="mp-knob" style={{ left: `${pct}%` }} />
          {hoverSeek && (
            <span className="mp-seek-tip tabular-nums" style={{ left: `${hoverSeek.ratio * 100}%` }}>
              {fmt(hoverSeek.time)}
            </span>
          )}
        </div>

        <div className="mp-bar">
          <button type="button" className="mp-icon" onClick={togglePlay} aria-label={playing ? '暂停' : '播放'} title={playing ? '暂停 (Space)' : '播放 (Space)'}>
            {playing ? <Pause size={18} fill="currentColor" /> : <Play size={18} fill="currentColor" />}
          </button>

          {onNext && (
            <button type="button" className="mp-icon" onClick={onNext} aria-label="下一集" title="下一集 (Shift+N)">
              <SkipForward size={17} />
            </button>
          )}

          <span className="mp-time tabular-nums">{fmt(current)} / {fmt(duration)}</span>

          <span className="mp-spacer" />

          {/* 音量控制（桌面端横向滑块；移动端/触控点击弹出音量面板） */}
          <div className="mp-vol-wrap">
            <button
              type="button"
              className={'mp-icon' + (volMenuOpen ? ' on' : '')}
              onClick={handleVolumeBtnClick}
              aria-label={muted || volume === 0 ? '取消静音' : '音量'}
              title="音量 / 静音 (M)"
              aria-expanded={volMenuOpen}
            >
              {muted || volume === 0 ? <VolumeX size={17} /> : volume < 0.5 ? <Volume1 size={17} /> : <Volume2 size={17} />}
            </button>
            <input
              type="range"
              className="mp-vol-range"
              min={0}
              max={1}
              step={0.05}
              value={muted ? 0 : volume}
              aria-label="音量"
              onChange={e => changeVolume(Number(e.target.value))}
            />
            {volMenuOpen && (
              <div className="mp-speed-menu mp-vol-menu" role="dialog" aria-label="音量调节">
                <span className="mp-vol-pct tabular-nums">
                  {muted || volume === 0 ? '静音' : `${Math.round(volume * 100)}%`}
                </span>
                <input
                  type="range"
                  className="mp-vol-slider-mobile"
                  min={0}
                  max={1}
                  step={0.05}
                  value={muted ? 0 : volume}
                  aria-label="拖动调节音量"
                  onChange={e => changeVolume(Number(e.target.value))}
                />
                {volReadonly && <span className="mp-sr-tip">请用侧边键调音量</span>}
                <button
                  type="button"
                  className={'mp-speed-opt' + (muted || volume === 0 ? ' is-active' : '')}
                  onClick={toggleMute}
                >
                  {muted || volume === 0 ? '取消静音' : '静音'}
                </button>
              </div>
            )}
          </div>

          {/* 超清画质增强菜单（Anime4K WebGPU / WebGL2 降级） */}
          <div className="mp-speed-wrap">
            <button
              type="button"
              className={'mp-icon mp-text' + (srMode !== 'off' ? ' on' : '')}
              onClick={() => {
                setSrMenuOpen(v => !v)
                setSpeedMenuOpen(false)
                setVolMenuOpen(false)
              }}
              title={srTitle}
              aria-expanded={srMenuOpen}
            >
              {srBtnLabel}
            </button>
            {srMenuOpen && (
              <div className="mp-speed-menu mp-sr-menu" role="menu">
                {srFailedSrc === src && src && (
                  <span className="mp-sr-tip">线路不支持</span>
                )}
                {SR_OPTIONS.map(opt => (
                  <button
                    key={opt.value}
                    type="button"
                    role="menuitemradio"
                    aria-checked={srMode === opt.value}
                    className={'mp-speed-opt' + (srMode === opt.value ? ' is-active' : '')}
                    onClick={() => pickSrMode(opt.value)}
                  >
                    {opt.label}
                  </button>
                ))}
                {srActive && (
                  <>
                    <div className="mp-sr-divider" />
                    <button
                      type="button"
                      className={'mp-speed-opt' + (srCompare ? ' is-active' : '')}
                      onClick={() => {
                        setSrCompare(prev => {
                          const next = !prev
                          showHud(next ? '对比：开' : '对比：关')
                          return next
                        })
                      }}
                    >
                      对比
                    </button>
                  </>
                )}
              </div>
            )}
          </div>

          {/* 倍速悬浮直选菜单 */}
          <div className="mp-speed-wrap">
            <button
              type="button"
              className={'mp-icon mp-text' + (speed !== 1 ? ' on' : '')}
              onClick={() => {
                setSpeedMenuOpen(v => !v)
                setSrMenuOpen(false)
                setVolMenuOpen(false)
              }}
              title="播放倍速"
              aria-expanded={speedMenuOpen}
            >
              {speed === 1 ? '倍速' : `${speed}x`}
            </button>
            {speedMenuOpen && (
              <div className="mp-speed-menu" role="menu">
                {SPEEDS.map(s => (
                  <button
                    key={s}
                    type="button"
                    role="menuitemradio"
                    aria-checked={speed === s}
                    className={'mp-speed-opt tabular-nums' + (speed === s ? ' is-active' : '')}
                    onClick={() => pickSpeed(s)}
                  >
                    {s.toFixed(s % 1 === 0 ? 1 : 2).replace(/0$/, '')}x
                  </button>
                ))}
              </div>
            )}
          </div>

          {danmaku && (
            <button
              type="button"
              className={'mp-icon mp-text' + (dmMode !== 'off' ? ' on' : '')}
              onClick={danmaku.onCycleMode}
              title={`弹幕：${dmMode === 'all' ? '全量' : dmMode === 'lite' ? '精简' : '关闭'} (D)`}
            >
              {dmMode === 'lite' ? '精' : '弹'}
            </button>
          )}

          {canPip && (
            <button type="button" className="mp-icon" onClick={togglePip} aria-label="画中画" title="画中画">
              <PictureInPicture2 size={17} />
            </button>
          )}

          {onToggleWidescreen && (
            <button
              type="button"
              className={'mp-icon' + (widescreen ? ' on' : '')}
              onClick={() => {
                onToggleWidescreen()
                showHud(widescreen ? '退出宽屏' : '宽屏模式')
              }}
              aria-label={widescreen ? '退出宽屏' : '宽屏模式'}
              title={widescreen ? '退出宽屏 (T)' : '宽屏模式 (T)'}
            >
              <RectangleHorizontal size={18} />
            </button>
          )}

          <button type="button" className="mp-icon" onClick={toggleFullscreen} aria-label={isFs ? '退出全屏' : '全屏'} title="全屏 (F)">
            {isFs ? <Minimize size={17} /> : <Maximize size={17} />}
          </button>
        </div>
      </div>
    </div>
  )
}

/** 弹幕层：支持全量/精简模式、透明度、字号、速度与显示区域定制。 */
function DanmakuLayer({ items, current, settings }: {
  items: DanmakuItem[]
  current: number
  settings: DanmakuSettings
}) {
  const layerRef = useRef<HTMLDivElement>(null)
  const cursorRef = useRef(0)
  const lastTimeRef = useRef(0)
  const lastSpawnAtRef = useRef(0)
  const laneFreeRef = useRef<number[]>(new Array(LANES).fill(0))

  useEffect(() => {
    if (current < lastTimeRef.current - 2) {
      cursorRef.current = 0
      laneFreeRef.current = new Array(LANES).fill(0)
      layerRef.current?.replaceChildren()
    }
    lastTimeRef.current = current
    const ms = current * 1000
    const layer = layerRef.current
    if (!layer) return
    const maxLanes = settings.mode === 'lite' ? 3 : Math.max(2, Math.round(LANES * settings.area))
    while (cursorRef.current < items.length && items[cursorRef.current].timeMs <= ms) {
      const item = items[cursorRef.current]
      cursorRef.current++
      if (ms - item.timeMs > 4000) continue
      const now = performance.now()
      if (settings.mode === 'lite' && now - lastSpawnAtRef.current < 420) continue
      lastSpawnAtRef.current = now
      spawn(layer, item, laneFreeRef.current, maxLanes, settings)
    }
  }, [current, items, settings])

  useEffect(() => () => layerRef.current?.replaceChildren(), [items])

  return (
    <div
      ref={layerRef}
      className="mp-danmaku"
      style={{
        height: `${Math.round(settings.area * 100)}%`,
        opacity: settings.opacity,
      }}
    />
  )
}

function spawn(
  layer: HTMLDivElement,
  item: DanmakuItem,
  laneFree: number[],
  maxLanes: number,
  settings: DanmakuSettings,
) {
  const now = performance.now()
  let lane = 0
  let best = laneFree[0]
  for (let i = 1; i < maxLanes; i++) {
    if (laneFree[i] < best) { best = laneFree[i]; lane = i }
  }
  laneFree[lane] = now + 1150
  const el = document.createElement('span')
  el.className = 'mp-danmaku-item'
  el.textContent = item.content
  el.style.color = item.color
  el.style.fontSize = `${settings.fontSize}px`
  el.style.top = `${4 + (lane / Math.max(1, maxLanes)) * 88}%`
  el.style.animationDuration = `${settings.speed}s`
  el.style.setProperty('--dm-dist', `-${layer.clientWidth + 140}px`)
  el.addEventListener('animationend', () => el.remove())
  layer.appendChild(el)
}

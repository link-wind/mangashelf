import { useCallback, useEffect, useRef, useState } from 'react'
import Hls from 'hls.js'

export interface DanmakuItem {
  id: string
  timeMs: number
  content: string
  color: string
}

interface PlayerProps {
  src: string | null
  kind: 'hls' | 'mp4' | null
  resumeMs?: number
  poster?: string
  /** 解析中/失败等覆盖层（由调用方提供） */
  overlay?: React.ReactNode
  onProgress?: (positionMs: number, durationMs: number) => void
  onEnded?: () => void
  onFatal?: () => void
  danmaku?: {
    items: DanmakuItem[]
    enabled: boolean
    sending?: boolean
    onToggle: () => void
    onSend: (content: string, color: string, timeMs: number) => Promise<void>
  }
}

const SPEEDS = [1, 1.25, 1.5, 2, 0.75, 0.5]
const LANES = 7

function fmt(sec: number): string {
  if (!Number.isFinite(sec) || sec < 0) return '00:00'
  const s = Math.floor(sec)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const ss = String(s % 60).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${String(m).padStart(2, '0')}:${ss}`
}

/**
 * 自定义播放器（M13，参照 gugu/哔哩哔哩皮肤）：HLS/MP4、进度条、倍速、
 * 全屏、键盘快捷键与弹幕层。调用方通过 overlay 提供解析中/失败覆盖层。
 */
export default function Player({ src, kind, resumeMs = 0, poster, overlay,
                                onProgress, onEnded, onFatal, danmaku }: PlayerProps) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const shellRef = useRef<HTMLDivElement>(null)
  const hlsRef = useRef<Hls | null>(null)
  const [playing, setPlaying] = useState(false)
  const [current, setCurrent] = useState(0)
  const [duration, setDuration] = useState(0)
  const [buffered, setBuffered] = useState(0)
  const [speedIdx, setSpeedIdx] = useState(0)
  const [controlsVisible, setControlsVisible] = useState(true)
  const [fullscreen, setFullscreen] = useState(false)
  const hideTimer = useRef<number | null>(null)

  // 加载媒体源（HLS 用 hls.js，Safari 原生回退）
  useEffect(() => {
    const v = videoRef.current
    if (!v || !src) return
    let hls: Hls | null = null
    if (kind === 'hls' && Hls.isSupported()) {
      hls = new Hls({ enableWorker: true })
      hlsRef.current = hls
      hls.loadSource(src)
      hls.attachMedia(v)
      hls.on(Hls.Events.ERROR, (_e, data) => {
        if (!data.fatal) return
        onFatal?.()
      })
    } else {
      v.src = src
    }
    const tryPlay = () => { void v.play().catch(() => { /* 自动播放被拦截，等用户点击 */ }) }
    v.addEventListener('loadeddata', tryPlay, { once: true })
    return () => {
      v.removeEventListener('loadeddata', tryPlay)
      hls?.destroy()
      hlsRef.current = null
      v.removeAttribute('src')
      v.load()
    }
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

  // 进度与缓冲
  useEffect(() => {
    const v = videoRef.current
    if (!v) return
    let lastSave = 0
    const onTime = () => {
      setCurrent(v.currentTime)
      if (v.buffered.length > 0) setBuffered(v.buffered.end(v.buffered.length - 1))
      const now = Date.now()
      if (now - lastSave > 5000) {
        lastSave = now
        onProgress?.(Math.floor(v.currentTime * 1000), Math.floor((v.duration || 0) * 1000))
      }
    }
    const onMeta = () => setDuration(v.duration || 0)
    const onPlay = () => setPlaying(true)
    const onPause = () => {
      setPlaying(false)
      if (v.currentTime > 0) onProgress?.(Math.floor(v.currentTime * 1000), Math.floor((v.duration || 0) * 1000))
    }
    v.addEventListener('timeupdate', onTime)
    v.addEventListener('durationchange', onMeta)
    v.addEventListener('play', onPlay)
    v.addEventListener('pause', onPause)
    v.addEventListener('ended', () => onEnded?.())
    return () => {
      v.removeEventListener('timeupdate', onTime)
      v.removeEventListener('durationchange', onMeta)
      v.removeEventListener('play', onPlay)
      v.removeEventListener('pause', onPause)
      if (v.currentTime > 0) onProgress?.(Math.floor(v.currentTime * 1000), Math.floor((v.duration || 0) * 1000))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [src])

  // 全屏状态同步
  useEffect(() => {
    const onFs = () => setFullscreen(!!document.fullscreenElement)
    document.addEventListener('fullscreenchange', onFs)
    return () => document.removeEventListener('fullscreenchange', onFs)
  }, [])

  const poke = useCallback(() => {
    setControlsVisible(true)
    if (hideTimer.current) window.clearTimeout(hideTimer.current)
    hideTimer.current = window.setTimeout(() => {
      if (!videoRef.current?.paused) setControlsVisible(false)
    }, 2600)
  }, [])

  function togglePlay() {
    const v = videoRef.current
    if (!v) return
    if (v.paused) void v.play().catch(() => {})
    else v.pause()
  }

  function seekTo(sec: number) {
    const v = videoRef.current
    if (!v) return
    v.currentTime = Math.max(0, Math.min(sec, v.duration || sec))
  }

  function cycleSpeed() {
    const v = videoRef.current
    if (!v) return
    const next = (speedIdx + 1) % SPEEDS.length
    setSpeedIdx(next)
    v.playbackRate = SPEEDS[next]
  }

  function toggleFullscreen() {
    const el = shellRef.current
    if (!el) return
    if (document.fullscreenElement) void document.exitFullscreen()
    else void el.requestFullscreen().catch(() => {})
  }

  // 键盘快捷键（聚焦在播放器内生效）
  useEffect(() => {
    const v = videoRef.current
    const el = shellRef.current
    if (!v || !el) return
    const onKey = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return
      if (e.code === 'Space') { e.preventDefault(); togglePlay() }
      else if (e.code === 'ArrowLeft') { e.preventDefault(); seekTo(v.currentTime - 5) }
      else if (e.code === 'ArrowRight') { e.preventDefault(); seekTo(v.currentTime + 5) }
      else if (e.key === 'f' || e.key === 'F') toggleFullscreen()
    }
    el.addEventListener('keydown', onKey)
    return () => el.removeEventListener('keydown', onKey)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const pct = duration > 0 ? (current / duration) * 100 : 0
  const bufPct = duration > 0 ? (buffered / duration) * 100 : 0

  return (
    <div ref={shellRef} className={'ms-player' + (controlsVisible ? ' show-ui' : '')}
         tabIndex={0} onMouseMove={poke} onMouseLeave={() => playing && setControlsVisible(false)}>
      <video ref={videoRef} poster={poster} playsInline
             onClick={togglePlay} onDoubleClick={toggleFullscreen} />

      {danmaku?.enabled && src && (
        <DanmakuLayer video={videoRef.current} items={danmaku.items} current={current} />
      )}

      {!playing && src && !overlay && (
        <button className="mp-bigplay" onClick={togglePlay} aria-label="播放">
          <svg viewBox="0 0 24 24" width="30" height="30"><path fill="currentColor" d="M8 5v14l11-7z" /></svg>
        </button>
      )}

      {overlay}

      <div className="mp-controls">
        <div className="mp-progress" onClick={e => {
          const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
          seekTo(((e.clientX - rect.left) / rect.width) * (duration || 0))
        }}>
          <div className="mp-buffer" style={{ width: `${bufPct}%` }} />
          <div className="mp-played" style={{ width: `${pct}%` }} />
          <span className="mp-knob" style={{ left: `${pct}%` }} />
        </div>
        <div className="mp-bar">
          <button className="mp-icon" onClick={togglePlay} aria-label={playing ? '暂停' : '播放'}>
            {playing
              ? <svg viewBox="0 0 24 24" width="20" height="20"><path fill="currentColor" d="M6 5h4v14H6zM14 5h4v14h-4z" /></svg>
              : <svg viewBox="0 0 24 24" width="20" height="20"><path fill="currentColor" d="M8 5v14l11-7z" /></svg>}
          </button>
          <span className="mp-time">{fmt(current)} / {fmt(duration)}</span>
          <span className="mp-spacer" />
          <button className="mp-icon mp-text" onClick={cycleSpeed} title="播放速度">{SPEEDS[speedIdx]}x</button>
          {danmaku && (
            <button className={'mp-icon mp-text' + (danmaku.enabled ? ' on' : '')}
                    onClick={danmaku.onToggle} title="弹幕开关">弹</button>
          )}
          <button className="mp-icon" onClick={toggleFullscreen} aria-label="全屏">
            <svg viewBox="0 0 24 24" width="20" height="20">
              <path fill="currentColor" d={fullscreen
                ? 'M6 6h4v2H8v2H6zm8 0h4v4h-2V8h-2zm-8 8h2v2h2v2H6zm10 2h-2v2h-4v-2h2v-2h4z'
                : 'M4 4h6v2H6v4H4zm10 0h6v6h-2V6h-4zM4 14h2v4h4v2H4zm14 0h2v6h-6v-2h4z'} />
            </svg>
          </button>
        </div>
        {danmaku?.enabled && (
          <DanmakuSender sending={danmaku.sending} current={current} onSend={danmaku.onSend} />
        )}
      </div>
    </div>
  )
}

/** 弹幕层：按时间轴滚动，最多 7 条轨道，seek 后退时重置。 */
function DanmakuLayer({ video, items, current }: {
  video: HTMLVideoElement | null
  items: DanmakuItem[]
  current: number
}) {
  const layerRef = useRef<HTMLDivElement>(null)
  const cursorRef = useRef(0)
  const lastTimeRef = useRef(0)
  const laneFreeRef = useRef<number[]>(new Array(LANES).fill(0))

  useEffect(() => {
    // 跳转/回退：重置游标
    if (current < lastTimeRef.current - 2) {
      cursorRef.current = 0
      laneFreeRef.current = new Array(LANES).fill(0)
      layerRef.current?.replaceChildren()
    }
    lastTimeRef.current = current
    const ms = current * 1000
    const layer = layerRef.current
    if (!layer) return
    while (cursorRef.current < items.length && items[cursorRef.current].timeMs <= ms) {
      const item = items[cursorRef.current]
      cursorRef.current++
      // 丢弃迟到太久的（跳转后）
      if (ms - item.timeMs > 4000) continue
      spawn(layer, item, laneFreeRef.current)
    }
  }, [current, items])

  // 换集/关闭时清空
  useEffect(() => () => layerRef.current?.replaceChildren(), [items])

  return <div ref={layerRef} className="mp-danmaku" />
}

function spawn(layer: HTMLDivElement, item: DanmakuItem, laneFree: number[]) {
  const now = performance.now()
  // 选择最空闲的轨道；全部占用时退化为随机
  let lane = 0
  let best = laneFree[0]
  for (let i = 1; i < LANES; i++) {
    if (laneFree[i] < best) { best = laneFree[i]; lane = i }
  }
  laneFree[lane] = now + 1200
  const el = document.createElement('span')
  el.className = 'mp-danmaku-item'
  el.textContent = item.content
  el.style.color = item.color
  el.style.top = `${6 + lane * 12}%`
  el.style.animationDuration = '8s'
  // 位移按播放器宽度计算，速度与窗口宽度无关
  el.style.setProperty('--dm-dist', `-${layer.clientWidth + 120}px`)
  el.addEventListener('animationend', () => el.remove())
  layer.appendChild(el)
}

/** 弹幕发送条：输入 + 颜色选择，发送到当前时间点。 */
function DanmakuSender({ sending, current, onSend }: {
  sending?: boolean
  current: number
  onSend: (content: string, color: string, timeMs: number) => Promise<void>
}) {
  const [text, setText] = useState('')
  const [color, setColor] = useState('#ffffff')
  const COLORS = ['#ffffff', '#00aeec', '#ff9500', '#ff5c8a', '#7c5cff', '#00c48c']

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    const t = text.trim()
    if (!t) return
    await onSend(t, color, Math.floor(current * 1000))
    setText('')
  }

  return (
    <form className="mp-danmaku-send" onSubmit={submit}>
      <span className="mp-colors">
        {COLORS.map(c => (
          <button type="button" key={c} className={'mp-color' + (c === color ? ' on' : '')}
                  style={{ background: c }} onClick={() => setColor(c)} aria-label={'颜色 ' + c} />
        ))}
      </span>
      <input value={text} maxLength={200} placeholder="发一条友善的弹幕…"
             onChange={e => setText(e.target.value)} />
      <button type="submit" className="mp-send" disabled={sending || !text.trim()}>发送</button>
    </form>
  )
}

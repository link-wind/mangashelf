import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ApiError } from './api'
import { useAuth } from './auth'

export interface PlaybackStatus {
  jobId: string | null
  jobState: string
  playUrl: string | null
  playKind: string | null
  errorCode: string | null
  errorMessage: string | null
}

interface EpisodeProgress {
  episodeId: string
  targetKind: 'catalog' | 'work'
  targetId: string
  ordinal: number
  positionMs: number
  durationMs: number | null
  version: number
  updatedAt: string | null
}

/** 渐进退避轮询间隔：避免任务几十毫秒完成时前端固定死等 2 秒。 */
const POLL_DELAYS_MS = [250, 350, 500, 800, 1200, 1500]
const MAX_MS = 60_000
const PREFETCH_DELAY_MS = 1500

/**
 * 外部集播放（separate-favorites-and-history）：
 * - GET 直链秒回 → POST 同步秒解直出 → 渐进退避轮询兜底；
 * - 切集时通过 requestSeq 隔离竞态，避免旧集结果覆盖新集；
 * - 当前集获得直链后在后台静默预热下一集，提升连播与切集流畅度。
 */
export function useEpisodePlayback(episodeId: string | undefined, nextEpisodeId?: string) {
  const { me } = useAuth()
  const meRef = useRef(me)
  meRef.current = me
  const [status, setStatus] = useState<PlaybackStatus | null>(null)
  const [resumeMs, setResumeMs] = useState(0)
  const [notice, setNotice] = useState('')
  const versionRef = useRef(0)
  const pendingRef = useRef<number | null>(null)
  const savingRef = useRef(false)
  const lastConflictRef = useRef(0)
  const currentEpisodeRef = useRef<string | undefined>(episodeId)
  const requestSeqRef = useRef(0)
  const prefetchedRef = useRef<Set<string>>(new Set())

  const resolve = useCallback(async (id: string) => {
    const seq = ++requestSeqRef.current
    const isStale = () => requestSeqRef.current !== seq || currentEpisodeRef.current !== id
    setStatus(null)
    try {
      let st = await api.get<PlaybackStatus>(`/api/v1/external-episodes/${id}/resolve`)
      if (isStale()) return
      if (!st.playUrl) {
        // POST 响应可能直接携带 API 层同步秒解出的 playUrl（如 7sefun B 线），无需再轮询
        st = await api.post<PlaybackStatus>(`/api/v1/external-episodes/${id}/resolve`, {})
        if (isStale()) return
        if (!st.playUrl && st.jobState !== 'FAILED' && !st.errorCode) {
          const started = Date.now()
          let attempt = 0
          for (;;) {
            const delay = POLL_DELAYS_MS[Math.min(attempt, POLL_DELAYS_MS.length - 1)]
            attempt++
            await new Promise(r => setTimeout(r, delay))
            if (isStale()) return
            if (Date.now() - started > MAX_MS) {
              st = { jobId: null, jobState: 'FAILED', playUrl: null, playKind: null,
                     errorCode: 'RESOLVE_TIMEOUT', errorMessage: '解析超时，请稍后重试' }
              break
            }
            st = await api.get<PlaybackStatus>(`/api/v1/external-episodes/${id}/resolve`)
            if (isStale()) return
            if (st.playUrl || st.jobState === 'FAILED' || st.errorCode) break
          }
        }
      }
      if (!isStale()) setStatus(st)
    } catch (e) {
      if (!isStale()) {
        setStatus({ jobId: null, jobState: 'FAILED', playUrl: null, playKind: null,
                    errorCode: 'RESOLVE_ERROR',
                    errorMessage: e instanceof ApiError ? e.message : '解析请求失败' })
      }
    }
  }, [])

  useEffect(() => {
    currentEpisodeRef.current = episodeId
    if (!episodeId) return
    let cancelled = false
    setResumeMs(0)
    versionRef.current = 0
    pendingRef.current = null
    void resolve(episodeId)
    void api.get<EpisodeProgress>(`/api/v1/me/episode-progress?episodeId=${episodeId}`)
      .then(p => {
        if (cancelled || currentEpisodeRef.current !== episodeId) return
        versionRef.current = p.version
        setResumeMs(p.positionMs)
      })
      .catch(() => { /* 进度不可用不影响播放 */ })
    return () => { cancelled = true }
  }, [episodeId, resolve])

  // 当前集已起播后，延迟静默预热下一集直链，连播/切下一集直接命中缓存
  useEffect(() => {
    if (!status?.playUrl || !nextEpisodeId || nextEpisodeId === episodeId) return
    if (prefetchedRef.current.has(nextEpisodeId)) return
    const timer = window.setTimeout(() => {
      prefetchedRef.current.add(nextEpisodeId)
      void api.post(`/api/v1/external-episodes/${nextEpisodeId}/resolve`, {}).catch(() => {
        // 预热失败不影响当前播放，允许后续重试
        prefetchedRef.current.delete(nextEpisodeId)
      })
    }, PREFETCH_DELAY_MS)
    return () => window.clearTimeout(timer)
  }, [status?.playUrl, episodeId, nextEpisodeId])

  const notifyConflict = useCallback(() => {
    const now = Date.now()
    if (now - lastConflictRef.current < 30_000) return
    lastConflictRef.current = now
    setNotice('已同步最新观看进度')
    window.setTimeout(() => setNotice(''), 4000)
  }, [])

  const flush = useCallback(() => {
    const targetId = episodeId
    if (savingRef.current || !targetId) return
    savingRef.current = true
    void (async () => {
      try {
        while (pendingRef.current !== null && currentEpisodeRef.current === targetId) {
          const positionMs = pendingRef.current
          pendingRef.current = null
          try {
            const res = await api.put<EpisodeProgress>('/api/v1/me/episode-progress', {
              episodeId: targetId, positionMs, durationMs: null,
              expectedVersion: versionRef.current,
            })
            if (currentEpisodeRef.current === targetId) versionRef.current = res.version
          } catch (e) {
            if (e instanceof ApiError && e.code === 'PROGRESS_CONFLICT') {
              const cur = (e.details?.current ?? {}) as { version?: number }
              if (typeof cur.version === 'number' && currentEpisodeRef.current === targetId) {
                versionRef.current = cur.version
              }
              notifyConflict()
            }
          }
        }
      } finally { savingRef.current = false }
    })()
  }, [episodeId, notifyConflict])

  const saveProgress = useCallback((positionMs: number) => {
    if (!meRef.current) return // 访客不保存进度（DESIGN 3.1：进度是私有数据）
    pendingRef.current = positionMs
    flush()
  }, [flush])

  const resolving = !status || (!status.playUrl && !status.errorCode
    && (status.jobState === 'QUEUED' || status.jobState === 'RUNNING' || status.jobState === 'NONE'))

  return { status, resumeMs, notice, resolve, saveProgress, resolving, failed: !!status?.errorCode }
}

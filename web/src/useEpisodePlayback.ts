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

const POLL_MS = 2000
const MAX_MS = 90_000

/**
 * 外部集播放（separate-favorites-and-history）：解析轮询 + 按集版本化进度。
 * 浏览态与作品态共用，进度由后端按剧集归属路由到目录或作品进度表。
 */
export function useEpisodePlayback(episodeId: string | undefined) {
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

  const resolve = useCallback(async (id: string) => {
    setStatus(null)
    try {
      let st = await api.get<PlaybackStatus>(`/api/v1/external-episodes/${id}/resolve`)
      if (!st.playUrl) {
        // 访客也可触发公开剧集的解析（后端以系统 owner 承载任务）；进度仍仅登录用户保存。
        await api.post(`/api/v1/external-episodes/${id}/resolve`, {})
        const started = Date.now()
        for (;;) {
          if (Date.now() - started > MAX_MS) {
            st = { jobId: null, jobState: 'FAILED', playUrl: null, playKind: null,
                   errorCode: 'RESOLVE_TIMEOUT', errorMessage: '解析超时，请稍后重试' }
            break
          }
          st = await api.get<PlaybackStatus>(`/api/v1/external-episodes/${id}/resolve`)
          if (st.playUrl || st.jobState === 'FAILED' || st.errorCode) break
          await new Promise(r => setTimeout(r, POLL_MS))
        }
      }
      setStatus(st)
    } catch (e) {
      setStatus({ jobId: null, jobState: 'FAILED', playUrl: null, playKind: null,
                  errorCode: 'RESOLVE_ERROR',
                  errorMessage: e instanceof ApiError ? e.message : '解析请求失败' })
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

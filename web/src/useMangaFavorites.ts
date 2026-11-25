import { useCallback, useEffect, useState } from 'react'
import { api } from './api'
import { useAuth } from './auth'
import { useToast } from './components/Toast'

export function mangaFavKey(source: string | null | undefined, mangaId: string | null | undefined): string {
  return `${source ?? ''}:${mangaId ?? ''}`
}

/** 共享漫画收藏状态 hook：加载当前用户已收藏的外部漫画集合，并提供一键收藏/取消收藏 + Toast 反馈。 */
export function useMangaFavorites() {
  const { me } = useAuth()
  const { showToast } = useToast()
  const [favKeys, setFavKeys] = useState<Set<string>>(new Set())
  const [busyKey, setBusyKey] = useState<string | null>(null)

  useEffect(() => {
    if (!me) {
      setFavKeys(new Set())
      return
    }
    void api.get<{ source: string | null; mangaId: string | null }[]>('/api/v1/me/favorites?type=manga')
      .then(list => {
        const next = new Set<string>()
        for (const f of list) {
          if (f.source && f.mangaId) next.add(mangaFavKey(f.source, f.mangaId))
        }
        setFavKeys(next)
      })
      .catch(() => { /* 收藏状态不可用不影响公开浏览 */ })
  }, [me])

  const isFavorited = useCallback((source: string | null | undefined, mangaId: string | null | undefined) => {
    if (!source || !mangaId) return false
    return favKeys.has(mangaFavKey(source, mangaId))
  }, [favKeys])

  const toggle = useCallback(async (source: string | null | undefined, mangaId: string | null | undefined, title: string) => {
    if (!source || !mangaId || source === 'local') return
    if (!me) {
      showToast('请先登录后再收藏漫画', 'info')
      return
    }
    const key = mangaFavKey(source, mangaId)
    const exists = favKeys.has(key)
    const base = `/api/v1/me/favorites/manga?source=${encodeURIComponent(source)}&mangaId=${encodeURIComponent(mangaId)}`
    setBusyKey(key)
    try {
      if (exists) {
        await api.delete(base)
        setFavKeys(prev => {
          const next = new Set(prev)
          next.delete(key)
          return next
        })
        showToast(`已取消收藏「${title}」`, 'info')
      } else {
        await api.put(`${base}&title=${encodeURIComponent(title)}`)
        setFavKeys(prev => new Set(prev).add(key))
        showToast(`已收藏「${title}」`, 'success')
      }
    } catch (e) {
      showToast(e instanceof Error ? e.message : '收藏操作失败', 'error')
    } finally {
      setBusyKey(null)
    }
  }, [favKeys, me, showToast])

  return { favKeys, busyKey, isFavorited, toggle }
}

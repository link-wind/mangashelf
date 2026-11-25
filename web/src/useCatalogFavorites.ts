import { useCallback, useEffect, useState } from 'react'
import { api } from './api'
import { useAuth } from './auth'
import { useToast } from './components/Toast'

/** 共享番剧收藏状态 hook：加载当前用户已收藏的目录 ID 集合并提供一键收藏/取消收藏 + Toast 反馈。 */
export function useCatalogFavorites() {
  const { me } = useAuth()
  const { showToast } = useToast()
  const [favIds, setFavIds] = useState<Set<string>>(new Set())
  const [busyId, setBusyId] = useState<string | null>(null)

  useEffect(() => {
    if (!me) {
      setFavIds(new Set())
      return
    }
    void api.get<{ catalogItemId: string | null }[]>('/api/v1/me/favorites?type=catalog')
      .then(list => setFavIds(new Set(list.map(f => f.catalogItemId).filter((x): x is string => Boolean(x)))))
      .catch(() => { /* 收藏列表不可用不影响公开浏览 */ })
  }, [me])

  const toggle = useCallback(async (catalogItemId: string | null | undefined, title: string) => {
    if (!catalogItemId) {
      showToast('该条目暂未收录到站内资源目录', 'info')
      return
    }
    if (!me) {
      showToast('请先登录后再收藏追番', 'info')
      return
    }
    const exists = favIds.has(catalogItemId)
    setBusyId(catalogItemId)
    try {
      if (exists) {
        await api.delete(`/api/v1/me/favorites/catalog/${catalogItemId}`)
        setFavIds(prev => {
          const next = new Set(prev)
          next.delete(catalogItemId)
          return next
        })
        showToast(`已取消收藏「${title}」`, 'info')
      } else {
        await api.put(`/api/v1/me/favorites/catalog/${catalogItemId}`)
        setFavIds(prev => new Set(prev).add(catalogItemId))
        showToast(`已追番收藏「${title}」`, 'success')
      }
    } catch (e) {
      showToast(e instanceof Error ? e.message : '收藏操作失败', 'error')
    } finally {
      setBusyId(null)
    }
  }, [favIds, me, showToast])

  return { favIds, busyId, toggle }
}

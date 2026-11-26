import { Command } from 'cmdk'
import {
  BookOpen, Calendar, Clock, Compass, Film, Moon, Search,
  Settings, Sparkles, Sun, X,
} from 'lucide-react'
import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../../api'
import type { Me } from '../../auth'
import { hdCover } from '../../externalManga'
import CoverImage from '../CoverImage'

const RECENT_KEY = 'ms-recent-searches'

interface QuickAnime {
  id: string
  name: string
  coverUrl: string
  remarks: string
  bangumiSubjectId?: number | null
}

interface QuickManga {
  title: string
  coverUrl: string | null
  primarySource: string
  primaryId: string
  workId: string | null
  remarks: string
}

export function loadRecentSearches(): string[] {
  try {
    const raw = localStorage.getItem(RECENT_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === 'string').slice(0, 8) : []
  } catch {
    return []
  }
}

export function pushRecentSearch(term: string) {
  const clean = term.trim()
  if (!clean) return
  try {
    const prev = loadRecentSearches().filter(x => x !== clean)
    localStorage.setItem(RECENT_KEY, JSON.stringify([clean, ...prev].slice(0, 8)))
  } catch { /* ignore */ }
}

export function clearRecentSearches() {
  try {
    localStorage.removeItem(RECENT_KEY)
  } catch { /* ignore */ }
}

interface CommandPaletteProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  me: Me | null
  theme: 'dark' | 'light'
  onToggleTheme: () => void
}

/** ⌘K / Ctrl+K 全局命令与即时快搜面板（基于 cmdk + motion）。 */
export default function CommandPalette({
  open, onOpenChange, me, theme, onToggleTheme,
}: CommandPaletteProps) {
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const [recent, setRecent] = useState<string[]>([])
  const [animeHits, setAnimeHits] = useState<QuickAnime[]>([])
  const [mangaHits, setMangaHits] = useState<QuickManga[]>([])
  const [searching, setSearching] = useState(false)

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        onOpenChange(!open)
      } else if (e.key === 'Escape' && open) {
        onOpenChange(false)
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [open, onOpenChange])

  useEffect(() => {
    if (open) {
      setRecent(loadRecentSearches())
    } else {
      setQuery('')
      setAnimeHits([])
      setMangaHits([])
    }
  }, [open])

  useEffect(() => {
    const trimmed = query.trim()
    if (!open || trimmed.length < 1) {
      setAnimeHits([])
      setMangaHits([])
      setSearching(false)
      return
    }
    let cancelled = false
    setSearching(true)
    const timer = window.setTimeout(() => {
      void Promise.all([
        api.get<{ items: QuickAnime[] }>(`/api/v1/catalog-items?limit=5&page=1&q=${encodeURIComponent(trimmed)}`)
          .then(r => r.items ?? [])
          .catch(() => [] as QuickAnime[]),
        api.get<{ items: QuickManga[] }>(`/api/v1/manga-discovery/search?q=${encodeURIComponent(trimmed)}&source=all&limit=5`)
          .then(r => r.items ?? [])
          .catch(() => [] as QuickManga[]),
      ]).then(([a, m]) => {
        if (cancelled) return
        setAnimeHits(a)
        setMangaHits(m)
      }).finally(() => {
        if (!cancelled) setSearching(false)
      })
    }, 260)
    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [open, query])

  function go(path: string, saveTerm?: string) {
    if (saveTerm) pushRecentSearch(saveTerm)
    onOpenChange(false)
    navigate(path)
  }

  const trimmed = query.trim()

  return (
    <AnimatePresence>
      {open && (
        <motion.div
          className="cmdk-backdrop"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.16 }}
          onClick={() => onOpenChange(false)}
        >
          <motion.div
            className="cmdk-dialog"
            initial={{ opacity: 0, scale: 0.96, y: -10 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.97, y: -8 }}
            transition={{ type: 'spring', stiffness: 460, damping: 32 }}
            onClick={e => e.stopPropagation()}
          >
            <Command label="全局快速搜索与跳转" shouldFilter={false}>
              <div className="cmdk-input-row">
                <Search size={16} className="cmdk-search-ico" />
                <Command.Input
                  value={query}
                  onValueChange={setQuery}
                  placeholder="搜索番剧、漫画，或快速前往任意页面…"
                  autoFocus
                />
                {query && (
                  <button type="button" className="cmdk-clear" aria-label="清空" onClick={() => setQuery('')}>
                    <X size={14} />
                  </button>
                )}
                <kbd className="cmdk-esc">ESC</kbd>
              </div>

              <Command.List className="cmdk-list">
                {trimmed && (
                  <Command.Group heading="全站检索">
                    <Command.Item
                      value={`search:${trimmed}`}
                      onSelect={() => go(`/search?q=${encodeURIComponent(trimmed)}`, trimmed)}
                    >
                      <Search size={15} className="cmdk-item-ico is-cyan" />
                      <span className="cmdk-item-title">在全站中搜索「{trimmed}」</span>
                      <span className="cmdk-item-hint">回车直达</span>
                    </Command.Item>
                  </Command.Group>
                )}

                {searching && trimmed && (
                  <div className="cmdk-loading">正在联想番剧与漫画…</div>
                )}

                {animeHits.length > 0 && (
                  <Command.Group heading="番剧">
                    {animeHits.map(a => (
                      <Command.Item
                        key={`a-${a.id}`}
                        value={`anime:${a.id}:${a.name}`}
                        onSelect={() => go(`/catalog/${a.id}`, trimmed)}
                      >
                        <div className="cmdk-thumb">
                          <CoverImage
                            src={a.bangumiSubjectId ? `/api/v1/bangumi-covers/${a.bangumiSubjectId}?size=large` : a.coverUrl}
                            alt={a.name}
                          />
                        </div>
                        <div className="cmdk-hit-meta">
                          <strong>{a.name}</strong>
                          <span>{a.remarks || '动画'}</span>
                        </div>
                        <span className="cmdk-kind-pill is-cyan">动画</span>
                      </Command.Item>
                    ))}
                  </Command.Group>
                )}

                {mangaHits.length > 0 && (
                  <Command.Group heading="漫画">
                    {mangaHits.map(m => {
                      const href = m.primarySource === 'local' || m.workId
                        ? `/manga/${m.workId ?? m.primaryId}`
                        : `/manga-ext/${m.primaryId}?${new URLSearchParams({ lang: 'all', source: m.primarySource, title: m.title })}`
                      return (
                        <Command.Item
                          key={`m-${m.primarySource}:${m.primaryId}`}
                          value={`manga:${m.primarySource}:${m.primaryId}:${m.title}`}
                          onSelect={() => go(href, trimmed)}
                        >
                          <div className="cmdk-thumb">
                            <CoverImage src={hdCover(m.coverUrl)} alt={m.title} />
                          </div>
                          <div className="cmdk-hit-meta">
                            <strong>{m.title}</strong>
                            <span>{m.remarks || '漫画'}</span>
                          </div>
                          <span className="cmdk-kind-pill is-rose">漫画</span>
                        </Command.Item>
                      )
                    })}
                  </Command.Group>
                )}

                {!trimmed && recent.length > 0 && (
                  <Command.Group heading="最近搜索">
                    {recent.slice(0, 5).map(term => (
                      <Command.Item
                        key={`r-${term}`}
                        value={`recent:${term}`}
                        onSelect={() => go(`/search?q=${encodeURIComponent(term)}`, term)}
                      >
                        <Clock size={14} className="cmdk-item-ico" />
                        <span className="cmdk-item-title">{term}</span>
                        <span className="cmdk-item-hint">搜索</span>
                      </Command.Item>
                    ))}
                  </Command.Group>
                )}

                {!trimmed && (
                  <Command.Group heading="快速前往">
                    <Command.Item value="nav:home" onSelect={() => go('/')}>
                      <Film size={15} className="cmdk-item-ico is-cyan" />
                      <span className="cmdk-item-title">番剧首页</span>
                      <span className="cmdk-item-hint">/</span>
                    </Command.Item>
                    <Command.Item value="nav:manga" onSelect={() => go('/manga')}>
                      <BookOpen size={15} className="cmdk-item-ico is-rose" />
                      <span className="cmdk-item-title">漫画首页</span>
                      <span className="cmdk-item-hint">/manga</span>
                    </Command.Item>
                    <Command.Item value="nav:index" onSelect={() => go('/index')}>
                      <Compass size={15} className="cmdk-item-ico is-cyan" />
                      <span className="cmdk-item-title">全站索引（番剧 / 漫画）</span>
                      <span className="cmdk-item-hint">/index</span>
                    </Command.Item>
                    <Command.Item value="nav:schedule" onSelect={() => go('/schedule')}>
                      <Calendar size={15} className="cmdk-item-ico is-cyan" />
                      <span className="cmdk-item-title">新番时刻表</span>
                      <span className="cmdk-item-hint">/schedule</span>
                    </Command.Item>
                    {me && (
                      <>
                        <Command.Item value="nav:me-anime" onSelect={() => go('/me?space=anime')}>
                          <Sparkles size={15} className="cmdk-item-ico is-cyan" />
                          <span className="cmdk-item-title">个人空间 · 动画空间</span>
                          <span className="cmdk-item-hint">/me</span>
                        </Command.Item>
                        <Command.Item value="nav:me-manga" onSelect={() => go('/me?space=manga')}>
                          <Sparkles size={15} className="cmdk-item-ico is-rose" />
                          <span className="cmdk-item-title">个人空间 · 漫画空间</span>
                          <span className="cmdk-item-hint">/me</span>
                        </Command.Item>
                        <Command.Item value="nav:settings" onSelect={() => go('/settings')}>
                          <Settings size={15} className="cmdk-item-ico" />
                          <span className="cmdk-item-title">账号与设置</span>
                          <span className="cmdk-item-hint">/settings</span>
                        </Command.Item>
                      </>
                    )}
                    <Command.Item
                      value="action:theme"
                      onSelect={() => { onToggleTheme(); onOpenChange(false) }}
                    >
                      {theme === 'dark' ? <Sun size={15} className="cmdk-item-ico" /> : <Moon size={15} className="cmdk-item-ico" />}
                      <span className="cmdk-item-title">{theme === 'dark' ? '切换为浅色主题' : '切换为深色主题'}</span>
                      <span className="cmdk-item-hint">外观</span>
                    </Command.Item>
                  </Command.Group>
                )}
              </Command.List>
            </Command>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  )
}

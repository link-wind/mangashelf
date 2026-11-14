import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api'
import { hdCover } from '../externalManga'

interface MangaCard {
  id: string; title: string; description: string; visibility: string
  chapterCount: number; latestOrdinal: number | null; latestTitle: string | null
  coverUrl: string | null; updatedAt: string
}

interface SourceInfo {
  id: string; name: string; language: string; capabilities: string[]; note: string
}
interface RankItem {
  source: string; id: string; title: string; authors: string[]; coverUrl: string | null
  finished: boolean; latestLabel: string; readable: boolean; externalUrl: string | null
  readSource: string | null; readId: string | null
}
interface DiscoveryItem extends RankItem {
  sourceCount?: number
  sources?: string[]
}
interface SourceSection {
  id: string; name: string; items: DiscoveryItem[]
  sourceId?: string | null; sourceName?: string | null
}
interface DiscoveryResult { rows: SourceSection[]; errors: string[] }

const SOURCE_KEY = 'ms-manga-source'
/** 综合聚合（M25）：跨中文源 RRF 融合榜。 */
const ALL = 'all'
const CACHE_TTL_MS = 10 * 60 * 1000

interface SectionCache { ts: number; sections: SourceSection[]; warnings: string[] }

function readCache(key: string): SectionCache | null {
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return null
    const cached = JSON.parse(raw) as SectionCache
    if (!cached.sections || Date.now() - cached.ts > CACHE_TTL_MS) return null
    return cached
  } catch { return null }
}

function writeCache(key: string, sections: SourceSection[], warnings: string[]) {
  try {
    localStorage.setItem(key, JSON.stringify({ ts: Date.now(), sections, warnings }))
  } catch { /* 超配额时忽略 */ }
}

/**
 * 漫画首页（M24，参照哔哩哔哩漫画/快看）：发现流（源切换 + 轮播 hero +
 * 推荐/榜单分区横排）+ 本地漫画区块。
 */
export default function MangaIndex() {
  return (
    <main className="watch-page">
      <div className="watch-title"><h1>漫画</h1></div>
      <div className="watch-meta">
        <span>正版中文源推荐与榜单 · 本地书库</span>
        <Link to="/manga-ext">外部漫画搜索 →</Link>
        <Link to="/uploads">上传漫画 →</Link>
      </div>

      <MangaDiscovery />
      <LocalManga />
    </main>
  )
}

/** 发现流：源切换 + hero 轮播 + 分区横向卡片行。 */
function MangaDiscovery() {
  const navigate = useNavigate()
  const [sources, setSources] = useState<SourceInfo[]>([])
  const [source, setSource] = useState<string>(() => localStorage.getItem(SOURCE_KEY) ?? ALL)
  const [sections, setSections] = useState<SourceSection[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [warnings, setWarnings] = useState<string[]>([])
  const [heroIdx, setHeroIdx] = useState(0)

  useEffect(() => {
    void (async () => {
      try {
        // 只保留中文源（M25）：MangaDex 等 multi 源不参与首页发现
        const list = (await api.get<SourceInfo[]>('/api/v1/manga-sources'))
          .filter(s => s.capabilities.includes('RANK') && s.language === 'zh')
        setSources(list)
        setSource(prev => {
          if (prev === ALL || list.some(s => s.id === prev)) return prev
          return ALL
        })
      } catch { /* 源列表不可用不影响本地漫画 */ }
    })()
  }, [])

  const load = useCallback(async (src: string) => {
    if (!src) return
    const cacheKey = `ms-manga-cache:${src}`
    const cached = readCache(cacheKey)
    setLoading(true); setError(''); setHeroIdx(0)
    if (cached) {
      // 有缓存先渲染（切换源/刷新首页不白屏），后台再拉最新
      setSections(cached.sections)
      setWarnings(cached.warnings ?? [])
    } else {
      setSections([]); setWarnings([])
    }
    try {
      let sections: SourceSection[]
      let warnings: string[] = []
      if (src === ALL) {
        const result = await api.get<DiscoveryResult>('/api/v1/manga-discovery?limit=12')
        sections = result.rows ?? []
        warnings = result.errors ?? []
      } else {
        sections = await api.get<SourceSection[]>(`/api/v1/manga-sources/${src}/sections?limit=12`)
      }
      setSections(sections)
      setWarnings(warnings)
      writeCache(cacheKey, sections, warnings)
    } catch (e) {
      const message = e instanceof Error ? e.message : '推荐加载失败'
      if (cached) setWarnings([message]) // 保留旧内容，仅提示刷新失败
      else setError(message)
    } finally { setLoading(false) }
  }, [])

  useEffect(() => {
    if (!source) return
    localStorage.setItem(SOURCE_KEY, source)
    void load(source)
  }, [source, load])

  const current = sources.find(s => s.id === source)
  // hero 取第一个有内容的分区前 5 条（综合模式即综合热榜）
  const featuredSection = useMemo(
    () => sections.find(s => s.items.length > 0) ?? null, [sections])
  const featured = useMemo(
    () => (featuredSection ? featuredSection.items.slice(0, 5) : []), [featuredSection])
  const hero = featured[heroIdx] ?? null
  const heroLabel = featuredSection
    ? (featuredSection.sourceName
        ? `${featuredSection.sourceName} · ${featuredSection.name}`
        : `多源聚合 · ${featuredSection.name}`)
    : ''
  const heroSources = hero?.sources ?? []

  // 自动轮播
  useEffect(() => {
    if (featured.length < 2) return
    const timer = window.setInterval(() => setHeroIdx(i => (i + 1) % featured.length), 6000)
    return () => window.clearInterval(timer)
  }, [featured.length, source])

  function open(it: RankItem) {
    if (it.externalUrl && !it.readId) {
      window.open(it.externalUrl, '_blank', 'noopener,noreferrer')
      return
    }
    navigate(`/manga-ext/${it.readId ?? it.id}?lang=all` +
             `&source=${it.readSource ?? it.source}` +
             `&title=${encodeURIComponent(it.title)}`)
  }

  return (
    <section className="manga-discover">
      <div className="discover-head">
        <div className="source-tabs">
          <button className={source === ALL ? 'mini' : 'mini ghost'}
                  onClick={() => setSource(ALL)}>综合</button>
          {sources.map(s => (
            <button key={s.id} className={s.id === source ? 'mini' : 'mini ghost'}
                    onClick={() => setSource(s.id)}>{s.name}</button>
          ))}
        </div>
        <span className="muted" style={{ fontSize: 12 }}>
          {loading && sections.length > 0 ? '更新中…' : (source === ALL
            ? '聚合中文源推荐与榜单（RRF 融合，同作去重）'
            : current?.note ?? '选择数据源查看推荐与榜单')}
        </span>
      </div>

      {error && (
        <div className="discover-error">
          <span className="error">{error}</span>
          <button className="ghost mini" onClick={() => void load(source)}>重试</button>
        </div>
      )}
      {warnings.length > 0 && (
        <p className="muted" style={{ fontSize: 12, margin: '0 0 10px' }}>
          {warnings.join('；')}
        </p>
      )}

      {loading && sections.length === 0 && (
        <>
          <div className="manga-hero-banner skeleton" />
          <p className="muted">加载推荐…</p>
        </>
      )}

      {hero && (
        <div className="manga-hero-banner">
          {/* 交叉淡入：每张待显示的背景独立成层，key 变化触发淡入动画 */}
          <div className="manga-hero-bg" key={hero.source + hero.id}
               style={hero.coverUrl ? { backgroundImage: `url(${hdCover(hero.coverUrl)})` } : undefined} />
          <div className="manga-hero-shade" />
          <div className="manga-hero-inner">
            <button className="manga-hero-cover" onClick={() => open(hero)} aria-label={hero.title}>
              {hero.coverUrl
                ? <img src={hero.coverUrl ?? ""} alt={hero.title} />
                : <span className="cover-initial">{hero.title.slice(0, 1)}</span>}
            </button>
            <div className="manga-hero-info">
              <span className="manga-hero-kicker">{heroLabel}</span>
              <h2>{hero.title}</h2>
              {hero.authors?.length > 0 && (
                <p className="mh-author">{hero.authors.join(' / ')}</p>
              )}
              <div className="mh-chips">
                {hero.latestLabel && <span className="chip">{hero.latestLabel}</span>}
                {hero.finished && <span className="chip">已完结</span>}
                {heroSources.length > 1 && (
                  <span className="chip ghost" title={heroSources.join('、')}>
                    {heroSources.length} 源
                  </span>
                )}
                <span className="chip ghost">{hero.readable ? '站内可读' : hero.readId ? '有站内目录' : '站外阅读'}</span>
              </div>
              <div className="manga-hero-actions">
                <button className="pill" onClick={() => open(hero)}>
                  {hero.readable ? '开始阅读' : hero.readId ? '查看章节' : '前往原站'}
                </button>
              </div>
            </div>
          </div>
          {featured.length > 1 && (
            <div className="manga-hero-thumbs">
              {featured.map((it, i) => (
                <button key={it.source + it.id}
                        className={'manga-hero-thumb' + (i === heroIdx ? ' on' : '')}
                        onClick={() => setHeroIdx(i)} title={it.title}>
                  {it.coverUrl
                    ? <img src={it.coverUrl ?? ""} alt="" loading="lazy" />
                    : <span className="cover-initial">{it.title.slice(0, 1)}</span>}
                  <span className="manga-hero-thumb-title">{it.title}</span>
                </button>
              ))}
            </div>
          )}
        </div>
      )}

      {sections.filter(s => s.items.length > 0).map(section => (
        <SectionRow key={section.id + (section.sourceId ?? '')} section={section}
                    showRank={section.id === 'all' || section.name.includes('榜')}
                    onOpen={open} />
      ))}

      {!loading && !error && sections.length > 0 && sections.every(s => s.items.length === 0) && (
        <p className="muted">该源暂无推荐内容。</p>
      )}
    </section>
  )
}

/** 分区横向卡片行。 */
function SectionRow({ section, showRank, onOpen }: {
  section: SourceSection; showRank: boolean; onOpen: (it: RankItem) => void
}) {
  return (
    <section className="manga-section">
      <div className="section-head">
        <h2>
          {section.name}
          {section.sourceName && (
            <span className="muted" style={{ fontSize: 12, fontWeight: 400, marginLeft: 8 }}>
              {section.sourceName}
            </span>
          )}
        </h2>
        <span className="muted" style={{ fontSize: 12 }}>{section.items.length} 部</span>
      </div>
      <div className="manga-row">
        {section.items.map((it, i) => (
          <button key={it.source + it.id} className="manga-tile" onClick={() => onOpen(it)}
                  title={(it.sources?.length ?? 0) > 1
                    ? `${it.title}（${it.sources!.join('、')}）` : it.title}>
            <div className="mt-cover">
              {it.coverUrl
                ? <img src={it.coverUrl ?? ""} alt={it.title} loading="lazy" decoding="async" />
                : <span className="cover-initial">{it.title.slice(0, 1)}</span>}
              {showRank && <span className={'mt-rank' + (i < 3 ? ' top' : '')}>{i + 1}</span>}
              {(it.sourceCount ?? 1) > 1 && <span className="mt-multi">{it.sourceCount} 源</span>}
              {!it.readable && !it.readId && <span className="mt-outside">站外</span>}
            </div>
            <div className="mt-title clamp">{it.title}</div>
            <div className="mt-meta">{it.latestLabel || (it.finished ? '已完结' : '连载中')}</div>
          </button>
        ))}
      </div>
    </section>
  )
}

/** 本地漫画：搜索 + 网格（保留原书库功能）。 */
function LocalManga() {
  const [items, setItems] = useState<MangaCard[]>([])
  const [cursor, setCursor] = useState<string | null>(null)
  const [q, setQ] = useState('')
  const [activeQuery, setActiveQuery] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const load = useCallback(async (nextCursor: string | null, query: string, replace: boolean) => {
    setLoading(true); setError('')
    try {
      const qs = new URLSearchParams({ limit: '24' })
      if (nextCursor) qs.set('cursor', nextCursor)
      if (query) qs.set('q', query)
      const page = await api.get<{ items: MangaCard[]; nextCursor: string | null }>(`/api/v1/manga?${qs}`)
      setItems(prev => replace ? page.items : [...prev, ...page.items])
      setCursor(page.nextCursor)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败')
    } finally { setLoading(false) }
  }, [])

  useEffect(() => { void load(null, '', true) }, [load])

  function search() {
    setActiveQuery(q.trim())
    void load(null, q.trim(), true)
  }

  return (
    <section className="manga-local">
      <div className="section-head" style={{ marginTop: 30 }}>
        <h2>我的本地漫画 <span className="muted" style={{ fontSize: 13, fontWeight: 400 }}>
          {items.length} 部</span></h2>
        <Link to="/uploads">上传漫画 →</Link>
      </div>

      <div className="row-inline" style={{ marginBottom: 14 }}>
        <input placeholder="按标题搜索本地漫画…" className="search" value={q}
               onChange={e => setQ(e.target.value)}
               onKeyDown={e => e.key === 'Enter' && search()} />
        <button onClick={search} disabled={loading}>搜索</button>
        {activeQuery && (
          <button className="ghost" onClick={() => { setQ(''); setActiveQuery(''); void load(null, '', true) }}>
            清除
          </button>
        )}
      </div>

      {error && <p className="error">{error}</p>}
      {items.length === 0 && !loading && (
        <p className="muted">还没有可读的本地漫画——到「我的素材」上传图片或 CBZ 并发布章节。</p>
      )}

      <div className="card-grid">
        {items.map(m => (
          <Link key={m.id} to={`/manga/${m.id}`} className="work-card">
            <div className="cover">
              {m.coverUrl
                ? <img src={m.coverUrl} alt={m.title} loading="lazy" decoding="async"
                       onError={e => {
                         const img = e.target as HTMLImageElement
                         img.style.display = 'none'
                         const fb = img.nextElementSibling as HTMLElement | null
                         if (fb) fb.style.display = 'grid'
                       }} />
                : null}
              <span className="cover-initial" style={m.coverUrl ? { display: 'none' } : {}}>
                {m.title.slice(0, 1)}
              </span>
              {m.visibility === 'PRIVATE' && <span className="badge-anim" style={{ background: 'var(--priv)' }}>私人</span>}
            </div>
            <div className="work-card-body">
              <div className="work-card-title clamp">{m.title}</div>
              <div className="work-card-meta">
                <span>{m.chapterCount} 话</span>
                {m.latestOrdinal != null && <span>更新至第 {m.latestOrdinal} 话</span>}
              </div>
            </div>
          </Link>
        ))}
      </div>

      {cursor && (
        <div style={{ marginTop: 20, textAlign: 'center' }}>
          <button className="ghost" disabled={loading} onClick={() => void load(cursor, activeQuery, false)}>
            {loading ? '加载中…' : '加载更多'}
          </button>
        </div>
      )}
    </section>
  )
}

import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api'
import { proxyImage } from '../externalManga'

interface MangaCard {
  id: string; title: string; description: string; visibility: string
  chapterCount: number; latestOrdinal: number | null; latestTitle: string | null
  coverUrl: string | null; updatedAt: string
}

interface SourceInfo { id: string; name: string; capabilities: string[]; note: string }
interface RankItem {
  source: string; id: string; title: string; authors: string[]; coverUrl: string | null
  finished: boolean; latestLabel: string; readable: boolean; externalUrl: string | null
  readSource: string | null; readId: string | null
}
interface SourceSection { id: string; name: string; items: RankItem[] }

const SOURCE_KEY = 'ms-manga-source'

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
  const [source, setSource] = useState<string>(() => localStorage.getItem(SOURCE_KEY) ?? '')
  const [sections, setSections] = useState<SourceSection[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [heroIdx, setHeroIdx] = useState(0)

  useEffect(() => {
    void (async () => {
      try {
        const list = (await api.get<SourceInfo[]>('/api/v1/manga-sources'))
          .filter(s => s.capabilities.includes('RANK'))
        setSources(list)
        if (list.length > 0) {
          setSource(prev => {
            if (prev && list.some(s => s.id === prev)) return prev
            return list.find(s => s.id === 'tencent')?.id ?? list[0].id
          })
        }
      } catch { /* 源列表不可用不影响本地漫画 */ }
    })()
  }, [])

  const load = useCallback(async (src: string) => {
    if (!src) return
    setLoading(true); setError(''); setSections([]); setHeroIdx(0)
    try {
      setSections(await api.get<SourceSection[]>(`/api/v1/manga-sources/${src}/sections?limit=12`))
    } catch (e) {
      setError(e instanceof Error ? e.message : '推荐加载失败')
    } finally { setLoading(false) }
  }, [])

  useEffect(() => {
    if (!source) return
    localStorage.setItem(SOURCE_KEY, source)
    void load(source)
  }, [source, load])

  const current = sources.find(s => s.id === source)
  // hero 取第一个有内容的分区前 5 条
  const featured = useMemo(() => {
    const first = sections.find(s => s.items.length > 0)
    return first ? first.items.slice(0, 5) : []
  }, [sections])
  const hero = featured[heroIdx] ?? null

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
          {sources.map(s => (
            <button key={s.id} className={s.id === source ? 'mini' : 'mini ghost'}
                    onClick={() => setSource(s.id)}>{s.name}</button>
          ))}
        </div>
        <span className="muted" style={{ fontSize: 12 }}>
          {current?.note ?? '选择数据源查看推荐与榜单'}
        </span>
      </div>

      {error && (
        <div className="discover-error">
          <span className="error">{error}</span>
          <button className="ghost mini" onClick={() => void load(source)}>重试</button>
        </div>
      )}

      {loading && sections.length === 0 && (
        <>
          <div className="manga-hero-banner skeleton" />
          <p className="muted">加载推荐…</p>
        </>
      )}

      {hero && (
        <div className="manga-hero-banner"
             style={hero.coverUrl ? { backgroundImage: `url(${proxyImage(hero.coverUrl)})` } : undefined}>
          <div className="manga-hero-shade" />
          <div className="manga-hero-inner">
            <button className="manga-hero-cover" onClick={() => open(hero)} aria-label={hero.title}>
              {hero.coverUrl
                ? <img src={proxyImage(hero.coverUrl)} alt={hero.title} />
                : <span className="cover-initial">{hero.title.slice(0, 1)}</span>}
            </button>
            <div className="manga-hero-info">
              <span className="manga-hero-kicker">{current?.name ?? ''} · 推荐</span>
              <h2>{hero.title}</h2>
              {hero.authors?.length > 0 && (
                <p className="mh-author">{hero.authors.join(' / ')}</p>
              )}
              <div className="mh-chips">
                {hero.latestLabel && <span className="chip">{hero.latestLabel}</span>}
                {hero.finished && <span className="chip">已完结</span>}
                <span className="chip ghost">{hero.readable ? '站内可读' : '站外阅读'}</span>
              </div>
              <div className="manga-hero-actions">
                <button className="pill" onClick={() => open(hero)}>
                  {hero.readable ? '开始阅读' : '前往原站'}
                </button>
              </div>
            </div>
          </div>
          {featured.length > 1 && (
            <div className="manga-hero-dots">
              {featured.map((it, i) => (
                <button key={it.source + it.id}
                        className={i === heroIdx ? 'active' : ''}
                        aria-label={`第 ${i + 1} 张`}
                        onClick={() => setHeroIdx(i)} />
              ))}
            </div>
          )}
        </div>
      )}

      {sections.filter(s => s.items.length > 0).map(section => (
        <SectionRow key={section.id} section={section}
                    showRank={section.id !== 'recommend' && section.id !== 'hot'}
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
        <h2>{section.name}</h2>
        <span className="muted" style={{ fontSize: 12 }}>{section.items.length} 部</span>
      </div>
      <div className="manga-row">
        {section.items.map((it, i) => (
          <button key={it.source + it.id} className="manga-tile" onClick={() => onOpen(it)}
                  title={it.title}>
            <div className="mt-cover">
              {it.coverUrl
                ? <img src={proxyImage(it.coverUrl)} alt={it.title} loading="lazy" decoding="async" />
                : <span className="cover-initial">{it.title.slice(0, 1)}</span>}
              {showRank && <span className={'mt-rank' + (i < 3 ? ' top' : '')}>{i + 1}</span>}
              {!it.readable && <span className="mt-outside">站外</span>}
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

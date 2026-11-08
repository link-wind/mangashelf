import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { api, ApiError } from '../api'

interface SourceRow { id: string; name: string; baseUrl: string; enabled: boolean }
interface SearchItem { name: string; url: string }
interface EpisodeItem { title: string; url: string }
interface BoundEpisode { id: string; sourceId: string; sourceName: string; title: string; episodeUrl: string; ordinal: number }

/** 作品外部集管理（M6：声明式规则源搜索 → 绑定 → 展示）。 */
export default function ExternalEpisodes() {
  const { workId } = useParams()
  const [sources, setSources] = useState<SourceRow[]>([])
  const [selectedSource, setSelectedSource] = useState('')
  const [keyword, setKeyword] = useState('')
  const [results, setResults] = useState<SearchItem[]>([])
  const [episodes, setEpisodes] = useState<EpisodeItem[]>([])
  const [selectedEpisodes, setSelectedEpisodes] = useState<Set<number>>(new Set())
  const [bound, setBound] = useState<BoundEpisode[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  const loadBound = useCallback(async () => {
    if (!workId) return
    try {
      setBound(await api.get<BoundEpisode[]>(`/api/v1/works/${workId}/external-episodes`))
    } catch { /* ignore */ }
  }, [workId])
  useEffect(() => { void loadBound() }, [loadBound])

  const loadSources = useCallback(async () => {
    try {
      const list = await api.get<SourceRow[]>('/api/v1/external-sources')
      setSources(list)
      if (list.length > 0 && !selectedSource) setSelectedSource(list[0].id)
    } catch { /* ignore */ }
  }, [selectedSource])
  useEffect(() => { void loadSources() }, [loadSources])

  async function search() {
    if (!selectedSource || !keyword.trim()) return
    setBusy(true); setError(''); setResults([]); setEpisodes([])
    try {
      setResults(await api.post<SearchItem[]>(`/api/v1/works/${workId}/external-search`,
        { sourceId: selectedSource, keyword: keyword.trim() }))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '搜索失败')
    } finally { setBusy(false) }
  }

  async function parseEpisodes(url: string) {
    setBusy(true); setError('')
    try {
      setEpisodes(await api.post<EpisodeItem[]>(`/api/v1/works/${workId}/external-episodes/parse`,
        { sourceId: selectedSource, episodePageUrl: url }))
      setSelectedEpisodes(new Set(episodes.map((_, i) => i)))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '解析失败')
    } finally { setBusy(false) }
  }

  async function bind() {
    if (!workId || !selectedSource || episodes.length === 0) return
    setBusy(true)
    try {
      const payload = episodes
        .filter((_, i) => selectedEpisodes.has(i))
        .map((e, i) => ({ title: e.title, url: e.url, ordinal: i + 1 }))
      await api.post(`/api/v1/works/${workId}/external-episodes`,
        { sourceId: selectedSource, episodes: payload })
      await loadBound()
      setEpisodes([]); setResults([]); setNotice('外部集绑定成功')
      setTimeout(() => setNotice(''), 3000)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '绑定失败')
    } finally { setBusy(false) }
  }

  async function unbind(id: string) {
    try { await api.delete(`/api/v1/external-episodes/${id}`); await loadBound() }
    catch (e) { setError(e instanceof ApiError ? e.message : '解绑失败') }
  }

  return (
    <div className="indent" style={{ marginTop: 16 }}>
      <strong>外部动画源</strong>
      {error && <p className="error">{error}</p>}
      {notice && <p className="muted">{notice}</p>}

      <div className="row-inline" style={{ marginTop: 8 }}>
        <select value={selectedSource} onChange={e => setSelectedSource(e.target.value)}>
          {sources.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
          {sources.length === 0 && <option value="">（无可用规则源）</option>}
        </select>
        <input placeholder="搜索关键词" style={{ width: 180 }} value={keyword}
               onChange={e => setKeyword(e.target.value)}
               onKeyDown={e => e.key === 'Enter' && void search()} />
        <button onClick={() => void search()} disabled={busy || !selectedSource}>搜索</button>
      </div>

      {results.length > 0 && (
        <div className="stack" style={{ marginTop: 8 }}>
          <p className="muted">搜索结果（点击解析分集）：</p>
          {results.map((r, i) => (
            <button key={i} className="ghost" style={{ textAlign: 'left', fontSize: 13 }}
                    onClick={() => void parseEpisodes(r.url)}>
              {r.name}
            </button>
          ))}
        </div>
      )}

      {episodes.length > 0 && (
        <div className="stack" style={{ marginTop: 8 }}>
          <p className="muted">解析到 {episodes.length} 集（勾选后绑定）：</p>
          {episodes.map((e, i) => (
            <label key={i} className="scope-item">
              <input type="checkbox" checked={selectedEpisodes.has(i)}
                     onChange={() => {
                       const next = new Set(selectedEpisodes)
                       if (next.has(i)) next.delete(i); else next.add(i)
                       setSelectedEpisodes(next)
                     }} />
              {e.title}
            </label>
          ))}
          <button onClick={() => void bind()} disabled={busy || selectedEpisodes.size === 0}>
            绑定选中集
          </button>
        </div>
      )}

      {bound.length > 0 && (
        <ul className="revisions" style={{ marginTop: 12 }}>
          {bound.map(e => (
            <li key={e.id}>
              <span>{e.sourceName} · {e.title}</span>
              <span className="row-inline">
                <a className="btn ghost" href={e.episodeUrl} target="_blank" rel="noopener noreferrer">播放</a>
                <button className="ghost" onClick={() => void unbind(e.id)}>解绑</button>
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

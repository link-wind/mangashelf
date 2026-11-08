import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError, putPresigned } from '../api'

interface WorkRow { id: string; title: string; status: string; visibility: string }
interface ChapterRow { id: string; ordinal: number; title: string; currentRevisionId: string | null }
interface RevisionRow { id: string; revisionNo: number; status: string; pageCount: number; editVersion: number }

interface UploadRow {
  uploadId: string
  status: string
  jobs: { id: string; kind: string; status: string; stage: string | null }[]
}

interface RevisionDetail {
  id: string
  status: string
  pageCount: number
  editVersion: number
  revisionNo: number
}

interface PageItem { id: string; ordinal: number }

/** 我的素材（DESIGN 4）：上传 → 解析进度 → 页序确认 → 发布。 */
export default function MyUploads() {
  const [works, setWorks] = useState<WorkRow[]>([])
  const [error, setError] = useState('')
  const [title, setTitle] = useState('')

  const loadWorks = useCallback(async () => {
    try {
      const page = await api.get<{ items: WorkRow[] }>('/api/v1/works?limit=50')
      // 仅显示自己的作品（列表含公开他人作品，这里按 owner 过滤由详情页判断，简化：全部展示）
      setWorks(page.items)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '加载失败')
    }
  }, [])
  useEffect(() => { void loadWorks() }, [loadWorks])

  async function createWork() {
    try {
      await api.post('/api/v1/works', { title: title || '未命名作品', description: '' })
      setTitle('')
      await loadWorks()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '创建失败')
    }
  }

  return (
    <main className="page">
      <h1>我的素材</h1>
      {error && <p className="error">{error}</p>}
      <div className="row-inline">
        <input placeholder="作品标题" value={title} onChange={e => setTitle(e.target.value)} />
        <button onClick={() => void createWork()}>创建作品</button>
      </div>
      <div className="stack">
        {works.map(w => <WorkAdmin key={w.id} work={w} />)}
      </div>
      <h2 style={{ marginTop: 32 }}>动画管理</h2>
      <AnimationAdmin />
    </main>
  )
}

function WorkAdmin({ work }: { work: WorkRow }) {
  const [open, setOpen] = useState(false)
  return (
    <div className="card">
      <div className="row-between">
        <strong>{work.title}</strong>
        <div className="row-inline">
          <span className={`tag ${work.visibility === 'PUBLIC' ? 'pub' : 'priv'}`}>{work.visibility === 'PUBLIC' ? '公开' : '私人'}</span>
          <span className="muted">{work.status}</span>
          <button className="ghost" onClick={() => setOpen(!open)}>{open ? '收起' : '管理'}</button>
        </div>
      </div>
      {open && <WorkAdminDetail workId={work.id} workStatus={work.status} />}
    </div>
  )
}

function WorkAdminDetail({ workId, workStatus }: { workId: string; workStatus: string }) {
  const [chapters, setChapters] = useState<ChapterRow[]>([])
  const [error, setError] = useState('')
  const [chapterTitle, setChapterTitle] = useState('')

  const load = useCallback(async () => {
    try {
      const view = await api.get<{ chapters: ChapterRow[] }>(`/api/v1/works/${workId}`)
      setChapters(view.chapters)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '加载失败')
    }
  }, [workId])
  useEffect(() => { void load() }, [load])

  async function addChapter() {
    try {
      await api.post(`/api/v1/works/${workId}/chapters`, { title: chapterTitle })
      setChapterTitle('')
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '创建失败')
    }
  }

  async function publishWork() {
    try {
      await api.post(`/api/v1/works/${workId}/publish`)
      setError('')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '发布失败')
    }
  }

  async function makePublic() {
    try {
      await api.patch(`/api/v1/works/${workId}`, { visibility: 'PUBLIC' })
      setError('')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '操作失败')
    }
  }

  return (
    <div className="indent">
      {error && <p className="error">{error}</p>}
      <div className="row-inline">
        <input placeholder="章节标题" value={chapterTitle} onChange={e => setChapterTitle(e.target.value)} />
        <button onClick={() => void addChapter()}>添加章节</button>
        <button className="ghost" onClick={makePublic}>设为公开</button>
        <button onClick={publishWork} disabled={workStatus === 'PUBLISHED'}>发布作品</button>
      </div>
      <div className="stack">
        {chapters.map(c => <ChapterAdmin key={c.id} chapter={c} />)}
        {chapters.length === 0 && <p className="muted">先添加章节，再为章节上传 CBZ 或单图。</p>}
      </div>
    </div>
  )
}

function ChapterAdmin({ chapter }: { chapter: ChapterRow }) {
  const [revisions, setRevisions] = useState<RevisionRow[]>([])
  const [upload, setUpload] = useState<UploadRow | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const fileRef = useRef<HTMLInputElement>(null)

  const loadRevisions = useCallback(async () => {
    try {
      const view = await api.get<{ revisions: RevisionRow[] }>(`/api/v1/chapters/${chapter.id}`)
      setRevisions(view.revisions)
    } catch { /* 非所有者视图无 revisions 字段 */ }
  }, [chapter.id])
  useEffect(() => { void loadRevisions() }, [loadRevisions])

  async function uploadAndCreateRevision() {
    const file = fileRef.current?.files?.[0]
    if (!file) { setError('请选择 CBZ 或图片文件'); return }
    setBusy(true); setError(''); setUpload(null)
    try {
      const created = await api.post<{ uploadId: string; presigned: { url: string } }>('/api/v1/uploads', {
        purpose: 'MANGA_PAGE',
        expectedSizeBytes: file.size,
        contentType: file.type || 'application/zip',
        idempotencyKey: `web-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      })
      await putPresigned(created.presigned.url, file.type || 'application/zip', file)
      const done = await api.post<{ jobId: string }>(`/api/v1/uploads/${created.uploadId}/complete`)
      setUpload({ uploadId: created.uploadId, status: 'PENDING', jobs: [{ id: done.jobId, kind: 'content.verify', status: 'QUEUED', stage: null }] })
      await pollUpload(created.uploadId, done.jobId)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '上传失败')
    } finally {
      setBusy(false)
    }
  }

  async function pollUpload(uploadId: string, jobId: string) {
    // 轮询核验任务（SSE 的事件流在这里等价简化为快照轮询）
    for (let i = 0; i < 120; i++) {
      const snap = await api.get<UploadRow>(`/api/v1/uploads/${uploadId}`)
      setUpload(snap)
      const failed = snap.jobs.some(j => j.status === 'FAILED')
      if (failed) throw new ApiError('VERIFY_FAILED', '文件核验失败', 0)
      const ok = snap.jobs.some(j => j.kind === 'content.verify' && j.status === 'SUCCEEDED')
      if (ok) {
        // 核验完成后自动创建章节版本
        const rev = await api.post<{ revisionId: string; jobId: string }>(
          `/api/v1/chapters/${chapter.id}/revisions`, { uploadId })
        await pollRevision(rev.revisionId)
        await loadRevisions()
        return
      }
      await new Promise(r => setTimeout(r, 1000))
    }
    throw new ApiError('TIMEOUT', '核验超时', 0)
  }

  async function pollRevision(revisionId: string) {
    for (let i = 0; i < 300; i++) {
      const rev = await api.get<RevisionDetail>(`/api/v1/chapter-revisions/${revisionId}`)
      if (rev.status === 'FAILED') throw new ApiError('PARSE_FAILED', '解析失败：' + (rev as unknown as { errorMessage?: string }).errorMessage, 0)
      if (rev.status === 'READY') return
      await new Promise(r => setTimeout(r, 1000))
    }
  }

  async function confirmOrder(rev: RevisionRow) {
    try {
      const pages: PageItem[] = []
      let cursor: string | undefined
      do {
        const page = await api.get<{ pages: PageItem[]; nextCursor: string | null }>(
          `/api/v1/chapter-revisions/${rev.id}/pages?limit=50${cursor ? `&cursor=${cursor}` : ''}`)
        pages.push(...page.pages)
        cursor = page.nextCursor ?? undefined
      } while (cursor)
      await api.patch(`/api/v1/chapter-revisions/${rev.id}/page-order`, {
        orderedPageIds: pages.sort((a, b) => a.ordinal - b.ordinal).map(p => p.id),
        expectedEditVersion: rev.editVersion,
      })
      await loadRevisions()
      setError('')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '页序确认失败')
    }
  }

  async function publishRevision(rev: RevisionRow) {
    try {
      await api.post(`/api/v1/chapter-revisions/${rev.id}/publish`)
      await loadRevisions()
      setError('')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '发布失败')
    }
  }

  return (
    <div className="indent">
      <strong>第 {chapter.ordinal} 话 {chapter.title}</strong>
      {error && <p className="error">{error}</p>}
      <div className="row-inline">
        <input type="file" ref={fileRef} accept=".cbz,.zip,image/*" />
        <button onClick={() => void uploadAndCreateRevision()} disabled={busy}>
          {busy ? '上传/解析中…' : '上传并解析为新版本'}
        </button>
      </div>
      {upload && (
        <p className="muted">
          核验任务：{upload.jobs.map(j => `${j.kind}=${j.status}`).join('，') || '排队中'}
        </p>
      )}
      <ul className="revisions">
        {revisions.map(r => (
          <li key={r.id}>
            <span>v{r.revisionNo} · {r.status}{r.pageCount > 0 ? ` · ${r.pageCount} 页` : ''}</span>
            <span className="row-inline">
              {r.status === 'READY' && <button className="ghost" onClick={() => void confirmOrder(r)}>确认页序</button>}
              {r.status === 'READY' && <button onClick={() => void publishRevision(r)}>发布此版本</button>}
            </span>
          </li>
        ))}
        {revisions.length === 0 && <li className="muted">尚无版本</li>}
      </ul>
    </div>
  )
}

// ── M2 动画管理（DESIGN 13/16.1）：创建 → 上传视频 → 验证（绑定来源）→ 发布/下架 ──

interface AnimationRow {
  id: string
  title: string
  status: string
  visibility: string
}

interface AnimationRevisionRow {
  id: string
  revisionNo: number
  status: string
  durationMs: number | null
}

interface SourceOption {
  chapterRevisionId: string
  label: string
  pageCount: number
}

function AnimationAdmin() {
  const [items, setItems] = useState<AnimationRow[]>([])
  const [title, setTitle] = useState('')
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    try {
      const page = await api.get<{ items: AnimationRow[] }>('/api/v1/animations?limit=50')
      setItems(page.items)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败')
    }
  }, [])
  useEffect(() => { void load() }, [load])

  async function create() {
    try {
      await api.post('/api/v1/animations', { title: title || '未命名动画', description: '' })
      setTitle('')
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '创建失败')
    }
  }

  return (
    <div className="stack">
      {error && <p className="error">{error}</p>}
      <div className="row-inline">
        <input placeholder="动画标题" value={title} onChange={e => setTitle(e.target.value)} />
        <button onClick={() => void create()}>创建动画</button>
      </div>
      {items.map(a => <AnimationCard key={a.id} animation={a} onChanged={load} />)}
      {items.length === 0 && <p className="muted">还没有动画——先创建动画，再上传视频并绑定来源。</p>}
    </div>
  )
}

function AnimationCard({ animation, onChanged }: { animation: AnimationRow; onChanged: () => void }) {
  const [open, setOpen] = useState(false)
  return (
    <div className="card">
      <div className="row-between">
        <strong>{animation.title}</strong>
        <div className="row-inline">
          <span className={`tag ${animation.visibility === 'PUBLIC' ? 'pub' : 'priv'}`}>
            {animation.visibility === 'PUBLIC' ? '公开' : '私人'}
          </span>
          <span className="muted">{animation.status}</span>
          <button className="ghost" onClick={() => setOpen(!open)}>{open ? '收起' : '管理'}</button>
        </div>
      </div>
      {open && <AnimationDetail animation={animation} onChanged={onChanged} />}
    </div>
  )
}

function AnimationDetail({ animation, onChanged }: { animation: AnimationRow; onChanged: () => void }) {
  const [revisions, setRevisions] = useState<AnimationRevisionRow[]>([])
  const [sourceOptions, setSourceOptions] = useState<SourceOption[]>([])
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [videoFile, setVideoFile] = useState<File | null>(null)
  const [posterFile, setPosterFile] = useState<File | null>(null)
  const [subtitleFile, setSubtitleFile] = useState<File | null>(null)
  const [sourceRev, setSourceRev] = useState('')
  const [pageStart, setPageStart] = useState('1')
  const [pageEnd, setPageEnd] = useState('3')

  const load = useCallback(async () => {
    try {
      const view = await api.get<{ revisions: AnimationRevisionRow[] }>(
        `/api/v1/animations/${animation.id}`)
      setRevisions(view.revisions)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '加载失败')
    }
  }, [animation.id])
  useEffect(() => { void load() }, [load])

  // 来源下拉：自己作品里 READY/PUBLISHED 的章节当前版本
  const loadSources = useCallback(async () => {
    try {
      const page = await api.get<{ items: { id: string; title: string }[] }>('/api/v1/works?limit=50')
      const options: SourceOption[] = []
      for (const w of page.items) {
        const view = await api.get<{ chapters: { id: string; title: string; ordinal: number; currentRevisionId: string | null }[] }>(`/api/v1/works/${w.id}`)
        for (const c of view.chapters) {
          if (c.currentRevisionId) {
            options.push({ chapterRevisionId: c.currentRevisionId,
              label: `《${w.title}》第 ${c.ordinal} 话 ${c.title}`, pageCount: 0 })
          }
        }
      }
      setSourceOptions(options)
      if (options.length > 0 && !sourceRev) setSourceRev(options[0].chapterRevisionId)
    } catch { /* 忽略 */ }
  }, [])
  useEffect(() => { void loadSources() }, [loadSources])

  async function createRevision() {
    try {
      await api.post(`/api/v1/animations/${animation.id}/revisions`)
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '创建失败')
    }
  }

  async function uploadAndVerify(rev: AnimationRevisionRow) {
    if (!videoFile) { setError('请选择 MP4 视频文件'); return }
    setBusy(true); setError('')
    try {
      async function upload(f: File, purpose: string, contentType: string): Promise<string> {
        const created = await api.post<{ uploadId: string; presigned: { url: string } }>('/api/v1/uploads', {
          purpose, expectedSizeBytes: f.size, contentType,
          idempotencyKey: `anim-${purpose}-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
        })
        await putPresigned(created.presigned.url, contentType, f)
        await api.post(`/api/v1/uploads/${created.uploadId}/complete`)
        // 轮询核验
        for (let i = 0; i < 120; i++) {
          const snap = await api.get<{ assetId: string | null; assetState: string; status: string }>(`/api/v1/uploads/${created.uploadId}`)
          if (snap.assetState === 'VERIFIED' && snap.assetId) return snap.assetId
          if (snap.status === 'FAILED') throw new ApiError('UPLOAD_FAILED', '文件核验失败', 0)
          await new Promise(r => setTimeout(r, 1000))
        }
        throw new ApiError('TIMEOUT', '核验超时', 0)
      }
      const videoAssetId = await upload(videoFile, 'ANIMATION_VIDEO', videoFile.type || 'video/mp4')
      const posterAssetId = posterFile ? await upload(posterFile, 'ANIMATION_POSTER', posterFile.type || 'image/jpeg') : null
      const subtitleAssetId = subtitleFile ? await upload(subtitleFile, 'ANIMATION_SUBTITLE', subtitleFile.type || 'text/vtt') : null
      await api.post<{ jobId: string }>(`/api/v1/animation-revisions/${rev.id}/verify`, {
        videoAssetId, posterAssetId, subtitleAssetId,
        sources: [{ chapterRevisionId: sourceRev,
                    pageStart: Number(pageStart) || 1, pageEnd: Number(pageEnd) || 3, ordinal: 1 }],
      })
      // 轮询验证任务
      for (let i = 0; i < 180; i++) {
        const detail = await api.get<{ status: string; errorCode?: string; errorMessage?: string }>(
          `/api/v1/animation-revisions/${rev.id}`)
        if (detail.status === 'FAILED') throw new ApiError(detail.errorCode ?? 'VERIFY_FAILED',
          detail.errorMessage ?? '媒体验证失败', 0)
        if (detail.status === 'READY') break
        await new Promise(r => setTimeout(r, 1000))
      }
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '上传/验证失败')
    } finally {
      setBusy(false)
    }
  }

  async function publish(rev: AnimationRevisionRow) {
    try {
      await api.post(`/api/v1/animation-revisions/${rev.id}/publish`)
      await load(); onChanged()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '发布失败')
    }
  }

  async function withdraw() {
    try {
      await api.post(`/api/v1/animations/${animation.id}/withdraw`)
      await load(); onChanged()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '下架失败')
    }
  }

  return (
    <div className="indent">
      {error && <p className="error">{error}</p>}
      <div className="row-inline">
        <button onClick={() => void createRevision()}>创建新版本</button>
        {animation.status === 'PUBLISHED' && <button className="ghost" onClick={() => void withdraw()}>下架</button>}
      </div>
      <div className="stack">
        {revisions.map(r => (
          <div key={r.id} className="card">
            <div className="row-between">
              <strong>v{r.revisionNo} · {r.status}{r.durationMs ? ` · ${Math.round(r.durationMs / 1000)}s` : ''}</strong>
            </div>
            <div className="row-inline">
              <label>视频 MP4<input type="file" accept="video/mp4" onChange={e => setVideoFile(e.target.files?.[0] ?? null)} /></label>
              <label>封面（可选）<input type="file" accept="image/jpeg" onChange={e => setPosterFile(e.target.files?.[0] ?? null)} /></label>
              <label>字幕 VTT/SRT（可选）<input type="file" accept=".vtt,.srt" onChange={e => setSubtitleFile(e.target.files?.[0] ?? null)} /></label>
            </div>
            <div className="row-inline">
              <select value={sourceRev} onChange={e => setSourceRev(e.target.value)}>
                {sourceOptions.map(o => <option key={o.chapterRevisionId} value={o.chapterRevisionId}>{o.label}</option>)}
                {sourceOptions.length === 0 && <option value="">（先发布一部漫画）</option>}
              </select>
              <input type="number" min={1} style={{ width: 80 }} value={pageStart} onChange={e => setPageStart(e.target.value)} placeholder="起始页" />
              <input type="number" min={1} style={{ width: 80 }} value={pageEnd} onChange={e => setPageEnd(e.target.value)} placeholder="结束页" />
            </div>
            <div className="row-inline">
              <button onClick={() => void uploadAndVerify(r)} disabled={busy || !videoFile}>
                {busy ? '上传/验证中…' : '上传并验证'}
              </button>
              <button onClick={() => void publish(r)} disabled={r.status !== 'READY'}>发布此版本</button>
              <Link className="btn ghost" to={`/watch/${animation.id}`}>观看</Link>
            </div>
          </div>
        ))}
        {revisions.length === 0 && <p className="muted">先创建新版本。</p>}
      </div>
    </div>
  )
}

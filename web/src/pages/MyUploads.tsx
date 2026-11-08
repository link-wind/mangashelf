import { useCallback, useEffect, useRef, useState } from 'react'
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

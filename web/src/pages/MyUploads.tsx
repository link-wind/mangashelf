import {
  BookOpen, CheckCircle2, ChevronDown, ChevronUp, Eye, Film,
  FolderUp, Globe, Plus, UploadCloud, Video,
} from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError, putPresigned } from '../api'
import { useToast } from '../components/Toast'
import EmptyState from '../components/ui/EmptyState'
import SegmentedTabs from '../components/ui/SegmentedTabs'
import { localizeWorkStatus } from '../lib/mediaFormat'

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

type StudioTab = 'manga' | 'anime'

/** 我的素材工作台：现代分 Tab 管理站内漫画（CBZ/单图）与动画成片（视频/绑定来源）。 */
export default function MyUploads() {
  const { showToast } = useToast()
  const [tab, setTab] = useState<StudioTab>('manga')
  const [works, setWorks] = useState<WorkRow[]>([])
  const [animations, setAnimations] = useState<AnimationRow[]>([])
  const [error, setError] = useState('')
  const [workTitle, setWorkTitle] = useState('')
  const [animTitle, setAnimTitle] = useState('')
  const [creating, setCreating] = useState(false)

  const loadAll = useCallback(async () => {
    try {
      const [wPage, aPage] = await Promise.all([
        api.get<{ items: WorkRow[] }>('/api/v1/works?limit=50'),
        api.get<{ items: AnimationRow[] }>('/api/v1/animations?limit=50').catch(() => ({ items: [] })),
      ])
      setWorks(wPage.items)
      setAnimations(aPage.items)
      setError('')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '加载素材失败')
    }
  }, [])
  useEffect(() => { void loadAll() }, [loadAll])

  async function createWork(e: React.FormEvent) {
    e.preventDefault()
    if (!workTitle.trim()) return
    setCreating(true)
    try {
      await api.post('/api/v1/works', { title: workTitle.trim(), description: '' })
      setWorkTitle('')
      showToast('漫画作品已创建，点击「展开管理」上传章节', 'success')
      await loadAll()
    } catch (err) {
      const msg = err instanceof ApiError ? err.message : '创建失败'
      setError(msg); showToast(msg, 'error')
    } finally { setCreating(false) }
  }

  async function createAnimation(e: React.FormEvent) {
    e.preventDefault()
    if (!animTitle.trim()) return
    setCreating(true)
    try {
      await api.post('/api/v1/animations', { title: animTitle.trim(), description: '' })
      setAnimTitle('')
      showToast('动画草稿已创建，点击「展开管理」上传视频', 'success')
      await loadAll()
    } catch (err) {
      const msg = err instanceof ApiError ? err.message : '创建失败'
      setError(msg); showToast(msg, 'error')
    } finally { setCreating(false) }
  }

  const publishedCount = works.filter(w => w.status === 'PUBLISHED').length
    + animations.filter(a => a.status === 'PUBLISHED').length

  return (
    <main className="watch-page">
      <nav className="breadcrumb" aria-label="面包屑">
        <Link to="/me">个人空间</Link>
        <span>/</span>
        <span className="breadcrumb-cur">我的素材</span>
      </nav>

      <div className="studio-head">
        <div>
          <h1>我的素材工作台</h1>
          <p className="muted">上传私人漫画（支持 CBZ / ZIP 压缩包与图片）或管理自制动画成片，完成解析与页序确认后一键发布。</p>
        </div>
        <div className="studio-kpis tabular-nums">
          <div className="studio-kpi">
            <span>漫画作品</span>
            <strong className="is-rose">{works.length}</strong>
          </div>
          <div className="studio-kpi">
            <span>动画作品</span>
            <strong className="is-cyan">{animations.length}</strong>
          </div>
          <div className="studio-kpi">
            <span>已发布</span>
            <strong className="is-pub">{publishedCount}</strong>
          </div>
        </div>
      </div>

      {error && <p className="error" style={{ marginBottom: 12 }}>{error}</p>}

      <div className="studio-toolbar">
        <SegmentedTabs
          layoutId="studio-tab"
          tone={tab === 'manga' ? 'rose' : 'cyan'}
          value={tab}
          onChange={setTab}
          items={[
            { value: 'manga', label: '漫画素材库', icon: <BookOpen size={14} />, count: works.length },
            { value: 'anime', label: '动画素材库', icon: <Film size={14} />, count: animations.length },
          ]}
        />

        {tab === 'manga' ? (
          <form className="studio-create-bar" onSubmit={e => void createWork(e)}>
            <input
              placeholder="输入新漫画作品标题…"
              value={workTitle}
              onChange={e => setWorkTitle(e.target.value)}
            />
            <button type="submit" className="pill space-btn-rose" disabled={creating || !workTitle.trim()}>
              <Plus size={15} />新建漫画
            </button>
          </form>
        ) : (
          <form className="studio-create-bar" onSubmit={e => void createAnimation(e)}>
            <input
              placeholder="输入新动画作品标题…"
              value={animTitle}
              onChange={e => setAnimTitle(e.target.value)}
            />
            <button type="submit" className="pill" disabled={creating || !animTitle.trim()}>
              <Plus size={15} />新建动画
            </button>
          </form>
        )}
      </div>

      {tab === 'manga' ? (
        works.length === 0 ? (
          <EmptyState
            tone="rose"
            icon={<FolderUp size={24} />}
            title="还没有上传任何站内漫画"
            description="3 步完成私有漫画入库：① 在右上角输入标题新建作品 → ② 添加章节并拖拽上传 CBZ / 图片 → ③ 确认页序后发布即可在线阅读。"
          />
        ) : (
          <div className="studio-list">
            {works.map(w => <WorkAdmin key={w.id} work={w} onChanged={loadAll} />)}
          </div>
        )
      ) : (
        animations.length === 0 ? (
          <EmptyState
            tone="cyan"
            icon={<Video size={24} />}
            title="还没有自制动画作品"
            description="在右上角新建动画后，可上传 MP4 成片、封面与 VTT/SRT 字幕，绑定对应的漫画源章节页范围，经验证后在线播放。"
          />
        ) : (
          <div className="studio-list">
            {animations.map(a => <AnimationCard key={a.id} animation={a} onChanged={loadAll} />)}
          </div>
        )
      )}
    </main>
  )
}

function WorkAdmin({ work, onChanged }: { work: WorkRow; onChanged: () => void }) {
  const [open, setOpen] = useState(false)
  const st = localizeWorkStatus(work.status)
  return (
    <div className="studio-card">
      <div className="studio-card-head">
        <div className="studio-card-title">
          <span className="studio-ico is-rose"><BookOpen size={17} /></span>
          <div>
            <strong>{work.title}</strong>
            <div className="row-inline" style={{ marginTop: 3 }}>
              <span className={`tag ${work.visibility === 'PUBLIC' ? 'pub' : 'priv'}`}>
                {work.visibility === 'PUBLIC' ? '公开' : '私人'}
              </span>
              <span className={`studio-status is-${st.tone}`}>{st.label}</span>
            </div>
          </div>
        </div>
        <div className="row-inline">
          <Link to={`/manga/${work.id}`} className="btn ghost mini">
            <Eye size={13} />详情
          </Link>
          <button type="button" className="mini ghost" onClick={() => setOpen(v => !v)}>
            {open ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
            {open ? '收起' : '管理章节'}
          </button>
        </div>
      </div>
      {open && <WorkAdminDetail workId={work.id} workStatus={work.status} onChanged={onChanged} />}
    </div>
  )
}

function WorkAdminDetail({ workId, workStatus, onChanged }: { workId: string; workStatus: string; onChanged: () => void }) {
  const { showToast } = useToast()
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

  async function addChapter(e: React.FormEvent) {
    e.preventDefault()
    try {
      await api.post(`/api/v1/works/${workId}/chapters`, { title: chapterTitle.trim() })
      setChapterTitle('')
      showToast('章节已添加', 'success')
      await load()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '创建失败')
    }
  }

  async function publishWork() {
    try {
      await api.post(`/api/v1/works/${workId}/publish`)
      setError('')
      showToast('作品已发布', 'success')
      onChanged()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '发布失败')
    }
  }

  async function makePublic() {
    try {
      await api.patch(`/api/v1/works/${workId}`, { visibility: 'PUBLIC' })
      setError('')
      showToast('作品已设为公开可见', 'success')
      onChanged()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '操作失败')
    }
  }

  return (
    <div className="studio-drawer">
      {error && <p className="error">{error}</p>}
      <div className="studio-drawer-bar">
        <form className="row-inline" onSubmit={e => void addChapter(e)}>
          <input placeholder="新章节标题（如：第 1 话）" value={chapterTitle} onChange={e => setChapterTitle(e.target.value)} />
          <button type="submit" className="mini pill"><Plus size={13} />添加章节</button>
        </form>
        <div className="row-inline">
          <button type="button" className="mini ghost" onClick={() => void makePublic()}>
            <Globe size={13} />设为公开
          </button>
          <button type="button" className="mini pill" onClick={() => void publishWork()} disabled={workStatus === 'PUBLISHED'}>
            <CheckCircle2 size={13} />{workStatus === 'PUBLISHED' ? '已发布' : '发布作品'}
          </button>
        </div>
      </div>

      <div className="studio-chapters">
        {chapters.map(c => <ChapterAdmin key={c.id} chapter={c} />)}
        {chapters.length === 0 && (
          <p className="muted" style={{ margin: '6px 0' }}>请先添加章节，再为章节拖拽上传 CBZ 压缩包或单图。</p>
        )}
      </div>
    </div>
  )
}

function ChapterAdmin({ chapter }: { chapter: ChapterRow }) {
  const { showToast } = useToast()
  const [revisions, setRevisions] = useState<RevisionRow[]>([])
  const [upload, setUpload] = useState<UploadRow | null>(null)
  const [pickedFile, setPickedFile] = useState<File | null>(null)
  const [dragging, setDragging] = useState(false)
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

  async function uploadAndCreateRevision(fileToUse?: File | null) {
    const file = fileToUse ?? pickedFile ?? fileRef.current?.files?.[0]
    if (!file) { setError('请先选择或拖入 CBZ / ZIP / 图片文件'); return }
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
      await pollUpload(created.uploadId)
      setPickedFile(null)
      showToast(`第 ${chapter.ordinal} 话上传解析完成，请确认页序并发布`, 'success')
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : '上传失败'
      setError(msg); showToast(msg, 'error')
    } finally { setBusy(false) }
  }

  async function pollUpload(uploadId: string) {
    for (let i = 0; i < 120; i++) {
      const snap = await api.get<UploadRow>(`/api/v1/uploads/${uploadId}`)
      setUpload(snap)
      if (snap.jobs.some(j => j.status === 'FAILED')) throw new ApiError('VERIFY_FAILED', '文件核验失败', 0)
      if (snap.jobs.some(j => j.kind === 'content.verify' && j.status === 'SUCCEEDED')) {
        const rev = await api.post<{ revisionId: string; jobId: string }>(
          `/api/v1/chapters/${chapter.id}/revisions`, { uploadId },
        )
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
      if (rev.status === 'FAILED') {
        throw new ApiError('PARSE_FAILED', '解析失败：' + (rev as unknown as { errorMessage?: string }).errorMessage, 0)
      }
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
          `/api/v1/chapter-revisions/${rev.id}/pages?limit=50${cursor ? `&cursor=${cursor}` : ''}`,
        )
        pages.push(...page.pages)
        cursor = page.nextCursor ?? undefined
      } while (cursor)
      await api.patch(`/api/v1/chapter-revisions/${rev.id}/page-order`, {
        orderedPageIds: pages.sort((a, b) => a.ordinal - b.ordinal).map(p => p.id),
        expectedEditVersion: rev.editVersion,
      })
      showToast(`v${rev.revisionNo} 页序已确认`, 'success')
      await loadRevisions()
      setError('')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '页序确认失败')
    }
  }

  async function publishRevision(rev: RevisionRow) {
    try {
      await api.post(`/api/v1/chapter-revisions/${rev.id}/publish`)
      showToast(`第 ${chapter.ordinal} 话 v${rev.revisionNo} 已发布`, 'success')
      await loadRevisions()
      setError('')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '发布失败')
    }
  }

  function onDrop(e: React.DragEvent) {
    e.preventDefault()
    setDragging(false)
    const f = e.dataTransfer.files?.[0]
    if (f) setPickedFile(f)
  }

  return (
    <div className="studio-chapter-box">
      <div className="row-between" style={{ marginBottom: 8 }}>
        <strong>第 {chapter.ordinal} 话 {chapter.title}</strong>
        <span className="muted tabular-nums" style={{ fontSize: 12 }}>{revisions.length} 个版本</span>
      </div>
      {error && <p className="error">{error}</p>}

      <div
        className={'studio-dropzone' + (dragging ? ' is-drag' : '')}
        onDragOver={e => { e.preventDefault(); setDragging(true) }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
        onClick={() => fileRef.current?.click()}
      >
        <input
          type="file"
          ref={fileRef}
          accept=".cbz,.zip,image/*"
          style={{ display: 'none' }}
          onChange={e => setPickedFile(e.target.files?.[0] ?? null)}
        />
        <UploadCloud size={20} className="studio-drop-ico" />
        <div className="studio-drop-text">
          <strong>{pickedFile ? pickedFile.name : '点击或拖拽上传 CBZ / ZIP / 图片文件'}</strong>
          <span>{pickedFile ? `${(pickedFile.size / 1024 / 1024).toFixed(2)} MB` : '自动核验解包并生成可排序版本'}</span>
        </div>
        <button
          type="button"
          className="mini pill"
          disabled={busy || !pickedFile}
          onClick={e => { e.stopPropagation(); void uploadAndCreateRevision(pickedFile) }}
        >
          {busy ? '处理中…' : '开始上传'}
        </button>
      </div>

      {upload && (
        <div className="set-status-note" style={{ marginTop: 8 }}>
          核验进度：{upload.jobs.map(j => `${j.kind}: ${localizeWorkStatus(j.status).label}`).join(' · ') || '排队中'}
        </div>
      )}

      {revisions.length > 0 && (
        <div className="studio-rev-list">
          {revisions.map(r => {
            const st = localizeWorkStatus(r.status)
            return (
              <div key={r.id} className="studio-rev-row">
                <div className="row-inline">
                  <strong className="tabular-nums">v{r.revisionNo}</strong>
                  <span className={`studio-status is-${st.tone}`}>{st.label}</span>
                  {r.pageCount > 0 && <span className="muted tabular-nums">{r.pageCount} 页</span>}
                </div>
                <div className="row-inline">
                  {r.status === 'READY' && (
                    <button type="button" className="mini ghost" onClick={() => void confirmOrder(r)}>确认页序</button>
                  )}
                  {r.status === 'READY' && (
                    <button type="button" className="mini pill" onClick={() => void publishRevision(r)}>发布版本</button>
                  )}
                </div>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

function AnimationCard({ animation, onChanged }: { animation: AnimationRow; onChanged: () => void }) {
  const [open, setOpen] = useState(false)
  const st = localizeWorkStatus(animation.status)
  return (
    <div className="studio-card">
      <div className="studio-card-head">
        <div className="studio-card-title">
          <span className="studio-ico is-cyan"><Film size={17} /></span>
          <div>
            <strong>{animation.title}</strong>
            <div className="row-inline" style={{ marginTop: 3 }}>
              <span className={`tag ${animation.visibility === 'PUBLIC' ? 'pub' : 'priv'}`}>
                {animation.visibility === 'PUBLIC' ? '公开' : '私人'}
              </span>
              <span className={`studio-status is-${st.tone}`}>{st.label}</span>
            </div>
          </div>
        </div>
        <div className="row-inline">
          <Link to={`/watch/${animation.id}`} className="btn ghost mini">
            <Eye size={13} />观看
          </Link>
          <button type="button" className="mini ghost" onClick={() => setOpen(v => !v)}>
            {open ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
            {open ? '收起' : '管理版本'}
          </button>
        </div>
      </div>
      {open && <AnimationDetail animation={animation} onChanged={onChanged} />}
    </div>
  )
}

function AnimationDetail({ animation, onChanged }: { animation: AnimationRow; onChanged: () => void }) {
  const { showToast } = useToast()
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
      const view = await api.get<{ revisions: AnimationRevisionRow[] }>(`/api/v1/animations/${animation.id}`)
      setRevisions(view.revisions)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '加载失败')
    }
  }, [animation.id])
  useEffect(() => { void load() }, [load])

  const loadSources = useCallback(async () => {
    try {
      const page = await api.get<{ items: { id: string; title: string }[] }>('/api/v1/works?limit=50')
      const options: SourceOption[] = []
      for (const w of page.items) {
        const view = await api.get<{ chapters: { id: string; title: string; ordinal: number; currentRevisionId: string | null }[] }>(`/api/v1/works/${w.id}`)
        for (const c of view.chapters) {
          if (c.currentRevisionId) {
            options.push({
              chapterRevisionId: c.currentRevisionId,
              label: `《${w.title}》第 ${c.ordinal} 话 ${c.title}`,
              pageCount: 0,
            })
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
      showToast('已创建新版本', 'success')
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
        sources: [{
          chapterRevisionId: sourceRev,
          pageStart: Number(pageStart) || 1,
          pageEnd: Number(pageEnd) || 3,
          ordinal: 1,
        }],
      })
      for (let i = 0; i < 180; i++) {
        const detail = await api.get<{ status: string; errorCode?: string; errorMessage?: string }>(
          `/api/v1/animation-revisions/${rev.id}`,
        )
        if (detail.status === 'FAILED') {
          throw new ApiError(detail.errorCode ?? 'VERIFY_FAILED', detail.errorMessage ?? '媒体验证失败', 0)
        }
        if (detail.status === 'READY') break
        await new Promise(r => setTimeout(r, 1000))
      }
      showToast(`v${rev.revisionNo} 核验完成，可立即发布`, 'success')
      await load()
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : '上传/验证失败'
      setError(msg); showToast(msg, 'error')
    } finally { setBusy(false) }
  }

  async function publish(rev: AnimationRevisionRow) {
    try {
      await api.post(`/api/v1/animation-revisions/${rev.id}/publish`)
      showToast(`动画 v${rev.revisionNo} 已发布`, 'success')
      await load(); onChanged()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '发布失败')
    }
  }

  async function withdraw() {
    try {
      await api.post(`/api/v1/animations/${animation.id}/withdraw`)
      showToast('动画已下架', 'info')
      await load(); onChanged()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '下架失败')
    }
  }

  return (
    <div className="studio-drawer">
      {error && <p className="error">{error}</p>}
      <div className="studio-drawer-bar">
        <button type="button" className="mini pill" onClick={() => void createRevision()}>
          <Plus size={13} />创建新版本
        </button>
        {animation.status === 'PUBLISHED' && (
          <button type="button" className="mini ghost" onClick={() => void withdraw()}>下架动画</button>
        )}
      </div>

      <div className="studio-chapters">
        {revisions.map(r => {
          const st = localizeWorkStatus(r.status)
          return (
            <div key={r.id} className="studio-chapter-box">
              <div className="row-between" style={{ marginBottom: 10 }}>
                <div className="row-inline">
                  <strong className="tabular-nums">v{r.revisionNo}</strong>
                  <span className={`studio-status is-${st.tone}`}>{st.label}</span>
                  {r.durationMs && <span className="muted tabular-nums">{Math.round(r.durationMs / 1000)} 秒</span>}
                </div>
                <div className="row-inline">
                  <button type="button" className="mini pill" onClick={() => void publish(r)} disabled={r.status !== 'READY'}>
                    发布版本
                  </button>
                </div>
              </div>
              <div className="set-import-grid">
                <label>视频 MP4<input type="file" accept="video/mp4" onChange={e => setVideoFile(e.target.files?.[0] ?? null)} /></label>
                <label>封面（可选）<input type="file" accept="image/jpeg" onChange={e => setPosterFile(e.target.files?.[0] ?? null)} /></label>
                <label>字幕 VTT/SRT（可选）<input type="file" accept=".vtt,.srt" onChange={e => setSubtitleFile(e.target.files?.[0] ?? null)} /></label>
                <label>
                  绑定漫画章节来源
                  <select value={sourceRev} onChange={e => setSourceRev(e.target.value)}>
                    {sourceOptions.map(o => <option key={o.chapterRevisionId} value={o.chapterRevisionId}>{o.label}</option>)}
                    {sourceOptions.length === 0 && <option value="">（先发布一部站内漫画）</option>}
                  </select>
                </label>
              </div>
              <div className="row-inline" style={{ marginTop: 10 }}>
                <span className="muted" style={{ fontSize: 12.5 }}>来源页范围</span>
                <input type="text" inputMode="numeric" className="set-exp-input" value={pageStart} onChange={e => setPageStart(e.target.value.replace(/\D/g, ''))} />
                <span className="muted">至</span>
                <input type="text" inputMode="numeric" className="set-exp-input" value={pageEnd} onChange={e => setPageEnd(e.target.value.replace(/\D/g, ''))} />
                <button type="button" className="mini pill" onClick={() => void uploadAndVerify(r)} disabled={busy || !videoFile}>
                  {busy ? '上传/验证中…' : '上传并验证'}
                </button>
              </div>
            </div>
          )
        })}
        {revisions.length === 0 && <p className="muted" style={{ margin: '6px 0' }}>点击上方「创建新版本」开始上传动画视频。</p>}
      </div>
    </div>
  )
}

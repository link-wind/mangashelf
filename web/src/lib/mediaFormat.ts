/**
 * 全站统一元数据清洗与去重工具：
 * 1) 统一判断已完结/连载中，消除「连载中 + 全264话」「每周三放送 + 已完结」矛盾；
 * 2) 过滤简繁冗余、纯数字裸话数与无意义的 "漫画" 占位副文；
 * 3) 清洗 Bangumi 冗长制作会社原串；
 * 4) 拆分中/日文 [简介原文]，支持详情页按需展开；
 * 5) 统一漫画跨源简短徽标名。
 */

const COMPLETED_RE = /(?:已完结|完结|全\s*\d+\s*[话話集回卷]|全集|完本|BD|DVD|剧场版|HD中字|HD)/i
const ONGOING_RE = /(?:更新[至到]|连载中|連載中|连载|連載)/i
const GENERIC_SUBTITLE_SET = new Set(['漫画', '漫畫', '连载中', '連載中', '已完结', '已完結', '完结', '完結', '番剧', '动画'])

export const MANGA_SOURCE_SHORT: Record<string, string> = {
  tencent: '腾讯',
  copymanga: '拷贝',
  bilibili: '哔哩',
  baozimh: '包子',
  hipmh: '嗨皮',
  local: '站内',
}

export const MANGA_SOURCE_FULL: Record<string, string> = {
  tencent: '腾讯动漫',
  copymanga: '拷贝漫画',
  bilibili: '哔哩漫画',
  baozimh: '包子漫画',
  hipmh: '嗨皮漫画',
  local: '站内上传',
}

export function sourceShortLabel(source: string | null | undefined, isLocal = false): string {
  if (isLocal || source === 'local') return '站内'
  if (!source) return ''
  return MANGA_SOURCE_SHORT[source] ?? source
}

export function sourceFullLabel(source: string | null | undefined, isLocal = false): string {
  if (isLocal || source === 'local') return '站内上传'
  if (!source) return '跨源'
  return MANGA_SOURCE_FULL[source] ?? source
}

/** 统一判定作品是否已完结（避免「全264话」被误标为「连载中」）。 */
export function isCompletedStatus(remarks: string | null | undefined, finished?: boolean | null): boolean {
  if (finished === true) return true
  const r = (remarks ?? '').trim()
  if (!r) return false
  if (ONGOING_RE.test(r) && !/全\s*\d+/.test(r)) return false
  return COMPLETED_RE.test(r)
}

export function formatStatusLabel(remarks: string | null | undefined, finished?: boolean | null): '已完结' | '连载中' {
  return isCompletedStatus(remarks, finished) ? '已完结' : '连载中'
}

/** 清洗漫画更新备注：消除「連載中」「更新至 196」等不统一文案，避免与状态胶囊复读。 */
export function cleanMangaRemarks(remarks: string | null | undefined): string {
  const raw = (remarks ?? '').trim()
  if (!raw || GENERIC_SUBTITLE_SET.has(raw)) return ''
  // 纯数字更新：更新至 196 → 更新至第196话
  const bareNum = raw.match(/^(?:更新[至到]|更[至到])\s*(\d+)\s*$/)
  if (bareNum) return `更新至第 ${bareNum[1]} 话`
  // 去掉「更新至 」「更新到 」多余空格
  return raw.replace(/^(更新[至到])\s+/, '$1 ')
}

/** 过滤无意义占位副文（如 `"漫画"` / `"連载中"`）。 */
export function cleanSubtitle(text: string | null | undefined, fallback = ''): string {
  const raw = (text ?? '').trim()
  if (!raw || GENERIC_SUBTITLE_SET.has(raw)) return fallback
  return raw
}

/** 清洗动画剧集标题：避免出现「第 1 话 第01集」这类重复拼接。 */
export function formatEpisodeTitle(ordinal: number | null | undefined, title: string | null | undefined): string {
  const num = ordinal ?? 1
  const raw = (title ?? '').trim()
  if (!raw) return `第 ${num} 集`
  // 标题本身就是「第01集」「01」「第1话」「EP01」等纯编号
  if (/^(?:第?\s*0*\d+\s*[集话話回期]?|ep\.?\s*0*\d+)$/i.test(raw)) {
    return `第 ${num} 集`
  }
  return `第 ${num} 集 · ${raw}`
}

/** 清洗 Bangumi 冗长制作会社原串：仅保留前 1~2 个简洁厂牌名，剔除箭头/括号注释。 */
export function cleanStudioName(raw: string | null | undefined, maxLen = 22): string {
  if (!raw) return ''
  const stripped = raw
    .replace(/\[[^\]]*\]/g, '')
    .replace(/（[^）]*）/g, '')
    .replace(/\([^)]*\)/g, '')
    .replace(/→[^、,/]+/g, '')
    .trim()
  const parts = stripped
    .split(/[、,\/／]+/)
    .map(s => s.trim())
    .filter(s => s.length > 0 && !/^(?:フジテレビ|テレビ東京|TBS|NHK|MBS)$/i.test(s))
  const chosen = (parts.length > 0 ? parts.slice(0, 2).join(' / ') : stripped).trim()
  if (!chosen) return ''
  return chosen.length > maxLen ? `${chosen.slice(0, maxLen)}…` : chosen
}

/** 拆分中/日文简介（部分 Bangumi 条目包含 [简介原文] 段落）。 */
export function splitBilingualSummary(raw: string | null | undefined): { zh: string; original: string | null } {
  const text = (raw ?? '').trim()
  if (!text) return { zh: '', original: null }
  const marker = /(?:\r?\n)*[\[【]\s*(?:简介原文|日文简介|原文简介|原文)\s*[\]】](?:\r?\n)*/
  const m = text.match(marker)
  if (!m || m.index === undefined) {
    return { zh: text, original: null }
  }
  const zh = text.slice(0, m.index).trim()
  const original = text.slice(m.index + m[0].length).trim()
  return {
    zh: zh || original,
    original: zh && original ? original : null,
  }
}

/** 工作状态枚举中文化（用于 /uploads 与本地作品详情）。 */
export function localizeWorkStatus(status: string | null | undefined): { label: string; tone: 'pub' | 'warn' | 'muted' | 'danger' } {
  switch ((status ?? '').toUpperCase()) {
    case 'PUBLISHED': return { label: '已发布', tone: 'pub' }
    case 'READY': return { label: '待发布', tone: 'warn' }
    case 'PROCESSING':
    case 'QUEUED':
    case 'RUNNING':
    case 'PENDING': return { label: '处理中', tone: 'warn' }
    case 'WITHDRAWN': return { label: '已下架', tone: 'muted' }
    case 'FAILED': return { label: '处理失败', tone: 'danger' }
    case 'DRAFT':
    default: return { label: '草稿', tone: 'muted' }
  }
}

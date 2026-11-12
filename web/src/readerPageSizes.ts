export interface PageDimensions {
  width: number
  height: number
}

export type PageSizes = Record<number, PageDimensions>

interface SavedSizes {
  key: string
  sizes: PageSizes
}

const STORAGE_KEY = 'mangashelf.reader.pageSizes.v1'

function readEntries(): SavedSizes[] {
  try {
    const entries: unknown = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '[]')
    if (!Array.isArray(entries)) return []
    return entries.slice(0, 20).filter(entry =>
      typeof entry?.key === 'string' && entry.sizes && typeof entry.sizes === 'object')
  } catch {
    return []
  }
}

export function loadPageSizes(key: string): PageSizes {
  const stored = readEntries().find(entry => entry.key === key)?.sizes ?? {}
  const sizes: PageSizes = {}
  for (const [ordinal, size] of Object.entries(stored).slice(0, 1000)) {
    if (/^[1-9]\d{0,3}$/.test(ordinal) && size
        && Number.isFinite(size.width) && Number.isFinite(size.height)
        && size.width > 0 && size.width <= 100000 && size.height > 0 && size.height <= 100000) {
      sizes[Number(ordinal)] = { width: size.width, height: size.height }
    }
  }
  return sizes
}

export function savePageSizes(key: string, sizes: PageSizes) {
  if (!Object.keys(sizes).length) return
  try {
    const entries = readEntries().filter(entry => entry.key !== key)
    localStorage.setItem(STORAGE_KEY, JSON.stringify([
      { key, sizes: Object.fromEntries(Object.entries(sizes).slice(-1000)) },
      ...entries,
    ].slice(0, 20)))
  } catch {
    return
  }
}

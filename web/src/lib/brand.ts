export const BRAND_NAME = 'GemiNeko'
export const BRAND_NAME_ZH = '杰米猫'
export const BRAND_TITLE_FULL = 'GemiNeko · 杰米猫番漫空间'
export const BRAND_SEO_TITLE = 'GemiNeko（杰米猫）· 番剧与漫画空间 - 每周新番时刻表与高分索引'
export const BRAND_TAGLINE = '杰米猫 · 番剧追更与跨源漫画空间'
export const BRAND_ICON_URL = '/brand-icon.png'
export const DEFAULT_AVATAR_URL = '/default-avatar.png'

export function pageTitle(prefix?: string | null): string {
  const clean = prefix?.trim()
  return clean ? `${clean} · ${BRAND_NAME}` : BRAND_TITLE_FULL
}

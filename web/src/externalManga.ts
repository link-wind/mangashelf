/** 外部漫画封面辅助：统一提档至 Retina 高清尺寸并规避上游失效缩略图。 */

/** 用于 onError：图片加载失败直接隐藏，不回退代理。 */
export function onImgError(e: React.SyntheticEvent<HTMLImageElement>) {
  e.currentTarget.style.display = 'none'
}

/** 列表卡片默认高清归一：腾讯任意数字缩略图后缀 → /0（原图），哔哩低清缩略图 → @600w.webp。 */
export function normalizeCoverUrl(url: string | null | undefined): string {
  if (!url) return ''
  if (url.includes('manhua.acimg.cn')) {
    return url.replace(/\/\d+$/, '/0')
  }
  if (url.includes('hdslb.com')) {
    return url.replace(/@\d+w\.(jpg|jpeg|webp|png)$/i, '@600w.webp')
  }
  return url
}

/** Hero 大海报与氛围背景超清档：腾讯 → /0，哔哩 → @960w.webp。 */
export function hdCover(url: string | null | undefined): string {
  if (!url) return ''
  if (url.includes('manhua.acimg.cn')) return url.replace(/\/\d+$/, '/0')
  if (url.includes('hdslb.com')) return url.replace(/@\d+w\.(jpg|jpeg|webp|png)$/i, '@960w.webp')
  return url
}

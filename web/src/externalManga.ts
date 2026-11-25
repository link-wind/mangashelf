/** 外部漫画封面辅助：规避上游失效缩略图尺寸档位并生成高清封面地址。 */

/** 用于 onError：图片加载失败直接隐藏，不回退代理。 */
export function onImgError(e: React.SyntheticEvent<HTMLImageElement>) {
  e.currentTarget.style.display = 'none'
}

/** 修正上游不可用的缩略图后缀（实测腾讯 manhua.acimg.cn 的 /420 档超时无响应，改走 /0 秒回）。 */
export function normalizeCoverUrl(url: string | null | undefined): string {
  if (!url) return ''
  if (url.includes('manhua.acimg.cn') && /\/420$/.test(url)) {
    return url.replace(/\/420$/, '/0')
  }
  return url
}

/** 封面高清档（Hero 海报与氛围背景用）：腾讯任意数字后缀 → /0（原图），哔哩 @480w → @960w。 */
export function hdCover(url: string | null | undefined): string {
  if (!url) return ''
  if (url.includes('manhua.acimg.cn')) return url.replace(/\/\d+$/, '/0')
  if (url.includes('hdslb.com')) return url.replace(/@\d+w\.(jpg|webp|png)$/i, '@960w.$1')
  return url
}

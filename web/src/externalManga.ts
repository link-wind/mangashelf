/** 外部漫画封面辅助：各源封面均为可直接访问地址（MangaDex 代理已随源移除）。 */

/** 用于 onError：图片加载失败直接隐藏，不回退代理。 */
export function onImgError(e: React.SyntheticEvent<HTMLImageElement>) {
  e.currentTarget.style.display = 'none'
}

/** 封面高清档（M24 hero 背景用）：腾讯 /210→/0（原图），哔哩 @480w→@960w。 */
export function hdCover(url: string | null | undefined): string {
  if (!url) return ''
  if (url.includes('manhua.acimg.cn')) return url.replace(/\/(210|600|0)$/, '/0')
  if (url.includes('hdslb.com')) return url.replace(/@\d+w\.jpg$/, '@960w.jpg')
  return url
}

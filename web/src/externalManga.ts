/** 外部漫画（MangaDex）图片：优先直连 CDN，失败回退本服务代理。 */
export function extImage(url: string | null | undefined): string {
  return url ?? ''
}

export function proxyImage(url: string): string {
  return `/api/v1/external-manga/image?u=${encodeURIComponent(url)}`
}

/** 用于 onError：首次失败换成代理地址，再失败隐藏。 */
export function onImgError(e: React.SyntheticEvent<HTMLImageElement>) {
  const img = e.currentTarget
  const original = img.dataset.original || img.src
  if (!original) return
  if (!img.dataset.proxied) {
    img.dataset.original = original
    img.dataset.proxied = '1'
    img.src = proxyImage(original)
  } else {
    img.style.display = 'none'
  }
}

/** 站内截图 URL（canvas 源）：直接返回，浏览器直连本服务。 */
export function capturedImage(url: string | null | undefined): string {
  if (!url) return ''
  if (url.startsWith('/captured/')) return url
  return url
}

/** 封面高清档（M24 hero 背景用）：腾讯 /210→/0（原图），哔哩 @480w→@960w。 */
export function hdCover(url: string | null | undefined): string {
  if (!url) return ''
  if (url.includes('manhua.acimg.cn')) return url.replace(/\/(210|600|0)$/, '/0')
  if (url.includes('hdslb.com')) return url.replace(/@\d+w\.jpg$/, '@960w.jpg')
  return url
}

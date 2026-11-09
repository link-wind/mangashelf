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

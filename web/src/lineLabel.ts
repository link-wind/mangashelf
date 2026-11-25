/** 将底层采集线路/源标识转换为面向用户的友好展示名，避免暴露爬虫或资源站代号。 */
export function formatAnimeLine(index: number): string {
  return `国内线路 ${index + 1}`
}

export function formatMangaLine(index: number): string {
  return `阅读线路 ${index + 1}`
}

// API 封装（DESIGN 16.2 错误结构 / SPA CSRF）。
// CSRF：从 XSRF-TOKEN Cookie 读取原始令牌，写 X-XSRF-TOKEN 头（Cookie 值已 URL 编码，需解码）。

export class ApiError extends Error {
  constructor(public code: string, message: string, public status: number,
              public details?: Record<string, unknown>) {
    super(message)
  }
}

function readCookie(name: string): string | null {
  const hit = document.cookie.split('; ').find(c => c.startsWith(name + '='))
  return hit ? decodeURIComponent(hit.slice(name.length + 1)) : null
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const xsrf = readCookie('XSRF-TOKEN')
  if (xsrf && method !== 'GET') headers['X-XSRF-TOKEN'] = xsrf

  const res = await fetch(path, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (res.status === 204) return undefined as T
  const data = await res.json().catch(() => ({}))
  if (!res.ok) {
    throw new ApiError(data.code ?? 'UNKNOWN', data.message ?? `请求失败（${res.status}）`, res.status, data.details)
  }
  return data as T
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
  patch: <T>(path: string, body?: unknown) => request<T>('PATCH', path, body),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body ?? {}),
  delete: <T>(path: string) => request<T>('DELETE', path),
}

/** 上传二进制到预签名地址（同源 /media 代理，避免跨域）。 */
export async function putPresigned(url: string, contentType: string, file: Blob): Promise<void> {
  const sameOrigin = url.replace(/^https?:\/\/[^/]+/, '/media')
  const res = await fetch(sameOrigin, {
    method: 'PUT',
    headers: { 'Content-Type': contentType },
    body: file,
  })
  if (!res.ok) throw new ApiError('UPLOAD_FAILED', `上传失败（${res.status}）`, res.status)
}

/** 签名读地址改走同源代理（D9）。 */
export function mediaUrl(url: string): string {
  return url.replace(/^https?:\/\/[^/]+/, '/media')
}

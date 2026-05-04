/**
 * Simple fetch wrapper that prepends the Tramai API base URL.
 *
 * The base URL is injected by tramai-settings.js via window.__TRAMAI__.apiBaseUrl
 * before the Vue app mounts.
 */

function baseUrl(): string {
  return window.__TRAMAI__?.apiBaseUrl ?? ''
}

function buildUrl(path: string, params?: Record<string, string>): string {
  const url = new URL(`${baseUrl()}${path}`, window.location.origin)
  if (params) {
    Object.entries(params).forEach(([k, v]) => url.searchParams.set(k, v))
  }
  return url.toString()
}

export interface ApiResponse<T> {
  data: T | null
  error: string | null
  status: number
}

export async function apiGet<T>(path: string, params?: Record<string, string>): Promise<ApiResponse<T>> {
  try {
    const res = await fetch(buildUrl(path, params))
    if (!res.ok) {
      return { data: null, error: `HTTP ${res.status}: ${res.statusText}`, status: res.status }
    }
    const data = await res.json() as T
    return { data, error: null, status: res.status }
  } catch (e) {
    return { data: null, error: (e as Error).message, status: 0 }
  }
}

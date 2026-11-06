const BASE = '/api/v1'

interface ApiResult<T> {
  code: number
  message: string
  data: T
}

export class ApiError extends Error {
  constructor(
    public code: number,
    message: string,
  ) {
    super(message)
  }
}

let accessToken = localStorage.getItem('lanjie.access') ?? ''
let refreshTokenValue = localStorage.getItem('lanjie.refresh') ?? ''

export function setTokens(access: string, refresh: string) {
  accessToken = access
  refreshTokenValue = refresh
  localStorage.setItem('lanjie.access', access)
  localStorage.setItem('lanjie.refresh', refresh)
}

export function getAccessToken(): string {
  return accessToken
}

export function clearTokens() {
  accessToken = ''
  refreshTokenValue = ''
  localStorage.removeItem('lanjie.access')
  localStorage.removeItem('lanjie.refresh')
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'DELETE' | 'PUT'
  body?: unknown
  /** 需要登录态的请求；401 时自动用 refreshToken 续期重试一次 */
  auth?: boolean
  /** 写接口防重复提交 */
  idempotencyKey?: string
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, auth = false, idempotencyKey } = options

  const send = (token: string | null) =>
    fetch(BASE + path, {
      method,
      headers: {
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    }).then(async (response) => {
      const result = (await response.json()) as ApiResult<T> | null
      return { status: response.status, result }
    })

  let { status, result } = await send(auth ? accessToken : null)

  if (status === 401 && auth && refreshTokenValue) {
    const renewed = await renewSession()
    if (renewed) {
      ;({ status, result } = await send(accessToken))
    } else {
      clearTokens()
      window.location.href = '/login'
      throw new ApiError(401, '登录已失效，请重新登录')
    }
  }

  if (!result) {
    throw new ApiError(status, '服务无响应，请稍后再试')
  }
  if (result.code !== 0) {
    throw new ApiError(result.code, result.message)
  }
  return result.data
}

/** 用 refreshToken 换新 token 对；成功返回 true */
async function renewSession(): Promise<boolean> {
  try {
    const response = await fetch(BASE + '/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: refreshTokenValue }),
    })
    const payload = (await response.json()) as ApiResult<{ accessToken: string; refreshToken: string }>
    if (response.status !== 401 && payload.code === 0) {
      setTokens(payload.data.accessToken, payload.data.refreshToken)
      return true
    }
    return false
  } catch {
    return false
  }
}

/** 登出时通知后端撤销 refresh token；失败不阻断本地登出 */
export async function revokeServerSession(): Promise<void> {
  if (!refreshTokenValue) return
  try {
    await fetch(BASE + '/auth/logout', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: refreshTokenValue }),
    })
  } catch {
    // 本地照常登出
  }
}

import { t } from '@/i18n'
import axios, { type AxiosError, type AxiosInstance, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { pinia } from '@/stores'
import type { ApiErrorEnvelope } from '@/types/models'
import { getAccessToken } from './token'

/** 统一业务错误：页面据此渲染错误态，拦截器据此提示。 */
export class AppError extends Error {
  readonly code: string
  readonly status: number
  readonly details: unknown
  readonly requestId?: string

  constructor(options: {
    code: string
    message: string
    status: number
    details?: unknown
    requestId?: string
    cause?: unknown
  }) {
    super(options.message)
    this.name = 'AppError'
    this.code = options.code
    this.status = options.status
    this.details = options.details ?? null
    this.requestId = options.requestId
    if (options.cause !== undefined) {
      this.cause = options.cause
    }
  }
}

declare module 'axios' {
  interface AxiosRequestConfig {
    /** 静默请求：不弹全局错误提示（表单内联错误、启动引导等场景使用） */
    silent?: boolean
    /** 内部标记：401 刷新后是否已重试过一次 */
    retried?: boolean
  }
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'
const NETWORK_ERROR_MESSAGE = t('error.network')
const AUTH_ENDPOINTS = ['/auth/login', '/auth/register', '/auth/refresh']

const STATUS_MESSAGES: Record<number, string> = {
  400: t('error.badRequest'),
  401: t('error.unauthorized'),
  403: t('error.forbidden'),
  404: t('error.notFound'),
  409: t('error.conflict'),
  422: t('error.businessRule'),
  429: t('error.tooManyRequests'),
  500: t('error.server'),
  502: t('error.unavailable'),
  503: t('error.unavailable'),
}

let lastToast = { message: '', at: 0 }

function showError(message: string): void {
  const now = Date.now()
  if (message === lastToast.message && now - lastToast.at < 2000) return
  lastToast = { message, at: now }
  ElMessage.error(message)
}

function toAppError(error: AxiosError<ApiErrorEnvelope>): AppError {
  if (!error.response) {
    return new AppError({ code: 'NETWORK_ERROR', message: NETWORK_ERROR_MESSAGE, status: 0, cause: error })
  }
  const { status, data } = error.response
  const envelope = data && typeof data === 'object' && 'error' in data ? (data as ApiErrorEnvelope).error : undefined
  // 代理层（Vite/Nginx）在服务不可达时可能返回 HTML 错误页而非统一错误信封。
  // 只有网关级失败才算「连不上服务器」；4xx（如 CORS 拒绝返回 403 Invalid CORS request）
  // 必须保留真实状态语义，否则用户会误以为后端没启动。
  if (!envelope && (typeof data === 'string' || data === undefined)) {
    const body = typeof data === 'string' ? data : ''
    const gatewayFailure = status === 502 || status === 503 || status === 504
      || /proxy|ECONNREFUSED|socket hang up|ERR_CONNECTION/i.test(body)
    if (gatewayFailure) {
      return new AppError({
        code: 'NETWORK_ERROR',
        message: NETWORK_ERROR_MESSAGE,
        status,
        cause: error,
      })
    }
    return new AppError({
      code: `HTTP_${status}`,
      message: STATUS_MESSAGES[status] ?? t('error.httpStatus', { status }),
      status,
      cause: error,
    })
  }
  return new AppError({
    code: envelope?.code ?? `HTTP_${status}`,
    message: envelope?.message ?? STATUS_MESSAGES[status] ?? t('error.generic'),
    status,
    details: envelope?.details,
    requestId: envelope?.requestId,
    cause: error,
  })
}

export function resolveErrorMessage(error: unknown, fallback = t('error.generic')): string {
  if (error instanceof AppError) return error.message
  if (error instanceof Error && error.message) return error.message
  return fallback
}

let refreshPromise: Promise<string | null> | null = null

/** 单飞刷新：并发 401 只触发一次 /auth/refresh，避免刷新令牌竞态。 */
function refreshAccessTokenOnce(): Promise<string | null> {
  if (!refreshPromise) {
    refreshPromise = (async () => {
      try {
        const { useAuthStore } = await import('@/stores/auth')
        const auth = useAuthStore(pinia)
        return await auth.refresh()
      } finally {
        refreshPromise = null
      }
    })()
  }
  return refreshPromise
}

function attachToken(config: InternalAxiosRequestConfig): void {
  const token = getAccessToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
}

function createHttp(): AxiosInstance {
  const instance = axios.create({
    baseURL: API_BASE_URL,
    withCredentials: true,
    timeout: 20000,
  })

  instance.interceptors.request.use((config) => {
    attachToken(config)
    return config
  })

  instance.interceptors.response.use(
    (response) => response,
    async (error: AxiosError<ApiErrorEnvelope>) => {
      const config = error.config
      const status = error.response?.status
      const url = config?.url ?? ''
      const isAuthEndpoint = AUTH_ENDPOINTS.some((endpoint) => url.includes(endpoint))

      if (status === 401 && config && !config.retried && !isAuthEndpoint) {
        config.retried = true
        try {
          const token = await refreshAccessTokenOnce()
          if (token) {
            config.headers.Authorization = `Bearer ${token}`
            return instance.request(config)
          }
        } catch {
          // 刷新失败：清理会话，按普通错误继续提示
        }
        try {
          const { useAuthStore } = await import('@/stores/auth')
          useAuthStore(pinia).clearSession()
        } catch {
          // store 尚未初始化（启动阶段）时忽略，避免错误处理自身抛异常
        }
      }

      const appError = toAppError(error)
      if (!config?.silent) {
        showError(appError.message)
      }
      return Promise.reject(appError)
    },
  )

  return instance
}

export const http = createHttp()

export { API_BASE_URL }

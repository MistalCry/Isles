import { t } from '@/i18n'
import axios from 'axios'
import type { AuthResponse, CurrentUser, LoginPayload, RegisterPayload } from '@/types/models'
import { API_BASE_URL, http } from './client'
import { toUserSummary } from './adapters'

/**
 * 刷新令牌使用独立的裸 axios 实例：
 * 不经过业务拦截器，避免 401 -> refresh -> 401 的递归。
 */
const refreshClient = axios.create({
  baseURL: API_BASE_URL,
  withCredentials: true,
  timeout: 15000,
})

export async function login(payload: LoginPayload): Promise<AuthResponse> {
  const { data } = await http.post('/auth/login', payload, { silent: true })
  const record = (data ?? {}) as Record<string, unknown>
  return {
    accessToken: typeof record.accessToken === 'string' ? record.accessToken : '',
    user: record.user ? (toUserSummary(record.user) as CurrentUser) : null,
  }
}

export async function register(payload: RegisterPayload): Promise<CurrentUser> {
  const { data } = await http.post('/auth/register', payload, { silent: true })
  return toUserSummary(data) as CurrentUser
}

export async function refreshAccessToken(): Promise<string> {
  const { data } = await refreshClient.post('/auth/refresh')
  if (!data?.accessToken) {
    throw new Error(t('auth.refreshFailed'))
  }
  return data.accessToken
}

export async function logout(): Promise<void> {
  await http.post('/auth/logout', undefined, { silent: true })
}

export async function getMe(): Promise<CurrentUser> {
  const { data } = await http.get('/auth/me', { silent: true })
  return toUserSummary(data) as CurrentUser
}

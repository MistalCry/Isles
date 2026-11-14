/**
 * accessToken 只保存在内存（Pinia 之外的低层引用避免请求层与 store 循环依赖），
 * 刷新令牌由 HttpOnly Cookie 持有（docs/02 §6.2）。
 */
let accessToken: string | null = null

export function getAccessToken(): string | null {
  return accessToken
}

export function setAccessToken(token: string | null): void {
  accessToken = token
}

export function hasAccessToken(): boolean {
  return Boolean(accessToken)
}

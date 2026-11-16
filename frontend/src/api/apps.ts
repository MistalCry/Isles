import { http } from './client'

export type ChannelAppType = 'VOICE_ROOM' | 'LIVE_ROOM' | 'CUSTOM'

export interface ChannelApp {
  id: string
  channelId?: string
  type: ChannelAppType
  name: string
  iconUrl?: string | null
  description?: string | null
  enabled: boolean
  sort: number
}

export interface ChannelAppPayload {
  type: ChannelAppType
  name: string
  iconUrl?: string | null
  description?: string | null
  enabled?: boolean
  sort?: number
}

function str(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value : null
}

function typeOf(value: unknown): ChannelAppType {
  const type = String(value ?? 'CUSTOM').toUpperCase()
  return type === 'VOICE_ROOM' || type === 'LIVE_ROOM' ? type : 'CUSTOM'
}

/**
 * 后端 `AppView` 用 `appType` / `sortOrder`，前端统一成 type / sort。
 * 仍兼容旧字段名，避免后端调整时整块炸掉。
 */
export function toChannelApp(value: unknown): ChannelApp {
  const raw = (value ?? {}) as Record<string, unknown>
  return {
    id: String(raw.id ?? ''),
    channelId: str(raw.channelId) ?? undefined,
    type: typeOf(raw.appType ?? raw.type),
    name: String(raw.name ?? ''),
    iconUrl: str(raw.iconUrl) ?? str(raw.icon),
    description: str(raw.description),
    enabled: raw.enabled === undefined ? true : Boolean(raw.enabled),
    sort: Number(raw.sortOrder ?? raw.sort ?? 0) || 0,
  }
}

function itemsOf(data: unknown): unknown[] {
  if (Array.isArray(data)) return data
  const record = (data ?? {}) as Record<string, unknown>
  return Array.isArray(record.items) ? record.items : []
}

function toPayload(payload: Partial<ChannelAppPayload>): Record<string, unknown> {
  const body: Record<string, unknown> = {}
  if (payload.type !== undefined) body.appType = payload.type
  if (payload.name !== undefined) body.name = payload.name
  if (payload.iconUrl !== undefined) body.iconUrl = payload.iconUrl
  if (payload.description !== undefined) body.description = payload.description
  if (payload.enabled !== undefined) body.enabled = payload.enabled
  if (payload.sort !== undefined) body.sortOrder = payload.sort
  return body
}

/** 公开：频道应用列表（后端按 sortOrder 升序返回） */
export async function listChannelApps(channelId: string): Promise<ChannelApp[]> {
  const { data } = await http.get(`/channels/${encodeURIComponent(channelId)}/apps`)
  return itemsOf(data)
    .map(toChannelApp)
    .sort((a, b) => a.sort - b.sort)
}

export async function createChannelApp(channelId: string, payload: ChannelAppPayload): Promise<ChannelApp> {
  const { data } = await http.post(`/channels/${encodeURIComponent(channelId)}/apps`, toPayload(payload))
  return toChannelApp(data)
}

export async function updateChannelApp(
  channelId: string,
  appId: string,
  payload: Partial<ChannelAppPayload>,
): Promise<ChannelApp> {
  const { data } = await http.put(
    `/channels/${encodeURIComponent(channelId)}/apps/${encodeURIComponent(appId)}`,
    toPayload(payload),
  )
  return toChannelApp(data)
}

export async function deleteChannelApp(channelId: string, appId: string): Promise<void> {
  await http.delete(`/channels/${encodeURIComponent(channelId)}/apps/${encodeURIComponent(appId)}`)
}

/** 排序：按传入顺序落 sortOrder=index */
export async function reorderChannelApps(channelId: string, appIds: string[]): Promise<ChannelApp[]> {
  const { data } = await http.put(`/channels/${encodeURIComponent(channelId)}/apps/order`, { appIds })
  return itemsOf(data)
    .map(toChannelApp)
    .sort((a, b) => a.sort - b.sort)
}

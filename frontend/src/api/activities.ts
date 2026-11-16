import { http } from './client'

/** 发布状态：草稿 → 已发布 → 已取消（后端 ActivityView.status） */
export type ActivityPublishState = 'DRAFT' | 'PUBLISHED' | 'CANCELLED'
/** 展示状态：按开始/结束时间推算（仅对已发布活动有意义） */
export type ActivityStatus = 'UPCOMING' | 'ONGOING' | 'ENDED'

export interface ChannelActivity {
  id: string
  channelId?: string
  title: string
  description?: string | null
  location?: string | null
  startsAt: string
  endsAt?: string | null
  capacity?: number | null
  signupCount: number
  signedUp: boolean
  publishState: ActivityPublishState
  status: ActivityStatus
  createdAt?: string | null
}

export interface ActivityPayload {
  title: string
  description?: string | null
  location?: string | null
  /** ISO 时间串 */
  startsAt: string
  endsAt?: string | null
  capacity?: number | null
}

function str(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value : null
}

function publishStateOf(raw: Record<string, unknown>): ActivityPublishState {
  const value = String(raw.status ?? 'PUBLISHED').toUpperCase()
  if (value === 'DRAFT' || value === 'CANCELLED') return value
  return 'PUBLISHED'
}

function resolveTimeStatus(startsAt: string, endsAt: string | null): ActivityStatus {
  const now = Date.now()
  const start = new Date(startsAt).getTime()
  const end = endsAt ? new Date(endsAt).getTime() : start + 2 * 60 * 60 * 1000
  if (Number.isNaN(start)) return 'UPCOMING'
  if (now < start) return 'UPCOMING'
  if (now <= end) return 'ONGOING'
  return 'ENDED'
}

export function toChannelActivity(value: unknown): ChannelActivity | null {
  if (!value || typeof value !== 'object') return null
  const raw = value as Record<string, unknown>
  const id = str(raw.id)
  const startsAt = str(raw.startAt) ?? str(raw.startsAt) ?? str(raw.startTime)
  if (!id || !startsAt) return null
  const endsAt = str(raw.endAt) ?? str(raw.endsAt) ?? str(raw.endTime)
  return {
    id,
    channelId: str(raw.channelId) ?? undefined,
    title: String(raw.title ?? ''),
    description: str(raw.description),
    location: str(raw.location) ?? str(raw.place),
    startsAt,
    endsAt,
    capacity: raw.capacity === undefined || raw.capacity === null ? null : Number(raw.capacity),
    signupCount: Number(raw.goingCount ?? raw.signupCount ?? 0) || 0,
    signedUp: Boolean(raw.signedUp ?? raw.joined ?? false),
    publishState: publishStateOf(raw),
    status: resolveTimeStatus(startsAt, endsAt),
    createdAt: str(raw.createdAt),
  }
}

function itemsOf(data: unknown): unknown[] {
  if (Array.isArray(data)) return data
  const record = (data ?? {}) as Record<string, unknown>
  return Array.isArray(record.items) ? record.items : []
}

/**
 * 频道活动列表。
 * 后端默认只返回已发布（PUBLISHED）的活动；管理中心要传 status=DRAFT 才能看到草稿。
 */
export async function listChannelActivities(
  channelId: string,
  options: { status?: ActivityPublishState; page?: number; size?: number } = {},
): Promise<ChannelActivity[]> {
  const { data } = await http.get(`/channels/${encodeURIComponent(channelId)}/activities`, {
    params: {
      status: options.status,
      page: options.page ?? 1,
      size: options.size ?? 50,
    },
  })
  return itemsOf(data)
    .map(toChannelActivity)
    .filter((item): item is ChannelActivity => Boolean(item))
    .sort((a, b) => new Date(a.startsAt).getTime() - new Date(b.startsAt).getTime())
}

/** 管理中心：草稿 + 已发布一起取（后端一次只认一个 status） */
export async function listManageActivities(channelId: string): Promise<ChannelActivity[]> {
  const [drafts, published] = await Promise.all([
    listChannelActivities(channelId, { status: 'DRAFT' }).catch(() => []),
    listChannelActivities(channelId, { status: 'PUBLISHED' }).catch(() => []),
  ])
  return [...drafts, ...published].sort(
    (a, b) => new Date(a.startsAt).getTime() - new Date(b.startsAt).getTime(),
  )
}

function toPayload(payload: Partial<ActivityPayload>): Record<string, unknown> {
  const body: Record<string, unknown> = {}
  if (payload.title !== undefined) body.title = payload.title
  if (payload.description !== undefined) body.description = payload.description
  if (payload.location !== undefined) body.location = payload.location
  if (payload.startsAt !== undefined) body.startAt = payload.startsAt
  if (payload.endsAt !== undefined) body.endAt = payload.endsAt
  if (payload.capacity !== undefined) body.capacity = payload.capacity
  return body
}

export async function createChannelActivity(
  channelId: string,
  payload: ActivityPayload,
): Promise<ChannelActivity | null> {
  const { data } = await http.post(`/channels/${encodeURIComponent(channelId)}/activities`, toPayload(payload))
  return toChannelActivity(data)
}

export async function updateChannelActivity(
  activityId: string,
  payload: Partial<ActivityPayload>,
): Promise<ChannelActivity | null> {
  const { data } = await http.put(`/activities/${encodeURIComponent(activityId)}`, toPayload(payload))
  return toChannelActivity(data)
}

export async function deleteChannelActivity(activityId: string): Promise<void> {
  await http.delete(`/activities/${encodeURIComponent(activityId)}`)
}

/** 草稿 → 已发布 */
export async function publishChannelActivity(activityId: string): Promise<ChannelActivity | null> {
  const { data } = await http.post(`/activities/${encodeURIComponent(activityId)}/publish`)
  return toChannelActivity(data)
}

/** 已发布 → 已取消（保留记录，不再展示在频道页） */
export async function cancelChannelActivity(activityId: string): Promise<ChannelActivity | null> {
  const { data } = await http.post(`/activities/${encodeURIComponent(activityId)}/cancel`)
  return toChannelActivity(data)
}

export async function signupActivity(activityId: string): Promise<void> {
  await http.post(`/activities/${encodeURIComponent(activityId)}/signup`)
}

export async function cancelActivitySignup(activityId: string): Promise<void> {
  await http.delete(`/activities/${encodeURIComponent(activityId)}/signup`)
}

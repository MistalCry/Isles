import type { UserSummary } from '@/types/models'
import { http } from './client'

export type VoiceRoomStatus = 'ACTIVE' | 'CLOSED'

export interface VoiceSeat {
  index: number
  userId?: string | null
  user?: UserSummary | null
  muted?: boolean
}

export interface VoiceRoom {
  id: string
  channelId: string
  appId?: string | null
  title: string
  ownerId?: string | null
  status: VoiceRoomStatus
  seatCount: number
  seats: VoiceSeat[]
}

function str(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value : null
}

/**
 * 后端 `SeatView` 是扁平字段（seatIndex/userId/nickname/avatarUrl），
 * 这里拼成前端统一的 `user` 结构，麦位渲染只认一种形状。
 */
function seatUser(raw: Record<string, unknown>): UserSummary | null {
  const id = str(raw.userId)
  if (!id) return null
  return {
    id,
    username: str(raw.username) ?? '',
    nickname: str(raw.nickname),
    avatarUrl: str(raw.avatarUrl),
  } as UserSummary
}

function seatsOf(raw: Record<string, unknown>, count: number): VoiceSeat[] {
  const list = Array.isArray(raw.seats) ? raw.seats : []
  const seats: VoiceSeat[] = list.map((item, i) => {
    const seat = (item ?? {}) as Record<string, unknown>
    const user = seatUser(seat)
    return {
      index: Number(seat.seatIndex ?? seat.index ?? i),
      userId: user?.id ?? null,
      user,
      muted: Boolean(seat.muted),
    }
  })
  // 后端只回已占用麦位时补空位，保证网格始终是 seatCount 个
  for (let i = 0; i < count; i++) {
    if (!seats.some((seat) => seat.index === i)) seats.push({ index: i, userId: null, user: null, muted: false })
  }
  return seats.sort((a, b) => a.index - b.index)
}

export function toVoiceRoom(value: unknown): VoiceRoom | null {
  if (!value || typeof value !== 'object') return null
  const raw = value as Record<string, unknown>
  const id = str(raw.id)
  if (!id) return null
  const seatCount = Number(raw.maxSeats ?? raw.seatCount ?? 8) || 8
  return {
    id,
    channelId: String(raw.channelId ?? ''),
    appId: str(raw.appId),
    title: String(raw.title ?? ''),
    ownerId: str(raw.hostId) ?? str(raw.ownerId),
    status: String(raw.status ?? 'ACTIVE').toUpperCase() === 'CLOSED' ? 'CLOSED' : 'ACTIVE',
    seatCount,
    seats: seatsOf(raw, seatCount),
  }
}

/** 频道内进行中的语音房（后端只返回 open 房间，可能为空数组） */
export async function listOpenVoiceRooms(channelId: string): Promise<VoiceRoom[]> {
  const { data } = await http.get(`/channels/${encodeURIComponent(channelId)}/voice-rooms`)
  const list = Array.isArray(data) ? data : []
  return list
    .map(toVoiceRoom)
    .filter((room): room is VoiceRoom => room !== null && room.status === 'ACTIVE')
}

export async function createVoiceRoom(
  channelId: string,
  payload: { title: string; maxSeats?: number; appId?: string | null },
): Promise<VoiceRoom> {
  const { data } = await http.post(`/channels/${encodeURIComponent(channelId)}/voice-rooms`, {
    title: payload.title,
    maxSeats: payload.maxSeats,
    appId: payload.appId ?? undefined,
  })
  const room = toVoiceRoom(data)
  if (!room) throw new Error('invalid room payload')
  return room
}

export async function getVoiceRoom(roomId: string): Promise<VoiceRoom> {
  const { data } = await http.get(`/voice-rooms/${encodeURIComponent(roomId)}`)
  const room = toVoiceRoom(data)
  if (!room) throw new Error('invalid room payload')
  return room
}

export async function joinVoiceRoom(roomId: string): Promise<VoiceRoom | null> {
  const { data } = await http.post(`/voice-rooms/${encodeURIComponent(roomId)}/join`)
  return toVoiceRoom(data)
}

export async function takeVoiceSeat(roomId: string, seatIndex: number): Promise<VoiceRoom | null> {
  const { data } = await http.post(`/voice-rooms/${encodeURIComponent(roomId)}/seats`, { seatIndex })
  return toVoiceRoom(data)
}

/** 下麦：后端按「当前用户的麦位」处理，不需要传序号 */
export async function releaseVoiceSeat(roomId: string): Promise<VoiceRoom | null> {
  const { data } = await http.delete(`/voice-rooms/${encodeURIComponent(roomId)}/seats/me`)
  return toVoiceRoom(data)
}

/** 静音状态挂在麦位上（其他端据此显示麦克风图标） */
export async function setSeatMuted(roomId: string, seatIndex: number, muted: boolean): Promise<void> {
  await http.post(`/voice-rooms/${encodeURIComponent(roomId)}/seats/${seatIndex}/mute`, { muted })
}

export async function closeVoiceRoom(roomId: string): Promise<void> {
  await http.post(`/voice-rooms/${encodeURIComponent(roomId)}/close`)
}

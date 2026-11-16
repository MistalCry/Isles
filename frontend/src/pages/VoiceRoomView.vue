<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Close, Microphone, Mute, VideoPlay } from '@element-plus/icons-vue'
import { channelsApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import {
  closeVoiceRoom,
  createVoiceRoom,
  getVoiceRoom,
  joinVoiceRoom,
  listOpenVoiceRooms,
  releaseVoiceSeat,
  setSeatMuted,
  takeVoiceSeat,
  type VoiceRoom,
  type VoiceSeat,
} from '@/api/voice'
import { useAuthStore } from '@/stores/auth'
import { useRealtimeStore } from '@/stores/realtime'
import type { Channel } from '@/types/models'
import UserAvatar from '@/components/UserAvatar.vue'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'

const { t } = useI18n()

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const realtime = useRealtimeStore()

const channelId = computed(() => String(route.params.id ?? ''))
const myId = computed(() => auth.currentUser?.id ?? '')

const channel = ref<Channel | null>(null)
const room = ref<VoiceRoom | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const creating = ref(false)
const seatBusy = ref(false)
const leaving = ref(false)

const createForm = ref({ title: '', seatCount: 8 })

const localStream = ref<MediaStream | null>(null)
const muted = ref(false)
const micReady = ref(false)

/** P2P mesh：每个远端一个 RTCPeerConnection + 一个 audio 元素 */
interface Peer {
  pc: RTCPeerConnection
  audio: HTMLAudioElement
}
const peers = new Map<string, Peer>()
const unsubscribers: Array<() => void> = []
const signalingReady = ref(false)

const mySeat = computed<VoiceSeat | null>(() => {
  if (!room.value || !myId.value) return null
  return room.value.seats.find((seat) => seat.userId === myId.value) ?? null
})
const seated = computed(() => Boolean(mySeat.value))
const isOwner = computed(() => {
  if (!room.value || !myId.value) return false
  return room.value.ownerId === myId.value
})
/** 在麦上的人（契约里没有听众列表，mesh 只在麦位之间建连） */
const speakers = computed(() => (room.value?.seats ?? []).filter((seat) => Boolean(seat.userId)))

/** 固定 id 比较决定谁发 offer，避免双方同时 offer 造成 glare */
function isInitiator(peerId: string): boolean {
  return myId.value < peerId
}

function sendSignal(to: string, type: 'offer' | 'answer' | 'ice', payload: unknown): void {
  if (!room.value) return
  realtime.publish(`/app/voice/${room.value.id}/signal`, { to, type, payload })
}

function ensurePeer(peerId: string): Peer {
  const existing = peers.get(peerId)
  if (existing) return existing
  const pc = new RTCPeerConnection({
    // 本机/局域网用 host candidate 即可；公网需要 TURN，这里先给 STUN
    iceServers: [{ urls: 'stun:stun.l.google.com:19302' }],
  })
  const audio = new Audio()
  audio.autoplay = true
  const peer: Peer = { pc, audio }

  const stream = localStream.value
  if (stream) {
    for (const track of stream.getAudioTracks()) pc.addTrack(track, stream)
  }
  pc.onicecandidate = (event) => {
    if (event.candidate) sendSignal(peerId, 'ice', { candidate: event.candidate.toJSON() })
  }
  pc.ontrack = (event) => {
    audio.srcObject = event.streams[0] ?? new MediaStream([event.track])
    void audio.play().catch(() => undefined)
  }
  peers.set(peerId, peer)
  return peer
}

function dropPeer(peerId: string): void {
  const peer = peers.get(peerId)
  if (!peer) return
  peer.pc.close()
  peer.audio.srcObject = null
  peers.delete(peerId)
}

async function callPeer(peerId: string): Promise<void> {
  const { pc } = ensurePeer(peerId)
  const offer = await pc.createOffer()
  await pc.setLocalDescription(offer)
  sendSignal(peerId, 'offer', { sdp: pc.localDescription })
}

/**
 * 信令负载：后端透传 `{to, type, payload}`，并包一层
 * `{ id, type: 'VOICE_SIGNAL', ts, version, data: { roomId, from, data } }`。
 */
async function handleSignal(message: unknown): Promise<void> {
  const envelope = (message ?? {}) as Record<string, unknown>
  const data = (envelope.data ?? envelope) as Record<string, unknown>
  const from = String(data.from ?? '')
  const inner = (data.data ?? data) as Record<string, unknown>
  const to = String(inner.to ?? '')
  const type = String(inner.type ?? '')
  const payload = (inner.payload ?? {}) as Record<string, unknown>
  if (!from || from === myId.value) return
  if (to && to !== myId.value) return
  try {
    if (type === 'offer') {
      const { pc } = ensurePeer(from)
      await pc.setRemoteDescription(payload.sdp as RTCSessionDescriptionInit)
      const answer = await pc.createAnswer()
      await pc.setLocalDescription(answer)
      sendSignal(from, 'answer', { sdp: pc.localDescription })
    } else if (type === 'answer') {
      const peer = peers.get(from)
      if (peer) await peer.pc.setRemoteDescription(payload.sdp as RTCSessionDescriptionInit)
    } else if (type === 'ice') {
      const peer = peers.get(from)
      if (peer && payload.candidate) await peer.pc.addIceCandidate(payload.candidate as RTCIceCandidateInit)
    }
  } catch {
    // 单个信令失败不打断房间，下一次 state 刷新会重建连接
  }
}

/** 状态主题只推「房间有变化」的轻提示，真实状态以 REST 为准 */
let refreshTimer: number | null = null
function scheduleRefresh(): void {
  if (refreshTimer !== null) return
  refreshTimer = window.setTimeout(() => {
    refreshTimer = null
    void refreshRoom()
  }, 600)
}

async function refreshRoom(): Promise<void> {
  if (!room.value) return
  try {
    const next = await getVoiceRoom(room.value.id)
    if (next.status === 'CLOSED') {
      cleanup()
      room.value = null
      ElMessage.info(t('voice.closedByHost'))
      return
    }
    room.value = next
    void reconcilePeers()
  } catch {
    // 刷新失败保留当前快照
  }
}

/** 麦位变化时对齐连接：新上麦的建连、下麦的拆掉 */
async function reconcilePeers(): Promise<void> {
  const ids = speakers.value.map((seat) => seat.userId as string).filter((id) => id && id !== myId.value)
  for (const peerId of [...peers.keys()]) {
    if (!ids.includes(peerId)) dropPeer(peerId)
  }
  for (const peerId of ids) {
    if (!peers.has(peerId) && isInitiator(peerId)) {
      await callPeer(peerId).catch(() => undefined)
    }
  }
}

function subscribeRoom(roomId: string): void {
  unsubscribers.push(
    realtime.subscribe(`/topic/voice/${roomId}/signal`, (body) => {
      void handleSignal(body)
    }),
  )
  unsubscribers.push(
    realtime.subscribe(`/topic/voice/${roomId}/state`, () => {
      scheduleRefresh()
    }),
  )
}

function unsubscribeAll(): void {
  while (unsubscribers.length > 0) unsubscribers.pop()?.()
  if (refreshTimer !== null) {
    window.clearTimeout(refreshTimer)
    refreshTimer = null
  }
}

async function ensureLocalStream(): Promise<boolean> {
  if (localStream.value) return true
  if (!navigator.mediaDevices?.getUserMedia) {
    ElMessage.error(t('voice.micUnsupported'))
    return false
  }
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false })
    localStream.value = stream
    micReady.value = true
    return true
  } catch {
    ElMessage.error(t('voice.micDenied'))
    return false
  }
}

async function enterRoom(target: VoiceRoom): Promise<void> {
  const joined = await joinVoiceRoom(target.id).catch(() => null)
  room.value = joined ?? target
  subscribeRoom(target.id)
  await reconcilePeers()
}

async function loadChannel(): Promise<void> {
  try {
    channel.value = await channelsApi.getChannel(channelId.value)
  } catch {
    channel.value = null
  }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const rooms = await listOpenVoiceRooms(channelId.value)
    if (rooms.length > 0) {
      await enterRoom(rooms[0])
    } else {
      room.value = null
    }
  } catch (cause) {
    error.value = resolveErrorMessage(cause, t('voice.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function createRoom(): Promise<void> {
  if (!createForm.value.title.trim()) {
    ElMessage.warning(t('voice.roomTitleRequired'))
    return
  }
  creating.value = true
  try {
    const created = await createVoiceRoom(channelId.value, {
      title: createForm.value.title.trim(),
      maxSeats: createForm.value.seatCount,
    })
    await enterRoom(created)
    ElMessage.success(t('voice.created'))
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('voice.createFailed')))
  } finally {
    creating.value = false
  }
}

async function takeSeat(seat: VoiceSeat): Promise<void> {
  if (!room.value) return
  if (!myId.value) {
    void router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  const micOk = await ensureLocalStream()
  if (!micOk) return
  seatBusy.value = true
  try {
    const updated = await takeVoiceSeat(room.value.id, seat.index)
    if (updated) room.value = updated
    await syncTracksAndRenegotiate()
    ElMessage.success(t('voice.seated'))
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('voice.seatFailed')))
  } finally {
    seatBusy.value = false
  }
}

async function leaveSeat(): Promise<void> {
  if (!room.value) return
  seatBusy.value = true
  try {
    const updated = await releaseVoiceSeat(room.value.id)
    if (updated) room.value = updated
    muted.value = false
    await syncTracksAndRenegotiate()
    ElMessage.success(t('voice.leftSeat'))
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('voice.seatFailed')))
  } finally {
    seatBusy.value = false
  }
}

/** 上/下麦后本地音轨变化：给已有连接补轨道并重新协商 */
async function syncTracksAndRenegotiate(): Promise<void> {
  const stream = localStream.value
  if (!stream) return
  for (const [peerId, { pc }] of peers) {
    const senders = pc.getSenders()
    for (const track of stream.getAudioTracks()) {
      if (!senders.some((sender) => sender.track === track)) pc.addTrack(track, stream)
    }
    if (isInitiator(peerId)) await callPeer(peerId).catch(() => undefined)
  }
}

async function toggleMute(): Promise<void> {
  const seat = mySeat.value
  if (!room.value || !seat) return
  const next = !muted.value
  muted.value = next
  for (const track of localStream.value?.getAudioTracks() ?? []) {
    track.enabled = !next
  }
  try {
    await setSeatMuted(room.value.id, seat.index, next)
  } catch {
    // 状态同步失败不影响本地静音
  }
}

async function closeRoom(): Promise<void> {
  if (!room.value) return
  const confirmed = await ElMessageBox.confirm(t('voice.closeConfirm'), t('voice.close'), {
    type: 'warning',
    confirmButtonText: t('voice.close'),
    cancelButtonText: t('common.cancel'),
  }).catch(() => false)
  if (!confirmed) return
  try {
    await closeVoiceRoom(room.value.id)
    ElMessage.success(t('voice.closed'))
    cleanup()
    room.value = null
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('voice.closeFailed')))
  }
}

async function exitRoom(): Promise<void> {
  if (!room.value) {
    backToChannel()
    return
  }
  leaving.value = true
  try {
    // 契约里没有「退出房间」接口：下麦 + 断开本地连接即可
    if (mySeat.value) await releaseVoiceSeat(room.value.id).catch(() => undefined)
    cleanup()
    room.value = null
    backToChannel()
  } finally {
    leaving.value = false
  }
}

function backToChannel(): void {
  void router.push({ name: 'channel', params: { id: channelId.value } })
}

function cleanup(): void {
  unsubscribeAll()
  for (const peerId of [...peers.keys()]) dropPeer(peerId)
  for (const track of localStream.value?.getTracks() ?? []) track.stop()
  localStream.value = null
  micReady.value = false
  muted.value = false
}

watch(
  () => realtime.connected,
  (connected) => {
    signalingReady.value = connected
  },
  { immediate: true },
)

onMounted(async () => {
  realtime.connect()
  await loadChannel()
  await load()
})

onBeforeUnmount(() => {
  cleanup()
})
</script>

<template>
  <div class="page voice-page">
    <header class="voice-page__head">
      <el-button text :icon="ArrowLeft" @click="exitRoom">{{ t('ai.backToChannel') }}</el-button>
      <div class="voice-page__identity">
        <h1>{{ room?.title || t('voice.title') }}</h1>
        <p class="muted">
          {{ channel?.name || '' }}
          <span v-if="room"> · {{ t('voice.onMic', { n: speakers.length }) }}</span>
        </p>
      </div>
      <div class="voice-page__actions">
        <el-tag v-if="!signalingReady" size="small" type="warning" effect="plain">{{ t('voice.signalOffline') }}</el-tag>
        <el-button v-if="room && isOwner" type="danger" plain :icon="Close" @click="closeRoom">
          {{ t('voice.close') }}
        </el-button>
        <el-button v-if="room" :icon="ArrowLeft" :loading="leaving" @click="exitRoom">{{ t('voice.leave') }}</el-button>
      </div>
    </header>

    <LoadingState v-if="loading" :rows="4" :tip="t('common.loading')" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />

    <template v-else-if="!room">
      <EmptyState :title="t('voice.noRoom')" :description="t('voice.noRoomHint')" />
      <section v-if="auth.isAuthenticated" class="isles-card voice-create">
        <h2>{{ t('voice.create') }}</h2>
        <el-form label-position="top" @submit.prevent>
          <el-form-item :label="t('voice.roomTitle')" required>
            <el-input v-model="createForm.title" maxlength="60" show-word-limit :placeholder="t('voice.roomTitleHint')" />
          </el-form-item>
          <el-form-item :label="t('voice.seatCount')">
            <el-input-number v-model="createForm.seatCount" :min="2" :max="12" />
          </el-form-item>
        </el-form>
        <el-button type="primary" :loading="creating" :icon="VideoPlay" @click="createRoom">
          {{ t('voice.create') }}
        </el-button>
      </section>
    </template>

    <template v-else>
      <section class="voice-stage isles-card">
        <div class="voice-stage__seats">
          <div v-for="seat in room.seats" :key="seat.index" class="voice-seat" :class="{ 'is-mine': seat.userId === myId }">
            <template v-if="seat.user">
              <UserAvatar :src="seat.user.avatarUrl" :name="seat.user.nickname || seat.user.username" :size="52" />
              <span class="voice-seat__name">{{ seat.user.nickname || seat.user.username }}</span>
              <span v-if="seat.muted" class="voice-seat__mic is-muted">
                <el-icon :size="12"><Mute /></el-icon>
              </span>
              <el-button
                v-if="seat.userId === myId"
                text
                size="small"
                :loading="seatBusy"
                @click="leaveSeat"
              >{{ t('voice.leaveSeat') }}</el-button>
            </template>
            <template v-else>
              <span class="voice-seat__empty">{{ t('voice.emptySeat', { n: seat.index + 1 }) }}</span>
              <el-button text size="small" :loading="seatBusy" @click="takeSeat(seat)">
                {{ t('voice.takeSeat') }}
              </el-button>
            </template>
            <span class="voice-seat__index">{{ seat.index + 1 }}</span>
          </div>
        </div>

        <footer class="voice-stage__controls">
          <el-button
            :type="muted ? 'danger' : 'default'"
            :icon="muted ? Mute : Microphone"
            :disabled="!seated"
            @click="toggleMute"
          >
            {{ muted ? t('voice.unmute') : t('voice.mute') }}
          </el-button>
          <span class="muted">
            {{ seated ? t('voice.seatedHint') : micReady ? t('voice.listeningHint') : t('voice.micHint') }}
          </span>
        </footer>
      </section>

      <p v-if="speakers.length === 0" class="muted voice-page__hint">{{ t('voice.emptyRoomHint') }}</p>
    </template>
  </div>
</template>

<style scoped lang="scss">
.voice-page {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-4);
}

.voice-page__head {
  display: flex;
  gap: var(--isles-space-3);
  align-items: center;
}

.voice-page__identity {
  flex: 1;
  min-width: 0;

  h1 {
    font-size: var(--isles-font-lg);
  }
}

.voice-page__actions {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
}

.voice-page__hint {
  font-size: var(--isles-font-sm);
}

.voice-create {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-3);
  align-items: flex-start;
  padding: var(--isles-space-4);
}

.voice-stage {
  padding: var(--isles-space-4);
}

.voice-stage__seats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--isles-space-3);
}

.voice-seat {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-1);
  align-items: center;
  justify-content: center;
  min-height: 120px;
  padding: var(--isles-space-3);
  background: var(--isles-surface-muted);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);

  &.is-mine {
    border-color: var(--isles-primary);
    box-shadow: 0 0 0 2px var(--isles-primary-soft);
  }
}

.voice-seat__name {
  max-width: 100%;
  overflow: hidden;
  font-size: var(--isles-font-xs);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.voice-seat__empty {
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.voice-seat__index {
  position: absolute;
  top: 6px;
  left: 8px;
  color: var(--isles-text-muted);
  font-size: 10px;
}

.voice-seat__mic {
  display: inline-flex;
  color: var(--isles-primary);

  &.is-muted {
    color: var(--isles-danger, #e5484d);
  }
}

.voice-stage__controls {
  display: flex;
  gap: var(--isles-space-3);
  align-items: center;
  margin-top: var(--isles-space-4);
}

@media (max-width: 767px) {
  .voice-stage__seats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>

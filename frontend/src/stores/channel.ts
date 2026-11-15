import { t } from '@/i18n'
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { channelsApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import type { Channel, ChannelSummary, CreateChannelPayload } from '@/types/models'

/**
 * 频道相关跨页面共享状态：我的频道（侧边栏/我的频道页）、当前频道（未加入频道页）。
 * 具体频道/帖子数据仍由页面级请求获取，不复制进全局 store（docs/02 §6.2）。
 */
export const useChannelStore = defineStore('channel', () => {
  const myChannels = ref<ChannelSummary[]>([])
  const loadingMyChannels = ref(false)
  const myChannelsError = ref<string | null>(null)
  const currentChannel = ref<ChannelSummary | null>(null)

  let fetchTask: Promise<void> | null = null

  const joinedChannelIds = computed(() => new Set(myChannels.value.map((channel) => channel.id)))

  function isJoined(channelId?: string | null): boolean {
    return Boolean(channelId && joinedChannelIds.value.has(channelId))
  }

  async function fetchMyChannels(force = false): Promise<void> {
    if (fetchTask) return fetchTask
    if (!force && myChannels.value.length > 0) return
    loadingMyChannels.value = true
    myChannelsError.value = null
    fetchTask = (async () => {
      try {
        myChannels.value = await channelsApi.getMyChannels()
      } catch (error) {
        myChannelsError.value = resolveErrorMessage(error, t('channel.mineFailed'))
      } finally {
        loadingMyChannels.value = false
        fetchTask = null
      }
    })()
    return fetchTask
  }

  function upsertMyChannel(channel: ChannelSummary): void {
    const index = myChannels.value.findIndex((item) => item.id === channel.id)
    if (index >= 0) {
      myChannels.value[index] = { ...myChannels.value[index], ...channel }
    } else {
      myChannels.value = [channel, ...myChannels.value]
    }
  }

  function removeMyChannel(channelId: string): void {
    myChannels.value = myChannels.value.filter((channel) => channel.id !== channelId)
  }

  function clear(): void {
    myChannels.value = []
    currentChannel.value = null
    myChannelsError.value = null
    loadingMyChannels.value = false
  }

  async function createChannel(payload: CreateChannelPayload): Promise<Channel> {
    const channel = await channelsApi.createChannel(payload)
    if (channel?.id) {
      upsertMyChannel(channel)
    }
    return channel
  }

  async function joinChannel(channelId: string): Promise<Channel> {
    const channel = await channelsApi.joinChannel(channelId)
    upsertMyChannel(channel ?? { id: channelId, name: '' })
    if (currentChannel.value?.id === channelId) {
      currentChannel.value = null
    }
    return channel
  }

  function setCurrentChannel(channel: ChannelSummary | null): void {
    currentChannel.value = channel
  }

  function clearCurrentChannel(channelId?: string): void {
    if (!channelId || currentChannel.value?.id === channelId) {
      currentChannel.value = null
    }
  }

  return {
    myChannels,
    loadingMyChannels,
    myChannelsError,
    currentChannel,
    joinedChannelIds,
    isJoined,
    fetchMyChannels,
    upsertMyChannel,
    removeMyChannel,
    clear,
    createChannel,
    joinChannel,
    setCurrentChannel,
    clearCurrentChannel,
  }
})

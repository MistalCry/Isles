<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { Plus, Search } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useChannelStore } from '@/stores/channel'
import { useRequireLogin } from '@/composables/useRequireLogin'
import type { Channel } from '@/types/models'
import ChannelCard from '@/components/ChannelCard.vue'
import CreateChannelDialog from '@/components/CreateChannelDialog.vue'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'

const router = useRouter()
const auth = useAuthStore()
const channelStore = useChannelStore()
const { myChannels, loadingMyChannels, myChannelsError } = storeToRefs(channelStore)
const { requireLogin } = useRequireLogin()

const createDialogVisible = ref(false)

onMounted(() => {
  if (auth.isAuthenticated) {
    void channelStore.fetchMyChannels(true)
  }
})

function openCreate(): void {
  if (!requireLogin('登录后可创建频道')) return
  createDialogVisible.value = true
}

function handleCreated(channel: Channel): void {
  createDialogVisible.value = false
  void router.push({ name: 'channel', params: { id: channel.id } })
}

function goSearch(): void {
  void router.push({ name: 'search' })
}
</script>

<template>
  <div class="page my-channels-page">
    <header class="page-header">
      <div>
        <h1 class="page-title">我的频道</h1>
        <p class="page-subtitle">你加入的所有岛屿</p>
      </div>
      <div class="my-channels-page__actions">
        <el-button :icon="Search" @click="goSearch">发现频道</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">创建频道</el-button>
      </div>
    </header>

    <LoadingState v-if="loadingMyChannels && myChannels.length === 0" :rows="3" tip="正在加载我的频道…" />

    <ErrorState
      v-else-if="myChannelsError && myChannels.length === 0"
      :message="myChannelsError"
      @retry="channelStore.fetchMyChannels(true)"
    />

    <EmptyState
      v-else-if="myChannels.length === 0"
      title="还没有加入频道"
      description="去搜索或发现感兴趣的频道，加入后就会出现在这里。"
    >
      <template #action>
        <el-button type="primary" @click="goSearch">去发现频道</el-button>
        <el-button @click="openCreate">创建我的频道</el-button>
      </template>
    </EmptyState>

    <div v-else class="my-channels-page__grid">
      <ChannelCard v-for="channel in myChannels" :key="channel.id" :channel="channel" layout="grid" />
    </div>

    <CreateChannelDialog v-model="createDialogVisible" @created="handleCreated" />
  </div>
</template>

<style scoped lang="scss">
.my-channels-page__actions {
  display: flex;
  gap: var(--isles-space-2);
}

.my-channels-page__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: var(--isles-space-4);
}

@media (max-width: 767px) {
  .my-channels-page__grid {
    grid-template-columns: 1fr;
  }
}
</style>

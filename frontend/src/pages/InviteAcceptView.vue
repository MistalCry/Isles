<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { channelsApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import type { InviteAcceptResult } from '@/api/channels'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'

const { t } = useI18n()


const route = useRoute()
const router = useRouter()
const loading = ref(true)
const error = ref<string | null>(null)
const result = ref<InviteAcceptResult | null>(null)

async function accept(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    result.value = await channelsApi.acceptInvite(String(route.params.token ?? ''))
    if (result.value.status === 'JOINED') {
      ElMessage.success(t('invite.joined'))
    } else {
      ElMessage.success(t('invite.pending'))
    }
  } catch (err) {
    error.value = resolveErrorMessage(err, t('invite.invalid'))
  } finally {
    loading.value = false
  }
}

function goChannel(): void {
  if (!result.value?.channelId) return
  void router.push({ name: 'channel', params: { id: result.value.channelId } })
}

onMounted(() => {
  void accept()
})
</script>

<template>
  <div class="page invite-accept-page">
    <LoadingState v-if="loading" :rows="3" :tip="t('invite.processing')" />

    <ErrorState
      v-else-if="error"
      :message="error"
      :retry-text="t('common.retry')"
      @retry="accept"
    >
      <template #action>
        <el-button @click="$router.push('/feed')">{{ t('channel.page.backToFeed') }}</el-button>
      </template>
    </ErrorState>

    <div v-else-if="result" class="invite-result isles-card">
      <el-result
        :icon="result.status === 'JOINED' ? 'success' : 'info'"
        :title="result.status === 'JOINED' ? t('invite.joinedSuccess') : t('invite.pendingSuccess')"
        :sub-title="
          result.status === 'JOINED'
            ? t('invite.joinedHint')
            : t('invite.pendingHint')
        "
      >
        <template #extra>
          <el-button type="primary" @click="goChannel">{{ t('invite.enterChannel') }}</el-button>
          <el-button @click="$router.push('/notifications')">{{ t('invite.viewNotifications') }}</el-button>
        </template>
      </el-result>
    </div>
  </div>
</template>

<style scoped lang="scss">
.invite-accept-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
}

.invite-result {
  width: min(560px, 100%);
}
</style>

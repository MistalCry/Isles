<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { channelsApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import type { InviteAcceptResult } from '@/api/channels'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'

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
      ElMessage.success('已加入频道')
    } else {
      ElMessage.success('加入申请已提交，等待管理员审批')
    }
  } catch (err) {
    error.value = resolveErrorMessage(err, '邀请链接无效或已过期')
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
    <LoadingState v-if="loading" :rows="3" tip="正在处理邀请链接…" />

    <ErrorState
      v-else-if="error"
      :message="error"
      retry-text="重试"
      @retry="accept"
    >
      <template #action>
        <el-button @click="$router.push('/feed')">回到频道动态</el-button>
      </template>
    </ErrorState>

    <div v-else-if="result" class="invite-result isles-card">
      <el-result
        :icon="result.status === 'JOINED' ? 'success' : 'info'"
        :title="result.status === 'JOINED' ? '已成功加入频道' : '加入申请已提交'"
        :sub-title="
          result.status === 'JOINED'
            ? '现在可以在频道内发帖、评论和互动了。'
            : '需要频道管理员审批，通过后会收到通知。'
        "
      >
        <template #extra>
          <el-button type="primary" @click="goChannel">进入频道</el-button>
          <el-button @click="$router.push('/notifications')">查看通知</el-button>
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

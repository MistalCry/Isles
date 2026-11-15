<script setup lang="ts">
import ChannelAiSettingsPage from '@/pages/admin/ChannelAiSettingsPage.vue'
import type { Channel } from '@/types/models'

/**
 * 频道管理中心 · 频道助手设置（docs/03 FR-MGR-08 / FR-ASSIST-01～06）。
 *
 * <p>设置页与平台后台共用同一个组件（`ChannelAiSettingsPage`），避免两套实现；
 * 权限由后端 `channel.ai.manage` 权限点校验，非频道主/超级管理员会收到 403 提示。
 */
defineProps<{
  channel: Channel
  channelId: string
}>()
</script>

<template>
  <section class="manage-section">
    <ChannelAiSettingsPage :channel-id="channelId" :channel-name="channel.name" />
  </section>
</template>

<style scoped lang="scss">
.manage-section {
  min-width: 0;
}
</style>

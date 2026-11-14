<script setup lang="ts">
import { WarningFilled } from '@element-plus/icons-vue'

withDefaults(
  defineProps<{
    message?: string
    retryText?: string
    showRetry?: boolean
  }>(),
  {
    message: '内容加载失败，请稍后再试',
    retryText: '重新加载',
    showRetry: true,
  },
)

defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="error-state" role="alert">
    <el-icon class="error-state__icon" :size="28"><WarningFilled /></el-icon>
    <h3 class="error-state__title">加载失败</h3>
    <p class="error-state__message">{{ message }}</p>
    <div class="error-state__actions">
      <el-button v-if="showRetry" type="primary" plain @click="$emit('retry')">{{ retryText }}</el-button>
      <slot name="action" />
    </div>
  </div>
</template>

<style scoped lang="scss">
.error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--isles-space-2);
  padding: var(--isles-space-10) var(--isles-space-5);
  text-align: center;
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
}

.error-state__icon {
  color: var(--isles-warning);
}

.error-state__title {
  font-size: var(--isles-font-md);
  font-weight: 600;
}

.error-state__message {
  max-width: 420px;
  color: var(--isles-text-muted);
  font-size: var(--isles-font-sm);
}

.error-state__actions {
  display: flex;
  gap: var(--isles-space-3);
  margin-top: var(--isles-space-3);
}
</style>

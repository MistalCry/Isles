<script setup lang="ts">
withDefaults(
  defineProps<{
    rows?: number
    tip?: string
  }>(),
  {
    rows: 3,
    tip: '',
  },
)
</script>

<template>
  <div class="loading-state" role="status" aria-live="polite">
    <el-skeleton :rows="rows" animated />
    <p v-if="tip" class="loading-state__tip">{{ tip }}</p>
  </div>
</template>

<style scoped lang="scss">
.loading-state {
  padding: var(--isles-space-5);
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
}

.loading-state__tip {
  margin-top: var(--isles-space-3);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-sm);
  text-align: center;
}
</style>

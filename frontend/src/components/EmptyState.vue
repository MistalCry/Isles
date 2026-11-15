<script setup lang="ts">
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
withDefaults(
  defineProps<{
    title?: string
    description?: string
    compact?: boolean
  }>(),
  {
    title: '',
    description: '',
    compact: false,
  },
)
</script>

<template>
  <div class="empty-state" :class="{ 'empty-state--compact': compact }">
    <div class="empty-state__mark" aria-hidden="true">
      <span class="empty-state__island" />
      <span class="empty-state__wave" />
    </div>
    <h3 class="empty-state__title">{{ title || t('empty.defaultTitle') }}</h3>
    <p v-if="description" class="empty-state__description">{{ description }}</p>
    <div v-if="$slots.action" class="empty-state__action">
      <slot name="action" />
    </div>
  </div>
</template>

<style scoped lang="scss">
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--isles-space-2);
  padding: var(--isles-space-10) var(--isles-space-5);
  text-align: center;
  background: var(--isles-surface);
  border: 1px dashed var(--isles-border-strong);
  border-radius: var(--isles-radius-lg);
}

.empty-state--compact {
  padding: var(--isles-space-6) var(--isles-space-4);
  border: none;
  background: transparent;
}

.empty-state__mark {
  position: relative;
  width: 72px;
  height: 56px;
  margin-bottom: var(--isles-space-2);
}

.empty-state__island {
  position: absolute;
  left: 16px;
  top: 12px;
  width: 40px;
  height: 24px;
  background: linear-gradient(160deg, var(--isles-primary-soft), #cfe3e8);
  border-radius: 50% 50% 46% 46%;
}

.empty-state__wave {
  position: absolute;
  left: 4px;
  bottom: 8px;
  width: 64px;
  height: 10px;
  border-radius: var(--isles-radius-pill);
  background: repeating-linear-gradient(
    90deg,
    var(--isles-border-strong) 0 8px,
    transparent 8px 14px
  );
  opacity: 0.8;
}

.empty-state__title {
  font-size: var(--isles-font-md);
  font-weight: 600;
}

.empty-state__description {
  max-width: 320px;
  color: var(--isles-text-muted);
  font-size: var(--isles-font-sm);
}

.empty-state__action {
  margin-top: var(--isles-space-3);
}
</style>

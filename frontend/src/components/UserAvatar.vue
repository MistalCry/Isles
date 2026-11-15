<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, ref, watch } from 'vue'

const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    name?: string | null
    src?: string | null
    size?: number
    alt?: string
    square?: boolean
  }>(),
  {
    name: '',
    src: null,
    size: 40,
    alt: '',
    square: false,
  },
)

const failed = ref(false)

const initial = computed(() => {
  const name = (props.name ?? '').trim()
  return name ? name.slice(0, 1).toUpperCase() : t('channel.create.island')
})

const wrapperStyle = computed(() => ({
  width: `${props.size}px`,
  height: `${props.size}px`,
  fontSize: `${Math.max(12, Math.round(props.size * 0.4))}px`,
  borderRadius: props.square ? 'var(--isles-radius-md)' : '50%',
}))

const imageAlt = computed(() => props.alt || t('avatar.altOf', { name: props.name ?? t('avatar.user') }))

watch(
  () => props.src,
  () => {
    failed.value = false
  },
)
</script>

<template>
  <span class="user-avatar" :style="wrapperStyle">
    <img v-if="src && !failed" :src="src" :alt="imageAlt" loading="lazy" @error="failed = true" />
    <span v-else class="user-avatar__fallback" aria-hidden="true">{{ initial }}</span>
  </span>
</template>

<style scoped lang="scss">
.user-avatar {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: linear-gradient(135deg, var(--isles-primary-soft), #d8e8ed);
  color: var(--isles-primary-strong);
  font-weight: 600;
  user-select: none;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.user-avatar__fallback {
  line-height: 1;
}
</style>

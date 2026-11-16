<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowLeft, ArrowRight, Close, VideoPlay } from '@element-plus/icons-vue'
import type { MediaItem } from '@/types/models'

const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    media?: MediaItem[] | null
    compact?: boolean
    /** 单图最大高度（px）：私信气泡等窄容器传更小的值 */
    maxHeight?: number
    /**
     * 是否可点击看大图。列表卡片里传 false：整卡是一个链接，
     * 点媒体应该进详情页而不是弹灯箱（否则点击被灯箱截走）。
     */
    interactive?: boolean
    /** 被折叠的数量：在最后一个格子上盖「+N」，提示还有多少没展示 */
    overflowCount?: number
  }>(),
  {
    media: () => [],
    compact: false,
    maxHeight: 420,
    interactive: true,
    overflowCount: 0,
  },
)

const items = computed(() => (props.media ?? []).filter((item) => Boolean(item?.url)))
const images = computed(() => items.value.filter((item) => item.type === 'IMAGE'))
const lightboxOpen = ref(false)
const activeImageIndex = ref(0)

const gridClass = computed(() => {
  const count = items.value.length
  if (count <= 1) return 'media-viewer--single'
  if (count === 2) return 'media-viewer--double'
  return 'media-viewer--grid'
})

/**
 * 视频懒播放：列表里一屏可能有多个视频，全部 autoplay 会同时出声/抢带宽。
 * 进入视口（≥50%）才播，离开立即暂停；不支持 IntersectionObserver 的环境保持暂停。
 */
const videoEls = new Map<number, HTMLVideoElement>()
let videoObserver: IntersectionObserver | null = null

function setVideoRef(el: unknown, index: number): void {
  const video = el instanceof HTMLVideoElement ? el : null
  if (!video) {
    videoEls.delete(index)
    return
  }
  videoEls.set(index, video)
  videoObserver?.observe(video)
}

function ensureObserver(): void {
  if (videoObserver || typeof IntersectionObserver === 'undefined') return
  videoObserver = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        const video = entry.target as HTMLVideoElement
        if (entry.isIntersecting && entry.intersectionRatio >= 0.5) {
          // 自动播放被浏览器策略拦截时静默失败，用户仍可点播放
          void video.play().catch(() => undefined)
        } else if (!video.paused) {
          video.pause()
        }
      }
    },
    { threshold: [0, 0.5, 1] },
  )
  videoEls.forEach((video) => videoObserver?.observe(video))
}

function openLightbox(item: MediaItem): void {
  const index = images.value.findIndex((image) => image.url === item.url)
  activeImageIndex.value = index >= 0 ? index : 0
  lightboxOpen.value = true
}

onMounted(ensureObserver)

onBeforeUnmount(() => {
  videoObserver?.disconnect()
  videoObserver = null
  videoEls.clear()
})

function closeLightbox(): void {
  lightboxOpen.value = false
}

function showPrevious(): void {
  if (images.value.length <= 1) return
  activeImageIndex.value = (activeImageIndex.value - 1 + images.value.length) % images.value.length
}

function showNext(): void {
  if (images.value.length <= 1) return
  activeImageIndex.value = (activeImageIndex.value + 1) % images.value.length
}

function handleKeydown(event: KeyboardEvent): void {
  if (!lightboxOpen.value) return
  if (event.key === 'Escape') closeLightbox()
  if (event.key === 'ArrowLeft') showPrevious()
  if (event.key === 'ArrowRight') showNext()
}

let previousOverflow = ''

watch(lightboxOpen, (open) => {
  if (open) {
    previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    document.addEventListener('keydown', handleKeydown)
  } else {
    document.body.style.overflow = previousOverflow
    document.removeEventListener('keydown', handleKeydown)
  }
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', handleKeydown)
  document.body.style.overflow = previousOverflow
})

const activeImage = computed(() => images.value[activeImageIndex.value] ?? null)
</script>

<template>
  <div
    v-if="items.length > 0"
    class="media-viewer"
    :class="[gridClass, { 'media-viewer--compact': compact }]"
    :style="{ '--media-max-height': `${maxHeight}px` }"
  >
    <template v-for="(item, index) in items" :key="`${item.url}-${index}`">
      <component
        :is="interactive ? 'button' : 'div'"
        v-if="item.type === 'IMAGE'"
        :type="interactive ? 'button' : undefined"
        class="media-viewer__cell media-viewer__image"
        :class="{ 'is-static': !interactive }"
        :aria-label="interactive ? t('media.viewer.viewNth', { n: index + 1 }) : undefined"
        @click="interactive && openLightbox(item)"
      >
        <img :src="item.thumbUrl || item.url" :alt="t('media.viewer.postPhoto', { n: index + 1 })" loading="lazy" />
        <span
          v-if="overflowCount > 0 && index === items.length - 1"
          class="media-viewer__more"
        >+{{ overflowCount }}</span>
      </component>

      <div v-else class="media-viewer__cell media-viewer__video">
        <video
          :ref="(el) => setVideoRef(el, index)"
          :src="item.url"
          :poster="item.thumbUrl || undefined"
          controls
          muted
          loop
          playsinline
          preload="metadata"
          :aria-label="t('media.viewer.postVideo', { n: index + 1 })"
        >{{ t('media.viewer.unsupported') }}</video>
        <span class="media-viewer__video-tip" aria-hidden="true">
          <el-icon><VideoPlay /></el-icon>{{ t('media.viewer.mutedPlaying') }}</span>
        <span
          v-if="overflowCount > 0 && index === items.length - 1"
          class="media-viewer__more"
        >+{{ overflowCount }}</span>
      </div>
    </template>
  </div>

  <Teleport to="body">
    <div
      v-if="lightboxOpen && activeImage"
      class="media-lightbox"
      role="dialog"
      aria-modal="true"
      :aria-label="t('media.viewer.preview')"
      @click.self="closeLightbox"
    >
      <button type="button" class="media-lightbox__close" :aria-label="t('media.viewer.close')" @click="closeLightbox">
        <el-icon :size="22"><Close /></el-icon>
      </button>

      <button
        v-if="images.length > 1"
        type="button"
        class="media-lightbox__nav media-lightbox__nav--prev"
        :aria-label="t('media.viewer.prev')"
        @click="showPrevious"
      >
        <el-icon :size="24"><ArrowLeft /></el-icon>
      </button>

      <img class="media-lightbox__image" :src="activeImage.url" :alt="t('media.viewer.photo', { n: activeImageIndex + 1 })" />

      <button
        v-if="images.length > 1"
        type="button"
        class="media-lightbox__nav media-lightbox__nav--next"
        :aria-label="t('media.viewer.next')"
        @click="showNext"
      >
        <el-icon :size="24"><ArrowRight /></el-icon>
      </button>

      <p class="media-lightbox__counter">{{ activeImageIndex + 1 }} / {{ images.length }}</p>
    </div>
  </Teleport>
</template>

<style scoped lang="scss">
.media-viewer {
  display: grid;
  gap: var(--isles-space-2);
  margin-top: var(--isles-space-3);
  overflow: hidden;
  border-radius: var(--isles-radius-md);
}

.media-viewer--single {
  grid-template-columns: 1fr;
}

.media-viewer--double {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.media-viewer--grid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.media-viewer__cell {
  position: relative;
  display: block;
  width: 100%;
  padding: 0;
  overflow: hidden;
  background: var(--isles-surface-muted);
  border: none;
  border-radius: var(--isles-radius-sm);
  cursor: pointer;
}

.media-viewer--single .media-viewer__cell {
  max-height: var(--media-max-height, 420px);
}

.media-viewer--grid .media-viewer__cell,
.media-viewer--double .media-viewer__cell {
  aspect-ratio: 1 / 1;
}

.media-viewer__image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.2s ease;
}

.media-viewer__image:hover img {
  transform: scale(1.02);
}

.media-viewer__video {
  cursor: default;
  aspect-ratio: 16 / 9;
}

.media-viewer--grid .media-viewer__video {
  aspect-ratio: 1 / 1;
}

.media-viewer__video video {
  width: 100%;
  height: 100%;
  object-fit: cover;
  background: #0d1b21;
}

.media-viewer__more {
  position: absolute;
  top: 6px;
  right: 6px;
  padding: 1px 6px;
  color: #fff;
  font-size: var(--isles-font-xs);
  font-weight: 600;
  line-height: 1.6;
  background: rgb(0 0 0 / 55%);
  border-radius: 999px;
  pointer-events: none;
}

.media-viewer__cell.is-static {
  cursor: pointer;
}

.media-viewer__video-tip {
  position: absolute;
  left: var(--isles-space-2);
  bottom: var(--isles-space-2);
  display: flex;
  gap: 4px;
  align-items: center;
  padding: 2px 8px;
  color: #fff;
  font-size: 11px;
  background: rgba(10, 24, 31, 0.6);
  border-radius: var(--isles-radius-pill);
  pointer-events: none;
}

.media-lightbox {
  position: fixed;
  inset: 0;
  z-index: var(--isles-z-overlay);
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(7, 15, 20, 0.92);
}

.media-lightbox__image {
  max-width: 90vw;
  max-height: 86vh;
  object-fit: contain;
  border-radius: var(--isles-radius-sm);
}

.media-lightbox__close,
.media-lightbox__nav {
  position: absolute;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  color: #fff;
  background: rgba(255, 255, 255, 0.12);
  border: none;
  border-radius: 50%;
  cursor: pointer;
  transition: background 0.15s ease;

  &:hover {
    background: rgba(255, 255, 255, 0.24);
  }
}

.media-lightbox__close {
  top: 24px;
  right: 24px;
}

.media-lightbox__nav--prev {
  left: 24px;
}

.media-lightbox__nav--next {
  right: 24px;
}

.media-lightbox__counter {
  position: absolute;
  bottom: 24px;
  color: rgba(255, 255, 255, 0.85);
  font-size: var(--isles-font-sm);
}
</style>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ArrowLeft, ArrowRight, Close, VideoPlay } from '@element-plus/icons-vue'
import type { MediaItem } from '@/types/models'

const props = withDefaults(
  defineProps<{
    media?: MediaItem[] | null
    compact?: boolean
  }>(),
  {
    media: () => [],
    compact: false,
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

function openLightbox(item: MediaItem): void {
  const index = images.value.findIndex((image) => image.url === item.url)
  activeImageIndex.value = index >= 0 ? index : 0
  lightboxOpen.value = true
}

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
  <div v-if="items.length > 0" class="media-viewer" :class="[gridClass, { 'media-viewer--compact': compact }]">
    <template v-for="(item, index) in items" :key="`${item.url}-${index}`">
      <button
        v-if="item.type === 'IMAGE'"
        type="button"
        class="media-viewer__cell media-viewer__image"
        :aria-label="`查看第 ${index + 1} 张图片`"
        @click="openLightbox(item)"
      >
        <img :src="item.thumbUrl || item.url" :alt="`帖子图片 ${index + 1}`" loading="lazy" />
      </button>

      <div v-else class="media-viewer__cell media-viewer__video">
        <video
          :src="item.url"
          :poster="item.thumbUrl || undefined"
          controls
          autoplay
          muted
          loop
          playsinline
          preload="metadata"
          :aria-label="`帖子视频 ${index + 1}`"
        >
          您的浏览器不支持视频播放。
        </video>
        <span class="media-viewer__video-tip" aria-hidden="true">
          <el-icon><VideoPlay /></el-icon>
          静音播放中
        </span>
      </div>
    </template>
  </div>

  <Teleport to="body">
    <div
      v-if="lightboxOpen && activeImage"
      class="media-lightbox"
      role="dialog"
      aria-modal="true"
      aria-label="图片预览"
      @click.self="closeLightbox"
    >
      <button type="button" class="media-lightbox__close" aria-label="关闭预览" @click="closeLightbox">
        <el-icon :size="22"><Close /></el-icon>
      </button>

      <button
        v-if="images.length > 1"
        type="button"
        class="media-lightbox__nav media-lightbox__nav--prev"
        aria-label="上一张"
        @click="showPrevious"
      >
        <el-icon :size="24"><ArrowLeft /></el-icon>
      </button>

      <img class="media-lightbox__image" :src="activeImage.url" :alt="`图片 ${activeImageIndex + 1}`" />

      <button
        v-if="images.length > 1"
        type="button"
        class="media-lightbox__nav media-lightbox__nav--next"
        aria-label="下一张"
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
  max-height: 420px;
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

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
/**
 * 可复用图片上传 + 裁剪组件（头像 / 封面 / 背景统一使用，避免各处重复实现）。
 *
 * 能力：选择图片 → 拖拽平移 + 滑块/滚轮缩放 → 按固定比例裁剪 → 上传 → 回填 URL；
 * 也可以「使用原图」直接上传，保留原始分辨率与格式（GIF 动图、PNG 透明只能靠这条）。
 *
 * <p>导出策略（docs/03 FR-MEDIA-05）：
 * 导出尺寸按**裁剪区域的原图像素**计算，只在超过 `maxOutputWidth` 时才等比缩小，
 * 不再像旧实现那样无条件缩放到固定宽度（4000×3000 的照片会被压成 512×512）。
 */
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Picture } from '@element-plus/icons-vue'
import { mediaApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { IMAGE_ACCEPT, MAX_IMAGE_SIZE_MB } from '@/constants/app'
import { resolveBaseScale, resolveCropRect, resolveExportSize } from '@/utils/crop'

const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    modelValue?: string | null
    /** 宽 / 高，头像 1，频道背景 4 */
    aspectRatio?: number
    /** 导出宽度上限（像素）：裁剪区域小于该值时保持原像素，不做放大 */
    maxOutputWidth?: number
    label?: string
    hint?: string
    shape?: 'square' | 'circle' | 'rect'
    disabled?: boolean
    /** 裁剪框展示宽度（px） */
    boxWidth?: number
    /** 预览图宽度（px）；不传时按裁剪框宽度展示（封面等长条图保持原样） */
    previewWidth?: number
  }>(),
  {
    modelValue: null,
    aspectRatio: 1,
    maxOutputWidth: 4096,
    label: '',
    hint: '',
    shape: 'square',
    disabled: false,
    boxWidth: 320,
    previewWidth: 0,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: string | null]
  uploaded: [url: string]
}>()

const fileInput = ref<HTMLInputElement>()
const imageRef = ref<HTMLImageElement>()
const dialogVisible = ref(false)
const uploading = ref(false)
const usingOriginal = ref(false)
const loadingImage = ref(false)

const sourceUrl = ref('')
const sourceName = ref('image.png')
/** 原始文件：用于「使用原图」与格式判定 */
const sourceFile = ref<File | null>(null)
const natural = ref({ width: 0, height: 0 })
const zoom = ref(1)
const offset = ref({ x: 0, y: 0 })
const dragging = ref(false)
const dragStart = ref({ x: 0, y: 0, ox: 0, oy: 0 })

const resolvedLabel = computed(() => props.label || t('media.uploadLabel'))
const boxHeight = computed(() => Math.round(props.boxWidth / props.aspectRatio))
/** 预览宽度：头像类传小值，封面类不传则与裁剪框一致 */
const previewWidth = computed(() => props.previewWidth > 0 ? props.previewWidth : props.boxWidth)
const previewStyle = computed(() => ({
  width: `${previewWidth.value}px`,
  height: `${Math.round(previewWidth.value / props.aspectRatio)}px`,
  borderRadius: props.shape === 'circle' ? '50%' : 'var(--isles-radius-md)',
}))
/** 裁剪弹窗里的取景框固定用 boxWidth，不受预览尺寸影响 */
const stageStyle = computed(() => ({
  width: `${props.boxWidth}px`,
  height: `${boxHeight.value}px`,
  borderRadius: props.shape === 'circle' ? '50%' : 'var(--isles-radius-md)',
}))

/** 让图片刚好覆盖裁剪框的缩放比例 */
const baseScale = computed(() =>
  resolveBaseScale(natural.value.width, natural.value.height, props.boxWidth, boxHeight.value),
)

const renderScale = computed(() => baseScale.value * zoom.value)

const imageStyle = computed(() => {
  const scale = renderScale.value
  return {
    width: `${natural.value.width * scale}px`,
    height: `${natural.value.height * scale}px`,
    transform: `translate(${offset.value.x}px, ${offset.value.y}px)`,
  }
})

/** 当前裁剪区域（原图像素） */
const cropRect = computed(() =>
  resolveCropRect({
    naturalWidth: natural.value.width,
    naturalHeight: natural.value.height,
    boxWidth: props.boxWidth,
    boxHeight: boxHeight.value,
    scale: renderScale.value,
    offsetX: offset.value.x,
    offsetY: offset.value.y,
  }),
)

/** 实际导出尺寸：只在超过上限时等比缩小 */
const exportSize = computed(() => resolveExportSize(cropRect.value, props.maxOutputWidth))

const isGif = computed(() => (sourceFile.value?.type ?? '').toLowerCase().includes('gif'))

function clampOffset(): void {
  const scale = renderScale.value
  const renderWidth = natural.value.width * scale
  const renderHeight = natural.value.height * scale
  const minX = props.boxWidth - renderWidth
  const minY = boxHeight.value - renderHeight
  offset.value = {
    x: Math.min(0, Math.max(minX, offset.value.x)),
    y: Math.min(0, Math.max(minY, offset.value.y)),
  }
}

function centerImage(): void {
  const scale = renderScale.value
  offset.value = {
    x: (props.boxWidth - natural.value.width * scale) / 2,
    y: (boxHeight.value - natural.value.height * scale) / 2,
  }
  clampOffset()
}

function triggerPick(): void {
  if (props.disabled) return
  fileInput.value?.click()
}

async function handleFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!file.type.startsWith('image/')) {
    ElMessage.warning(t('media.crop.pickImage'))
    return
  }
  const invalid = mediaApi.validateMediaFile(file)
  if (invalid) {
    ElMessage.warning(invalid)
    return
  }
  loadingImage.value = true
  try {
    if (sourceUrl.value) URL.revokeObjectURL(sourceUrl.value)
    sourceUrl.value = URL.createObjectURL(file)
    sourceFile.value = file
    sourceName.value = file.name.replace(/\.[^.]+$/, '') || 'image'
    const size = await loadImageSize(sourceUrl.value)
    natural.value = size
    zoom.value = 1
    dialogVisible.value = true
    await nextTick()
    centerImage()
  } catch {
    // HEIC/HEIF 等无法解码的格式：不弹「读取失败」死路，改为按原图上传并说明原因
    if (mediaApi.isPreviewUnsupported(file)) {
      sourceFile.value = file
      ElMessage.info(t('media.heicNoPreview'))
      await useOriginal()
    } else {
      ElMessage.error(t('media.crop.readFailed'))
    }
  } finally {
    loadingImage.value = false
  }
}

function loadImageSize(url: string): Promise<{ width: number; height: number }> {
  return new Promise((resolve, reject) => {
    const image = new Image()
    image.onload = () => resolve({ width: image.naturalWidth, height: image.naturalHeight })
    image.onerror = () => reject(new Error('image load failed'))
    image.src = url
  })
}

function onPointerDown(event: PointerEvent): void {
  if (!dialogVisible.value) return
  dragging.value = true
  dragStart.value = { x: event.clientX, y: event.clientY, ox: offset.value.x, oy: offset.value.y }
  ;(event.target as HTMLElement).setPointerCapture?.(event.pointerId)
}

function onPointerMove(event: PointerEvent): void {
  if (!dragging.value) return
  offset.value = {
    x: dragStart.value.ox + (event.clientX - dragStart.value.x),
    y: dragStart.value.oy + (event.clientY - dragStart.value.y),
  }
  clampOffset()
}

function onPointerUp(): void {
  dragging.value = false
}

function onZoomChange(value: number | number[]): void {
  const next = Array.isArray(value) ? value[0] : value
  // 以裁剪框中心为锚点缩放
  const scaleBefore = renderScale.value
  const centerX = (props.boxWidth / 2 - offset.value.x) / scaleBefore
  const centerY = (boxHeight.value / 2 - offset.value.y) / scaleBefore
  zoom.value = next
  const scaleAfter = renderScale.value
  offset.value = {
    x: props.boxWidth / 2 - centerX * scaleAfter,
    y: boxHeight.value / 2 - centerY * scaleAfter,
  }
  clampOffset()
}

function onWheel(event: WheelEvent): void {
  const step = event.deltaY > 0 ? -0.08 : 0.08
  onZoomChange(Math.min(4, Math.max(1, Number((zoom.value + step).toFixed(2)))))
}

/** 画布编码：PNG/WebP 保留透明通道，其余走 JPEG；超过大小上限时逐级降质 */
async function encodeBlob(canvas: HTMLCanvasElement): Promise<Blob | null> {
  const sourceType = (sourceFile.value?.type ?? '').toLowerCase()
  const keepAlpha = sourceType.includes('png') || sourceType.includes('webp')
  const mime = keepAlpha ? 'image/png' : 'image/jpeg'
  const limit = MAX_IMAGE_SIZE_MB * 1024 * 1024
  const qualities = keepAlpha ? [undefined] : [0.95, 0.9, 0.85, 0.8, 0.7]
  let last: Blob | null = null
  for (const quality of qualities) {
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, mime, quality))
    if (!blob) return null
    last = blob
    if (blob.size <= limit) return blob
  }
  return last
}

async function uploadBlob(blob: Blob, filename: string, type: string): Promise<void> {
  uploading.value = true
  try {
    const file = new File([blob], filename, { type })
    const uploaded = await mediaApi.uploadMedia(file)
    emit('update:modelValue', uploaded.url)
    emit('uploaded', uploaded.url)
    ElMessage.success(t('media.crop.uploaded'))
    dialogVisible.value = false
  } catch (err) {
    ElMessage.error(resolveErrorMessage(err, t('media.crop.uploadFailed')))
  } finally {
    uploading.value = false
  }
}

async function confirmCrop(): Promise<void> {
  const image = imageRef.value
  if (!image || !natural.value.width) return
  const rect = cropRect.value
  const { width: outWidth, height: outHeight, downscaled } = exportSize.value

  const canvas = document.createElement('canvas')
  canvas.width = outWidth
  canvas.height = outHeight
  const context = canvas.getContext('2d')
  if (!context) return
  context.imageSmoothingQuality = 'high'
  context.drawImage(image, rect.x, rect.y, rect.width, rect.height, 0, 0, outWidth, outHeight)

  const blob = await encodeBlob(canvas)
  if (!blob) {
    ElMessage.error(t('media.crop.failed'))
    return
  }
  if (blob.size > MAX_IMAGE_SIZE_MB * 1024 * 1024) {
    ElMessage.error(t('media.crop.tooLarge', { max: MAX_IMAGE_SIZE_MB }))
    return
  }
  if (downscaled) {
    ElMessage.info(t('media.crop.downscaled', { w: outWidth, h: outHeight }))
  }
  if (isGif.value) {
    ElMessage.warning(t('media.crop.gifWarning'))
  }
  const ext = blob.type === 'image/png' ? 'png' : 'jpg'
  await uploadBlob(blob, `${sourceName.value}-crop.${ext}`, blob.type)
}

/** 直接上传原始文件：保留原始分辨率、透明与动图 */
async function useOriginal(): Promise<void> {
  const file = sourceFile.value
  if (!file) return
  const invalid = mediaApi.validateMediaFile(file)
  if (invalid) {
    ElMessage.warning(invalid)
    return
  }
  usingOriginal.value = true
  uploading.value = true
  try {
    const uploaded = await mediaApi.uploadMedia(file)
    emit('update:modelValue', uploaded.url)
    emit('uploaded', uploaded.url)
    ElMessage.success(t('media.crop.originalUploaded'))
    dialogVisible.value = false
  } catch (err) {
    ElMessage.error(resolveErrorMessage(err, t('media.crop.uploadFailed')))
  } finally {
    uploading.value = false
    usingOriginal.value = false
  }
}

function clearImage(): void {
  emit('update:modelValue', null)
}

function closeDialog(): void {
  dialogVisible.value = false
}

watch(dialogVisible, (open) => {
  if (!open && sourceUrl.value) {
    URL.revokeObjectURL(sourceUrl.value)
    sourceUrl.value = ''
  }
})

onBeforeUnmount(() => {
  if (sourceUrl.value) URL.revokeObjectURL(sourceUrl.value)
})
</script>

<template>
  <div class="image-crop-upload">
    <div class="image-crop-upload__preview" :style="previewStyle">
      <img v-if="modelValue" :src="modelValue" :alt="resolvedLabel" />
      <div v-else class="image-crop-upload__placeholder">
        <el-icon><Picture /></el-icon>
        <span v-if="previewWidth >= 120">{{ hint || t('media.crop.empty') }}</span>
      </div>
    </div>
    <div class="image-crop-upload__actions">
      <el-button size="small" :icon="Picture" :loading="loadingImage" :disabled="disabled" @click="triggerPick">
        {{ modelValue ? t('media.crop.change') : resolvedLabel }}
      </el-button>
      <el-button v-if="modelValue && !disabled" size="small" text type="danger" @click="clearImage">{{ t('common.remove') }}</el-button>
    </div>
    <input ref="fileInput" type="file" :accept="IMAGE_ACCEPT" class="visually-hidden" @change="handleFileChange" />

    <el-dialog
      v-model="dialogVisible"
      :title="t('media.crop.title')"
      width="min(440px, 94vw)"
      append-to-body
      class="image-crop-dialog"
    >
      <div class="image-crop-upload__stage">
        <div
          class="image-crop-upload__box"
          :style="stageStyle"
          @pointerdown="onPointerDown"
          @pointermove="onPointerMove"
          @pointerup="onPointerUp"
          @pointercancel="onPointerUp"
          @wheel.prevent="onWheel"
        >
          <img
            v-if="sourceUrl"
            ref="imageRef"
            :src="sourceUrl"
            :style="imageStyle"
            class="image-crop-upload__source"
            :alt="t('media.crop.pending')"
            draggable="false"
          />
          <div class="image-crop-upload__grid" aria-hidden="true" />
        </div>
      </div>
      <div class="image-crop-upload__zoom">
        <span class="muted">{{ t('media.crop.zoom') }}</span>
        <el-slider :model-value="zoom" :min="1" :max="4" :step="0.01" :show-tooltip="false" @input="onZoomChange" />
      </div>
      <p class="muted image-crop-upload__tip">
        {{ t('media.crop.dragHint') }}
        {{ t('media.crop.exportSize') }}
        <strong>{{ exportSize.width }}×{{ exportSize.height }}</strong>
        {{ t('media.crop.sourceArea', { w: cropRect.width, h: cropRect.height }) }}
        <template v-if="isGif">{{ t('media.crop.gifHint') }}</template>
      </p>
      <template #footer>
        <el-button @click="closeDialog">{{ t('common.cancel') }}</el-button>
        <el-button :loading="usingOriginal" :disabled="uploading" @click="useOriginal">{{ t('media.crop.useOriginal') }}</el-button>
        <el-button
          type="primary"
          :loading="uploading && !usingOriginal"
          :disabled="uploading"
          @click="confirmCrop"
        >{{ t('media.crop.confirm') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.image-crop-upload {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
  align-items: flex-start;
}

.image-crop-upload__preview {
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: var(--isles-surface-muted);
  border: 1px solid var(--isles-border);

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.image-crop-upload__placeholder {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: center;
  justify-content: center;
  padding: 8px;
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
  text-align: center;
}

.image-crop-upload__actions {
  display: flex;
  gap: var(--isles-space-2);
}

.image-crop-upload__stage {
  display: flex;
  justify-content: center;
  padding: var(--isles-space-2) 0;
}

.image-crop-upload__box {
  position: relative;
  overflow: hidden;
  cursor: grab;
  background: #111;
  border: 1px solid var(--isles-border);
  touch-action: none;
  user-select: none;

  &:active {
    cursor: grabbing;
  }
}

.image-crop-upload__source {
  position: absolute;
  top: 0;
  left: 0;
  max-width: none;
  pointer-events: none;
}

.image-crop-upload__grid {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background-image:
    linear-gradient(to right, rgb(255 255 255 / 35%) 1px, transparent 1px),
    linear-gradient(to bottom, rgb(255 255 255 / 35%) 1px, transparent 1px);
  background-size: 33.33% 33.33%;
  box-shadow: inset 0 0 0 1px rgb(255 255 255 / 55%);
}

.image-crop-upload__zoom {
  display: flex;
  gap: var(--isles-space-3);
  align-items: center;

  :deep(.el-slider) {
    flex: 1;
  }
}

.image-crop-upload__tip {
  margin-top: var(--isles-space-2);
  font-size: var(--isles-font-xs);
}
</style>

<script setup lang="ts">
/**
 * 可复用图片上传 + 裁剪组件（头像 / 封面 / 背景统一使用，避免各处重复实现）。
 *
 * 能力：选择图片 → 拖拽平移 + 滑块/滚轮缩放 → 按固定比例裁剪 → 上传 → 回填 URL。
 * 比例由 `aspectRatio` 决定：头像 1（正方形/圆形），频道背景 4（长条）。
 */
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Picture } from '@element-plus/icons-vue'
import { mediaApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'

const props = withDefaults(
  defineProps<{
    modelValue?: string | null
    /** 宽 / 高，头像 1，频道背景 4 */
    aspectRatio?: number
    /** 导出图片的宽度（像素），高度按比例换算 */
    outputWidth?: number
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
    outputWidth: 512,
    label: '上传图片',
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
const loadingImage = ref(false)

const sourceUrl = ref('')
const sourceName = ref('image.png')
const natural = ref({ width: 0, height: 0 })
const zoom = ref(1)
const offset = ref({ x: 0, y: 0 })
const dragging = ref(false)
const dragStart = ref({ x: 0, y: 0, ox: 0, oy: 0 })

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
const baseScale = computed(() => {
  const { width, height } = natural.value
  if (!width || !height) return 1
  return Math.max(props.boxWidth / width, boxHeight.value / height)
})

const renderScale = computed(() => baseScale.value * zoom.value)

const imageStyle = computed(() => {
  const scale = renderScale.value
  return {
    width: `${natural.value.width * scale}px`,
    height: `${natural.value.height * scale}px`,
    transform: `translate(${offset.value.x}px, ${offset.value.y}px)`,
  }
})

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
    ElMessage.warning('请选择图片文件')
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
    sourceName.value = file.name.replace(/\.[^.]+$/, '') || 'image'
    const size = await loadImageSize(sourceUrl.value)
    natural.value = size
    zoom.value = 1
    dialogVisible.value = true
    await nextTick()
    centerImage()
  } catch {
    ElMessage.error('图片读取失败，请换一张试试')
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

async function confirmCrop(): Promise<void> {
  const image = imageRef.value
  if (!image || !natural.value.width) return
  const scale = renderScale.value
  const sourceX = Math.max(0, -offset.value.x / scale)
  const sourceY = Math.max(0, -offset.value.y / scale)
  const sourceWidth = Math.min(natural.value.width - sourceX, props.boxWidth / scale)
  const sourceHeight = Math.min(natural.value.height - sourceY, boxHeight.value / scale)

  const canvas = document.createElement('canvas')
  canvas.width = props.outputWidth
  canvas.height = Math.round(props.outputWidth / props.aspectRatio)
  const context = canvas.getContext('2d')
  if (!context) return
  context.imageSmoothingQuality = 'high'
  context.drawImage(image, sourceX, sourceY, sourceWidth, sourceHeight, 0, 0, canvas.width, canvas.height)

  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.92))
  if (!blob) {
    ElMessage.error('裁剪失败，请重试')
    return
  }
  uploading.value = true
  try {
    const file = new File([blob], `${sourceName.value}-crop.jpg`, { type: 'image/jpeg' })
    const uploaded = await mediaApi.uploadMedia(file)
    emit('update:modelValue', uploaded.url)
    emit('uploaded', uploaded.url)
    ElMessage.success('图片已上传')
    dialogVisible.value = false
  } catch (err) {
    ElMessage.error(resolveErrorMessage(err, '上传失败，请稍后重试'))
  } finally {
    uploading.value = false
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
      <img v-if="modelValue" :src="modelValue" :alt="label" />
      <div v-else class="image-crop-upload__placeholder">
        <el-icon><Picture /></el-icon>
        <span v-if="previewWidth >= 120">{{ hint || '暂无图片' }}</span>
      </div>
    </div>
    <div class="image-crop-upload__actions">
      <el-button size="small" :icon="Picture" :loading="loadingImage" :disabled="disabled" @click="triggerPick">
        {{ modelValue ? '更换图片' : label }}
      </el-button>
      <el-button v-if="modelValue && !disabled" size="small" text type="danger" @click="clearImage">移除</el-button>
    </div>
    <input ref="fileInput" type="file" accept="image/*" class="visually-hidden" @change="handleFileChange" />

    <el-dialog
      v-model="dialogVisible"
      title="裁剪图片"
      width="min(420px, 94vw)"
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
            alt="待裁剪图片"
            draggable="false"
          />
          <div class="image-crop-upload__grid" aria-hidden="true" />
        </div>
      </div>
      <div class="image-crop-upload__zoom">
        <span class="muted">缩放</span>
        <el-slider :model-value="zoom" :min="1" :max="4" :step="0.01" :show-tooltip="false" @input="onZoomChange" />
      </div>
      <p class="muted image-crop-upload__tip">拖动图片调整位置，滚轮或滑块缩放；导出尺寸 {{ outputWidth }}×{{ Math.round(outputWidth / aspectRatio) }}</p>
      <template #footer>
        <el-button @click="closeDialog">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="confirmCrop">裁剪并上传</el-button>
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

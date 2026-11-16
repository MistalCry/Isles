<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Picture, Promotion } from '@element-plus/icons-vue'
import { channelsApi, mediaApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useRequireLogin } from '@/composables/useRequireLogin'
import { EMOJI_LIST, MAX_MEDIA_COUNT, MEDIA_ACCEPT } from '@/constants/app'
import type { Board, MediaItem, Post } from '@/types/models'
import UserAvatar from './UserAvatar.vue'
import RichTextEditor from './RichTextEditor.vue'
import ImageCropUpload from './media/ImageCropUpload.vue'
import MentionPicker from './MentionPicker.vue'
import { mentionToken } from '@/utils/mention'

const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    mode?: 'quick' | 'full'
    channelId: string
    boards?: Board[]
    defaultBoardId?: string
  }>(),
  {
    mode: 'quick',
    boards: () => [],
    defaultBoardId: '',
  },
)

const emit = defineEmits<{
  published: [post: Post]
  cancel: []
  'request-full': []
}>()

const auth = useAuthStore()
const { requireLogin } = useRequireLogin()

const content = ref('')
const title = ref('')
const boardId = ref(props.defaultBoardId || props.boards[0]?.id || '')
const mediaItems = ref<MediaItem[]>([])
const coverUrl = ref<string | null>(null)
const uploading = ref(false)
/** 当前文件的上传进度（0-100）与失败待重试文件 */
const uploadPercent = ref(0)
const failedUpload = ref<File | null>(null)
const submitting = ref(false)
const editorRef = ref<{ insertText: (text: string) => void } | null>(null)
const errorMessage = ref<string | null>(null)
const fileInputRef = ref<HTMLInputElement>()

const isFull = computed(() => props.mode === 'full')
const plainText = computed(() => content.value.replace(/<[^>]*>/g, '').replace(/&nbsp;/g, ' ').trim())
const canSubmit = computed(() => plainText.value.length > 0 && !submitting.value)

watch(
  () => props.boards,
  (boards) => {
    if (!boardId.value && boards.length > 0) {
      boardId.value = boards[0]?.id ?? ''
    }
  },
  { immediate: true },
)

function appendEmoji(emoji: string): void {
  content.value += emoji
}

function triggerFilePicker(): void {
  if (!requireLogin(t('comment.uploadLoginRequired'))) return
  fileInputRef.value?.click()
}

async function handleFiles(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (files.length === 0) return
  const remaining = MAX_MEDIA_COUNT - mediaItems.value.length
  if (remaining <= 0) {
    ElMessage.warning(t('comment.mediaLimit', { n: MAX_MEDIA_COUNT }))
    return
  }
  for (const file of files.slice(0, remaining)) {
    const invalid = mediaApi.validateMediaFile(file)
    if (invalid) {
      ElMessage.warning(invalid)
      continue
    }
    if (mediaApi.isPreviewUnsupported(file)) {
      // HEIC 等格式浏览器无法预览，明确告知「按原图上传」，避免用户以为是失败
      ElMessage.info(t('media.heicNoPreview'))
    }
    await uploadFile(file)
  }
}

/** 单个文件上传：带百分比，失败时记住文件供「重试」 */
async function uploadFile(file: File): Promise<boolean> {
  uploading.value = true
  uploadPercent.value = 0
  errorMessage.value = null
  failedUpload.value = null
  try {
    const uploaded = await mediaApi.uploadMedia(file, (percent) => {
      uploadPercent.value = percent
    })
    mediaItems.value.push(uploaded)
    return true
  } catch (err) {
    errorMessage.value = resolveErrorMessage(err, t('comment.mediaUploadFailed'))
    failedUpload.value = file
    return false
  } finally {
    uploading.value = false
    uploadPercent.value = 0
  }
}

async function retryUpload(): Promise<void> {
  const file = failedUpload.value
  if (!file) return
  await uploadFile(file)
}

function removeMedia(index: number): void {
  mediaItems.value.splice(index, 1)
}

function reset(): void {
  content.value = ''
  title.value = ''
  mediaItems.value = []
  coverUrl.value = null
  errorMessage.value = null
}

async function submit(): Promise<void> {
  if (!requireLogin(t('post.publishLoginRequired'))) return
  if (!plainText.value) {
    ElMessage.warning(t('post.emptyContent'))
    return
  }
  if (boardId.value === '') {
    ElMessage.warning(t('post.boardRequired'))
    return
  }
  if (!props.channelId) {
    ElMessage.warning(t('post.channelMissing'))
    return
  }
  submitting.value = true
  errorMessage.value = null
  try {
    const post = await channelsApi.createChannelPost(props.channelId, {
      boardId: boardId.value,
      title: title.value.trim() || undefined,
      // 长文走富文本（HTML），快捷发帖为纯文本；后端按 contentFormat 做白名单净化
      content: isFull.value ? content.value : content.value.trim(),
      contentFormat: isFull.value ? 'HTML' : 'PLAIN',
      coverUrl: isFull.value ? coverUrl.value : undefined,
      media: mediaItems.value,
    })
    ElMessage.success(t('post.published'))
    reset()
    emit('published', post)
  } catch (err) {
    errorMessage.value = resolveErrorMessage(err, t('post.publishFailed'))
  } finally {
    submitting.value = false
  }
}
function insertMention(payload: { id: string; name: string }): void {
  const token = `${mentionToken(payload.id, payload.name)} `
  if (isFull.value && editorRef.value) {
    editorRef.value.insertText(token)
    return
  }
  content.value = content.value ? `${content.value} ${token}` : token
}
</script>

<template>
  <div class="post-composer" :class="`post-composer--${mode}`">
    <p v-if="!auth.isAuthenticated" class="post-composer__guest-hint">{{ t('post.composerGuestHint') }}</p>

    <el-alert
      v-if="errorMessage"
      :title="errorMessage"
      type="error"
      :closable="false"
      show-icon
      class="post-composer__error"
    />

    <template v-if="isFull">
      <el-input v-model="title" maxlength="100" show-word-limit :placeholder="t('common.optionalTitle')" class="post-composer__title" />
      <RichTextEditor
        ref="editorRef"
        v-model="content"
        :min-height="240"
        :placeholder="t('post.longContentHint')"
      />
    </template>

    <div v-else class="post-composer__quick-row">
      <UserAvatar
        v-if="auth.currentUser"
        :src="auth.currentUser.avatarUrl"
        :name="auth.displayName"
        :size="34"
      />
      <UserAvatar v-else :name="t('common.visitor')" :size="34" />
      <el-input
        v-model="content"
        class="post-composer__quick-input"
        maxlength="2000"
        :placeholder="t('post.placeholderQuick')"
        :aria-label="t('post.quickLabel')"
        @keyup.enter="submit"
      />
    </div>

    <div v-if="mediaItems.length > 0" class="post-composer__media">
      <div v-for="(item, index) in mediaItems" :key="`${item.url}-${index}`" class="post-composer__media-item">
        <img v-if="item.type === 'IMAGE'" :src="item.thumbUrl || item.url" :alt="t('media.selectedImage', { n: index + 1 })" />
        <video v-else :src="item.url" muted playsinline preload="metadata" />
        <button type="button" class="post-composer__media-remove" :aria-label="t('media.crop.removeMedia')" @click="removeMedia(index)">
          ×
        </button>
      </div>
    </div>

    <div class="post-composer__toolbar">
      <el-popover trigger="click" :width="220" placement="top-start">
        <template #reference>
          <button type="button" class="composer-btn" :aria-label="t('media.crop.insertEmoji')">😊 emoji</button>
        </template>
        <div class="emoji-panel">
          <button v-for="emoji in EMOJI_LIST" :key="emoji" type="button" class="emoji-btn" @click="appendEmoji(emoji)">
            {{ emoji }}
          </button>
        </div>
      </el-popover>

      <button
        type="button"
        class="composer-btn"
        :disabled="uploading"
        :aria-label="t('media.uploadMedia')"
        :title="t('media.formatsHint')"
        @click="triggerFilePicker"
      >
        <el-icon><Picture /></el-icon>
        {{ uploading ? t('media.uploadPercent', { percent: uploadPercent }) : t('post.mediaLabel') }}
      </button>

      <button v-if="failedUpload && !uploading" type="button" class="composer-btn composer-btn--text" @click="retryUpload">
        {{ t('media.uploadRetry') }}
      </button>

      <span v-if="mediaItems.length > 0" class="muted post-composer__count">
        {{ mediaItems.length }} / {{ MAX_MEDIA_COUNT }}
      </span>

      <MentionPicker :channel-id="channelId" :label="t('post.mentionLabel')" :disabled="!auth.isAuthenticated" @select="insertMention" />

      <button v-if="!isFull" type="button" class="composer-btn composer-btn--text" @click="$emit('request-full')">{{ t('channel.publishLongBtn') }}</button>

      <div class="post-composer__spacer" />

      <el-button v-if="isFull" @click="$emit('cancel')">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :icon="Promotion" :loading="submitting" :disabled="!canSubmit" @click="submit">{{ t('common.publish') }}</el-button>
    </div>

    <div v-if="isFull" class="post-composer__cover">
      <span class="muted">{{ t('post.coverHint') }}</span>
      <ImageCropUpload
        v-model="coverUrl"
        :aspect-ratio="16 / 9"
        :max-output-width="0"
        :box-width="320"
        :label="t('media.crop.coverLabel')"
        :hint="t('media.crop.coverHint')"
      />
    </div>

    <div v-if="isFull" class="post-composer__full-row">
      <el-select
        v-model="boardId"
        :placeholder="t('post.boardPick')"
        class="post-composer__board"
        :disabled="boards.length === 0"
      >
        <el-option v-for="board in boards" :key="board.id" :label="board.name" :value="board.id" />
      </el-select>
      <span class="muted">
        {{
          boards.length === 0
            ? t('post.noBoards')
            : t('post.publishToBoard', {
                name: boards.find((board) => board.id === boardId)?.name ?? t('post.defaultBoard'),
              })
        }}
      </span>
      <span class="muted post-composer__hint">{{ t('post.editorHint') }}</span>
    </div>

    <input
      ref="fileInputRef"
      type="file"
      :accept="MEDIA_ACCEPT"
      multiple
      class="visually-hidden"
      :aria-label="t('media.pickFile')"
      @change="handleFiles"
    />
  </div>
</template>

<style scoped lang="scss">
.post-composer {
  padding: var(--isles-space-4);
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
}

.post-composer--quick {
  box-shadow: var(--isles-shadow-md);
}

.post-composer__guest-hint {
  margin-bottom: var(--isles-space-2);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.post-composer__error {
  margin-bottom: var(--isles-space-3);
}

.post-composer__title {
  margin-bottom: var(--isles-space-3);
}

.post-composer__quick-row {
  display: flex;
  gap: var(--isles-space-3);
  align-items: center;
}

.post-composer__quick-input {
  flex: 1;
}

.post-composer__media {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  margin-top: var(--isles-space-3);
}

.post-composer__media-item {
  position: relative;
  width: 72px;
  height: 72px;
  overflow: hidden;
  background: var(--isles-surface-muted);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-sm);

  img,
  video {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.post-composer__media-remove {
  position: absolute;
  top: 2px;
  right: 2px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  color: #fff;
  font-size: 12px;
  line-height: 1;
  background: rgba(12, 28, 36, 0.65);
  border: none;
  border-radius: 50%;
  cursor: pointer;
}

.post-composer__toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  align-items: center;
  margin-top: var(--isles-space-3);
}

.post-composer__spacer {
  flex: 1;
}

.composer-btn {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  padding: 4px 10px;
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-xs);
  background: var(--isles-surface-muted);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-pill);
  cursor: pointer;

  &:hover {
    color: var(--isles-primary);
    border-color: var(--isles-border-strong);
  }

  &:disabled {
    cursor: not-allowed;
    opacity: 0.6;
  }
}

.composer-btn--text {
  background: transparent;
  border-color: transparent;
  color: var(--isles-primary);
}

.post-composer__count {
  font-size: var(--isles-font-xs);
}

.emoji-panel {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: var(--isles-space-1);
}

.emoji-btn {
  padding: var(--isles-space-1);
  font-size: var(--isles-font-md);
  background: transparent;
  border: none;
  border-radius: var(--isles-radius-sm);
  cursor: pointer;

  &:hover {
    background: var(--isles-hover);
  }
}

.post-composer__cover {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
  margin-top: var(--isles-space-3);
}

.post-composer__full-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-3);
  align-items: center;
  margin-top: var(--isles-space-3);
  font-size: var(--isles-font-xs);
}

.post-composer__board {
  width: 200px;
}

.post-composer__hint {
  margin-left: auto;
}

@media (max-width: 767px) {
  .post-composer {
    padding: var(--isles-space-3);
  }

  .post-composer__toolbar {
    gap: var(--isles-space-1);
  }

  .post-composer__hint {
    margin-left: 0;
    width: 100%;
  }
}
</style>

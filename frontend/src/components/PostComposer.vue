<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Picture, Promotion } from '@element-plus/icons-vue'
import { channelsApi, mediaApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useRequireLogin } from '@/composables/useRequireLogin'
import { EMOJI_LIST, MAX_MEDIA_COUNT } from '@/constants/app'
import type { Board, MediaItem, Post } from '@/types/models'
import UserAvatar from './UserAvatar.vue'

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
const uploading = ref(false)
const submitting = ref(false)
const errorMessage = ref<string | null>(null)
const fileInputRef = ref<HTMLInputElement>()

const isFull = computed(() => props.mode === 'full')
const canSubmit = computed(() => content.value.trim().length > 0 && !submitting.value)

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
  if (!requireLogin('登录后可上传图片或视频')) return
  fileInputRef.value?.click()
}

async function handleFiles(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (files.length === 0) return
  const remaining = MAX_MEDIA_COUNT - mediaItems.value.length
  if (remaining <= 0) {
    ElMessage.warning(`最多添加 ${MAX_MEDIA_COUNT} 个媒体文件`)
    return
  }
  for (const file of files.slice(0, remaining)) {
    const invalid = mediaApi.validateMediaFile(file)
    if (invalid) {
      ElMessage.warning(invalid)
      continue
    }
    uploading.value = true
    errorMessage.value = null
    try {
      const uploaded = await mediaApi.uploadMedia(file)
      mediaItems.value.push(uploaded)
    } catch (err) {
      errorMessage.value = resolveErrorMessage(err, '媒体上传失败，请稍后重试')
    } finally {
      uploading.value = false
    }
  }
}

function removeMedia(index: number): void {
  mediaItems.value.splice(index, 1)
}

function reset(): void {
  content.value = ''
  title.value = ''
  mediaItems.value = []
  errorMessage.value = null
}

async function submit(): Promise<void> {
  if (!requireLogin('登录后可发帖')) return
  if (!content.value.trim()) {
    ElMessage.warning('写点什么再发布吧')
    return
  }
  if (!props.channelId) {
    ElMessage.warning('缺少频道信息，暂时无法发帖')
    return
  }
  submitting.value = true
  errorMessage.value = null
  try {
    const post = await channelsApi.createChannelPost(props.channelId, {
      boardId: boardId.value || null,
      title: title.value.trim() || undefined,
      content: content.value.trim(),
      contentFormat: 'TEXT',
      media: mediaItems.value,
    })
    ElMessage.success('发布成功')
    reset()
    emit('published', post)
  } catch (err) {
    errorMessage.value = resolveErrorMessage(err, '发布失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="post-composer" :class="`post-composer--${mode}`">
    <p v-if="!auth.isAuthenticated" class="post-composer__guest-hint">
      登录后即可在本频道发帖、评论与点赞。
    </p>

    <el-alert
      v-if="errorMessage"
      :title="errorMessage"
      type="error"
      :closable="false"
      show-icon
      class="post-composer__error"
    />

    <template v-if="isFull">
      <el-input v-model="title" maxlength="100" show-word-limit placeholder="标题（选填）" class="post-composer__title" />
      <el-input
        v-model="content"
        type="textarea"
        :rows="6"
        maxlength="5000"
        show-word-limit
        placeholder="写下你想分享的内容…"
        aria-label="长文正文"
      />
    </template>

    <div v-else class="post-composer__quick-row">
      <UserAvatar
        v-if="auth.currentUser"
        :src="auth.currentUser.avatarUrl"
        :name="auth.displayName"
        :size="34"
      />
      <UserAvatar v-else name="游" :size="34" />
      <el-input
        v-model="content"
        class="post-composer__quick-input"
        maxlength="2000"
        placeholder="说点什么，和频道里的岛民聊聊…"
        aria-label="快捷发帖内容"
        @keyup.enter="submit"
      />
    </div>

    <div v-if="mediaItems.length > 0" class="post-composer__media">
      <div v-for="(item, index) in mediaItems" :key="`${item.url}-${index}`" class="post-composer__media-item">
        <img v-if="item.type === 'IMAGE'" :src="item.thumbUrl || item.url" :alt="`已选择图片 ${index + 1}`" />
        <video v-else :src="item.url" muted playsinline preload="metadata" />
        <button type="button" class="post-composer__media-remove" aria-label="移除该媒体" @click="removeMedia(index)">
          ×
        </button>
      </div>
    </div>

    <div class="post-composer__toolbar">
      <el-popover trigger="click" :width="220" placement="top-start">
        <template #reference>
          <button type="button" class="composer-btn" aria-label="插入 emoji">😊 emoji</button>
        </template>
        <div class="emoji-panel">
          <button v-for="emoji in EMOJI_LIST" :key="emoji" type="button" class="emoji-btn" @click="appendEmoji(emoji)">
            {{ emoji }}
          </button>
        </div>
      </el-popover>

      <button type="button" class="composer-btn" :disabled="uploading" aria-label="上传图片或视频" @click="triggerFilePicker">
        <el-icon><Picture /></el-icon>
        {{ uploading ? '上传中…' : '图片/视频' }}
      </button>

      <span v-if="mediaItems.length > 0" class="muted post-composer__count">
        {{ mediaItems.length }} / {{ MAX_MEDIA_COUNT }}
      </span>

      <button v-if="!isFull" type="button" class="composer-btn composer-btn--text" @click="$emit('request-full')">
        发长文
      </button>

      <div class="post-composer__spacer" />

      <el-button v-if="isFull" @click="$emit('cancel')">取消</el-button>
      <el-button type="primary" :icon="Promotion" :loading="submitting" :disabled="!canSubmit" @click="submit">
        发布
      </el-button>
    </div>

    <div v-if="isFull" class="post-composer__full-row">
      <el-select
        v-model="boardId"
        placeholder="选择板块"
        class="post-composer__board"
        :disabled="boards.length === 0"
      >
        <el-option v-for="board in boards" :key="board.id" :label="board.name" :value="board.id" />
      </el-select>
      <span class="muted">
        {{ boards.length === 0 ? '该频道暂无板块信息' : `将发布到 ${boards.find((board) => board.id === boardId)?.name ?? '默认板块'}` }}
      </span>
      <span class="muted post-composer__hint">富文本编辑器（封面/视频排序）将在 M2 补齐</span>
    </div>

    <input
      ref="fileInputRef"
      type="file"
      accept="image/*,video/*"
      multiple
      class="visually-hidden"
      aria-label="选择图片或视频文件"
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

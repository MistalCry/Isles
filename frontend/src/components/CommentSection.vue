<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type InputInstance } from 'element-plus'
import { ChatDotRound, Picture } from '@element-plus/icons-vue'
import { commentsApi, manageApi, mediaApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useCursorList } from '@/composables/useCursorList'
import { useRequireLogin } from '@/composables/useRequireLogin'
import { validateComment } from '@/utils/validators'
import { formatCount } from '@/utils/format'
import { EMOJI_LIST, MAX_MEDIA_COUNT } from '@/constants/app'
import type { Comment, CommentSort, MediaItem } from '@/types/models'
import UserAvatar from './UserAvatar.vue'
import MentionPicker from './MentionPicker.vue'
import { mentionToken } from '@/utils/mention'
import CommentItem from './CommentItem.vue'
import LoadingState from './LoadingState.vue'
import ErrorState from './ErrorState.vue'
import EmptyState from './EmptyState.vue'

const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    postId: string
    postAuthorId?: string
    channelId?: string
    commentCount?: number
    embedded?: boolean
    defaultSort?: CommentSort
    /** 是否允许当前用户评论（频道成员校验；未加入频道时为 false） */
    canComment?: boolean
  }>(),
  {
    postAuthorId: '',
    channelId: '',
    commentCount: 0,
    embedded: false,
    defaultSort: 'likes',
    canComment: true,
  },
)

const emit = defineEmits<{
  created: [comment: Comment]
  deleted: [comment: Comment]
}>()

const auth = useAuthStore()
const { requireLogin } = useRequireLogin()

const sort = ref<CommentSort>(props.defaultSort)
const content = ref('')
const replyTarget = ref<Comment | null>(null)
const submitting = ref(false)
/** 评论附件：图片/视频（≤9）与表情，与文字至少有一项非空 */
const mediaItems = ref<MediaItem[]>([])
const emojis = ref<string[]>([])
const uploading = ref(false)
const fileInput = ref<HTMLInputElement>()
const inputRef = ref<InputInstance>()
const likingIds = reactive(new Set<string>())

const list = useCursorList<Comment>((cursor) =>
  commentsApi.getComments(props.postId, { sort: sort.value, cursor, limit: 20 }),
)

onMounted(() => {
  void list.refresh()
})

watch(sort, () => {
  void list.refresh()
})

watch(
  () => props.postId,
  () => {
    replyTarget.value = null
    content.value = ''
    void list.refresh()
  },
)

function replyTargetName(comment: Comment): string {
  return comment.author.nickname || comment.author.username || t('common.islander')
}

function handleReply(comment: Comment): void {
  if (!requireLogin(t('comment.replyLoginRequired'))) return
  replyTarget.value = comment
  void nextTick(() => inputRef.value?.focus())
}

function cancelReply(): void {
  replyTarget.value = null
}

function triggerFilePicker(): void {
  if (!requireLogin(t('comment.uploadLoginRequired'))) return
  fileInput.value?.click()
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
    uploading.value = true
    try {
      mediaItems.value.push(await mediaApi.uploadMedia(file))
    } catch (err) {
      ElMessage.error(resolveErrorMessage(err, t('comment.mediaUploadFailed')))
    } finally {
      uploading.value = false
    }
  }
}

function removeMedia(index: number): void {
  mediaItems.value.splice(index, 1)
}

function appendEmoji(emoji: string): void {
  if (emojis.value.length >= 20) {
    ElMessage.warning(t('comment.emojiLimit'))
    return
  }
  emojis.value.push(emoji)
}

function removeEmoji(index: number): void {
  emojis.value.splice(index, 1)
}

function insertMention(payload: { id: string; name: string }): void {
  const token = `${mentionToken(payload.id, payload.name)} `
  const textarea = (inputRef.value as unknown as { textarea?: HTMLTextAreaElement } | null)?.textarea
  if (!textarea) {
    content.value += token
    return
  }
  const start = textarea.selectionStart ?? content.value.length
  const end = textarea.selectionEnd ?? start
  content.value = content.value.slice(0, start) + token + content.value.slice(end)
  void nextTick(() => {
    textarea.focus()
    const caret = start + token.length
    textarea.setSelectionRange(caret, caret)
  })
}

async function handleDelete(comment: Comment): Promise<void> {
  try {
    await ElMessageBox.confirm(t('comment.deleteConfirm'), t('comment.deleteTitle'), {
      confirmButtonText: t('common.delete'),
      cancelButtonText: t('common.cancel'),
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await manageApi.deleteComment(comment.id)
    ElMessage.success(t('comment.deleted'))
    emit('deleted', comment)
    await list.refresh()
  } catch (err) {
    ElMessage.error(resolveErrorMessage(err, t('comment.deleteFailed')))
  }
}

async function submit(): Promise<void> {
  if (!requireLogin(t('comment.loginRequired'))) return
  if (!props.canComment) {
    ElMessage.warning(t('comment.joinBeforePost'))
    return
  }
  const message = validateComment(content.value, mediaItems.value.length, emojis.value.length)
  if (message) {
    ElMessage.warning(message)
    return
  }
  submitting.value = true
  try {
    const created = await commentsApi.createComment(props.postId, {
      content: content.value.trim(),
      parentId: replyTarget.value
        ? (replyTarget.value.parentId ?? replyTarget.value.id)
        : null,
      replyToUserId: replyTarget.value?.author.id ?? null,
      media: mediaItems.value,
      emoji: emojis.value,
    })
    content.value = ''
    mediaItems.value = []
    emojis.value = []
    replyTarget.value = null
    emit('created', created)
    await list.refresh()
    ElMessage.success(t('comment.posted'))
  } catch {
    // 错误提示由 axios 拦截器统一处理
  } finally {
    submitting.value = false
  }
}

async function handleLike(comment: Comment): Promise<void> {
  if (!requireLogin(t('comment.likeLoginRequired'))) return
  if (likingIds.has(comment.id)) return
  likingIds.add(comment.id)
  try {
    if (comment.isLiked) {
      const result = await commentsApi.unlikeComment(comment.id)
      comment.isLiked = false
      comment.likeCount = result.likeCount ?? Math.max(0, comment.likeCount - 1)
    } else {
      const result = await commentsApi.likeComment(comment.id)
      comment.isLiked = true
      comment.likeCount = result.likeCount ?? comment.likeCount + 1
    }
  } catch {
    // 拦截器已提示
  } finally {
    likingIds.delete(comment.id)
  }
}

const placeholder = () => (auth.isAuthenticated ? t('comment.placeholder') : t('comment.placeholderGuest'))
</script>

<template>
  <section class="comment-section" :class="{ 'comment-section--embedded': embedded }" :aria-label="t('comment.section')">
    <header class="comment-section__toolbar">
      <h2 class="comment-section__title">{{ t('common.comment') }}<span v-if="commentCount > 0" class="comment-section__count">{{ formatCount(commentCount) }}</span>
      </h2>
      <el-radio-group v-model="sort" size="small" :aria-label="t('comment.sortLabel')">
        <el-radio-button value="likes">{{ t('comment.sort.hot') }}</el-radio-button>
        <el-radio-button value="latest">{{ t('comment.sort.latest') }}</el-radio-button>
      </el-radio-group>
    </header>

    <div v-if="!canComment" class="comment-section__join-tip">
      <p>{{ t('comment.joinRequired') }}</p>
      <RouterLink v-if="channelId" :to="{ name: 'channel', params: { id: channelId } }" class="link">{{ t('comment.joinHintLink') }}</RouterLink>
    </div>

    <div v-else class="comment-composer">
      <UserAvatar
        v-if="auth.currentUser"
        :src="auth.currentUser.avatarUrl"
        :name="auth.displayName"
        :size="34"
      />
      <UserAvatar v-else :name="t('common.visitor')" :size="34" />
      <div class="comment-composer__body">
        <div v-if="replyTarget" class="comment-composer__reply">
          <span>{{ t('comment.replyTo', { name: replyTargetName(replyTarget) }) }}</span>
          <button type="button" class="link" @click="cancelReply">{{ t('common.cancel') }}</button>
        </div>
        <el-input
          ref="inputRef"
          v-model="content"
          type="textarea"
          :rows="2"
          maxlength="2000"
          show-word-limit
          :placeholder="placeholder()"
          :aria-label="placeholder()"
          @keydown.ctrl.enter.prevent="submit"
        />
        <div v-if="mediaItems.length > 0" class="comment-composer__media">
          <div v-for="(item, index) in mediaItems" :key="`${item.url}-${index}`" class="comment-composer__media-item">
            <img v-if="item.type === 'IMAGE'" :src="item.thumbUrl || item.url" :alt="t('media.selectedImage', { n: index + 1 })" />
            <video v-else :src="item.url" muted playsinline preload="metadata" />
            <button
              type="button"
              class="comment-composer__media-remove"
              :aria-label="t('media.crop.removeMedia')"
              @click="removeMedia(index)"
            >
              ×
            </button>
          </div>
        </div>

        <div v-if="emojis.length > 0" class="comment-composer__emoji-selected">
          <button
            v-for="(emoji, index) in emojis"
            :key="`${emoji}-${index}`"
            type="button"
            class="comment-composer__emoji-chip"
            :aria-label="t('common.removeEmoji', { emoji })"
            @click="removeEmoji(index)"
          >
            {{ emoji }}<span aria-hidden="true"> ×</span>
          </button>
        </div>

        <div class="comment-composer__actions">
          <div class="comment-composer__tools">
            <MentionPicker :channel-id="channelId" label="@" :disabled="!auth.isAuthenticated" @select="insertMention" />
            <el-popover trigger="click" :width="220" placement="top-start">
              <template #reference>
                <button type="button" class="comment-composer__tool" :aria-label="t('media.crop.insertEmoji')">😊</button>
              </template>
              <div class="comment-composer__emoji-panel">
                <button
                  v-for="emoji in EMOJI_LIST"
                  :key="emoji"
                  type="button"
                  class="comment-composer__emoji-btn"
                  @click="appendEmoji(emoji)"
                >
                  {{ emoji }}
                </button>
              </div>
            </el-popover>
            <button
              type="button"
              class="comment-composer__tool"
              :disabled="uploading || !auth.isAuthenticated"
              :aria-label="t('media.uploadMedia')"
              @click="triggerFilePicker"
            >
              <el-icon :size="15"><Picture /></el-icon>
              <span v-if="uploading">{{ t('common.uploading') }}</span>
            </button>
            <span v-if="mediaItems.length > 0" class="muted">{{ mediaItems.length }} / {{ MAX_MEDIA_COUNT }}</span>
            <span class="muted">{{ t('comment.shortcutHint') }}</span>
          </div>
          <el-button
            type="primary"
            size="small"
            :loading="submitting"
            @click="submit"
          >
            {{ auth.isAuthenticated ? t('comment.submit') : t('comment.submitGuest') }}
          </el-button>
        </div>
        <input
          ref="fileInput"
          type="file"
          accept="image/*,video/*"
          multiple
          class="visually-hidden"
          @change="handleFiles"
        />
        <p v-if="!auth.isAuthenticated" class="comment-section__guest-tip">{{ t('comment.guestHint') }}</p>
      </div>
    </div>

    <LoadingState v-if="list.initialLoading.value" :rows="3" />

    <ErrorState
      v-else-if="list.error.value"
      :message="list.error.value"
      @retry="list.refresh()"
    />

    <div v-else-if="list.items.value.length === 0" class="comment-section__empty">
      <EmptyState compact :title="t('comment.emptyTitle')" :description="t('comment.emptyHint')" />
    </div>

    <div v-else class="comment-list">
      <CommentItem
        v-for="comment in list.items.value"
        :key="comment.id"
        :comment="comment"
        :post-author-id="postAuthorId"
        :channel-id="channelId"
        :current-user-id="auth.currentUser?.id"
        @reply="handleReply"
        @like="handleLike"
        @delete="handleDelete"
      />

      <div class="comment-section__more">
        <el-button
          v-if="list.hasMore.value"
          text
          type="primary"
          :loading="list.loading.value"
          @click="list.loadMore()"
        >{{ t('comment.loadMore') }}</el-button>
        <span v-else class="muted">
          <el-icon><ChatDotRound /></el-icon>{{ t('comment.allLoaded') }}</span>
      </div>
    </div>
  </section>
</template>

<style scoped lang="scss">
.comment-section__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--isles-space-3);
  margin-bottom: var(--isles-space-4);
}

.comment-section__title {
  font-size: var(--isles-font-md);
  font-weight: 600;
}

.comment-section__count {
  margin-left: var(--isles-space-1);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-sm);
  font-weight: 400;
}

.comment-section__join-tip {
  display: flex;
  gap: var(--isles-space-3);
  align-items: center;
  justify-content: space-between;
  padding: var(--isles-space-3) var(--isles-space-4);
  margin-bottom: var(--isles-space-3);
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
  background: var(--isles-surface-muted);
  border-radius: var(--isles-radius-md);
}

.comment-composer__media {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  margin-top: var(--isles-space-2);
}

.comment-composer__media-item {
  position: relative;
  width: 72px;
  height: 72px;
  overflow: hidden;
  background: var(--isles-surface-muted);
  border-radius: var(--isles-radius-sm);

  img,
  video {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.comment-composer__media-remove {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 18px;
  height: 18px;
  padding: 0;
  font-size: 12px;
  line-height: 16px;
  color: #fff;
  cursor: pointer;
  background: rgb(0 0 0 / 55%);
  border: none;
  border-radius: 50%;
}

.comment-composer__emoji-selected {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: var(--isles-space-2);
}

.comment-composer__emoji-chip {
  padding: 1px 8px;
  font-size: 14px;
  cursor: pointer;
  background: var(--isles-surface-muted);
  border: 1px solid var(--isles-border);
  border-radius: 999px;
}

.comment-composer__emoji-panel {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 4px;
}

.comment-composer__emoji-btn {
  padding: 4px;
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
  background: transparent;
  border: none;
  border-radius: var(--isles-radius-sm);

  &:hover {
    background: var(--isles-surface-muted);
  }
}

.comment-composer__tool {
  display: inline-flex;
  gap: 2px;
  align-items: center;
  padding: 0 6px;
  font-size: var(--isles-font-sm);
  line-height: 24px;
  color: var(--isles-text-secondary);
  cursor: pointer;
  background: transparent;
  border: none;
  border-radius: var(--isles-radius-sm);

  &:hover:not(:disabled) {
    color: var(--isles-primary);
    background: var(--isles-surface-muted);
  }

  &:disabled {
    cursor: not-allowed;
    opacity: 0.5;
  }
}

.comment-composer__tools {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
}

.comment-composer {
  display: flex;
  gap: var(--isles-space-3);
  align-items: flex-start;
  padding-bottom: var(--isles-space-4);
}

.comment-composer__body {
  flex: 1;
  min-width: 0;
}

.comment-composer__reply {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 10px;
  margin-bottom: var(--isles-space-2);
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-xs);
  background: var(--isles-surface-muted);
  border-radius: var(--isles-radius-sm);

  button {
    background: transparent;
    border: none;
    font-size: inherit;
  }
}

.comment-composer__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: var(--isles-space-2);
  font-size: var(--isles-font-xs);
}

.comment-section__guest-tip {
  margin-top: var(--isles-space-1);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.comment-section__empty {
  padding-top: var(--isles-space-2);
}

.comment-section__more {
  display: flex;
  justify-content: center;
  padding-top: var(--isles-space-4);

  span {
    display: inline-flex;
    gap: 4px;
    align-items: center;
    font-size: var(--isles-font-xs);
  }
}

.comment-section--embedded {
  .comment-section__toolbar {
    position: sticky;
    top: 0;
    z-index: 1;
    padding-top: var(--isles-space-2);
    background: var(--isles-surface);
  }
}
</style>

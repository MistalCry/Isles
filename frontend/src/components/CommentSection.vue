<script setup lang="ts">
import { nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, type InputInstance } from 'element-plus'
import { ChatDotRound } from '@element-plus/icons-vue'
import { commentsApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { useCursorList } from '@/composables/useCursorList'
import { useRequireLogin } from '@/composables/useRequireLogin'
import { validateComment } from '@/utils/validators'
import { formatCount } from '@/utils/format'
import type { Comment, CommentSort } from '@/types/models'
import UserAvatar from './UserAvatar.vue'
import CommentItem from './CommentItem.vue'
import LoadingState from './LoadingState.vue'
import ErrorState from './ErrorState.vue'
import EmptyState from './EmptyState.vue'

const props = withDefaults(
  defineProps<{
    postId: string
    postAuthorId?: string
    channelId?: string
    commentCount?: number
    embedded?: boolean
    defaultSort?: CommentSort
  }>(),
  {
    postAuthorId: '',
    channelId: '',
    commentCount: 0,
    embedded: false,
    defaultSort: 'likes',
  },
)

const emit = defineEmits<{
  created: [comment: Comment]
}>()

const auth = useAuthStore()
const { requireLogin } = useRequireLogin()

const sort = ref<CommentSort>(props.defaultSort)
const content = ref('')
const replyTarget = ref<Comment | null>(null)
const submitting = ref(false)
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
  return comment.author.nickname || comment.author.username || '岛民'
}

function handleReply(comment: Comment): void {
  if (!requireLogin('登录后可回复评论')) return
  replyTarget.value = comment
  void nextTick(() => inputRef.value?.focus())
}

function cancelReply(): void {
  replyTarget.value = null
}

async function submit(): Promise<void> {
  if (!requireLogin('登录后可发表评论')) return
  const message = validateComment(content.value)
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
    })
    content.value = ''
    replyTarget.value = null
    emit('created', created)
    await list.refresh()
    ElMessage.success('评论已发布')
  } catch {
    // 错误提示由 axios 拦截器统一处理
  } finally {
    submitting.value = false
  }
}

async function handleLike(comment: Comment): Promise<void> {
  if (!requireLogin('登录后可点赞评论')) return
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

const placeholder = () => (auth.isAuthenticated ? '友善发言，理性讨论…' : '登录后参与评论')
</script>

<template>
  <section class="comment-section" :class="{ 'comment-section--embedded': embedded }" aria-label="评论区">
    <header class="comment-section__toolbar">
      <h2 class="comment-section__title">
        评论
        <span v-if="commentCount > 0" class="comment-section__count">{{ formatCount(commentCount) }}</span>
      </h2>
      <el-radio-group v-model="sort" size="small" aria-label="评论排序">
        <el-radio-button value="likes">最热</el-radio-button>
        <el-radio-button value="latest">最新</el-radio-button>
      </el-radio-group>
    </header>

    <div class="comment-composer">
      <UserAvatar
        v-if="auth.currentUser"
        :src="auth.currentUser.avatarUrl"
        :name="auth.displayName"
        :size="34"
      />
      <UserAvatar v-else name="游" :size="34" />
      <div class="comment-composer__body">
        <div v-if="replyTarget" class="comment-composer__reply">
          <span>回复 @{{ replyTargetName(replyTarget) }}</span>
          <button type="button" class="link" @click="cancelReply">取消</button>
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
        <div class="comment-composer__actions">
          <span class="muted">Ctrl + Enter 快捷发表</span>
          <el-button
            type="primary"
            size="small"
            :loading="submitting"
            @click="submit"
          >
            {{ auth.isAuthenticated ? '发表评论' : '登录后评论' }}
          </el-button>
        </div>
        <p v-if="!auth.isAuthenticated" class="comment-section__guest-tip">
          游客可浏览评论，登录后即可参与讨论。
        </p>
      </div>
    </div>

    <LoadingState v-if="list.initialLoading.value" :rows="3" />

    <ErrorState
      v-else-if="list.error.value"
      :message="list.error.value"
      @retry="list.refresh()"
    />

    <div v-else-if="list.items.value.length === 0" class="comment-section__empty">
      <EmptyState compact title="还没有评论" description="成为第一个留下想法的人吧。" />
    </div>

    <div v-else class="comment-list">
      <CommentItem
        v-for="comment in list.items.value"
        :key="comment.id"
        :comment="comment"
        :post-author-id="postAuthorId"
        :channel-id="channelId"
        @reply="handleReply"
        @like="handleLike"
      />

      <div class="comment-section__more">
        <el-button
          v-if="list.hasMore.value"
          text
          type="primary"
          :loading="list.loading.value"
          @click="list.loadMore()"
        >
          加载更多评论
        </el-button>
        <span v-else class="muted">
          <el-icon><ChatDotRound /></el-icon>
          已加载全部评论
        </span>
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

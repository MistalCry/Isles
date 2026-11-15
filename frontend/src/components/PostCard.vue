<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ChatDotRound, Share, Star, StarFilled, View } from '@element-plus/icons-vue'
import { postsApi } from '@/api'
import { useRequireLogin } from '@/composables/useRequireLogin'
import { excerpt, formatCount, formatRelativeTime, sortMedia } from '@/utils/format'
import type { Comment, Post } from '@/types/models'
import UserAvatar from './UserAvatar.vue'
import UserCardPopover from './UserCardPopover.vue'
import MediaViewer from './MediaViewer.vue'
import QuickCommentSheet from './QuickCommentSheet.vue'
import ShareToDmDialog from './dm/ShareToDmDialog.vue'

const props = withDefaults(
  defineProps<{
    post: Post
    showChannel?: boolean
    showBoard?: boolean
    adminMode?: boolean
  }>(),
  {
    showChannel: false,
    showBoard: true,
    adminMode: false,
  },
)

const emit = defineEmits<{
  changed: [post: Post]
}>()

const { requireLogin } = useRequireLogin()

const likeCount = ref(props.post.likeCount ?? 0)
const liked = ref(Boolean(props.post.isLiked))
const commentCount = ref(props.post.commentCount ?? 0)
const liking = ref(false)
const commentsOpen = ref(false)
const shareOpen = ref(false)

const authorName = computed(
  () => props.post.author.nickname || props.post.author.username || '岛民',
)
const roleName = computed(
  () => props.post.author.channelRole?.name ?? props.post.author.roles?.[0]?.name ?? '',
)
const media = computed(() => sortMedia(props.post.media).slice(0, 9))
const postPath = computed(() => ({ name: 'post' as const, params: { id: props.post.id } }))
const channelPath = computed(() => ({
  name: 'channel' as const,
  params: { id: props.post.channelId },
}))
const channelName = computed(() => props.post.channel?.name ?? '频道')
const excerptText = computed(() => excerpt(props.post.content, 220))

watch(
  () => props.post.likeCount,
  (value) => {
    likeCount.value = value ?? 0
  },
)
watch(
  () => props.post.isLiked,
  (value) => {
    liked.value = Boolean(value)
  },
)
watch(
  () => props.post.commentCount,
  (value) => {
    commentCount.value = value ?? 0
  },
)

async function toggleLike(): Promise<void> {
  if (!requireLogin('登录后可点赞')) return
  if (liking.value) return
  liking.value = true
  try {
    if (liked.value) {
      const result = await postsApi.unlikePost(props.post.id)
      liked.value = false
      likeCount.value = result.likeCount ?? Math.max(0, likeCount.value - 1)
    } else {
      const result = await postsApi.likePost(props.post.id)
      liked.value = true
      likeCount.value = result.likeCount ?? likeCount.value + 1
    }
  } catch {
    // 错误提示由 axios 拦截器统一处理
  } finally {
    liking.value = false
  }
}

function handleCommentCreated(_comment: Comment): void {
  commentCount.value += 1
  emit('changed', props.post)
}

function handleAdminAction(action: string): void {
  ElMessage.info(`${action}操作请在频道管理中心完成`)
}
</script>

<template>
  <article class="post-card">
    <header v-if="showChannel && post.channel" class="post-card__channel">
      <RouterLink :to="channelPath" class="post-card__channel-link">
        <UserAvatar :src="post.channel?.avatarUrl" :name="channelName" :size="26" square />
        <span>{{ channelName }}</span>
      </RouterLink>
    </header>

    <header class="post-card__author">
      <UserCardPopover :user="post.author" :channel-id="post.channelId">
        <button type="button" class="post-card__author-link" :aria-label="`查看 ${authorName} 的用户卡片`">
          <UserAvatar :src="post.author.avatarUrl" :name="authorName" :size="34" />
          <span class="post-card__author-meta">
            <strong>{{ authorName }}</strong>
            <small>@{{ post.author.username }}</small>
          </span>
        </button>
      </UserCardPopover>
      <el-tag v-if="roleName" size="small" effect="plain" type="info">{{ roleName }}</el-tag>
      <span v-if="post.board && showBoard" class="post-card__board">{{ post.board.name }}</span>
      <time class="post-card__time" :datetime="post.createdAt">
        {{ formatRelativeTime(post.createdAt) }}
      </time>
    </header>

    <div class="post-card__badges">
      <el-tag v-if="post.isPinned" size="small" type="danger" effect="plain">置顶</el-tag>
      <el-tag v-if="post.isEssence" size="small" type="warning" effect="plain">精华</el-tag>
    </div>

    <RouterLink :to="postPath" class="post-card__content">
      <h2 v-if="post.title" class="post-card__title line-clamp-2">{{ post.title }}</h2>
      <p class="post-card__excerpt line-clamp-3">{{ excerptText }}</p>
    </RouterLink>

    <MediaViewer :media="media" />

    <footer class="post-card__footer">
      <button
        type="button"
        class="post-action"
        :class="{ 'is-active': liked }"
        :disabled="liking"
        :aria-pressed="liked"
        aria-label="点赞"
        @click="toggleLike"
      >
        <el-icon :size="16"><StarFilled v-if="liked" /><Star v-else /></el-icon>
        <span>{{ formatCount(likeCount) }}</span>
      </button>

      <button type="button" class="post-action" aria-label="打开评论" @click="commentsOpen = true">
        <el-icon :size="16"><ChatDotRound /></el-icon>
        <span>{{ formatCount(commentCount) }}</span>
      </button>

      <button type="button" class="post-action" aria-label="分享帖子" @click="shareOpen = true">
        <el-icon :size="16"><Share /></el-icon>
        <span>分享</span>
      </button>

      <span class="post-card__views">
        <el-icon :size="14"><View /></el-icon>
        {{ formatCount(post.viewCount) }}
      </span>

      <div v-if="adminMode" class="post-card__admin" aria-label="管理操作">
        <button type="button" class="post-action post-action--admin" @click="handleAdminAction('置顶')">置顶</button>
        <button type="button" class="post-action post-action--admin" @click="handleAdminAction('精华')">加精</button>
        <button type="button" class="post-action post-action--admin" @click="handleAdminAction('删除')">删除</button>
      </div>
    </footer>

    <QuickCommentSheet
      v-model="commentsOpen"
      :post="post"
      :comment-count="commentCount"
      @created="handleCommentCreated"
    />

    <ShareToDmDialog v-model="shareOpen" :post="post" />
  </article>
</template>

<style scoped lang="scss">
.post-card {
  padding: var(--isles-space-5);
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
  box-shadow: var(--isles-shadow-sm);
  transition: border-color 0.15s ease;

  &:hover {
    border-color: var(--isles-border-strong);
  }
}

.post-card__channel {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  align-items: center;
  margin-bottom: var(--isles-space-3);
}

.post-card__channel-link {
  display: inline-flex;
  gap: var(--isles-space-2);
  align-items: center;
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
  font-weight: 600;

  &:hover {
    color: var(--isles-primary);
  }
}

.post-card__author {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  align-items: center;
  margin-bottom: var(--isles-space-3);
}

.post-card__author-link {
  display: inline-flex;
  gap: var(--isles-space-2);
  align-items: center;
  padding: 0;
  background: transparent;
  border: none;
  border-radius: var(--isles-radius-md);
  cursor: pointer;
}

.post-card__author-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.3;

  strong {
    font-size: var(--isles-font-sm);
  }

  small {
    color: var(--isles-text-muted);
    font-size: var(--isles-font-xs);
  }
}

.post-card__board {
  padding: 1px 8px;
  color: var(--isles-primary);
  font-size: var(--isles-font-xs);
  background: var(--isles-primary-soft);
  border-radius: var(--isles-radius-pill);
}

.post-card__time {
  margin-left: auto;
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.post-card__badges {
  display: flex;
  gap: var(--isles-space-2);
  margin-bottom: var(--isles-space-2);

  &:empty {
    display: none;
  }
}

.post-card__content {
  display: block;
  color: inherit;
}

.post-card__title {
  font-size: var(--isles-font-md);
  font-weight: 600;
  line-height: var(--isles-line-tight);

  &:hover {
    color: var(--isles-primary);
  }
}

.post-card__excerpt {
  margin-top: var(--isles-space-1);
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
  line-height: var(--isles-line-base);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.post-card__footer {
  display: flex;
  align-items: center;
  gap: var(--isles-space-5);
  margin-top: var(--isles-space-4);
  padding-top: var(--isles-space-3);
  border-top: 1px solid var(--isles-border);
}

.post-action {
  display: inline-flex;
  gap: var(--isles-space-1);
  align-items: center;
  padding: 4px 6px;
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
  background: transparent;
  border: none;
  border-radius: var(--isles-radius-sm);
  cursor: pointer;
  transition:
    color 0.15s ease,
    background 0.15s ease;

  &:hover {
    color: var(--isles-primary);
    background: var(--isles-hover);
  }

  &.is-active {
    color: var(--isles-accent);
  }

  &:disabled {
    cursor: not-allowed;
    opacity: 0.6;
  }
}

.post-action--admin {
  color: var(--isles-text-secondary);
}

.post-card__views {
  display: inline-flex;
  gap: var(--isles-space-1);
  align-items: center;
  margin-left: auto;
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.post-card__admin {
  display: flex;
  gap: var(--isles-space-1);
  padding-left: var(--isles-space-3);
  border-left: 1px solid var(--isles-border);
}

@media (max-width: 767px) {
  .post-card {
    padding: var(--isles-space-4);
  }

  .post-card__footer {
    gap: var(--isles-space-3);
  }

  .post-card__admin {
    flex-wrap: wrap;
    padding-left: 0;
    border-left: none;
  }
}
</style>

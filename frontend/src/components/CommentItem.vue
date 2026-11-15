<script setup lang="ts">
import { computed } from 'vue'
import { Star, StarFilled } from '@element-plus/icons-vue'
import { formatCount, formatRelativeTime } from '@/utils/format'
import type { Comment, UserSummary } from '@/types/models'
import UserAvatar from './UserAvatar.vue'
import UserCardPopover from './UserCardPopover.vue'
import MediaViewer from './MediaViewer.vue'

const props = withDefaults(
  defineProps<{
    comment: Comment
    postAuthorId?: string
    channelId?: string
  }>(),
  {
    postAuthorId: '',
    channelId: '',
  },
)

const emit = defineEmits<{
  reply: [comment: Comment]
  like: [comment: Comment]
}>()

const authorName = computed(
  () => props.comment.author.nickname || props.comment.author.username || '岛民',
)
const roleName = computed(
  () => props.comment.author.channelRole?.name ?? props.comment.author.roles?.[0]?.name ?? '',
)
const isPostAuthor = computed(
  () => Boolean(props.postAuthorId) && props.comment.author.id === props.postAuthorId,
)
const hasReplies = computed(() => (props.comment.replies?.length ?? 0) > 0)

function displayName(user?: UserSummary | null): string {
  return user?.nickname || user?.username || '岛民'
}

function handleReply(comment: Comment): void {
  emit('reply', comment)
}

function handleLike(comment: Comment): void {
  emit('like', comment)
}
</script>

<template>
  <article class="comment-item" :data-comment-id="comment.id">
    <UserCardPopover :user="comment.author" :channel-id="channelId">
      <button type="button" class="comment-item__avatar" :aria-label="`查看 ${authorName} 的用户卡片`">
        <UserAvatar :src="comment.author.avatarUrl" :name="authorName" :size="34" />
      </button>
    </UserCardPopover>

    <div class="comment-item__body">
      <header class="comment-item__head">
        <UserCardPopover :user="comment.author" :channel-id="channelId">
          <button type="button" class="comment-item__name">{{ authorName }}</button>
        </UserCardPopover>
        <span v-if="isPostAuthor" class="comment-badge comment-badge--author">作者</span>
        <el-tag v-if="roleName" size="small" effect="plain" type="info">{{ roleName }}</el-tag>
        <time class="comment-item__time" :datetime="comment.createdAt">
          {{ formatRelativeTime(comment.createdAt) }}
        </time>
      </header>

      <p v-if="comment.replyToUser" class="comment-item__reply-to">
        回复 @{{ displayName(comment.replyToUser) }}
      </p>

      <p class="comment-item__content">{{ comment.content }}</p>
      <MediaViewer :media="comment.media" compact />

      <div class="comment-item__actions">
        <button
          type="button"
          class="comment-action"
          :class="{ 'is-active': comment.isLiked }"
          @click="handleLike(comment)"
        >
          <el-icon :size="14"><StarFilled v-if="comment.isLiked" /><Star v-else /></el-icon>
          <span>{{ comment.likeCount > 0 ? formatCount(comment.likeCount) : '赞' }}</span>
        </button>
        <button type="button" class="comment-action" @click="handleReply(comment)">回复</button>
      </div>

      <div v-if="hasReplies" class="comment-replies">
        <article v-for="reply in comment.replies" :key="reply.id" class="comment-reply" :data-comment-id="reply.id">
          <UserCardPopover :user="reply.author" :channel-id="channelId">
            <button type="button" class="comment-reply__avatar" :aria-label="`查看 ${displayName(reply.author)} 的用户卡片`">
              <UserAvatar :src="reply.author.avatarUrl" :name="displayName(reply.author)" :size="26" />
            </button>
          </UserCardPopover>
          <div class="comment-reply__body">
            <header class="comment-reply__head">
              <span class="comment-reply__name">{{ displayName(reply.author) }}</span>
              <span v-if="postAuthorId && reply.author.id === postAuthorId" class="comment-badge comment-badge--author">
                作者
              </span>
              <time class="comment-item__time" :datetime="reply.createdAt">
                {{ formatRelativeTime(reply.createdAt) }}
              </time>
            </header>
            <p v-if="reply.replyToUser" class="comment-item__reply-to">
              回复 @{{ displayName(reply.replyToUser) }}
            </p>
            <p class="comment-item__content">{{ reply.content }}</p>
            <MediaViewer :media="reply.media" compact />
            <div class="comment-item__actions">
              <button
                type="button"
                class="comment-action"
                :class="{ 'is-active': reply.isLiked }"
                @click="handleLike(reply)"
              >
                <el-icon :size="13"><StarFilled v-if="reply.isLiked" /><Star v-else /></el-icon>
                <span>{{ reply.likeCount > 0 ? formatCount(reply.likeCount) : '赞' }}</span>
              </button>
              <button type="button" class="comment-action" @click="handleReply(reply)">回复</button>
            </div>
          </div>
        </article>
      </div>
    </div>
  </article>
</template>

<style scoped lang="scss">
.comment-item {
  display: flex;
  gap: var(--isles-space-3);
  padding: var(--isles-space-4) 0;
  border-bottom: 1px solid var(--isles-border);

  &:last-child {
    border-bottom: none;
  }
}

.comment-item__avatar {
  padding: 0;
  background: transparent;
  border: none;
  border-radius: 50%;
  cursor: pointer;
}

.comment-item__body {
  flex: 1;
  min-width: 0;
}

.comment-item__head {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  align-items: center;
}

.comment-item__name {
  padding: 0;
  background: transparent;
  border: none;
  font-size: var(--isles-font-sm);
  font-weight: 600;
  cursor: pointer;

  &:hover {
    color: var(--isles-primary);
  }
}

.comment-badge {
  padding: 0 6px;
  font-size: 10px;
  line-height: 16px;
  border-radius: 4px;
}

.comment-badge--author {
  color: var(--isles-accent);
  background: var(--isles-accent-soft);
}

.comment-item__time {
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.comment-item__reply-to {
  margin-top: 2px;
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.comment-item__content {
  margin-top: var(--isles-space-1);
  color: var(--isles-text);
  font-size: var(--isles-font-sm);
  line-height: var(--isles-line-base);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.comment-item__actions {
  display: flex;
  gap: var(--isles-space-4);
  margin-top: var(--isles-space-2);
}

.comment-action {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  padding: 0;
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
  background: transparent;
  border: none;
  cursor: pointer;

  &:hover {
    color: var(--isles-primary);
  }

  &.is-active {
    color: var(--isles-accent);
  }
}

.comment-replies {
  margin-top: var(--isles-space-3);
  padding: var(--isles-space-2) var(--isles-space-3);
  background: var(--isles-surface-muted);
  border-radius: var(--isles-radius-md);
}

.comment-reply {
  display: flex;
  gap: var(--isles-space-2);
  padding: var(--isles-space-2) 0;

  & + & {
    border-top: 1px solid var(--isles-border);
  }
}

.comment-reply__avatar {
  padding: 0;
  background: transparent;
  border: none;
  border-radius: 50%;
  cursor: pointer;
}

.comment-reply__body {
  flex: 1;
  min-width: 0;
}

.comment-reply__head {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  align-items: center;
}

.comment-reply__name {
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-xs);
  font-weight: 600;
}
</style>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { formatCount, excerpt } from '@/utils/format'
import type { Comment, Post } from '@/types/models'
import UserAvatar from './UserAvatar.vue'
import CommentSection from './CommentSection.vue'

const { t } = useI18n()

const props = defineProps<{
  modelValue: boolean
  post: Post
  commentCount?: number
  /** 非频道成员不能发言：弹窗里直接禁掉输入，别等提交才 403 */
  canComment?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  created: [comment: Comment]
}>()

const router = useRouter()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const authorName = computed(() => props.post.author.nickname || props.post.author.username || t('common.islander'))
const totalComments = computed(() => props.commentCount ?? props.post.commentCount)

function viewAll(): void {
  visible.value = false
  void router.push({ name: 'post', params: { id: props.post.id } })
}

function handleCreated(comment: Comment): void {
  emit('created', comment)
}
</script>

<template>
  <el-dialog v-model="visible" :title="t('common.comment')" width="min(640px, 94vw)" append-to-body>
    <div class="quick-comment__post">
      <UserAvatar :src="post.author.avatarUrl" :name="authorName" :size="36" />
      <div class="quick-comment__post-body">
        <div class="quick-comment__post-head">
          <strong>{{ authorName }}</strong>
          <span class="muted">{{ t('comment.countSuffix', { n: formatCount(totalComments) }) }}</span>
        </div>
        <p v-if="post.title" class="quick-comment__post-title">{{ post.title }}</p>
        <p class="quick-comment__post-excerpt line-clamp-2">{{ excerpt(post.content, 120) }}</p>
      </div>
    </div>

    <CommentSection
      :post-id="post.id"
      :post-author-id="post.author.id"
      :channel-id="post.channelId"
      :comment-count="totalComments"
      :can-comment="canComment !== false"
      embedded
      @created="handleCreated"
    />

    <template #footer>
      <div class="quick-comment__footer">
        <span class="muted">{{ t('quickComment.more') }}</span>
        <el-button type="primary" plain @click="viewAll">
          {{ t('quickComment.viewAll', { n: formatCount(totalComments) }) }}
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.quick-comment__post {
  display: flex;
  gap: var(--isles-space-3);
  align-items: flex-start;
  padding-bottom: var(--isles-space-3);
  margin-bottom: var(--isles-space-2);
  border-bottom: 1px solid var(--isles-border);
}

.quick-comment__post-body {
  flex: 1;
  min-width: 0;
}

.quick-comment__post-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--isles-space-3);

  strong {
    font-size: var(--isles-font-sm);
  }

  span {
    font-size: var(--isles-font-xs);
  }
}

.quick-comment__post-title {
  margin-top: 2px;
  font-size: var(--isles-font-sm);
  font-weight: 600;
}

.quick-comment__post-excerpt {
  margin-top: 2px;
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.quick-comment__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--isles-space-3);
}
</style>

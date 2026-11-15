<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ChatDotRound, EditPen, Share, Star, StarFilled, View } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { channelsApi, postsApi } from '@/api'
import { AppError, resolveErrorMessage } from '@/api/client'
import { useRequireLogin } from '@/composables/useRequireLogin'
import { useAuthStore } from '@/stores/auth'
import { formatCount, formatDateTime, sortMedia } from '@/utils/format'
import { renderContent } from '@/utils/sanitize'
import type { Post } from '@/types/models'
import UserAvatar from '@/components/UserAvatar.vue'
import UserCardPopover from '@/components/UserCardPopover.vue'
import MediaViewer from '@/components/MediaViewer.vue'
import CommentSection from '@/components/CommentSection.vue'
import RichTextEditor from '@/components/RichTextEditor.vue'
import ReportDialog from '@/components/ReportDialog.vue'
import ShareToDmDialog from '@/components/dm/ShareToDmDialog.vue'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'

const route = useRoute()
const router = useRouter()
const { requireLogin } = useRequireLogin()

const post = ref<Post | null>(null)
const loading = ref(true)
const errorMessage = ref('')
const errorStatus = ref(0)
const reportOpen = ref(false)
const shareOpen = ref(false)
const editOpen = ref(false)
const editSaving = ref(false)
const auth = useAuthStore()
const editForm = reactive({ title: '', content: '' })
/** null = 未知（不阻塞交互，由后端兜底）；false = 确认未加入频道 */
const joined = ref<boolean | null>(null)
const commentsRef = ref<HTMLElement | null>(null)

const liked = ref(false)
const likeCount = ref(0)
const commentCount = ref(0)
const liking = ref(false)

const authorName = computed(() => {
  const author = post.value?.author
  return author ? author.nickname || author.username || '岛民' : '岛民'
})
const roleName = computed(
  () => post.value?.author.channelRole?.name ?? post.value?.author.roles?.[0]?.name ?? '',
)
const channelName = computed(() => post.value?.channel?.name ?? '频道')
const safeHtml = computed(() =>
  post.value ? renderContent(post.value.content, post.value.contentFormat) : '',
)
const media = computed(() => sortMedia(post.value?.media))

async function loadMembership(channelId: string): Promise<void> {
  try {
    const channel = await channelsApi.getChannel(channelId)
    joined.value = Boolean(channel.isJoined ?? channel.joined)
  } catch {
    joined.value = null
  }
}

const canComment = computed(() => joined.value !== false)
const isAuthor = computed(() => Boolean(post.value && auth.currentUser?.id === post.value.author.id))
const isRichText = computed(() => post.value?.contentFormat === 'HTML')

function openEdit(): void {
  if (!post.value) return
  editForm.title = post.value.title ?? ''
  editForm.content = post.value.content ?? ''
  editOpen.value = true
}

async function saveEdit(): Promise<void> {
  if (!post.value) return
  const plain = editForm.content.replace(/<[^>]*>/g, '').replace(/&nbsp;/g, ' ').trim()
  if (!plain) {
    ElMessage.warning('正文不能为空')
    return
  }
  editSaving.value = true
  try {
    const updated = await postsApi.updatePost(post.value.id, {
      title: editForm.title.trim() || undefined,
      content: editForm.content,
      contentFormat: isRichText.value ? 'HTML' : 'PLAIN',
    })
    post.value = updated
    editOpen.value = false
    ElMessage.success('帖子已更新')
  } catch (err) {
    ElMessage.error(resolveErrorMessage(err, '保存失败，请稍后重试'))
  } finally {
    editSaving.value = false
  }
}

async function loadPost(): Promise<void> {
  const id = String(route.params.id ?? '')
  if (!id) return
  loading.value = true
  errorMessage.value = ''
  errorStatus.value = 0
  try {
    const data = await postsApi.getPost(id)
    post.value = data
    liked.value = Boolean(data.isLiked)
    likeCount.value = data.likeCount ?? 0
    commentCount.value = data.commentCount ?? 0
    void loadMembership(data.channelId)
    highlightTargetComment()
  } catch (err) {
    post.value = null
    const status = err instanceof AppError ? err.status : 0
    errorStatus.value = status
    errorMessage.value = status === 404 ? '帖子不存在或已被删除' : resolveErrorMessage(err, '帖子加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

/** 从通知跳转时定位到对应评论（FR-NTF-03）。 */
function highlightTargetComment(): void {
  const commentId = String(route.query.commentId ?? '')
  if (!commentId) return
  let attempts = 0
  const tryLocate = (): void => {
    const target = document.querySelector(`[data-comment-id="${commentId}"]`)
    if (target instanceof HTMLElement) {
      commentsRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
      target.scrollIntoView({ behavior: 'smooth', block: 'center' })
      target.classList.add('comment-highlight')
      window.setTimeout(() => target.classList.remove('comment-highlight'), 2400)
      return
    }
    attempts += 1
    if (attempts < 10) window.setTimeout(tryLocate, 400)
  }
  window.setTimeout(tryLocate, 400)
}

onMounted(() => {
  void loadPost()
})

watch(
  () => route.params.id,
  () => {
    void loadPost()
  },
)

async function toggleLike(): Promise<void> {
  if (!post.value) return
  if (!requireLogin('登录后可点赞')) return
  if (liking.value) return
  liking.value = true
  try {
    if (liked.value) {
      const result = await postsApi.unlikePost(post.value.id)
      liked.value = false
      likeCount.value = result.likeCount ?? Math.max(0, likeCount.value - 1)
    } else {
      const result = await postsApi.likePost(post.value.id)
      liked.value = true
      likeCount.value = result.likeCount ?? likeCount.value + 1
    }
  } catch {
    // 错误提示由 axios 拦截器统一处理
  } finally {
    liking.value = false
  }
}

function handleCommentCreated(): void {
  commentCount.value += 1
}

function openReport(): void {
  if (!requireLogin('登录后可举报')) return
  reportOpen.value = true
}

function scrollToComments(): void {
  commentsRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function goBack(): void {
  if (window.history.length > 1) {
    router.back()
  } else {
    void router.push('/feed')
  }
}
</script>

<template>
  <div class="page post-detail-page">
    <div class="post-detail-page__topbar">
      <el-button text :icon="ArrowLeft" @click="goBack">返回</el-button>
    </div>

    <LoadingState v-if="loading" :rows="6" tip="正在加载帖子…" />

    <ErrorState
      v-else-if="errorMessage"
      :message="errorMessage"
      retry-text="重新加载帖子"
      @retry="loadPost"
    >
      <template #action>
        <el-button @click="$router.push('/feed')">回到频道动态</el-button>
      </template>
    </ErrorState>

    <template v-else-if="post">
      <article class="post-detail isles-card">
        <header class="post-detail__head">
          <UserCardPopover :user="post.author" :channel-id="post.channelId">
            <button type="button" class="post-detail__author" :aria-label="`查看 ${authorName} 的用户卡片`">
              <UserAvatar :src="post.author.avatarUrl" :name="authorName" :size="42" />
              <span class="post-detail__author-meta">
                <strong>{{ authorName }}</strong>
                <small>
                  {{ formatDateTime(post.createdAt) }}
                  <span v-if="post.channel"> · {{ channelName }}</span>
                </small>
              </span>
            </button>
          </UserCardPopover>

          <el-tag v-if="roleName" size="small" effect="plain" type="info">{{ roleName }}</el-tag>
          <el-tag v-if="post.isPinned" size="small" type="danger" effect="plain">置顶</el-tag>
          <el-tag v-if="post.isEssence" size="small" type="warning" effect="plain">精华</el-tag>

          <div class="post-detail__head-actions">
            <el-button v-if="isAuthor" text size="small" :icon="EditPen" @click="openEdit">编辑</el-button>
            <el-button text size="small" @click="openReport">举报</el-button>
          </div>
        </header>

        <h1 v-if="post.title" class="post-detail__title">{{ post.title }}</h1>

        <div class="post-detail__meta">
          <RouterLink :to="{ name: 'channel', params: { id: post.channelId } }" class="link">
            {{ channelName }}
          </RouterLink>
          <span v-if="post.board">板块：{{ post.board.name }}</span>
          <span class="post-detail__views">
            <el-icon :size="14"><View /></el-icon>
            {{ formatCount(post.viewCount) }} 浏览
          </span>
        </div>

        <!-- 正文可能为 HTML：renderContent 内部已通过 DOMPurify 白名单净化 -->
        <div class="post-detail__content" v-html="safeHtml" />

        <MediaViewer :media="media" />

        <footer class="post-detail__actions">
          <button
            type="button"
            class="detail-action"
            :class="{ 'is-active': liked }"
            :disabled="liking"
            :aria-pressed="liked"
            @click="toggleLike"
          >
            <el-icon :size="18"><StarFilled v-if="liked" /><Star v-else /></el-icon>
            <span>{{ formatCount(likeCount) }} 点赞</span>
          </button>
          <button type="button" class="detail-action" @click="scrollToComments">
            <el-icon :size="18"><ChatDotRound /></el-icon>
            <span>{{ formatCount(commentCount) }} 评论</span>
          </button>
          <button type="button" class="detail-action" @click="shareOpen = true">
            <el-icon :size="18"><Share /></el-icon>
            <span>分享帖子</span>
          </button>
          <span class="detail-action detail-action--static">
            <el-icon :size="16"><View /></el-icon>
            <span>{{ formatCount(post.viewCount) }}</span>
          </span>
        </footer>
      </article>

      <section ref="commentsRef" class="post-detail__comments isles-card">
        <CommentSection
          :post-id="post.id"
          :post-author-id="post.author.id"
          :channel-id="post.channelId"
          :comment-count="commentCount"
          :can-comment="canComment"
          @created="handleCommentCreated"
        />
      </section>

      <el-dialog v-model="editOpen" title="编辑帖子" width="min(680px, 96vw)" top="6vh" append-to-body>
        <el-input v-model="editForm.title" maxlength="200" show-word-limit placeholder="标题（选填）" />
        <div class="post-detail__edit-body">
          <RichTextEditor v-if="isRichText" v-model="editForm.content" :min-height="240" />
          <el-input v-else v-model="editForm.content" type="textarea" :rows="8" maxlength="5000" show-word-limit />
        </div>
        <template #footer>
          <el-button @click="editOpen = false">取消</el-button>
          <el-button type="primary" :loading="editSaving" @click="saveEdit">保存</el-button>
        </template>
      </el-dialog>

      <ReportDialog v-model="reportOpen" target-type="post" :target-id="post.id" />
      <ShareToDmDialog v-model="shareOpen" :post="post" />
    </template>
  </div>
</template>

<style scoped lang="scss">
.post-detail-page__topbar {
  margin-bottom: var(--isles-space-2);
}

.post-detail {
  padding: var(--isles-space-6);
}

.post-detail__head {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  align-items: center;
}

.post-detail__author {
  display: inline-flex;
  gap: var(--isles-space-3);
  align-items: center;
  padding: 0;
  background: transparent;
  border: none;
  border-radius: var(--isles-radius-md);
  cursor: pointer;
}

.post-detail__author-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.35;

  strong {
    font-size: var(--isles-font-sm);
  }

  small {
    color: var(--isles-text-muted);
    font-size: var(--isles-font-xs);
  }
}

.post-detail__head-actions {
  margin-left: auto;
}

.post-detail__title {
  margin-top: var(--isles-space-4);
  font-size: var(--isles-font-xl);
  font-weight: 600;
  line-height: var(--isles-line-tight);
}

.post-detail__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-1) var(--isles-space-4);
  align-items: center;
  margin-top: var(--isles-space-2);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.post-detail__views {
  display: inline-flex;
  gap: 4px;
  align-items: center;
}

.post-detail__content {
  margin-top: var(--isles-space-4);
  color: var(--isles-text);
  font-size: var(--isles-font-base);
  line-height: 1.8;
  overflow-wrap: anywhere;

  :deep(p) {
    margin-bottom: var(--isles-space-3);
  }

  :deep(img) {
    max-width: 100%;
    border-radius: var(--isles-radius-md);
  }

  :deep(blockquote) {
    padding: var(--isles-space-2) var(--isles-space-4);
    margin: var(--isles-space-3) 0;
    color: var(--isles-text-secondary);
    background: var(--isles-surface-muted);
    border-left: 3px solid var(--isles-border-strong);
  }

  :deep(a) {
    color: var(--isles-primary);
    text-decoration: underline;
  }

  :deep(ul),
  :deep(ol) {
    padding-left: var(--isles-space-5);
    list-style: revert;
  }
}

.post-detail__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-5);
  align-items: center;
  margin-top: var(--isles-space-5);
  padding-top: var(--isles-space-4);
  border-top: 1px solid var(--isles-border);
}

.post-detail__edit-body {
  margin-top: var(--isles-space-3);
}

.detail-action {
  display: inline-flex;
  gap: var(--isles-space-1);
  align-items: center;
  padding: 6px 10px;
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
  background: transparent;
  border: none;
  border-radius: var(--isles-radius-sm);
  cursor: pointer;

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

.detail-action--static {
  margin-left: auto;
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
  cursor: default;

  &:hover {
    color: var(--isles-text-muted);
    background: transparent;
  }
}

.post-detail__comments {
  margin-top: var(--isles-space-4);
  padding: var(--isles-space-6);
}

:deep(.comment-highlight) {
  outline: 2px solid var(--isles-primary);
  outline-offset: 2px;
  border-radius: var(--isles-radius-sm);
  background: var(--isles-primary-soft);
  transition: background 0.3s ease;
}

@media (max-width: 767px) {
  .post-detail,
  .post-detail__comments {
    padding: var(--isles-space-4);
  }

  .post-detail__actions {
    gap: var(--isles-space-3);
  }

  .detail-action--static {
    margin-left: 0;
  }
}
</style>

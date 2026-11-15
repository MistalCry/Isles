<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { channelsApi, manageApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import {
  CHANNEL_PERMISSIONS,
  hasChannelPermission,
  resolveChannelPermissions,
} from '@/utils/channelPermissions'
import { formatCount } from '@/utils/format'
import type { Board, Channel } from '@/types/models'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'

const { t } = useI18n()

const props = defineProps<{
  channel: Channel
  channelId: string
}>()

const auth = useAuthStore()
const boards = ref<Board[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const dialogOpen = ref(false)
const saving = ref(false)
const editingBoard = ref<Board | null>(null)
const formError = ref<string | null>(null)
const form = reactive({ name: '', description: '', sort: 0 })

const permissions = computed(() => resolveChannelPermissions(props.channel, auth.currentUser?.id))
const canManage = computed(() => hasChannelPermission(permissions.value, CHANNEL_PERMISSIONS.BOARD_MANAGE))

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    boards.value = await channelsApi.getBoards(props.channelId)
  } catch (err) {
    error.value = resolveErrorMessage(err, t('manage.boards.loadFailed'))
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  editingBoard.value = null
  form.name = ''
  form.description = ''
  form.sort = boards.value.length
  formError.value = null
  dialogOpen.value = true
}

function openEdit(board: Board): void {
  editingBoard.value = board
  form.name = board.name
  form.description = ''
  form.sort = board.sortOrder ?? 0
  formError.value = null
  dialogOpen.value = true
}

async function save(): Promise<void> {
  if (!form.name.trim()) {
    formError.value = t('manage.boards.nameRequired')
    return
  }
  saving.value = true
  formError.value = null
  try {
    if (editingBoard.value) {
      await manageApi.updateBoard(props.channelId, editingBoard.value.id, {
        name: form.name.trim(),
        description: form.description.trim() || undefined,
        sort: form.sort,
      })
      ElMessage.success(t('manage.boards.updated'))
    } else {
      await manageApi.createBoard(props.channelId, {
        name: form.name.trim(),
        description: form.description.trim() || undefined,
        sort: form.sort,
      })
      ElMessage.success(t('manage.boards.created'))
    }
    dialogOpen.value = false
    await load()
  } catch (err) {
    formError.value = resolveErrorMessage(err, t('manage.boards.saveFailed'))
  } finally {
    saving.value = false
  }
}

async function remove(board: Board): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('manage.boards.deleteConfirm', { name: board.name }),
      t('manage.boards.deleteTitle'),
      {
      type: 'warning',
      confirmButtonText: t('common.delete'),
      cancelButtonText: t('common.cancel'),
    })
  } catch {
    return
  }
  try {
    await manageApi.deleteBoard(props.channelId, board.id)
    boards.value = boards.value.filter((item) => item.id !== board.id)
    ElMessage.success(t('manage.boards.deleted'))
  } catch {
    // 拦截器已提示
  }
}

function boardDescription(board: Board): string {
  return board.description || t('common.noDescription')
}

onMounted(() => {
  void load()
})
</script>

<template>
  <section class="manage-section">
    <header class="manage-section__header">
      <div>
        <h2>{{ t('manage.nav.boards') }}</h2>
        <p class="muted">{{ t('manage.boards.subtitle') }}</p>
      </div>
      <div class="manage-section__actions">
        <el-button :icon="Refresh" @click="load">{{ t('common.refresh') }}</el-button>
        <el-button v-if="canManage" type="primary" :icon="Plus" @click="openCreate">{{ t('manage.boards.create') }}</el-button>
      </div>
    </header>

    <el-alert
      v-if="!canManage"
      :title="t('channel.boardsReadonly')"
      type="info"
      :closable="false"
      show-icon
      class="manage-section__alert"
    />

    <LoadingState v-if="loading && boards.length === 0" :rows="3" :tip="t('channel.loadingBoards')" />
    <ErrorState v-else-if="error && boards.length === 0" :message="error" @retry="load" />
    <EmptyState v-else-if="boards.length === 0" :title="t('channel.noBoards')" :description="t('channel.noBoardsHint')" />

    <div v-else class="board-list">
      <article v-for="board in boards" :key="board.id" class="board-card isles-card">
        <div class="board-card__main">
          <div>
            <strong>{{ board.name }}</strong>
            <span class="manage-section__tag">{{ t('manage.boards.sortOrder', { n: board.sortOrder ?? 0 }) }}</span>
          </div>
          <p class="muted">{{ boardDescription(board) }}</p>
        </div>
        <div class="board-card__actions">
          <span class="muted">{{ t('manage.boards.postCount', { n: formatCount(board.postCount ?? 0) }) }}</span>
          <template v-if="canManage">
            <el-button text size="small" @click="openEdit(board)">{{ t('common.edit') }}</el-button>
            <el-button text size="small" type="danger" @click="remove(board)">{{ t('common.delete') }}</el-button>
          </template>
        </div>
      </article>
    </div>

    <el-dialog v-model="dialogOpen" :title="editingBoard ? t('manage.boards.edit') : t('manage.boards.create')" width="440px" append-to-body>
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" show-icon class="manage-section__alert" />
      <el-form label-position="top" @submit.prevent>
        <el-form-item :label="t('channel.boardName')" required>
          <el-input v-model="form.name" maxlength="32" show-word-limit />
        </el-form-item>
        <el-form-item :label="t('channel.boardDesc')">
          <el-input v-model="form.description" maxlength="200" />
        </el-form-item>
        <el-form-item :label="t('channel.boardSort')">
          <el-input-number v-model="form.sort" :min="0" :max="999" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped lang="scss">
.manage-section__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--isles-space-3);
  margin-bottom: var(--isles-space-4);

  h2 {
    font-size: var(--isles-font-lg);
    font-weight: 600;
  }

  p {
    margin-top: 2px;
    font-size: var(--isles-font-xs);
  }
}

.manage-section__actions {
  display: flex;
  gap: var(--isles-space-2);
}

.manage-section__alert {
  margin-bottom: var(--isles-space-3);
}

.manage-section__tag {
  margin-left: var(--isles-space-2);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.board-list {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
}

.board-card {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-3);
  align-items: center;
  justify-content: space-between;
  padding: var(--isles-space-4);
}

.board-card__main {
  flex: 1;
  min-width: 200px;

  p {
    margin-top: 2px;
    font-size: var(--isles-font-xs);
  }
}

.board-card__actions {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
  font-size: var(--isles-font-xs);
}
</style>

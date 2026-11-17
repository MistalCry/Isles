<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingState from '@/components/LoadingState.vue'
import UserAvatar from '@/components/UserAvatar.vue'
import {
  banAdminChannel,
  listAdminChannels,
  unbanAdminChannel,
  type AdminChannel,
  type AdminChannelPage,
} from './adminApi'
import { resolveErrorMessage } from '@/api/client'
import { useRequestSeq } from '@/composables/useRequestSeq'
import { hasPermission } from './adminPermissions'
import { useAdminAccess } from '@/composables/useAdminAccess'
import { formatCount, formatDateTime } from '@/utils/format'

const { t } = useI18n()
const { identity } = useAdminAccess()
/** 封禁/解封频道走 CONTENT_TAKEDOWN，只有 CONTENT_READ 的角色点了必 403 */
const canTakedown = computed(() => hasPermission(identity.value, 'CONTENT_TAKEDOWN'))

const keyword = ref('')
const status = ref('')
const page = ref(1)
const size = ref(20)
const data = ref<AdminChannelPage | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
/** 列表请求序号（分页/筛选快速切换时防串页） */
const loadSeq = useRequestSeq()

async function load(): Promise<void> {
  // 快速翻页/切筛选时只认最后一次请求
  const seq = loadSeq.next()
  loading.value = true
  error.value = null
  try {
    const result = await listAdminChannels({
      keyword: keyword.value.trim() || undefined,
      status: status.value || undefined,
      page: page.value,
      size: size.value,
    })
    if (!loadSeq.isLatest(seq)) return
    data.value = result
  } catch (cause) {
    if (!loadSeq.isLatest(seq)) return
    error.value = resolveErrorMessage(cause, t('admin.channels.loadFailed'))
  } finally {
    if (loadSeq.isLatest(seq)) loading.value = false
  }
}

function search(): void {
  page.value = 1
  void load()
}

async function toggleStatus(channel: AdminChannel): Promise<void> {
  const banned = channel.status === 'BANNED'
  const confirmed = await ElMessageBox.confirm(
    banned
      ? t('admin.channels.unbanConfirm', { name: channel.name })
      : t('admin.channels.banConfirm', { name: channel.name }),
    banned ? t('admin.channels.unbanTitle') : t('admin.channels.banTitle'),
    { type: 'warning' },
  ).catch(() => false)
  if (!confirmed) return
  try {
    if (banned) {
      await unbanAdminChannel(channel.id)
      ElMessage.success(t('admin.channels.unbanned'))
    } else {
      await banAdminChannel(channel.id)
      ElMessage.success(t('admin.channels.banned'))
    }
    await load()
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.channels.actionFailed')))
  }
}

onMounted(load)
</script>

<template>
  <section class="admin-channels">
    <header class="admin-section-head">
      <div>
        <h2 class="admin-section-title">{{ t('admin.channels.title') }}</h2>
        <p class="admin-section-subtitle">{{ t('admin.channels.subtitle') }}</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('common.refresh') }}</el-button>
    </header>

    <div class="admin-filters">
      <el-input
        v-model="keyword"
        :placeholder="t('admin.models.search')"
        clearable
        class="admin-filters__keyword"
        @keydown.enter="search"
      />
      <el-select v-model="status" :placeholder="t('common.allStatus')" clearable class="admin-filters__select" @change="search">
        <el-option :label="t('admin.channels.active')" value="ACTIVE" />
        <el-option :label="t('admin.channels.banned')" value="BANNED" />
        <el-option :label="t('admin.channels.dissolved')" value="DISSOLVED" />
      </el-select>
      <el-button type="primary" :icon="Search" @click="search">{{ t('common.query') }}</el-button>
    </div>

    <LoadingState v-if="loading && !data" :rows="3" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <EmptyState
      v-else-if="!data || data.items.length === 0"
      :title="t('admin.channels.noMatch')"
      :description="t('common.retryHint')"
    />
    <template v-else>
      <div class="admin-table-scroll">
      <el-table :data="data.items" class="admin-table" row-key="id">
        <el-table-column :label="t('common.channel')" min-width="220">
          <template #default="{ row }">
            <div class="channel-cell">
              <UserAvatar :src="row.avatarUrl" :name="row.name" :size="32" square />
              <div class="channel-cell__meta">
                <p class="channel-cell__name">{{ row.name }}</p>
                <p class="channel-cell__sub">{{ t('admin.channels.channelNo', { no: row.channelNo ?? '—' }) }}</p>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('admin.channels.membersPosts')" width="140">
          <template #default="{ row }">
            {{ formatCount(row.memberCount) }} / {{ formatCount(row.postCount) }}
          </template>
        </el-table-column>
        <el-table-column :label="t('common.status')" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'danger'" effect="plain">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('admin.channels.createdAt')" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="120" align="right" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              link
              :type="row.status === 'BANNED' ? 'primary' : 'danger'"
              :disabled="!canTakedown"
              @click="toggleStatus(row as AdminChannel)"
            >
              {{ row.status === 'BANNED' ? t('admin.channels.unban') : t('admin.channels.ban') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      </div>
      <footer class="admin-pager">
        <span>{{ t('common.totalCount', { n: data.total }) }}</span>
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          layout="prev, pager, next"
          :total="data.total"
          @current-change="load"
        />
      </footer>
    </template>
  </section>
</template>

<style scoped lang="scss">
.admin-section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: var(--isles-space-4);
}

.admin-section-title {
  font-size: var(--isles-font-lg);
  font-weight: 600;
}

.admin-section-subtitle {
  margin-top: var(--isles-space-1);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-sm);
}

.admin-filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  margin-bottom: var(--isles-space-3);
}

.admin-filters__keyword {
  width: 240px;
}

.admin-filters__select {
  width: 140px;
}

.admin-table {
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
}

.channel-cell {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
}

.channel-cell__meta {
  min-width: 0;
  line-height: 1.3;
}

.channel-cell__name {
  overflow: hidden;
  font-size: var(--isles-font-sm);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.channel-cell__sub {
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.admin-pager {
  display: flex;
  gap: var(--isles-space-3);
  align-items: center;
  justify-content: space-between;
  margin-top: var(--isles-space-3);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}
</style>

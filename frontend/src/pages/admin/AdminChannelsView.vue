<script setup lang="ts">
import { onMounted, ref } from 'vue'
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
import { formatCount, formatDateTime } from '@/utils/format'

const keyword = ref('')
const status = ref('')
const page = ref(1)
const size = ref(20)
const data = ref<AdminChannelPage | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    data.value = await listAdminChannels({
      keyword: keyword.value.trim() || undefined,
      status: status.value || undefined,
      page: page.value,
      size: size.value,
    })
  } catch (cause) {
    error.value = resolveErrorMessage(cause, '频道列表加载失败')
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.value = 1
  void load()
}

async function toggleStatus(channel: AdminChannel): Promise<void> {
  const banned = channel.status === 'BANNED'
  const confirmed = await ElMessageBox.confirm(
    banned ? `确定解禁频道「${channel.name}」？` : `确定封禁频道「${channel.name}」？封禁后频道内容将不可见。`,
    banned ? '解禁频道' : '封禁频道',
    { type: 'warning' },
  ).catch(() => false)
  if (!confirmed) return
  try {
    if (banned) {
      await unbanAdminChannel(channel.id)
      ElMessage.success('已解禁')
    } else {
      await banAdminChannel(channel.id)
      ElMessage.success('已封禁')
    }
    await load()
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, '操作失败'))
  }
}

onMounted(load)
</script>

<template>
  <section class="admin-channels">
    <header class="admin-section-head">
      <div>
        <h2 class="admin-section-title">频道管理</h2>
        <p class="admin-section-subtitle">封禁/解禁频道；封禁后频道对外不可见（status=BANNED）</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </header>

    <div class="admin-filters">
      <el-input
        v-model="keyword"
        placeholder="搜索频道名称 / 频道号"
        clearable
        class="admin-filters__keyword"
        @keydown.enter="search"
      />
      <el-select v-model="status" placeholder="全部状态" clearable class="admin-filters__select" @change="search">
        <el-option label="运营中" value="ACTIVE" />
        <el-option label="已封禁" value="BANNED" />
        <el-option label="已解散" value="DISSOLVED" />
      </el-select>
      <el-button type="primary" :icon="Search" @click="search">查询</el-button>
    </div>

    <LoadingState v-if="loading && !data" :rows="3" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <EmptyState
      v-else-if="!data || data.items.length === 0"
      title="没有匹配的频道"
      description="调整筛选条件后重试。"
    />
    <template v-else>
      <el-table :data="data.items" class="admin-table" row-key="id">
        <el-table-column label="频道" min-width="220">
          <template #default="{ row }">
            <div class="channel-cell">
              <UserAvatar :src="row.avatarUrl" :name="row.name" :size="32" square />
              <div class="channel-cell__meta">
                <p class="channel-cell__name">{{ row.name }}</p>
                <p class="channel-cell__sub">频道号 {{ row.channelNo ?? '—' }}</p>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="成员 / 帖子" width="140">
          <template #default="{ row }">
            {{ formatCount(row.memberCount) }} / {{ formatCount(row.postCount) }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'danger'" effect="plain">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="right">
          <template #default="{ row }">
            <el-button
              size="small"
              link
              :type="row.status === 'BANNED' ? 'primary' : 'danger'"
              @click="toggleStatus(row as AdminChannel)"
            >
              {{ row.status === 'BANNED' ? '解禁' : '封禁' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <footer class="admin-pager">
        <span>共 {{ data.total }} 条</span>
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

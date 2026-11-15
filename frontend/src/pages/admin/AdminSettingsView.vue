<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Timer } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingState from '@/components/LoadingState.vue'
import {
  listAdminSettingHistory,
  listAdminSettings,
  resetAdminSetting,
  updateAdminSetting,
  updateAdminSettings,
  type AdminSettingGroup,
  type AdminSettingHistoryItem,
  type AdminSettingItem,
} from './adminApi'
import { resolveErrorMessage } from '@/api/client'
import { formatDateTime } from '@/utils/format'
import { hasPermission } from './adminPermissions'
import { useAdminAccess } from '@/composables/useAdminAccess'
import { useSiteStore } from '@/stores/site'

const { t } = useI18n()
const { identity } = useAdminAccess()
const site = useSiteStore()
/** 只读角色（如 VIEWER / MODERATOR）不能改配置：后端会 403，这里提前把按钮禁掉 */
const canWrite = computed(() => hasPermission(identity.value, 'CONFIG_WRITE'))

const groups = ref<AdminSettingGroup[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
/** 草稿值：key → 待提交的字符串值（与后端一致，统一用字符串传输） */
const drafts = ref<Record<string, string>>({})
const savingKeys = ref<string[]>([])
const savingAll = ref(false)

const historyOpen = ref(false)
const historyKey = ref<string | null>(null)
const historyLoading = ref(false)
const historyItems = ref<AdminSettingHistoryItem[]>([])
const historyTotal = ref(0)
const historyPage = ref(1)
const historySize = 20

const items = computed(() => groups.value.flatMap((group) => group.items))

function draftOf(item: AdminSettingItem): string {
  return drafts.value[item.key] ?? item.value ?? ''
}

function isDirty(item: AdminSettingItem): boolean {
  return draftOf(item) !== (item.value ?? '')
}

const dirtyItems = computed(() => items.value.filter((item) => isDirty(item)))

function setDraft(key: string, value: string): void {
  drafts.value = { ...drafts.value, [key]: value }
}

/** 布尔在界面上是 switch，接口里是 "true"/"false" */
function boolValue(item: AdminSettingItem): boolean {
  return draftOf(item) === 'true'
}

function onBoolChange(item: AdminSettingItem, value: boolean | string | number): void {
  setDraft(item.key, value ? 'true' : 'false')
}

function onIntChange(item: AdminSettingItem, value: number | undefined): void {
  setDraft(item.key, value === undefined || value === null ? '' : String(value))
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    groups.value = await listAdminSettings()
    // 重新拉取后清空草稿，避免旧草稿覆盖服务端新值
    drafts.value = {}
  } catch (cause) {
    error.value = resolveErrorMessage(cause, t('admin.settings.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function saveOne(item: AdminSettingItem): Promise<void> {
  if (!canWrite.value || !isDirty(item)) return
  savingKeys.value = [...savingKeys.value, item.key]
  try {
    const updated = await updateAdminSetting(item.key, draftOf(item))
    applyUpdated(updated)
    // 站点名/简介等公开配置立即刷新，避免「后台改完前台还是旧值」
    void site.load(true)
    ElMessage.success(t('admin.settings.saved'))
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.settings.saveFailed')))
  } finally {
    savingKeys.value = savingKeys.value.filter((key) => key !== item.key)
  }
}

async function saveAll(): Promise<void> {
  if (!canWrite.value || dirtyItems.value.length === 0) return
  savingAll.value = true
  try {
    const updated = await updateAdminSettings(
      dirtyItems.value.map((item) => ({ key: item.key, value: draftOf(item) })),
    )
    updated.forEach(applyUpdated)
    void site.load(true)
    ElMessage.success(t('admin.settings.savedCount', { n: updated.length }))
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.settings.saveFailed')))
  } finally {
    savingAll.value = false
  }
}

/** 用服务端返回值替换本地项，并清掉该键的草稿 */
function applyUpdated(updated: AdminSettingItem): void {
  groups.value = groups.value.map((group) => ({
    ...group,
    items: group.items.map((item) => (item.key === updated.key ? { ...item, ...updated } : item)),
  }))
  const next = { ...drafts.value }
  delete next[updated.key]
  drafts.value = next
}

async function resetOne(item: AdminSettingItem): Promise<void> {
  if (!canWrite.value) return
  try {
    await ElMessageBox.confirm(
      t('admin.settings.resetConfirm', { label: item.label, value: item.defaultValue }),
      t('admin.settings.reset'),
      { type: 'warning', confirmButtonText: t('admin.settings.reset'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    return
  }
  savingKeys.value = [...savingKeys.value, item.key]
  try {
    const updated = await resetAdminSetting(item.key)
    applyUpdated(updated)
    void site.load(true)
    ElMessage.success(t('admin.settings.resetDone'))
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.settings.resetFailed')))
  } finally {
    savingKeys.value = savingKeys.value.filter((key) => key !== item.key)
  }
}

async function openHistory(key: string | null): Promise<void> {
  historyKey.value = key
  historyPage.value = 1
  historyOpen.value = true
  await loadHistory()
}

async function loadHistory(): Promise<void> {
  historyLoading.value = true
  try {
    const data = await listAdminSettingHistory({
      key: historyKey.value ?? undefined,
      page: historyPage.value,
      size: historySize,
    })
    historyItems.value = data.items
    historyTotal.value = data.total
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.settings.historyLoadFailed')))
    historyItems.value = []
    historyTotal.value = 0
  } finally {
    historyLoading.value = false
  }
}

function labelOf(key: string): string {
  return items.value.find((item) => item.key === key)?.label ?? key
}

onMounted(load)
</script>

<template>
  <section class="admin-settings">
    <header class="admin-section-head">
      <div>
        <h2 class="admin-section-title">{{ t('admin.layout.settings') }}</h2>
        <p class="admin-section-subtitle">{{ t('admin.settings.subtitle') }}</p>
      </div>
      <div class="admin-settings__head-actions">
        <el-button :icon="Timer" @click="openHistory(null)">{{ t('admin.settings.history') }}</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('common.refresh') }}</el-button>
        <el-button
          type="primary"
          :disabled="!canWrite || dirtyItems.length === 0"
          :loading="savingAll"
          @click="saveAll"
        >
          {{ t('admin.settings.saveAll') }}<template v-if="dirtyItems.length"> ({{ dirtyItems.length }})</template>
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="!canWrite"
      class="admin-settings__readonly"
      type="info"
      show-icon
      :closable="false"
      :title="t('admin.settings.readonly')"
    />

    <LoadingState v-if="loading && groups.length === 0" :rows="4" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <EmptyState v-else-if="groups.length === 0" :title="t('admin.settings.empty')" />

    <div v-else class="settings-groups">
      <article v-for="group in groups" :key="group.group" class="settings-group isles-card">
        <header class="settings-group__head">
          <h3>{{ group.label }}</h3>
          <span class="muted">{{ t('admin.settings.itemCount', { n: group.items.length }) }}</span>
        </header>

        <div v-for="item in group.items" :key="item.key" class="setting-row">
          <div class="setting-row__meta">
            <div class="setting-row__title">
              <strong>{{ item.label }}</strong>
              <el-tag v-if="item.overridden" size="small" type="warning" effect="plain">
                {{ t('admin.settings.overridden') }}
              </el-tag>
              <el-tag v-else size="small" type="info" effect="plain">{{ t('admin.settings.default') }}</el-tag>
              <el-tag v-if="item.publicVisible" size="small" effect="plain">{{ t('admin.settings.public') }}</el-tag>
            </div>
            <p v-if="item.description" class="setting-row__desc">{{ item.description }}</p>
            <p class="setting-row__key">
              <code>{{ item.key }}</code>
              <span class="muted">{{ t('admin.settings.defaultValue', { value: item.defaultValue || '—' }) }}</span>
            </p>
          </div>

          <div class="setting-row__control">
            <!-- 元数据驱动：类型来自后端 type，前端不硬编码任何配置项 -->
            <el-switch
              v-if="item.type === 'BOOL'"
              :model-value="boolValue(item)"
              :disabled="!canWrite"
              @update:model-value="onBoolChange(item, $event)"
            />
            <el-select
              v-else-if="item.type === 'ENUM'"
              :model-value="draftOf(item)"
              :disabled="!canWrite"
              class="setting-row__select"
              @update:model-value="setDraft(item.key, String($event))"
            >
              <el-option
                v-for="option in item.allowedValues ?? []"
                :key="option"
                :label="option"
                :value="option"
              />
            </el-select>
            <el-input-number
              v-else-if="item.type === 'INT'"
              :model-value="draftOf(item) === '' ? undefined : Number(draftOf(item))"
              :min="item.minValue ?? undefined"
              :max="item.maxValue ?? undefined"
              :disabled="!canWrite"
              controls-position="right"
              class="setting-row__number"
              @update:model-value="onIntChange(item, $event as number | undefined)"
            />
            <el-input
              v-else
              :model-value="draftOf(item)"
              :disabled="!canWrite"
              class="setting-row__input"
              @update:model-value="setDraft(item.key, String($event))"
            />
          </div>

          <div class="setting-row__actions">
            <el-button
              size="small"
              type="primary"
              link
              :disabled="!canWrite || !isDirty(item)"
              :loading="savingKeys.includes(item.key)"
              @click="saveOne(item)"
            >
              {{ t('common.save') }}
            </el-button>
            <el-button size="small" link :disabled="!canWrite || !item.overridden" @click="resetOne(item)">
              {{ t('admin.settings.reset') }}
            </el-button>
            <el-button size="small" link @click="openHistory(item.key)">{{ t('admin.settings.historyShort') }}</el-button>
          </div>
        </div>
      </article>
    </div>

    <el-drawer v-model="historyOpen" :title="t('admin.settings.historyTitle')" size="520px" append-to-body>
      <div class="history-head">
        <el-tag v-if="historyKey" closable effect="plain" @close="openHistory(null)">
          {{ labelOf(historyKey) }}
        </el-tag>
        <span v-else class="muted">{{ t('admin.settings.historyAll') }}</span>
        <el-button size="small" link :loading="historyLoading" @click="loadHistory">{{ t('common.refresh') }}</el-button>
      </div>

      <el-skeleton v-if="historyLoading && historyItems.length === 0" :rows="5" animated />
      <el-empty v-else-if="historyItems.length === 0" :description="t('admin.settings.historyEmpty')" />
      <ul v-else class="history-list">
        <li v-for="entry in historyItems" :key="entry.id" class="history-item">
          <p class="history-item__head">
            <strong>{{ labelOf(entry.key) }}</strong>
            <span class="muted">{{ formatDateTime(entry.createdAt) }}</span>
          </p>
          <p class="history-item__change">
            <span class="history-item__old">{{ entry.oldValue || '—' }}</span>
            <span aria-hidden="true">→</span>
            <span class="history-item__new">{{ entry.newValue || '—' }}</span>
          </p>
          <p class="history-item__meta muted">
            {{ t('admin.settings.historyActor', { name: entry.actorName || entry.actorId || t('common.unknown') }) }}
            <template v-if="entry.ip"> · {{ entry.ip }}</template>
          </p>
        </li>
      </ul>

      <el-pagination
        v-if="historyTotal > historySize"
        v-model:current-page="historyPage"
        class="history-pager"
        :page-size="historySize"
        layout="prev, pager, next"
        :total="historyTotal"
        @current-change="loadHistory"
      />
    </el-drawer>
  </section>
</template>

<style scoped lang="scss">
.admin-section-head {
  display: flex;
  gap: var(--isles-space-3);
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

.admin-settings__head-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
}

.admin-settings__readonly {
  margin-bottom: var(--isles-space-3);
}

.settings-groups {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-4);
}

.settings-group {
  padding: var(--isles-space-4);
}

.settings-group__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding-bottom: var(--isles-space-2);
  border-bottom: 1px solid var(--isles-border);

  h3 {
    font-size: var(--isles-font-md);
    font-weight: 600;
  }
}

.setting-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 260px 150px;
  gap: var(--isles-space-3);
  align-items: center;
  padding: var(--isles-space-3) 0;
  border-bottom: 1px dashed var(--isles-border);

  &:last-child {
    border-bottom: none;
    padding-bottom: 0;
  }
}

.setting-row__title {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  align-items: center;
}

.setting-row__desc {
  margin-top: 2px;
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
}

.setting-row__key {
  display: flex;
  gap: var(--isles-space-2);
  margin-top: 2px;
  font-size: var(--isles-font-xs);

  code {
    color: var(--isles-text-muted);
  }
}

.setting-row__control {
  display: flex;
  justify-content: flex-end;
}

.setting-row__select,
.setting-row__input {
  width: 100%;
}

.setting-row__number {
  width: 100%;
}

.setting-row__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-1);
  justify-content: flex-end;
}

.history-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--isles-space-3);
}

.history-list {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-3);
}

.history-item {
  padding-bottom: var(--isles-space-3);
  border-bottom: 1px dashed var(--isles-border);
}

.history-item__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  font-size: var(--isles-font-sm);
}

.history-item__change {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  margin-top: 2px;
  font-size: var(--isles-font-sm);
}

.history-item__old {
  color: var(--isles-text-muted);
  text-decoration: line-through;
}

.history-item__new {
  color: var(--isles-primary);
  font-weight: 600;
}

.history-item__meta {
  margin-top: 2px;
  font-size: var(--isles-font-xs);
}

.history-pager {
  margin-top: var(--isles-space-4);
  justify-content: center;
}

@media (max-width: 900px) {
  .setting-row {
    grid-template-columns: 1fr;
    align-items: stretch;
  }

  .setting-row__control,
  .setting-row__actions {
    justify-content: flex-start;
  }
}
</style>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search, Upload } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingState from '@/components/LoadingState.vue'
import {
  createAdminSensitiveWord,
  deleteAdminSensitiveWord,
  importAdminSensitiveWords,
  listAdminSensitiveWords,
  listAdminWordHits,
  scanAdminSensitiveWords,
  updateAdminSensitiveWord,
  type AdminSensitiveWord,
  type AdminSensitiveWordPage,
  type AdminWordHitPage,
  type WordAction,
  type WordCategory,
  type WordScanHit,
} from './adminApi'
import { resolveErrorMessage } from '@/api/client'
import { formatDateTime } from '@/utils/format'

const { t } = useI18n()

const CATEGORIES: WordCategory[] = ['POLITICS', 'PORN', 'AD', 'ABUSE', 'CUSTOM']
const ACTIONS: WordAction[] = ['BLOCK', 'REVIEW', 'MASK']

const CATEGORY_LABEL_KEYS: Record<WordCategory, string> = {
  POLITICS: 'admin.words.categoryPolitics',
  PORN: 'admin.words.categoryPorn',
  AD: 'admin.words.categoryAd',
  ABUSE: 'admin.words.categoryAbuse',
  CUSTOM: 'admin.words.categoryCustom',
}

const ACTION_LABEL_KEYS: Record<WordAction, string> = {
  BLOCK: 'admin.words.actionBlock',
  REVIEW: 'admin.words.actionReview',
  MASK: 'admin.words.actionMask',
}

type TabKey = 'words' | 'hits'

const tab = ref<TabKey>('words')
const keyword = ref('')
const page = ref(1)
const size = ref(20)
const data = ref<AdminSensitiveWordPage | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

const hitKeyword = ref('')
const hitScene = ref('')
const hitPage = ref(1)
const hits = ref<AdminWordHitPage | null>(null)
const hitsLoading = ref(false)
const hitsError = ref<string | null>(null)

const dialogVisible = ref(false)
const editing = ref<AdminSensitiveWord | null>(null)
const form = ref<{ word: string; category: WordCategory; action: WordAction; enabled: boolean }>({
  word: '',
  category: 'CUSTOM',
  action: 'BLOCK',
  enabled: true,
})
const submitting = ref(false)

const importVisible = ref(false)
const importForm = ref<{ words: string; category: WordCategory; action: WordAction }>({
  words: '',
  category: 'CUSTOM',
  action: 'BLOCK',
})
const importing = ref(false)

const scanVisible = ref(false)
const scanText = ref('')
const scanHits = ref<WordScanHit[] | null>(null)
const scanning = ref(false)

const dialogTitle = computed(() =>
  editing.value ? t('admin.words.edit') : t('admin.words.add'),
)

function categoryLabel(value?: string | null): string {
  const key = CATEGORY_LABEL_KEYS[(value ?? 'CUSTOM') as WordCategory]
  return key ? t(key) : (value ?? '—')
}

function actionLabel(value?: string | null): string {
  const key = ACTION_LABEL_KEYS[(value ?? 'BLOCK') as WordAction]
  return key ? t(key) : (value ?? '—')
}

function actionTagType(value?: string | null): 'danger' | 'warning' | 'info' {
  if (value === 'BLOCK') return 'danger'
  if (value === 'REVIEW') return 'warning'
  return 'info'
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    data.value = await listAdminSensitiveWords({
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size: size.value,
    })
  } catch (cause) {
    error.value = resolveErrorMessage(cause, t('admin.words.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function loadHits(): Promise<void> {
  hitsLoading.value = true
  hitsError.value = null
  try {
    hits.value = await listAdminWordHits({
      scene: hitScene.value.trim() || undefined,
      word: hitKeyword.value.trim() || undefined,
      page: hitPage.value,
      size: size.value,
    })
  } catch (cause) {
    hitsError.value = resolveErrorMessage(cause, t('admin.words.hitsLoadFailed'))
  } finally {
    hitsLoading.value = false
  }
}

function search(): void {
  page.value = 1
  void load()
}

function searchHits(): void {
  hitPage.value = 1
  void loadHits()
}

function switchTab(next: TabKey): void {
  if (tab.value === next) return
  tab.value = next
  if (next === 'hits' && !hits.value) void loadHits()
}

function openCreate(): void {
  editing.value = null
  form.value = { word: '', category: 'CUSTOM', action: 'BLOCK', enabled: true }
  dialogVisible.value = true
}

function openEdit(word: AdminSensitiveWord): void {
  editing.value = word
  form.value = {
    word: word.word,
    category: (word.category as WordCategory) ?? 'CUSTOM',
    action: (word.action as WordAction) ?? 'BLOCK',
    enabled: word.enabled,
  }
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  if (!form.value.word.trim()) {
    ElMessage.warning(t('admin.words.wordRequired'))
    return
  }
  submitting.value = true
  try {
    if (editing.value) {
      await updateAdminSensitiveWord(editing.value.id, { ...form.value, word: form.value.word.trim() })
    } else {
      await createAdminSensitiveWord({ ...form.value, word: form.value.word.trim() })
    }
    ElMessage.success(t('admin.words.saved'))
    dialogVisible.value = false
    await load()
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.words.failed')))
  } finally {
    submitting.value = false
  }
}

async function remove(word: AdminSensitiveWord): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('admin.words.deleteConfirm', { word: word.word }),
      t('common.delete'),
      { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    return
  }
  try {
    await deleteAdminSensitiveWord(word.id)
    ElMessage.success(t('admin.words.deleted'))
    await load()
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.words.failed')))
  }
}

async function submitImport(): Promise<void> {
  if (!importForm.value.words.trim()) {
    ElMessage.warning(t('admin.words.importRequired'))
    return
  }
  importing.value = true
  try {
    const result = await importAdminSensitiveWords(importForm.value)
    ElMessage.success(t('admin.words.importResult', { created: result.created, skipped: result.skipped }))
    importVisible.value = false
    importForm.value = { words: '', category: 'CUSTOM', action: 'BLOCK' }
    await load()
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.words.failed')))
  } finally {
    importing.value = false
  }
}

async function submitScan(): Promise<void> {
  if (!scanText.value.trim()) {
    ElMessage.warning(t('admin.words.scanRequired'))
    return
  }
  scanning.value = true
  try {
    scanHits.value = await scanAdminSensitiveWords(scanText.value)
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.words.failed')))
    scanHits.value = null
  } finally {
    scanning.value = false
  }
}

function openScan(): void {
  scanText.value = ''
  scanHits.value = null
  scanVisible.value = true
}

onMounted(load)
</script>

<template>
  <section class="admin-words">
    <header class="admin-section-head">
      <div>
        <h2 class="admin-section-title">{{ t('admin.layout.words') }}</h2>
        <p class="admin-section-subtitle">{{ t('admin.words.subtitle') }}</p>
      </div>
      <div class="admin-words__actions">
        <el-button :icon="Refresh" :loading="loading" @click="tab === 'words' ? load() : loadHits()">
          {{ t('common.refresh') }}
        </el-button>
        <el-button :icon="Search" @click="openScan">{{ t('admin.words.scan') }}</el-button>
        <el-button :icon="Upload" @click="importVisible = true">{{ t('admin.words.import') }}</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">{{ t('admin.words.add') }}</el-button>
      </div>
    </header>

    <div class="content-tabs">
      <button type="button" class="content-tab" :class="{ 'is-active': tab === 'words' }" @click="switchTab('words')">
        {{ t('admin.words.tabWords') }}
      </button>
      <button type="button" class="content-tab" :class="{ 'is-active': tab === 'hits' }" @click="switchTab('hits')">
        {{ t('admin.words.tabHits') }}
      </button>
    </div>

    <template v-if="tab === 'words'">
      <div class="admin-filters">
        <el-input
          v-model="keyword"
          :placeholder="t('admin.words.keywordPlaceholder')"
          :prefix-icon="Search"
          clearable
          class="admin-filters__search"
          @keyup.enter="search"
          @clear="search"
        />
        <el-button type="primary" :icon="Search" @click="search">{{ t('common.query') }}</el-button>
      </div>

      <LoadingState v-if="loading && !data" :rows="4" />
      <ErrorState v-else-if="error" :message="error" @retry="load" />
      <EmptyState
        v-else-if="data && data.items.length === 0"
        :title="t('admin.words.empty')"
        :description="t('admin.words.emptyHint')"
      />
      <template v-else-if="data">
        <el-table :data="data.items" class="admin-table" row-key="id">
          <el-table-column prop="word" :label="t('admin.words.word')" min-width="180" />
          <el-table-column :label="t('admin.words.category')" width="130">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ categoryLabel(row.category) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('admin.words.action')" width="120">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="actionTagType(row.action)">{{ actionLabel(row.action) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('admin.words.enabled')" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.enabled ? 'success' : 'info'" effect="plain">
                {{ row.enabled ? t('admin.words.enabledOn') : t('admin.words.enabledOff') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.time')" width="170">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column :label="t('common.actions')" width="130" align="right">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click="openEdit(row as AdminSensitiveWord)">
                {{ t('common.edit') }}
              </el-button>
              <el-button size="small" link type="danger" @click="remove(row as AdminSensitiveWord)">
                {{ t('common.delete') }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
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
    </template>

    <template v-else>
      <div class="admin-filters">
        <el-input
          v-model="hitKeyword"
          :placeholder="t('admin.words.hitWordPlaceholder')"
          clearable
          class="admin-filters__search"
          @keyup.enter="searchHits"
          @clear="searchHits"
        />
        <el-input
          v-model="hitScene"
          :placeholder="t('admin.words.hitScenePlaceholder')"
          clearable
          class="admin-filters__search"
          @keyup.enter="searchHits"
          @clear="searchHits"
        />
        <el-button type="primary" :icon="Search" @click="searchHits">{{ t('common.query') }}</el-button>
      </div>

      <LoadingState v-if="hitsLoading && !hits" :rows="4" />
      <ErrorState v-else-if="hitsError" :message="hitsError" @retry="loadHits" />
      <EmptyState
        v-else-if="hits && hits.items.length === 0"
        :title="t('admin.words.hitsEmpty')"
        :description="t('admin.words.hitsEmptyHint')"
      />
      <template v-else-if="hits">
        <el-table :data="hits.items" class="admin-table" row-key="id">
          <el-table-column prop="word" :label="t('admin.words.word')" min-width="140" />
          <el-table-column :label="t('admin.words.hitScene')" width="120">
            <template #default="{ row }">{{ row.scene || '—' }}</template>
          </el-table-column>
          <el-table-column :label="t('admin.words.action')" width="120">
            <template #default="{ row }">{{ actionLabel(row.action) }}</template>
          </el-table-column>
          <el-table-column :label="t('admin.words.targetId')" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.targetId || '—' }}</template>
          </el-table-column>
          <el-table-column :label="t('admin.words.userId')" width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.userId || '—' }}</template>
          </el-table-column>
          <el-table-column :label="t('common.time')" width="170">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
        <footer class="admin-pager">
          <span>{{ t('common.totalCount', { n: hits.total }) }}</span>
          <el-pagination
            v-model:current-page="hitPage"
            :page-size="size"
            layout="prev, pager, next"
            :total="hits.total"
            @current-change="loadHits"
          />
        </footer>
      </template>
    </template>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="460px" append-to-body>
      <el-form label-position="top">
        <el-form-item :label="t('admin.words.word')" required>
          <el-input v-model="form.word" maxlength="100" :placeholder="t('admin.words.wordPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('admin.words.category')">
          <el-select v-model="form.category" class="dialog-select">
            <el-option v-for="item in CATEGORIES" :key="item" :label="categoryLabel(item)" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('admin.words.action')">
          <el-select v-model="form.action" class="dialog-select">
            <el-option v-for="item in ACTIONS" :key="item" :label="actionLabel(item)" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('admin.words.enabled')">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importVisible" :title="t('admin.words.importTitle')" width="520px" append-to-body>
      <el-form label-position="top">
        <el-form-item :label="t('admin.words.importWords')">
          <el-input
            v-model="importForm.words"
            type="textarea"
            :rows="8"
            :placeholder="t('admin.words.importPlaceholder')"
          />
          <p class="dialog-hint">{{ t('admin.words.importHint') }}</p>
        </el-form-item>
        <el-form-item :label="t('admin.words.category')">
          <el-select v-model="importForm.category" class="dialog-select">
            <el-option v-for="item in CATEGORIES" :key="item" :label="categoryLabel(item)" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('admin.words.action')">
          <el-select v-model="importForm.action" class="dialog-select">
            <el-option v-for="item in ACTIONS" :key="item" :label="actionLabel(item)" :value="item" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="importing" @click="submitImport">{{ t('admin.words.import') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="scanVisible" :title="t('admin.words.scanTitle')" width="520px" append-to-body>
      <el-form label-position="top">
        <el-form-item :label="t('admin.words.scanText')">
          <el-input
            v-model="scanText"
            type="textarea"
            :rows="6"
            :placeholder="t('admin.words.scanPlaceholder')"
          />
        </el-form-item>
      </el-form>

      <template v-if="scanHits">
        <el-alert
          v-if="scanHits.length === 0"
          type="success"
          show-icon
          :closable="false"
          :title="t('admin.words.scanNoHit')"
        />
        <ul v-else class="scan-hits">
          <li v-for="hit in scanHits" :key="hit.word" class="scan-hit">
            <strong>{{ hit.word }}</strong>
            <el-tag size="small" effect="plain" :type="actionTagType(hit.action)">{{ actionLabel(hit.action) }}</el-tag>
          </li>
        </ul>
      </template>

      <template #footer>
        <el-button @click="scanVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="scanning" @click="submitScan">{{ t('admin.words.scanRun') }}</el-button>
      </template>
    </el-dialog>
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

.admin-words__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  justify-content: flex-end;
}

.content-tabs {
  display: flex;
  gap: var(--isles-space-2);
  margin-bottom: var(--isles-space-3);
}

.content-tab {
  padding: var(--isles-space-1) var(--isles-space-3);
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
  cursor: pointer;
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: 999px;

  &.is-active {
    color: var(--isles-primary);
    background: var(--isles-primary-soft);
    border-color: var(--isles-primary);
  }
}

.admin-filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  margin-bottom: var(--isles-space-3);
}

.admin-filters__search {
  width: 220px;
}

.admin-table {
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
}

.admin-pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: var(--isles-space-3);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-sm);
}

.dialog-select {
  width: 100%;
}

.dialog-hint {
  margin-top: var(--isles-space-1);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.scan-hits {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
}

.scan-hit {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
  padding: var(--isles-space-2);
  background: var(--isles-surface-muted);
  border-radius: var(--isles-radius-sm);
}
</style>

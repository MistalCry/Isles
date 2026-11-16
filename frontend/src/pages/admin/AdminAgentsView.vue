<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Plus, Refresh, Search, VideoPlay } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingState from '@/components/LoadingState.vue'
import UserAvatar from '@/components/UserAvatar.vue'
import { resolveErrorMessage } from '@/api/client'
import { formatDateTime, formatRelativeTime } from '@/utils/format'
import { hasPermission } from './adminPermissions'
import { useAdminAccess } from '@/composables/useAdminAccess'
import {
  createAdminAgent,
  deleteAdminAgent,
  getAgentSwitches,
  listAdminAgentRuns,
  listAdminAgents,
  pauseAdminAgent,
  resumeAdminAgent,
  runAdminAgentNow,
  updateAdminAgent,
  type AgentPage,
  type AgentProfile,
  type AgentRun,
  type AgentStatus,
  type AgentTone,
  type AgentUser,
} from './agentApi'
import { listAdminChannels, listAdminSettings, type AdminChannel } from './adminApi'
import { isFetchCall, isWebSearchCall, parseToolCalls, type ToolCallView } from './toolCalls'

const { t } = useI18n()
const { identity } = useAdminAccess()

const canManage = computed(() => hasPermission(identity.value, 'AGENT_MANAGE'))

const data = ref<AgentPage | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const statusFilter = ref<AgentStatus | ''>('')
const page = ref(1)
const size = ref(20)

/** 平台总开关 agent.enabled（后台配置项，缺省视为关闭） */
const globalEnabled = ref<boolean | null>(null)
const dailyPostLimit = ref(0)
const dailyTokenBudget = ref(0)
const busyId = ref<string | null>(null)

/**
 * 联网搜索配置 agent.search.provider（NONE / SEARXNG）。
 * null = 读不到配置项，页面显示「状态未知」而不是误报「未配置」。
 */
const searchProvider = ref<string | null>(null)
const searchLoaded = ref(false)

const SEARCH_PROVIDER_LABELS: Record<string, string> = { SEARXNG: 'SearXNG', TAVILY: 'Tavily' }

const searchState = computed(() => {
  if (!searchLoaded.value) {
    return { kind: 'loading', tagType: 'info' as const, label: t('admin.agents.searchLoading') }
  }
  const provider = (searchProvider.value ?? '').trim().toUpperCase()
  if (!provider) {
    return { kind: 'unknown', tagType: 'info' as const, label: t('admin.agents.searchUnknown') }
  }
  if (provider === 'NONE' || provider === 'OFF' || provider === 'DISABLED') {
    return { kind: 'off', tagType: 'info' as const, label: t('admin.agents.searchOff') }
  }
  const name = SEARCH_PROVIDER_LABELS[provider] ?? provider
  return { kind: 'on', tagType: 'success' as const, label: t('admin.agents.searchEnabled', { provider: name }) }
})

const createOpen = ref(false)
const creating = ref(false)
const createError = ref<string | null>(null)
const createForm = ref({ username: '', displayName: '', bio: '' })

const editOpen = ref(false)
const editing = ref<AgentUser | null>(null)
const saving = ref(false)
const formError = ref<string | null>(null)
const form = ref({
  displayName: '',
  bio: '',
  status: 'ACTIVE' as AgentStatus,
  tone: 'casual' as AgentTone,
  interests: [] as string[],
  activeHours: [] as Array<[number, number]>,
  postsPerDay: 2,
  commentsPerDay: 8,
  channels: [] as string[],
  toolsEnabled: [] as string[],
})

const channels = ref<AdminChannel[]>([])
const channelsLoading = ref(false)

const runsOpen = ref(false)
const runsAgent = ref<AgentUser | null>(null)
const runs = ref<AgentRun[]>([])
const runsLoading = ref(false)
const runsError = ref<string | null>(null)

/** 工具名 → i18n key（docs/12 §4）；未知工具名原样展示 */
const TOOL_KEYS: Record<string, string> = {
  search_site_posts: 'admin.agents.tool.searchSitePosts',
  list_channel_posts: 'admin.agents.tool.listChannelPosts',
  get_post_detail: 'admin.agents.tool.getPostDetail',
  create_post: 'admin.agents.tool.createPost',
  comment: 'admin.agents.tool.comment',
  like: 'admin.agents.tool.like',
  remember: 'admin.agents.tool.remember',
  recall: 'admin.agents.tool.recall',
  web_search: 'admin.agents.tool.webSearch',
  fetch_page: 'admin.agents.tool.fetchPage',
  generate_image: 'admin.agents.tool.generateImage',
}

const TOOLS = Object.keys(TOOL_KEYS)

function toolLabel(name: string): string {
  const key = TOOL_KEYS[name]
  return key ? t(key) : name
}

/** id 与 userId 并存容易混：列表只露 userId 后 6 位做标识 */
function shortId(userId?: string | null): string {
  return (userId ?? '').slice(-6)
}

function runReason(run: AgentRun): string {
  if (run.error) return run.error
  if (run.reason) return run.reason
  if (run.status === 'SKIPPED') return t('admin.agents.runSkippedNoReason')
  return ''
}

function statusLabel(status: AgentStatus): string {
  if (status === 'PAUSED') return t('admin.agents.statusPaused')
  if (status === 'DISABLED') return t('admin.agents.statusDisabled')
  return t('admin.agents.statusActive')
}

function statusTagType(status: AgentStatus): 'success' | 'warning' | 'info' {
  if (status === 'PAUSED') return 'warning'
  if (status === 'DISABLED') return 'info'
  return 'success'
}

function toneLabel(tone: AgentTone): string {
  if (tone === 'enthusiastic') return t('admin.agents.toneEnthusiastic')
  if (tone === 'dry') return t('admin.agents.toneDry')
  return t('admin.agents.toneCasual')
}

function interestSummary(agent: AgentUser): string {
  if (agent.profile.interests.length === 0) return t('admin.agents.noInterests')
  return agent.profile.interests.slice(0, 4).join(' · ')
}

function failureText(agent: AgentUser): string {
  if (agent.failureRate === null) return '—'
  return `${Math.round(agent.failureRate * 100)}%`
}

function lastRunLabel(status?: string | null): string {
  const value = String(status ?? '').toUpperCase()
  if (value === 'SUCCESS') return t('admin.agents.runStatusSuccess')
  if (value === 'GUARDED') return t('admin.agents.runStatusGuarded')
  if (value === 'FAILED') return t('admin.agents.runStatusFailed')
  if (value === 'SKIPPED') return t('admin.agents.runStatusSkipped')
  return ''
}

function lastRunType(status?: string | null): 'success' | 'warning' | 'danger' | 'info' {
  const value = String(status ?? '').toUpperCase()
  if (value === 'SUCCESS') return 'success'
  if (value === 'GUARDED') return 'warning'
  if (value === 'FAILED') return 'danger'
  return 'info'
}

async function loadGlobalSwitch(): Promise<void> {
  // 优先用专用开关接口（顺带拿到每日上限），失败再退回配置项
  const switches = await getAgentSwitches()
  if (switches) {
    globalEnabled.value = switches.enabled
    dailyPostLimit.value = switches.dailyPostLimit
    dailyTokenBudget.value = switches.dailyTokenBudget
    return
  }
  globalEnabled.value = null
}

/** 联网搜索开关读配置中心（需要 CONFIG_READ；读不到就显示「状态未知」） */
async function loadSearchProvider(): Promise<void> {
  try {
    const groups = await listAdminSettings()
    const item = groups.flatMap((group) => group.items ?? []).find((entry) => entry.key === 'agent.search.provider')
    searchProvider.value = item ? String(item.value ?? '') : null
  } catch {
    searchProvider.value = null
  } finally {
    searchLoaded.value = true
  }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    data.value = await listAdminAgents({ page: page.value, size: size.value, status: statusFilter.value })
  } catch (cause) {
    error.value = resolveErrorMessage(cause, t('admin.agents.loadFailed'))
    data.value = null
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.value = 1
  void load()
}

function changePage(next: number): void {
  page.value = next
  void load()
}

async function loadChannels(): Promise<void> {
  if (channels.value.length > 0) return
  channelsLoading.value = true
  try {
    const result = await listAdminChannels({ page: 1, size: 100 })
    channels.value = result.items
  } catch {
    channels.value = []
  } finally {
    channelsLoading.value = false
  }
}

function openCreate(): void {
  createForm.value = { username: '', displayName: '', bio: '' }
  createError.value = null
  createOpen.value = true
}

async function submitCreate(): Promise<void> {
  const username = createForm.value.username.trim()
  const displayName = createForm.value.displayName.trim()
  if (!/^[a-zA-Z0-9_]{3,32}$/.test(username)) {
    createError.value = t('admin.agents.usernameInvalid')
    return
  }
  if (!displayName) {
    createError.value = t('admin.agents.displayNameRequired')
    return
  }
  creating.value = true
  createError.value = null
  try {
    await createAdminAgent({ username, displayName, bio: createForm.value.bio.trim() || null })
    ElMessage.success(t('admin.agents.created'))
    createOpen.value = false
    await load()
  } catch (cause) {
    createError.value = resolveErrorMessage(cause, t('admin.agents.createFailed'))
  } finally {
    creating.value = false
  }
}

async function openEdit(agent: AgentUser): Promise<void> {
  editing.value = agent
  form.value = {
    displayName: agent.displayName,
    bio: agent.bio ?? '',
    status: agent.status,
    tone: agent.profile.tone,
    interests: [...agent.profile.interests],
    activeHours: agent.profile.activeHours.map((range) => [...range] as [number, number]),
    postsPerDay: agent.profile.postsPerDay,
    commentsPerDay: agent.profile.commentsPerDay,
    channels: [...agent.profile.channels],
    toolsEnabled: [...agent.profile.toolsEnabled],
  }
  formError.value = null
  editOpen.value = true
  void loadChannels()
}

function addHourRange(): void {
  form.value.activeHours.push([20, 23])
}

function removeHourRange(index: number): void {
  form.value.activeHours.splice(index, 1)
}

async function saveProfile(): Promise<void> {
  if (!editing.value) return
  if (!form.value.displayName.trim()) {
    formError.value = t('admin.agents.displayNameRequired')
    return
  }
  const invalid = form.value.activeHours.find(([start, end]) => !(end > start))
  if (invalid) {
    formError.value = t('admin.agents.hourRangeInvalid')
    return
  }
  saving.value = true
  formError.value = null
  const profile: AgentProfile = {
    interests: form.value.interests.map((item) => item.trim()).filter(Boolean),
    tone: form.value.tone,
    activeHours: form.value.activeHours,
    postsPerDay: form.value.postsPerDay,
    commentsPerDay: form.value.commentsPerDay,
    channels: form.value.channels,
    toolsEnabled: form.value.toolsEnabled,
  }
  try {
    const updated = await updateAdminAgent(editing.value.userId, {
      displayName: form.value.displayName.trim(),
      bio: form.value.bio.trim() || null,
      status: form.value.status,
      profile,
    })
    if (updated) applyAgent(updated)
    else await load()
    ElMessage.success(t('admin.agents.saved'))
    editOpen.value = false
  } catch (cause) {
    formError.value = resolveErrorMessage(cause, t('admin.agents.saveFailed'))
  } finally {
    saving.value = false
  }
}

function applyAgent(agent: AgentUser): void {
  if (!data.value) return
  data.value.items = data.value.items.map((item) => (item.id === agent.id ? agent : item))
}

async function togglePause(agent: AgentUser): Promise<void> {
  busyId.value = agent.userId
  try {
    if (agent.status === 'ACTIVE') {
      await pauseAdminAgent(agent.userId)
      agent.status = 'PAUSED'
      ElMessage.success(t('admin.agents.paused'))
    } else {
      await resumeAdminAgent(agent.userId)
      agent.status = 'ACTIVE'
      ElMessage.success(t('admin.agents.resumed'))
    }
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.agents.actionFailed')))
  } finally {
    busyId.value = null
  }
}

async function runNow(agent: AgentUser): Promise<void> {
  busyId.value = agent.userId
  try {
    const result = await runAdminAgentNow(agent.userId)
    if (result.status === 'FAILED') {
      ElMessage.error(result.run?.error || result.reason || t('admin.agents.runFailed'))
    } else if (result.status === 'GUARDED') {
      ElMessage.warning(t('admin.agents.runGuarded'))
    } else if (result.status === 'SKIPPED') {
      ElMessage.warning(result.reason || t('admin.agents.runSkipped'))
    } else {
      ElMessage.success(t('admin.agents.runDone'))
    }
    await load()
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.agents.actionFailed')))
  } finally {
    busyId.value = null
  }
}

async function removeAgent(agent: AgentUser): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    t('admin.agents.deleteConfirm', { name: agent.displayName }),
    t('admin.agents.deleteTitle'),
    { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') },
  ).catch(() => false)
  if (!confirmed) return
  busyId.value = agent.userId
  try {
    await deleteAdminAgent(agent.userId)
    ElMessage.success(t('admin.agents.deleted'))
    await load()
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.agents.actionFailed')))
  } finally {
    busyId.value = null
  }
}

async function openRuns(agent: AgentUser): Promise<void> {
  runsAgent.value = agent
  runs.value = []
  runsError.value = null
  runsOpen.value = true
  runsLoading.value = true
  toolCallCache.clear()
  try {
    // 注意：子资源接口认的是 userId，不是列表里的 id（人设 id）
    const result = await listAdminAgentRuns(agent.userId, { page: 1, size: 30 })
    runs.value = result.items
  } catch (cause) {
    runsError.value = resolveErrorMessage(cause, t('admin.agents.runsLoadFailed'))
  } finally {
    runsLoading.value = false
  }
}

/** 每条日志的 tool_calls_json 只解析一次（模板里会渲染多次） */
const toolCallCache = new Map<string, ToolCallView[]>()

function toolCalls(run: AgentRun): ToolCallView[] {
  const cached = toolCallCache.get(run.id)
  if (cached) return cached
  const parsed = parseToolCalls(run.toolCallsJson)
  toolCallCache.set(run.id, parsed)
  return parsed
}

function toolCallTitle(call: ToolCallView): string {
  return call.name ? toolLabel(call.name) : t('admin.agents.toolCallUnknown')
}

function prettyJson(value: unknown): string {
  if (value === undefined || value === null) return ''
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2)
    } catch {
      return value
    }
  }
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

function runStatusLabel(status: AgentRun['status']): string {
  if (status === 'GUARDED') return t('admin.agents.runStatusGuarded')
  if (status === 'FAILED') return t('admin.agents.runStatusFailed')
  if (status === 'SKIPPED') return t('admin.agents.runStatusSkipped')
  return t('admin.agents.runStatusSuccess')
}

function runStatusType(status: AgentRun['status']): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'GUARDED') return 'warning'
  if (status === 'FAILED') return 'danger'
  if (status === 'SKIPPED') return 'info'
  return 'success'
}

function outputLabel(type: AgentRun['outputType']): string {
  if (type === 'POST') return t('admin.agents.outputPost')
  if (type === 'COMMENT') return t('admin.agents.outputComment')
  if (type === 'LIKE') return t('admin.agents.outputLike')
  return t('admin.agents.outputNone')
}

onMounted(() => {
  void loadGlobalSwitch()
  void loadSearchProvider()
  void load()
})
</script>

<template>
  <section class="admin-agents">
    <header class="admin-section-head">
      <div>
        <h2 class="admin-section-title">{{ t('admin.layout.agents') }}</h2>
        <p class="admin-section-subtitle">{{ t('admin.agents.subtitle') }}</p>
        <div class="admin-agents__search">
          <el-tag size="small" :type="searchState.tagType" effect="plain">{{ searchState.label }}</el-tag>
          <span v-if="searchState.kind === 'off'" class="admin-agents__search-hint">
            {{ t('admin.agents.searchOffHint') }}
          </span>
          <span v-else-if="searchState.kind === 'unknown'" class="admin-agents__search-hint">
            {{ t('admin.agents.searchUnknownHint') }}
          </span>
        </div>
      </div>
      <div class="admin-agents__head-actions">
        <el-tag v-if="globalEnabled !== null" :type="globalEnabled ? 'success' : 'danger'" effect="plain">
          {{ globalEnabled ? t('admin.agents.globalOn') : t('admin.agents.globalOff') }}
        </el-tag>
        <span v-if="dailyPostLimit > 0" class="muted admin-agents__quota">
          {{ t('admin.agents.quota', { posts: dailyPostLimit, tokens: dailyTokenBudget }) }}
        </span>
        <el-button v-if="canManage" type="primary" :icon="Plus" @click="openCreate">{{ t('admin.agents.create') }}</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('common.refresh') }}</el-button>
      </div>
    </header>

    <el-alert
      v-if="globalEnabled === false"
      :title="t('admin.agents.globalDisabledTitle')"
      :description="t('admin.agents.globalDisabledHint')"
      type="warning"
      :closable="false"
      show-icon
      class="admin-agents__alert"
    />

    <div class="admin-filters">
      <el-select v-model="statusFilter" :placeholder="t('common.allStatus')" clearable class="admin-filters__select" @change="search">
        <el-option :label="t('admin.agents.statusActive')" value="ACTIVE" />
        <el-option :label="t('admin.agents.statusPaused')" value="PAUSED" />
        <el-option :label="t('admin.agents.statusDisabled')" value="DISABLED" />
      </el-select>
      <el-button type="primary" :icon="Search" @click="search">{{ t('common.query') }}</el-button>
    </div>

    <LoadingState v-if="loading && !data" :rows="3" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <EmptyState
      v-else-if="!data || data.items.length === 0"
      :title="t('admin.agents.empty')"
      :description="t('admin.agents.emptyHint')"
    />
    <template v-else>
      <div class="admin-table-scroll">
        <el-table :data="data.items" class="admin-table" row-key="id">
          <el-table-column :label="t('admin.agents.columnAgent')" min-width="200">
            <template #default="{ row }">
              <div class="agent-cell">
                <UserAvatar :src="row.avatarUrl" :name="row.displayName" :size="34" />
                <div class="agent-cell__meta">
                  <strong>{{ row.displayName }}</strong>
                  <small>@{{ row.username }} · …{{ shortId(row.userId) }}</small>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('admin.agents.columnProfile')" min-width="220">
            <template #default="{ row }">
              <p class="agent-profile-summary">{{ interestSummary(row as AgentUser) }}</p>
              <p class="cell-sub">
                {{ toneLabel(row.profile.tone) }} ·
                {{ t('admin.agents.limitsShort', { posts: row.profile.postsPerDay, comments: row.profile.commentsPerDay }) }}
              </p>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.status')" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="statusTagType(row.status)" effect="plain">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('admin.agents.columnToday')" width="120">
            <template #default="{ row }">
              <p>{{ t('admin.agents.todayPosts', { n: row.todayPosts }) }}</p>
              <p class="cell-sub">{{ t('admin.agents.todayComments', { n: row.todayComments }) }}</p>
            </template>
          </el-table-column>
          <el-table-column :label="t('admin.agents.columnLastRun')" width="140">
            <template #default="{ row }">
              <span v-if="row.lastRunAt">{{ formatRelativeTime(row.lastRunAt) }}</span>
              <span v-else class="cell-sub">{{ t('admin.agents.neverRun') }}</span>
              <p v-if="lastRunLabel(row.lastRunStatus)" class="cell-sub">
                <el-tag size="small" :type="lastRunType(row.lastRunStatus)" effect="plain">
                  {{ lastRunLabel(row.lastRunStatus) }}
                </el-tag>
              </p>
            </template>
          </el-table-column>
          <el-table-column :label="t('admin.agents.columnFailure')" width="100">
            <template #default="{ row }">
              <span :class="{ 'agent-failure--high': row.failureRate >= 0.3 }">{{ failureText(row as AgentUser) }}</span>
              <p class="cell-sub">{{ t('admin.agents.runsCount', { n: row.runCount }) }}</p>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.actions')" width="240" align="right" fixed="right">
            <template #default="{ row }">
              <el-button text size="small" @click="openRuns(row as AgentUser)">{{ t('admin.agents.logs') }}</el-button>
              <el-button v-if="canManage" text size="small" @click="openEdit(row as AgentUser)">{{ t('common.edit') }}</el-button>
              <el-button
                v-if="canManage"
                text
                size="small"
                :loading="busyId === row.userId"
                @click="togglePause(row as AgentUser)"
              >{{ row.status === 'ACTIVE' ? t('admin.agents.pause') : t('admin.agents.resume') }}</el-button>
              <el-button
                v-if="canManage"
                text
                size="small"
                :icon="VideoPlay"
                :loading="busyId === row.userId"
                @click="runNow(row as AgentUser)"
              >{{ t('admin.agents.runNow') }}</el-button>
              <el-button v-if="canManage" text size="small" type="danger" :icon="Delete" @click="removeAgent(row as AgentUser)">
                {{ t('common.delete') }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <footer v-if="data.total > size" class="admin-pager">
        <el-pagination
          layout="prev, pager, next"
          :current-page="page"
          :page-size="size"
          :total="data.total"
          background
          @current-change="changePage"
        />
      </footer>
    </template>

    <!-- 新建代理 -->
    <el-dialog v-model="createOpen" :title="t('admin.agents.create')" width="460px" append-to-body>
      <el-alert v-if="createError" :title="createError" type="error" :closable="false" show-icon class="admin-agents__alert" />
      <el-form label-position="top" @submit.prevent>
        <el-form-item :label="t('admin.agents.username')" required>
          <el-input v-model="createForm.username" maxlength="32" :placeholder="t('admin.agents.usernameHint')" />
        </el-form-item>
        <el-form-item :label="t('admin.agents.displayName')" required>
          <el-input v-model="createForm.displayName" maxlength="32" />
        </el-form-item>
        <el-form-item :label="t('admin.agents.bio')">
          <el-input v-model="createForm.bio" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">{{ t('admin.agents.create') }}</el-button>
      </template>
    </el-dialog>

    <!-- 人设编辑 -->
    <el-drawer v-model="editOpen" :title="t('admin.agents.editTitle', { name: editing?.displayName ?? '' })" size="520px">
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" show-icon class="admin-agents__alert" />
      <el-form label-position="top" @submit.prevent>
        <el-form-item :label="t('admin.agents.displayName')" required>
          <el-input v-model="form.displayName" maxlength="32" show-word-limit />
        </el-form-item>
        <el-form-item :label="t('admin.agents.bio')">
          <el-input v-model="form.bio" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item :label="t('common.status')">
          <el-select v-model="form.status" class="agent-form__full">
            <el-option :label="t('admin.agents.statusActive')" value="ACTIVE" />
            <el-option :label="t('admin.agents.statusPaused')" value="PAUSED" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('admin.agents.interests')">
          <el-select
            v-model="form.interests"
            multiple
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            :placeholder="t('admin.agents.interestsHint')"
            class="agent-form__full"
          >
            <el-option v-for="item in form.interests" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('admin.agents.tone')">
          <el-radio-group v-model="form.tone">
            <el-radio value="casual">{{ t('admin.agents.toneCasual') }}</el-radio>
            <el-radio value="enthusiastic">{{ t('admin.agents.toneEnthusiastic') }}</el-radio>
            <el-radio value="dry">{{ t('admin.agents.toneDry') }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="t('admin.agents.activeHours')">
          <div class="agent-hours">
            <div v-for="(range, index) in form.activeHours" :key="index" class="agent-hours__row">
              <el-input-number v-model="range[0]" :min="0" :max="23" :controls="false" class="agent-hours__input" />
              <span class="muted">—</span>
              <el-input-number v-model="range[1]" :min="1" :max="24" :controls="false" class="agent-hours__input" />
              <span class="muted">{{ t('admin.agents.hourUnit') }}</span>
              <el-button text type="danger" size="small" @click="removeHourRange(index)">{{ t('common.delete') }}</el-button>
            </div>
            <el-button text type="primary" size="small" @click="addHourRange">{{ t('admin.agents.addHourRange') }}</el-button>
            <p class="agent-form__hint">{{ t('admin.agents.activeHoursHint') }}</p>
          </div>
        </el-form-item>
        <el-form-item :label="t('admin.agents.limits')">
          <div class="agent-limits">
            <label>
              <span>{{ t('admin.agents.postsPerDay') }}</span>
              <el-input-number v-model="form.postsPerDay" :min="0" :max="20" />
            </label>
            <label>
              <span>{{ t('admin.agents.commentsPerDay') }}</span>
              <el-input-number v-model="form.commentsPerDay" :min="0" :max="100" />
            </label>
          </div>
        </el-form-item>
        <el-form-item :label="t('admin.agents.channels')">
          <el-select
            v-model="form.channels"
            multiple
            filterable
            :loading="channelsLoading"
            :placeholder="t('admin.agents.channelsHint')"
            class="agent-form__full"
          >
            <el-option v-for="channel in channels" :key="channel.id" :label="channel.name" :value="channel.id" />
          </el-select>
          <p class="agent-form__hint">{{ t('admin.agents.channelsEmptyHint') }}</p>
        </el-form-item>
        <el-form-item :label="t('admin.agents.tools')">
          <el-checkbox-group v-model="form.toolsEnabled" class="agent-tools">
            <el-checkbox v-for="tool in TOOLS" :key="tool" :value="tool">{{ toolLabel(tool) }}</el-checkbox>
          </el-checkbox-group>
          <p class="agent-form__hint">{{ t('admin.agents.toolsHint') }}</p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editOpen = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="saveProfile">{{ t('common.save') }}</el-button>
      </template>
    </el-drawer>

    <!-- 运行日志 -->
    <el-drawer v-model="runsOpen" :title="t('admin.agents.runsTitle', { name: runsAgent?.displayName ?? '' })" size="620px">
      <LoadingState v-if="runsLoading" :rows="4" />
      <ErrorState v-else-if="runsError" :message="runsError" @retry="runsAgent && openRuns(runsAgent)" />
      <EmptyState v-else-if="runs.length === 0" :title="t('admin.agents.runsEmpty')" :description="t('admin.agents.runsEmptyHint')" />
      <ul v-else class="agent-runs">
        <li v-for="run in runs" :key="run.id" class="agent-run">
          <header class="agent-run__head">
            <el-tag size="small" :type="runStatusType(run.status)" effect="plain">{{ runStatusLabel(run.status) }}</el-tag>
            <span class="agent-run__time">{{ formatDateTime(run.startedAt) }}</span>
            <span class="muted">{{ outputLabel(run.outputType) }}</span>
            <span v-if="run.outputId" class="agent-run__output">
              <RouterLink :to="{ name: 'post', params: { id: run.outputId } }" class="link">
                {{ t('admin.agents.viewOutput') }}
              </RouterLink>
            </span>
            <span class="muted agent-run__tokens">
              {{ t('admin.agents.tokens', { in: run.tokensIn ?? 0, out: run.tokensOut ?? 0 }) }}
            </span>
          </header>
          <p
            v-if="runReason(run)"
            class="agent-run__reason"
            :class="{ 'agent-run__reason--error': run.status === 'FAILED' }"
          >{{ runReason(run) }}</p>
          <el-collapse v-if="run.planJson || run.toolCallsJson" class="agent-run__collapse">
            <el-collapse-item v-if="run.planJson" :title="t('admin.agents.planJson')" :name="`plan-${run.id}`">
              <pre class="agent-run__json">{{ prettyJson(run.planJson) }}</pre>
            </el-collapse-item>
            <el-collapse-item v-if="run.toolCallsJson" :title="t('admin.agents.toolCalls')" :name="`tools-${run.id}`">
              <ul v-if="toolCalls(run).length > 0" class="tool-calls">
                <li v-for="(call, index) in toolCalls(run)" :key="`${run.id}-${index}`" class="tool-call">
                  <div class="tool-call__head">
                    <strong class="tool-call__name">{{ toolCallTitle(call) }}</strong>
                    <code v-if="call.name" class="tool-call__raw-name">{{ call.name }}</code>
                    <el-tag v-if="call.hitCount !== null && isWebSearchCall(call)" size="small" effect="plain">
                      {{ t('admin.agents.toolCallHits', { n: call.hitCount }) }}
                    </el-tag>
                    <el-tag v-if="call.charCount !== null && isFetchCall(call)" size="small" effect="plain">
                      {{ t('admin.agents.toolCallChars', { n: call.charCount }) }}
                    </el-tag>
                  </div>

                  <dl v-if="call.query || call.freshness || call.url" class="tool-call__fields">
                    <template v-if="call.query">
                      <dt>{{ t('admin.agents.toolCallQuery') }}</dt>
                      <dd>{{ call.query }}</dd>
                    </template>
                    <template v-if="call.freshness">
                      <dt>{{ t('admin.agents.toolCallFreshness') }}</dt>
                      <dd>{{ call.freshness }}</dd>
                    </template>
                    <template v-if="call.url">
                      <dt>{{ t('admin.agents.toolCallUrl') }}</dt>
                      <dd class="tool-call__url">{{ call.url }}</dd>
                    </template>
                  </dl>

                  <el-collapse v-if="call.titles.length > 0" class="tool-call__titles">
                    <el-collapse-item
                      :title="t('admin.agents.toolCallTitles', { n: call.titles.length })"
                      :name="`titles-${run.id}-${index}`"
                    >
                      <ol class="tool-call__title-list">
                        <li v-for="(title, order) in call.titles" :key="order">{{ title }}</li>
                      </ol>
                    </el-collapse-item>
                  </el-collapse>

                  <p v-if="call.summary" class="tool-call__summary">{{ call.summary }}</p>

                  <el-collapse class="tool-call__raw">
                    <el-collapse-item :title="t('admin.agents.toolCallRaw')" :name="`raw-${run.id}-${index}`">
                      <pre class="agent-run__json">{{ prettyJson(call.raw) }}</pre>
                    </el-collapse-item>
                  </el-collapse>
                </li>
              </ul>
              <pre v-else class="agent-run__json">{{ prettyJson(run.toolCallsJson) }}</pre>
            </el-collapse-item>
          </el-collapse>
        </li>
      </ul>
    </el-drawer>
  </section>
</template>

<style scoped lang="scss">
.admin-agents__head-actions {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
}

.admin-agents__quota {
  font-size: var(--isles-font-xs);
}

.admin-agents__alert {
  margin-bottom: var(--isles-space-3);
}

.admin-agents__search {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  align-items: center;
  margin-top: var(--isles-space-2);
}

.admin-agents__search-hint {
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.agent-cell {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
  min-width: 0;
}

.agent-cell__meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.3;

  strong {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    color: var(--isles-text-muted);
    font-size: var(--isles-font-xs);
  }
}

.agent-profile-summary {
  font-size: var(--isles-font-sm);
}

.agent-failure--high {
  color: var(--isles-danger, #e5484d);
  font-weight: 600;
}

.agent-form__full {
  width: 100%;
}

.agent-form__hint {
  margin-top: 4px;
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.agent-hours {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
  width: 100%;
}

.agent-hours__row {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
}

.agent-hours__input {
  width: 70px;
}

.agent-limits {
  display: flex;
  gap: var(--isles-space-4);

  label {
    display: flex;
    flex-direction: column;
    gap: 4px;
    font-size: var(--isles-font-xs);
    color: var(--isles-text-secondary);
  }
}

.agent-tools {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 2px 8px;
}

.agent-runs {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-3);
  padding: 0;
  margin: 0;
  list-style: none;
}

.agent-run {
  padding: var(--isles-space-3);
  background: var(--isles-surface-muted);
  border-radius: var(--isles-radius-md);
}

.agent-run__head {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  align-items: center;
  font-size: var(--isles-font-xs);
}

.agent-run__tokens {
  margin-left: auto;
}

.agent-run__reason {
  margin-top: var(--isles-space-2);
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-xs);

  &--error {
    color: var(--isles-danger, #e5484d);
  }
}

.agent-run__collapse {
  margin-top: var(--isles-space-2);
}

.agent-run__json {
  max-height: 260px;
  overflow: auto;
  padding: var(--isles-space-2);
  font-size: 11px;
  background: var(--isles-surface);
  border-radius: var(--isles-radius-sm);
  white-space: pre-wrap;
  word-break: break-all;
}

.tool-calls {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
  padding: 0;
  margin: 0;
  list-style: none;
}

.tool-call {
  padding: var(--isles-space-2) var(--isles-space-3);
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-sm);
}

.tool-call__head {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  align-items: center;
  font-size: var(--isles-font-sm);
}

.tool-call__raw-name {
  color: var(--isles-text-muted);
  font-family: var(--isles-font-mono, monospace);
  font-size: 11px;
}

.tool-call__fields {
  display: grid;
  grid-template-columns: max-content minmax(0, 1fr);
  gap: 2px var(--isles-space-2);
  margin: var(--isles-space-2) 0 0;
  font-size: var(--isles-font-xs);

  dt {
    color: var(--isles-text-muted);
  }

  dd {
    margin: 0;
    word-break: break-all;
  }
}

.tool-call__url {
  font-family: var(--isles-font-mono, monospace);
}

.tool-call__titles {
  margin-top: var(--isles-space-2);
  border-top: none;

  :deep(.el-collapse-item__header) {
    height: 28px;
    font-size: var(--isles-font-xs);
  }

  :deep(.el-collapse-item__wrap) {
    background: transparent;
  }
}

.tool-call__title-list {
  margin: 0;
  padding-left: 20px;
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-xs);

  li {
    margin-bottom: 2px;
    word-break: break-word;
  }
}

.tool-call__summary {
  display: -webkit-box;
  margin-top: var(--isles-space-2);
  overflow: hidden;
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-xs);
  white-space: pre-wrap;
  word-break: break-word;
  /* fetch_page 的摘要就是整页正文，这里截 3 行，全文看「原始 JSON」 */
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
}

.tool-call__raw {
  margin-top: 2px;
  border-top: none;

  :deep(.el-collapse-item__header) {
    height: 26px;
    color: var(--isles-text-muted);
    font-size: 11px;
  }

  :deep(.el-collapse-item__wrap) {
    background: transparent;
  }
}
</style>
